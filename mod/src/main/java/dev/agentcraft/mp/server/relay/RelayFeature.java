package dev.agentcraft.mp.server.relay;

import dev.agentcraft.mp.MpServerConfig;
import dev.agentcraft.mp.Plot;
import dev.agentcraft.mp.Plots;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.net.MpPayloads;
import dev.agentcraft.mp.net.PresenceS2C;
import dev.agentcraft.mp.net.PublicStateC2S;
import dev.agentcraft.mp.net.StudioEventC2S;
import dev.agentcraft.mp.net.StudioEventS2C;
import dev.agentcraft.mp.net.StudioStateS2C;
import dev.agentcraft.mp.server.StudioRange;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/** Server-side handlers for public-state relay, events and presence. */
public final class RelayFeature {
    private RelayFeature() {}

    /** Registers every server callback. Runs once, at mod init. */
    public static void init() {
        StudioRange.addListener(new StudioRange.Listener() {
            public void entered(ServerPlayer viewer, Plot plot) {
                MinecraftServer server = viewer.level().getServer();
                if (!active(server)) return;
                StudioRelay.onEntered(viewers(server), sink(server),
                    online(server), viewer.getUUID(), plot, Plots.directory(),
                    MpServerConfig.current());
            }
            public void left(ServerPlayer viewer, Plot plot) {
                // MP-06 sends nothing on range exit; MP-04 handles removal.
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, srv) ->
            srv.execute(() -> {
                if (!active(srv)) return;
                StudioRelay.onJoin(viewers(srv), sink(srv),
                    handler.getPlayer().getUUID(),
                    handler.getPlayer().getGameProfile().name(),
                    Plots.directory(), MpServerConfig.current());
            }));

        ServerPlayConnectionEvents.DISCONNECT.register((handler, srv) ->
            srv.execute(() -> {
                if (!active(srv)) return;
                StudioRelay.onDisconnect(viewers(srv), sink(srv),
                    handler.getPlayer().getUUID(),
                    handler.getPlayer().getGameProfile().name(),
                    Plots.directory(), MpServerConfig.current());
            }));

        ServerPlayNetworking.registerGlobalReceiver(PublicStateC2S.TYPE,
            (payload, context) -> {
                // Fabric calls a play receiver on the server thread: no hop.
                MinecraftServer server = context.server();
                if (!active(server)) return;
                // Accepts and relays only a changed state; UNCHANGED re-stores
                // the newest revision but is not fanned out.
                StudioRelay.acceptAndRelayState(
                    context.player().getUUID(), payload.state(),
                    Plots.directory(), MpServerConfig.current(),
                    MpPayloads.isModEquipped(context.player().getUUID()),
                    System.nanoTime(), viewers(server), sink(server));
            });

        ServerPlayNetworking.registerGlobalReceiver(StudioEventC2S.TYPE,
            (payload, context) -> {
                MinecraftServer server = context.server();
                if (!active(server)) return;
                var result = StudioRelay.acceptEvent(
                    context.player().getUUID(), payload.event(),
                    Plots.directory(), MpServerConfig.current(),
                    MpPayloads.isModEquipped(context.player().getUUID()),
                    System.nanoTime());
                if (result == StudioRelay.EventResult.ACCEPTED)
                    StudioRelay.relayEvent(viewers(server), sink(server),
                        StudioId.of(context.player().getUUID()), payload.event(),
                        Plots.directory());
            });

        ServerLifecycleEvents.SERVER_STOPPED.register(
            server -> StudioRelay.reset());
    }

    private static boolean active(MinecraftServer server) {
        return server.isDedicatedServer() && MpServerConfig.current().enabled();
    }

    /** Production sink: looks the recipient up and checks {@code canSend}. */
    private static StudioRelay.SendSink sink(MinecraftServer server) {
        return new StudioRelay.SendSink() {
            public boolean sendState(UUID recipient, StudioStateS2C payload) {
                return send(server, recipient, StudioStateS2C.TYPE, payload);
            }
            public boolean sendPresence(UUID recipient, PresenceS2C payload) {
                return send(server, recipient, PresenceS2C.TYPE, payload);
            }
            public boolean sendEvent(UUID recipient, StudioEventS2C payload) {
                return send(server, recipient, StudioEventS2C.TYPE, payload);
            }
        };
    }

    private static boolean send(MinecraftServer server, UUID recipient,
            CustomPacketPayload.Type<?> type, CustomPacketPayload payload) {
        ServerPlayer player = server.getPlayerList().getPlayer(recipient);
        if (player == null || !ServerPlayNetworking.canSend(player, type)) return false;
        ServerPlayNetworking.send(player, payload);
        return true;
    }

    /** Production viewer source: the mod-equipped players in range. */
    private static StudioRelay.ViewerSource viewers(MinecraftServer server) {
        return studio -> StudioRange.viewersOf(server, studio).stream()
            .map(ServerPlayer::getUUID).toList();
    }

    /** Production connectivity source: the server's own player list. */
    private static StudioRelay.OnlineSource online(MinecraftServer server) {
        return owner -> server.getPlayerList().getPlayer(owner) != null;
    }
}
