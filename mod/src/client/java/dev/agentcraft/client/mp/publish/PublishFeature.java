package dev.agentcraft.client.mp.publish;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanListener;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.LinkStatus;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.mp.MpMode;
import dev.agentcraft.client.mp.Studios;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.MpEvents;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.Plot;
import dev.agentcraft.mp.net.PublicStateC2S;
import dev.agentcraft.mp.net.StudioEventC2S;
import dev.agentcraft.mp.state.PublicEvent;
import dev.agentcraft.mp.state.PublicPolicy;
import dev.agentcraft.mp.state.PublicStudioState;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.jspecify.annotations.Nullable;

/** Publishes the local studio. The acting player is always the client player, never a command argument. */
public final class PublishFeature {
    private static final List<String> FLAGS = List.of("activityText", "sayText", "taskTitles", "goalText");

    private PublishFeature() {}

    public static void init() {
        PolicyStore store = PolicyStore.load(FabricLoader.getInstance().getConfigDir().resolve("agentcraft-public.json"));
        PublishScheduler scheduler = new PublishScheduler(Foreman::state, store::current, PublishFeature::rate,
            PublishFeature::player, () -> Anchors.self().owner(), PublishFeature::plot, outbound());
        Foreman.addListener(listener(scheduler, PublishFeature::track, PublishFeature::open, store::current, Foreman::state));
        // Mode listeners already run on the client thread; execute() keeps a netty caller off the scheduler.
        MpMode.addListener(mode -> Minecraft.getInstance().execute(() -> {
            modeChanged(mode, scheduler);
        }));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick(mc, scheduler));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, build) -> register(dispatcher, store, scheduler,
            MpMode::current, source -> source.getPlayer().getUUID(), () -> Anchors.self().owner()));
    }

    public static ForemanListener listener(PublishScheduler scheduler, BooleanSupplier track, BooleanSupplier multiplayer,
            Supplier<PublicPolicy> policy, Supplier<ForemanState> states) {
        return new ForemanListener() {
            private void touch() { if (track.getAsBoolean()) scheduler.markDirty(); }

            @Override public void onSnapshot(ForemanState state) { touch(); }
            @Override public void onAgent(Protocol.@Nullable Agent previous, Protocol.Agent agent) { touch(); }
            @Override public void onDecision(Protocol.@Nullable Decision previous, Protocol.Decision decision) { touch(); }
            @Override public void onRepo(Protocol.@Nullable Repo previous, Protocol.Repo repo) { touch(); }
            @Override public void onGoal(Protocol.@Nullable Goal previous, Protocol.Goal goal) { touch(); }
            @Override public void onConnection(LinkStatus status) { touch(); }

            @Override public void onTask(Protocol.@Nullable Task previous, Protocol.Task task) {
                touch();
                if (!track.getAsBoolean()) return;
                ForemanState state = states.get();
                if (state == null) return;
                PublicEvent event = Redactor.taskDone(state, previous, task);
                if (event != null) scheduler.offer(event, System.nanoTime(), multiplayer.getAsBoolean());
            }

            @Override public void onSay(Protocol.AgentSay say) {
                if (!track.getAsBoolean()) return;
                ForemanState state = states.get();
                if (state == null) return;
                PublicEvent event = Redactor.say(state, say, policy.get());
                if (event != null) scheduler.offer(event, System.nanoTime(), multiplayer.getAsBoolean());
            }
        };
    }

    public static void tick(Minecraft mc, PublishScheduler scheduler) {
        tick(scheduler, MpMode.current(), mc.player != null && mc.getConnection() != null, channels());
    }

    /** Tick entry point shared by production and tests; {@code connected} includes player and connection. */
    public static void tick(PublishScheduler scheduler, MpMode mode, boolean connected, boolean channelsOpen) {
        if (!connected || mode == MpMode.SINGLEPLAYER) return;
        switch (PublishGate.action(mode, channelsOpen)) {
            case NONE -> { }
            case REFUSE -> scheduler.flush(System.nanoTime(), false);
            case SEND -> scheduler.flush(System.nanoTime(), true);
        }
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> dispatcher, PolicyStore store,
            PublishScheduler scheduler, Supplier<MpMode> mode, Function<FabricClientCommandSource, UUID> player,
            Supplier<UUID> studio) {
        var root = ClientCommands.literal("agentcraft-public").executes(ctx -> {
            ctx.getSource().sendFeedback(Component.literal(describe(store.current())));
            return 1;
        });
        for (String flag : FLAGS) {
            root.then(ClientCommands.literal(flag)
                .executes(ctx -> apply(ctx.getSource(), store, scheduler, flag, null, mode, player, studio))
                .then(ClientCommands.argument("value", BoolArgumentType.bool())
                    .executes(ctx -> apply(ctx.getSource(), store, scheduler, flag,
                        BoolArgumentType.getBool(ctx, "value"), mode, player, studio))));
        }
        dispatcher.register(root);
    }

    /** Mode callback seam: only entering multiplayer requires a fresh published projection. */
    public static void modeChanged(MpMode mode, PublishScheduler scheduler) {
        if (mode == MpMode.MULTIPLAYER) scheduler.reconnect();
    }

    /** {@code value} null toggles. Changes are private to a connected AgentCraft multiplayer session. */
    public static String applyPolicy(PolicyStore store, String flag, boolean value, Supplier<MpMode> mode,
            Supplier<UUID> player, Supplier<UUID> studio, @Nullable PublishScheduler scheduler) {
        if (mode.get() != MpMode.MULTIPLAYER) {
            return describe(store.current()) + " Flags can be changed while connected to an AgentCraft multiplayer server.";
        }
        int before = store.bits();
        store.set(flag, value);
        int after = store.bits();
        if (before != after) {
            MpLog.event(MpEvents.POLICY_CHANGED, "player", player.get(), "studio", studio.get(), "before", before, "after", after);
            if (scheduler != null) scheduler.markDirty();
        }
        return describe(store.current());
    }

    public static String describe(PublicPolicy policy) {
        return "activityText=" + policy.activityText() + " sayText=" + policy.sayText()
            + " taskTitles=" + policy.taskTitles() + " goalText=" + policy.goalText();
    }

    private static int apply(FabricClientCommandSource source, PolicyStore store, PublishScheduler scheduler, String flag,
            @Nullable Boolean value, Supplier<MpMode> mode, Function<FabricClientCommandSource, UUID> player,
            Supplier<UUID> studio) {
        boolean next = value != null ? value : !PolicyStore.flag(store.current(), flag);
        String text = applyPolicy(store, flag, next, mode, () -> player.apply(source), studio, scheduler);
        source.sendFeedback(Component.literal(text));
        return 1;
    }

    private static boolean track() { return PublishGate.of(MpMode.current()) != PublishGate.QUIET; }

    private static boolean open() {
        if (PublishGate.of(MpMode.current()) != PublishGate.OPEN) return false;
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.getConnection() != null && channels();
    }

    private static boolean channels() {
        return ClientPlayNetworking.canSend(PublicStateC2S.TYPE) && ClientPlayNetworking.canSend(StudioEventC2S.TYPE);
    }

    private static int rate() { return MpMode.serverInfo().map(info -> info.publicStatePerSecond()).orElse(0); }

    private static UUID player() {
        var player = Minecraft.getInstance().player;
        if (player == null) throw new IllegalStateException("no player");
        return player.getUUID();
    }

    /** The own plot as the hello and the layout sync registered it; the scheduler resends when it changes. */
    private static Optional<Plot> plot() { return Studios.plot(Anchors.self()); }

    private static PublishScheduler.Out outbound() {
        return new PublishScheduler.Out() {
            @Override public void state(PublicStudioState state) { send(new PublicStateC2S(state)); }
            @Override public void event(PublicEvent event) { send(new StudioEventC2S(event)); }
        };
    }

    private static void send(CustomPacketPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;
        if (MpMode.current() != MpMode.MULTIPLAYER) return;
        if (!ClientPlayNetworking.canSend(payload.type())) return;
        ClientPlayNetworking.send(payload);
    }
}
