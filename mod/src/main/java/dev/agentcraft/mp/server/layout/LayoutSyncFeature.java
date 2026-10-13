package dev.agentcraft.mp.server.layout;

import dev.agentcraft.layout.Anchors;
import dev.agentcraft.layout.Anchors.Layout;
import dev.agentcraft.mp.*;
import dev.agentcraft.mp.net.LayoutRemoveS2C;
import dev.agentcraft.mp.net.LayoutS2C;
import dev.agentcraft.mp.server.StudioRange;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Dedicated-server layout fan-out driven by {@link StudioRange}. */
public final class LayoutSyncFeature {
    enum RepublishAction {
        SEND_LAYOUT, SEND_REMOVAL, NOTHING
    }

    private static final Set<StudioId> DIRTY = ConcurrentHashMap.newKeySet();
    /** Studios with a layout sent to a viewer and not withdrawn since. Server thread only. */
    private static final Set<StudioId> SENT = new HashSet<>();
    private static volatile boolean dirtyTrackingActive;
    private static boolean initialized;

    private LayoutSyncFeature() {}

    public static void init() {
        if (initialized) return;
        StudioRange.addListener(new StudioRange.Listener() {
            @Override
            public void entered(ServerPlayer viewer, Plot plot) {
                if (sendLayoutOnEnter(plot)) sendLayout(viewer.level().getServer(), viewer, plot, Anchors.forStudio(plot.owner()));
            }

            @Override
            public void left(ServerPlayer viewer, Plot plot) {
                if (sendRemoveOnLeave(plot)) sendRemove(viewer.level().getServer(), viewer, plot.owner());
            }
        });
        Anchors.addStudioListener((studio, layout) -> onAnchorsStudioChanged(studio));
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            if (enabled(server)) dirtyTrackingActive = true;
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            dirtyTrackingActive = false;
            DIRTY.clear();
            SENT.clear();
        });
        ServerTickEvents.END_SERVER_TICK.register(LayoutSyncFeature::tick);
        initialized = true;
    }

    private static void tick(MinecraftServer server) {
        if (!enabled(server)) return;
        Set<StudioId> batch = takeDirtyBatch();
        for (StudioId studio : batch) publishStudio(server, studio);
    }

    static Set<StudioId> takeDirtyBatch() {
        if (DIRTY.isEmpty()) return Set.of();
        Set<StudioId> batch = Set.copyOf(DIRTY);
        DIRTY.removeAll(batch);
        return batch;
    }

    static void onAnchorsStudioChanged(StudioId studio) {
        if (!dirtyTrackingActive || !shouldTrack(studio)) return;
        DIRTY.add(studio);
    }

    /** {@code sent}: a viewer was sent this studio's layout and no removal has followed it. */
    static RepublishAction republishAction(StudioId studio, PlotDirectory plots, boolean sent) {
        if (!shouldTrack(studio)) return RepublishAction.NOTHING;
        if (!Anchors.all().containsKey(studio)) return RepublishAction.SEND_REMOVAL;
        // An empty layout is never sent, so viewers that hold an earlier one must drop it.
        if (Anchors.forStudio(studio).isEmpty()) return sent ? RepublishAction.SEND_REMOVAL : RepublishAction.NOTHING;
        if (plots.plotOf(studio).isEmpty()) return RepublishAction.NOTHING;
        return RepublishAction.SEND_LAYOUT;
    }

    static void publishStudio(MinecraftServer server, StudioId studio) {
        if (!enabled(server)) return;
        switch (republishAction(studio, Plots.directory(), SENT.contains(studio))) {
            case SEND_REMOVAL -> {
                for (ServerPlayer viewer : StudioRange.viewersOf(server, studio)) sendRemove(server, viewer, studio);
                SENT.remove(studio);
            }
            case SEND_LAYOUT -> {
                Plot plot = Plots.directory().plotOf(studio).orElseThrow();
                Layout layout = Anchors.forStudio(studio);
                for (ServerPlayer viewer : StudioRange.viewersOf(server, studio)) sendLayout(server, viewer, plot, layout);
            }
            case NOTHING -> {}
        }
    }

    static boolean shouldTrack(StudioId studio) {
        return !studio.equals(StudioId.LOCAL);
    }

    static boolean sendLayoutOnEnter(Plot plot) {
        if (!shouldTrack(plot.owner())) return false;
        return Anchors.all().containsKey(plot.owner()) && !Anchors.forStudio(plot.owner()).isEmpty();
    }

    static boolean sendRemoveOnLeave(Plot plot) {
        return shouldTrack(plot.owner());
    }

    static void setDirtyTrackingActive(boolean active) {
        dirtyTrackingActive = active;
        if (!active) DIRTY.clear();
    }

    static boolean dirtyTrackingActive() {
        return dirtyTrackingActive;
    }

    static int dirtyCount() {
        return DIRTY.size();
    }

    private static boolean enabled(MinecraftServer server) {
        return server.isDedicatedServer() && MpServerConfig.current().enabled();
    }

    private static void sendLayout(MinecraftServer server, ServerPlayer viewer, Plot plot, Layout layout) {
        if (!enabled(server) || layout.isEmpty()) return;
        if (!ServerPlayNetworking.canSend(viewer, LayoutS2C.TYPE)) return;
        ServerPlayNetworking.send(viewer, new LayoutS2C(plot.owner(), plot.index(), layout));
        SENT.add(plot.owner());
        logLayoutSent(viewer.getUUID(), plot, layout);
    }

    private static void sendRemove(MinecraftServer server, ServerPlayer viewer, StudioId studio) {
        if (!enabled(server)) return;
        if (!ServerPlayNetworking.canSend(viewer, LayoutRemoveS2C.TYPE)) return;
        ServerPlayNetworking.send(viewer, new LayoutRemoveS2C(studio));
    }

    static void logLayoutSent(UUID viewer, Plot plot, Layout layout) {
        MpLog.event(MpEvents.LAYOUT_SENT, "to", viewer, "studio", plot.owner().owner(), "plot", plot.index(), "rev",
            layout.revision(), "anchors", layout.anchors().size());
    }
}
