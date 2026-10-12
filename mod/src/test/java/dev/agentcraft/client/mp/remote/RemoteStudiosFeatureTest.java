package dev.agentcraft.client.mp.remote;

import static org.junit.jupiter.api.Assertions.*;

import dev.agentcraft.client.mp.MpMode;
import dev.agentcraft.client.mp.Studios;
import dev.agentcraft.client.mp.StudioView;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.state.AgentStateWire;
import dev.agentcraft.mp.state.Counts;
import dev.agentcraft.mp.state.GoalStatusWire;
import dev.agentcraft.mp.state.GoalSummary;
import dev.agentcraft.mp.state.PublicAgent;
import dev.agentcraft.mp.state.PublicEvent;
import dev.agentcraft.mp.state.PublicPolicy;
import dev.agentcraft.mp.state.PublicStudioState;
import dev.agentcraft.mp.state.StationWire;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RemoteStudiosFeatureTest {
    private static final StudioId REMOTE = StudioId.of(
        UUID.fromString("33333333-3333-3333-3333-333333333333"));

    /**
     * One listener for the whole class, so the shared {@code Studios} registry
     * does not grow with the number of tests. Tests replace the sink; it is
     * cleared after each test.
     */
    private static final AtomicReference<BiConsumer<StudioId, PublicEvent>> EVENT_SINK =
        new AtomicReference<>((id, event) -> {});

    @BeforeAll
    static void registerEventSink() {
        Studios.addEventListener((id, event) -> EVENT_SINK.get().accept(id, event));
    }

    private static PublicStudioState state() {
        return new PublicStudioState(1, true,
            List.of(new PublicAgent("alex", "Alex", "default",
                AgentStateWire.IDLE, StationWire.DESK, true, false, false, null)),
            new Counts(0, 0, 0, 0, 0, 0, 0),
            new GoalSummary(GoalStatusWire.NONE, 0f, null),
            List.of(),
            new PublicPolicy(false, false, false, false),
            null);
    }

    @BeforeEach
    void setup() {
        Studios.setOwn(StudioId.LOCAL);
        RemoteStudiosFeature.onModeChanged(MpMode.SINGLEPLAYER);
        EVENT_SINK.set((id, event) -> {});
    }

    @AfterEach
    void teardown() {
        EVENT_SINK.set((id, event) -> {});
        Studios.reset();
        RemoteStudiosFeature.onModeChanged(MpMode.SINGLEPLAYER);
    }

    @Test
    void ownStudioStatePayloadChangesNothing() {
        // A payload whose studio equals the own studio is ignored.
        Studios.setOwn(REMOTE);
        var before = Studios.own().publicState();
        RemoteStudiosFeature.applyState(MpMode.MULTIPLAYER, REMOTE, REMOTE, state());
        assertEquals(before, Studios.own().publicState());
    }

    @Test
    void statePayloadOutsideMultiplayerChangesNothing() {
        RemoteStudiosFeature.applyState(MpMode.SINGLEPLAYER, StudioId.LOCAL,
            REMOTE, state());
        assertTrue(Studios.view(REMOTE).isEmpty());
    }

    @Test
    void remoteStatePayloadIsApplied() {
        RemoteStudiosFeature.applyState(MpMode.MULTIPLAYER, StudioId.LOCAL,
            REMOTE, state());
        assertTrue(Studios.view(REMOTE).isPresent());
        assertEquals(state(), Studios.view(REMOTE).get().publicState());
    }

    @Test
    void ownStudioPresencePayloadChangesNothing() {
        Studios.setOwn(REMOTE);
        RemoteStudiosFeature.applyPresence(MpMode.MULTIPLAYER, REMOTE, REMOTE,
            "Me", false);
        assertTrue(Studios.own().online());
    }

    @Test
    void presenceOutsideMultiplayerChangesNothing() {
        RemoteStudiosFeature.applyPresence(MpMode.SINGLEPLAYER, StudioId.LOCAL,
            REMOTE, "Bob", true);
        assertTrue(Studios.view(REMOTE).isEmpty());
    }

    @Test
    void remotePresencePayloadIsApplied() {
        RemoteStudiosFeature.applyPresence(MpMode.MULTIPLAYER, StudioId.LOCAL,
            REMOTE, "Bob", true);
        var view = Studios.view(REMOTE);
        assertTrue(view.isPresent());
        assertEquals("Bob", view.get().ownerName());
        assertTrue(view.get().online());
    }

    @Test
    void ownStudioEventPayloadDoesNotReachListeners() {
        Studios.setOwn(REMOTE);
        var received = new AtomicReference<PublicEvent>();
        EVENT_SINK.set((id, event) -> received.set(event));
        RemoteStudiosFeature.applyEvent(MpMode.MULTIPLAYER, REMOTE, REMOTE,
            new PublicEvent.Say("alex", null, null, 0));
        assertNull(received.get());
    }

    @Test
    void eventOutsideMultiplayerDoesNotReachListeners() {
        var received = new AtomicReference<PublicEvent>();
        EVENT_SINK.set((id, event) -> received.set(event));
        RemoteStudiosFeature.applyEvent(MpMode.SINGLEPLAYER, StudioId.LOCAL,
            REMOTE, new PublicEvent.Say("alex", null, null, 0));
        assertNull(received.get());
    }

    @Test
    void remoteEventReachesListeners() {
        var received = new AtomicReference<PublicEvent>();
        EVENT_SINK.set((id, event) -> received.set(event));
        var say = new PublicEvent.Say("alex", null, null, 0);
        RemoteStudiosFeature.applyEvent(MpMode.MULTIPLAYER, StudioId.LOCAL,
            REMOTE, say);
        assertEquals(say, received.get());
    }

    @Test
    void observeAddsOnceAndRemovesWithRememberedSlot() {
        var view = new StudioView(REMOTE, false, "Bob",
            true, Anchors.Layout.EMPTY, null, 4);
        try (var capture = MpLog.capture()) {
            RemoteStudiosFeature.observe(MpMode.MULTIPLAYER, REMOTE, view);
            RemoteStudiosFeature.observe(MpMode.MULTIPLAYER, REMOTE, view);
            RemoteStudiosFeature.observe(MpMode.MULTIPLAYER, REMOTE, null);
            var added = capture.lines().stream()
                .filter(l -> l.startsWith("event=remote_studio_added")).toList();
            var removed = capture.lines().stream()
                .filter(l -> l.startsWith("event=remote_studio_removed")).toList();
            assertEquals(1, added.size());
            assertEquals(1, removed.size());
            assertTrue(added.get(0).contains("slot=4"));
            assertTrue(removed.get(0).contains("slot=4"));
        }
    }

    @Test
    void observeOutsideMultiplayerDoesNotLog() {
        var view = new StudioView(REMOTE, false, "Bob",
            true, Anchors.Layout.EMPTY, null, 4);
        try (var capture = MpLog.capture()) {
            RemoteStudiosFeature.observe(MpMode.SINGLEPLAYER, REMOTE, view);
            RemoteStudiosFeature.observe(MpMode.SINGLEPLAYER, REMOTE, null);
            assertTrue(capture.lines().stream().noneMatch(
                l -> l.startsWith("event=remote_studio_")));
        }
    }

    @Test
    void owningStudioDoesNotLogRemoteTelemetry() {
        var view = new StudioView(REMOTE, true, "Bob",
            true, Anchors.Layout.EMPTY, null, 0);
        Studios.setOwn(REMOTE);
        try (var capture = MpLog.capture()) {
            RemoteStudiosFeature.observe(MpMode.MULTIPLAYER, REMOTE, view);
            RemoteStudiosFeature.observe(MpMode.MULTIPLAYER, REMOTE, null);
            assertTrue(capture.lines().stream().noneMatch(
                l -> l.startsWith("event=remote_studio_")));
        }
    }
}
