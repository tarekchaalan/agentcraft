package dev.agentcraft.mp.publish;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.ForemanStates;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.mp.MpMode;
import dev.agentcraft.client.mp.publish.PolicyStore;
import dev.agentcraft.client.mp.publish.PublishFeature;
import dev.agentcraft.client.mp.publish.PublishScheduler;
import dev.agentcraft.mp.MpEvents;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.MpReasons;
import dev.agentcraft.mp.Plot;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.state.PublicEvent;
import dev.agentcraft.mp.state.PublicPolicy;
import dev.agentcraft.mp.state.PublicStudioState;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublishFeatureTest {
    private static final UUID PLAYER = UUID.nameUUIDFromBytes("publish-command-player".getBytes());
    private static final UUID STUDIO = UUID.nameUUIDFromBytes("publish-command-studio".getBytes());

    @Test void tick_obeys_mode_connection_and_both_channels() {
        Harness quiet = new Harness(10);
        quiet.scheduler.markDirty();
        try (var capture = MpLog.capture()) {
            PublishFeature.tick(quiet.scheduler, MpMode.SINGLEPLAYER, true, true);
            assertTrue(quiet.states.isEmpty());
            assertTrue(capture.lines().isEmpty());
        }

        Harness vanilla = new Harness(10);
        vanilla.scheduler.markDirty();
        try (var capture = MpLog.capture()) {
            PublishFeature.tick(vanilla.scheduler, MpMode.REMOTE_VANILLA, true, true);
            PublishFeature.tick(vanilla.scheduler, MpMode.REMOTE_VANILLA, true, true);
            assertTrue(vanilla.states.isEmpty());
            assertEquals(List.of("event=" + MpEvents.PUBLIC_STATE_SKIPPED + " player=" + PLAYER + " studio=" + STUDIO
                + " plot=4 rev=0 reason=" + MpReasons.NOT_MULTIPLAYER), capture.lines());
        }

        Harness closed = new Harness(10);
        closed.scheduler.markDirty();
        PublishFeature.tick(closed.scheduler, MpMode.MULTIPLAYER, true, false);
        assertTrue(closed.states.isEmpty());

        Harness open = new Harness(10);
        open.scheduler.markDirty();
        PublishFeature.tick(open.scheduler, MpMode.MULTIPLAYER, true, true);
        assertEquals(1, open.states.size());

        Harness disconnected = new Harness(10);
        disconnected.scheduler.markDirty();
        PublishFeature.tick(disconnected.scheduler, MpMode.MULTIPLAYER, false, true);
        assertTrue(disconnected.states.isEmpty());
    }

    @Test void command_views_in_every_mode_and_sets_and_toggles_only_in_multiplayer(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("agentcraft-public.json");
        PolicyStore store = PolicyStore.load(file);
        Harness harness = new Harness(10, store::current);
        AtomicReference<MpMode> mode = new AtomicReference<>(MpMode.SINGLEPLAYER);
        List<String> feedback = new ArrayList<>();
        CommandDispatcher<FabricClientCommandSource> dispatcher = dispatcher(store, harness.scheduler, mode, feedback);
        try (var capture = MpLog.capture()) {
            for (MpMode current : MpMode.values()) {
                mode.set(current);
                assertEquals(1, dispatcher.execute("agentcraft-public", source(feedback)));
                assertEquals("activityText=false sayText=false taskTitles=false goalText=false", feedback.removeLast());
            }
            assertFalse(Files.exists(file));
            assertTrue(capture.lines().isEmpty());

            mode.set(MpMode.MULTIPLAYER);
            dispatcher.execute("agentcraft-public activityText true", source(feedback));
            assertEquals("activityText=true sayText=false taskTitles=false goalText=false", feedback.removeLast());
            assertTrue(Files.isRegularFile(file));
            assertEquals(new PublicPolicy(true, false, false, false), store.current());
            assertTrue(harness.scheduler.isDirty());
            assertEquals(List.of("event=" + MpEvents.POLICY_CHANGED + " player=" + PLAYER + " studio=" + STUDIO + " before=0 after=1"), capture.lines());
            harness.scheduler.flush(0, true);
            assertTrue(harness.states.getLast().policy().activityText());

            dispatcher.execute("agentcraft-public sayText", source(feedback));
            assertEquals("activityText=true sayText=true taskTitles=false goalText=false", feedback.removeLast());
            assertEquals(new PublicPolicy(true, true, false, false), store.current());
            assertTrue(harness.scheduler.isDirty());
            assertEquals(2, capture.lines().stream().filter(line -> line.startsWith("event=" + MpEvents.POLICY_CHANGED)).count());
            assertEquals("event=" + MpEvents.POLICY_CHANGED + " player=" + PLAYER + " studio=" + STUDIO + " before=1 after=3",
                capture.lines().getLast());
        }
    }

    @Test void set_in_singleplayer_is_read_only_and_unknown_flag_is_refused(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("agentcraft-public.json");
        PolicyStore store = PolicyStore.load(file);
        Harness harness = new Harness(10, store::current);
        AtomicReference<MpMode> mode = new AtomicReference<>(MpMode.SINGLEPLAYER);
        List<String> feedback = new ArrayList<>();
        CommandDispatcher<FabricClientCommandSource> dispatcher = dispatcher(store, harness.scheduler, mode, feedback);
        try (var capture = MpLog.capture()) {
            dispatcher.execute("agentcraft-public activityText true", source(feedback));
            assertEquals("activityText=false sayText=false taskTitles=false goalText=false Flags can be changed while connected to an AgentCraft multiplayer server.", feedback.removeLast());
            assertEquals(PublicPolicy.DEFAULT, store.current());
            assertFalse(Files.exists(file));
            assertFalse(harness.scheduler.isDirty());
            assertTrue(capture.lines().isEmpty());
            assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("agentcraft-public privateFlag true", source(feedback)));
            assertEquals(PublicPolicy.DEFAULT, store.current());
            assertFalse(Files.exists(file));
            assertTrue(capture.lines().isEmpty());
        }
    }

    @Test void listener_marks_each_publication_callback_only_while_tracking() {
        ForemanState state = ForemanStates.showcase();
        Protocol.Agent agent = state.agents().values().iterator().next();
        Protocol.Task task = state.tasks().values().iterator().next();
        Protocol.Decision decision = state.decisions().values().iterator().next();
        Protocol.Repo repo = state.repos().values().iterator().next();
        Protocol.Goal goal = state.goal();
        var callbacks = List.<java.util.function.Consumer<dev.agentcraft.client.foreman.ForemanListener>>of(
            listener -> listener.onSnapshot(state),
            listener -> listener.onAgent(null, agent),
            listener -> listener.onDecision(null, decision),
            listener -> listener.onRepo(null, repo),
            listener -> listener.onGoal(null, goal),
            listener -> listener.onConnection(state.link()),
            listener -> listener.onTask(task, task));
        for (var callback : callbacks) {
            Harness tracking = new Harness(10);
            var listener = PublishFeature.listener(tracking.scheduler, () -> true, () -> true, () -> PublicPolicy.DEFAULT, () -> state);
            callback.accept(listener);
            assertTrue(tracking.scheduler.isDirty(), callback.toString());

            Harness quiet = new Harness(10);
            var quietListener = PublishFeature.listener(quiet.scheduler, () -> false, () -> true, () -> PublicPolicy.DEFAULT, () -> state);
            callback.accept(quietListener);
            assertFalse(quiet.scheduler.isDirty(), callback.toString());
            assertTrue(quiet.events.isEmpty());
        }
    }

    @Test void entering_multiplayer_reconnects_and_resets_the_revision() {
        Harness harness = new Harness(10);
        harness.scheduler.markDirty();
        harness.scheduler.flush(0, true);
        harness.patch("thinking");
        harness.scheduler.markDirty();
        harness.scheduler.flush(1, true);
        assertEquals(List.of(1, 2), harness.states.stream().map(PublicStudioState::rev).toList());
        PublishFeature.modeChanged(MpMode.MULTIPLAYER, harness.scheduler);
        harness.scheduler.flush(2, true);
        assertEquals(1, harness.states.getLast().rev());
    }

    private static CommandDispatcher<FabricClientCommandSource> dispatcher(PolicyStore store, PublishScheduler scheduler,
            AtomicReference<MpMode> mode, List<String> feedback) {
        CommandDispatcher<FabricClientCommandSource> dispatcher = new CommandDispatcher<>();
        PublishFeature.register(dispatcher, store, scheduler, mode::get, ignored -> PLAYER, () -> STUDIO);
        return dispatcher;
    }

    private static FabricClientCommandSource source(List<String> feedback) {
        return (FabricClientCommandSource) java.lang.reflect.Proxy.newProxyInstance(
            FabricClientCommandSource.class.getClassLoader(), new Class<?>[] {FabricClientCommandSource.class},
            (proxy, method, args) -> {
                if (method.getName().equals("sendFeedback")) {
                    feedback.add(((Component) args[0]).getString());
                    return null;
                }
                throw new AssertionError("Unexpected command source call: " + method.getName());
            });
    }

    private final class Harness {
        final ForemanState state = ForemanStates.showcase();
        final List<PublicStudioState> states = new ArrayList<>();
        final List<PublicEvent> events = new ArrayList<>();
        final PublishScheduler scheduler;

        Harness(int rate) { this(rate, () -> PublicPolicy.DEFAULT); }

        Harness(int rate, java.util.function.Supplier<PublicPolicy> policy) {
            scheduler = new PublishScheduler(() -> state, policy, () -> rate, () -> PLAYER, () -> STUDIO,
                () -> Optional.of(new Plot(4, StudioId.of(STUDIO), BlockPos.ZERO)),
                new PublishScheduler.Out() {
                    @Override public void state(PublicStudioState state) { states.add(state); }
                    @Override public void event(PublicEvent event) { events.add(event); }
                });
        }

        void patch(String agentState) {
            var set = new com.google.gson.JsonObject();
            set.addProperty("state", agentState);
            state.patch("agent", "marlow", set);
        }
    }
}
