package dev.agentcraft.client.mp.layout;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.layout.Anchors.Layout;
import dev.agentcraft.client.mp.MpMode;
import dev.agentcraft.client.mp.Studios;
import dev.agentcraft.mp.*;
import dev.agentcraft.mp.net.LayoutRemoveS2C;
import dev.agentcraft.mp.net.LayoutS2C;
import dev.agentcraft.mp.net.ServerInfo;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Applies authoritative layouts from the dedicated server on the client thread. */
public final class LayoutSyncClient {
    private static boolean initialized;

    private LayoutSyncClient() {}

    public static void init() {
        if (initialized) return;
        ClientPlayNetworking.registerGlobalReceiver(LayoutS2C.TYPE,
            (payload, context) -> context.client().execute(() -> apply(payload)));
        ClientPlayNetworking.registerGlobalReceiver(LayoutRemoveS2C.TYPE,
            (payload, context) -> context.client().execute(() -> remove(payload)));
        initialized = true;
    }

    static void apply(LayoutS2C payload) {
        if (MpMode.current() != MpMode.MULTIPLAYER) return;
        int stride = MpMode.serverInfo().map(ServerInfo::plotStride).orElse(0);
        if (stride > 0 && !setPlot(payload.studio(), payload.plotIndex(), stride)) return;
        Anchors.publish(payload.studio(), payload.layout());
        logLayoutApplied(payload.studio(), payload.plotIndex(), payload.layout());
    }

    /** False when the grid has no origin for this index at this stride; the registry is left as it was. */
    private static boolean setPlot(StudioId studio, int plotIndex, int stride) {
        try {
            Studios.setPlot(studio, plotIndex, stride);
            return true;
        } catch (IllegalArgumentException e) {
            AgentCraft.LOGGER.warn("Ignoring a layout whose plot {} has no origin at stride {}", plotIndex, stride);
            return false;
        }
    }

    static void remove(LayoutRemoveS2C payload) {
        if (MpMode.current() != MpMode.MULTIPLAYER) return;
        StudioId studio = payload.studio();
        // The listener Studios.init registers on Anchors drops the view and the plot, inline because this
        // is the client thread. A direct Studios.remove here would notify the studio listeners twice.
        Anchors.remove(studio);
        logLayoutRemoved(studio);
    }

    static void logLayoutApplied(StudioId studio, int plotIndex, Layout layout) {
        MpLog.event(MpEvents.LAYOUT_APPLIED, "player", Anchors.self().owner(), "studio", studio.owner(), "plot",
            plotIndex, "rev", layout.revision(), "anchors", layout.anchors().size());
    }

    static void logLayoutRemoved(StudioId studio) {
        MpLog.event(MpEvents.LAYOUT_REMOVED, "player", Anchors.self().owner(), "studio", studio.owner());
    }
}
