package dev.agentcraft.client.mp.layout;

import static org.junit.jupiter.api.Assertions.*;

import dev.agentcraft.client.mp.MpMode;
import dev.agentcraft.client.mp.Studios;
import dev.agentcraft.client.mp.StudioView;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.*;
import dev.agentcraft.mp.net.*;
import java.util.*;
import java.util.concurrent.Executor;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class LayoutSyncClientTest {
    private boolean bridged;

    @AfterEach
    void reset() {
        bridged = false;
        MpMode.disconnected();
        Studios.reset();
        Anchors.publish(Anchors.Layout.EMPTY);
    }

    private static void enterMultiplayer(UUID player) {
        StudioId own = StudioId.of(player);
        var hello = new HelloS2C(MpProtocol.VERSION, own, 1, new ServerInfo(128, 12, 4, 10));
        MpMode.joined(false, MpMode.hashServer("layout-sync.test:25604"));
        assertTrue(MpMode.receiveHello(hello, false, player));
    }

    /**
     * Registers the listener {@code Studios.init} registers. {@code Studios.init} itself hands the work to
     * {@code Minecraft.getInstance()}, which is null without a running client, so {@code client} stands in:
     * {@code Runnable::run} is what the Minecraft executor does for a caller already on the client thread.
     * Neither registry can drop a listener, so this one goes quiet when the test ends.
     */
    private void bridgeAnchorsToStudios(Executor client) {
        bridged = true;
        Anchors.addStudioListener((studio, layout) -> { if (bridged) Studios.anchorsChanged(client, studio, layout); });
    }

    private static LayoutS2C samplePayload(StudioId remote) {
        var layout = Anchors.builder("remote").bounds(-46, 60, -36, 46, 100, 54).put("desk", 0, 64, 0, 0, 0).build();
        layout = new Anchors.Layout(layout.name(), 7, layout.bounds(), layout.anchors());
        return new LayoutS2C(remote, 2, layout);
    }

    @Test
    void apply_publishes_server_revision_and_registers_plot() {
        UUID player = UUID.randomUUID();
        StudioId remote = StudioId.of(UUID.randomUUID());
        enterMultiplayer(player);
        var payload = samplePayload(remote);
        LayoutSyncClient.apply(payload);
        assertEquals(payload.layout(), Anchors.forStudio(remote));
        assertEquals(PlotGrid.originOf(2, 128), Studios.plot(remote).orElseThrow().origin());
    }

    @Test
    void layout_applied_telemetry_lists_studio_plot_rev_and_anchors() {
        UUID player = UUID.randomUUID();
        StudioId remote = StudioId.of(UUID.randomUUID());
        enterMultiplayer(player);
        var payload = samplePayload(remote);
        try (var capture = MpLog.capture()) {
            LayoutSyncClient.apply(payload);
            assertEquals(1, capture.lines().stream().filter(l -> l.contains(MpEvents.LAYOUT_APPLIED)).count());
            String applied = capture.lines().stream().filter(l -> l.contains(MpEvents.LAYOUT_APPLIED)).findFirst().orElseThrow();
            assertTrue(applied.contains("anchors=1"));
            assertTrue(applied.contains("rev=7"));
            assertTrue(applied.contains("plot=2"));
            assertTrue(applied.contains("studio=" + remote.owner()));
        }
    }

    @Test
    void apply_ignores_a_plot_index_the_grid_cannot_place() {
        UUID player = UUID.randomUUID();
        StudioId remote = StudioId.of(UUID.randomUUID());
        StudioId own = StudioId.of(player);
        // The widest stride the hello codec accepts: ring 29 is already past the world border.
        MpMode.joined(false, MpMode.hashServer("layout-sync.test:25604"));
        assertTrue(MpMode.receiveHello(new HelloS2C(MpProtocol.VERSION, own, 1, new ServerInfo(1048576, 12, 4, 10)), false, player));
        var good = samplePayload(remote);
        LayoutSyncClient.apply(good);
        Plot before = Studios.plot(remote).orElseThrow();
        var newer = new Anchors.Layout("remote", 8, good.layout().bounds(), good.layout().anchors());
        try (var capture = MpLog.capture()) {
            assertDoesNotThrow(() -> LayoutSyncClient.apply(new LayoutS2C(remote, Integer.MAX_VALUE, newer)));
            assertTrue(capture.lines().isEmpty());
        }
        assertEquals(good.layout(), Anchors.forStudio(remote));
        assertEquals(before, Studios.plot(remote).orElseThrow());
    }

    @Test
    void remove_clears_layout_plot_view_and_logs_removed() {
        UUID player = UUID.randomUUID();
        StudioId remote = StudioId.of(UUID.randomUUID());
        enterMultiplayer(player);
        bridgeAnchorsToStudios(Runnable::run);
        LayoutSyncClient.apply(samplePayload(remote));
        assertTrue(Studios.view(remote).isPresent());
        try (var capture = MpLog.capture()) {
            LayoutSyncClient.remove(new LayoutRemoveS2C(remote));
            assertTrue(Anchors.forStudio(remote).isEmpty());
            assertTrue(Studios.plot(remote).isEmpty());
            assertTrue(Studios.view(remote).isEmpty());
            assertEquals(1, capture.lines().stream().filter(l -> l.contains(MpEvents.LAYOUT_REMOVED)).count());
        }
    }

    @Test
    void remove_notifies_studio_listeners_exactly_once() {
        UUID player = UUID.randomUUID();
        StudioId remote = StudioId.of(UUID.randomUUID());
        enterMultiplayer(player);
        bridgeAnchorsToStudios(Runnable::run);
        List<StudioView> updates = new ArrayList<>();
        List<StudioId> removals = new ArrayList<>();
        Studios.addListener((id, view) -> {
            if (!bridged) return;
            if (view == null) removals.add(id); else updates.add(view);
        });
        var payload = samplePayload(remote);
        LayoutSyncClient.apply(payload);
        assertEquals(1, updates.size());
        assertEquals(payload.layout(), updates.getFirst().layout());
        assertTrue(removals.isEmpty());
        LayoutSyncClient.remove(new LayoutRemoveS2C(remote));
        assertEquals(List.of(remote), removals);
        assertEquals(1, updates.size());
        assertTrue(Studios.view(remote).isEmpty());
        assertTrue(Studios.plot(remote).isEmpty());
    }

    @Test
    void apply_and_remove_noop_outside_multiplayer() {
        UUID player = UUID.randomUUID();
        StudioId remote = StudioId.of(UUID.randomUUID());
        MpMode.joined(false, MpMode.hashServer("layout-sync.test:25604"));
        assertEquals(MpMode.SINGLEPLAYER, MpMode.current());
        var layout = Anchors.builder("ghost").bounds(0, 60, 0, 1, 70, 1).put("x", 0, 64, 0, 0, 0).build();
        Anchors.publish(remote, layout);
        try (var capture = MpLog.capture()) {
            LayoutSyncClient.apply(samplePayload(remote));
            assertEquals(layout, Anchors.forStudio(remote));
            assertTrue(Studios.plot(remote).isEmpty());
            assertTrue(capture.lines().stream().noneMatch(l -> l.contains(MpEvents.LAYOUT_APPLIED)));
        }
        LayoutSyncClient.remove(new LayoutRemoveS2C(remote));
        assertEquals(layout, Anchors.forStudio(remote));
    }
}
