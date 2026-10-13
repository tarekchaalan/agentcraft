package dev.agentcraft.mp.relay;

import static dev.agentcraft.mp.relay.StudioRelayAcceptTest.FixedViewers;
import static dev.agentcraft.mp.relay.StudioRelayAcceptTest.RecordingSink;
import static dev.agentcraft.mp.relay.StudioRelayAcceptTest.agent;
import static dev.agentcraft.mp.relay.StudioRelayAcceptTest.fakeDir;
import static dev.agentcraft.mp.relay.StudioRelayAcceptTest.minimalState;
import static dev.agentcraft.mp.relay.StudioRelayAcceptTest.policy;
import static dev.agentcraft.mp.relay.StudioRelayAcceptTest.send;
import static dev.agentcraft.mp.relay.StudioRelayAcceptTest.state;
import static org.junit.jupiter.api.Assertions.*;

import dev.agentcraft.mp.MpEvents;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.MpReasons;
import dev.agentcraft.mp.MpServerConfig;
import dev.agentcraft.mp.Plot;
import dev.agentcraft.mp.PlotDirectory;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.server.relay.StudioRelay;
import dev.agentcraft.mp.state.PublicEvent;
import dev.agentcraft.mp.state.PublicJson;
import dev.agentcraft.mp.state.PublicStudioState;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StudioRelayTelemetryTest {
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();
    private static final UUID VIEWER = UUID.randomUUID();

    @BeforeEach @AfterEach
    void clean() { StudioRelay.reset(); }

    /** Asserts one captured line equals the expected line exactly. */
    private static void assertLine(List<String> lines, String expected) {
        assertTrue(lines.contains(expected),
            "expected line missing: " + expected + "\ncaptured: " + lines);
    }

    @Test
    void captures_public_state_rejected_no_plot_with_correlators() {
        var dir = fakeDir(OWNER);
        try (var capture = MpLog.capture()) {
            StudioRelay.acceptState(STRANGER, minimalState(), dir,
                MpServerConfig.DEFAULT, true, 0);
            var expected = "event=" + MpEvents.PUBLIC_STATE_REJECTED
                + " player=" + STRANGER + " studio=" + STRANGER + " rev=1"
                + " reason=" + MpReasons.NO_PLOT;
            assertLine(capture.lines(), expected);
        }
    }

    @Test
    void captures_public_state_rejected_bad_version() {
        var dir = fakeDir(OWNER);
        try (var capture = MpLog.capture()) {
            StudioRelay.acceptState(OWNER, minimalState(), dir,
                MpServerConfig.DEFAULT, false, 0);
            var expected = "event=" + MpEvents.PUBLIC_STATE_REJECTED
                + " player=" + OWNER + " studio=" + OWNER + " rev=1"
                + " reason=" + MpReasons.BAD_VERSION;
            assertLine(capture.lines(), expected);
        }
    }

    @Test
    void captures_public_state_rejected_rate_limited_with_plot_and_rev() {
        var dir = fakeDir(OWNER);
        var cfg = new MpServerConfig(true, 128, true, true, true, true, true,
            12, 1, 10);
        try (var capture = MpLog.capture()) {
            StudioRelay.acceptState(OWNER, minimalState(), dir, cfg, true, 0);
            StudioRelay.acceptState(OWNER, minimalState(), dir, cfg, true, 0);
            StudioRelay.acceptState(OWNER, minimalState(), dir, cfg, true, 0);
            var expected = "event=" + MpEvents.PUBLIC_STATE_REJECTED
                + " player=" + OWNER + " studio=" + OWNER + " plot=1 rev=1"
                + " reason=" + MpReasons.RATE_LIMITED;
            assertLine(capture.lines(), expected);
        }
    }

    @Test
    void captures_studio_event_rejected_no_plot() {
        var dir = fakeDir(OWNER);
        try (var capture = MpLog.capture()) {
            StudioRelay.acceptEvent(STRANGER,
                new PublicEvent.Say("alex", null, null, 0), dir,
                MpServerConfig.DEFAULT, true, 0);
            var expected = "event=" + MpEvents.STUDIO_EVENT_REJECTED
                + " player=" + STRANGER + " studio=" + STRANGER
                + " reason=" + MpReasons.NO_PLOT;
            assertLine(capture.lines(), expected);
        }
    }

    @Test
    void captures_studio_event_rejected_bad_version() {
        var dir = fakeDir(OWNER);
        try (var capture = MpLog.capture()) {
            StudioRelay.acceptEvent(OWNER,
                new PublicEvent.Say("alex", null, null, 0), dir,
                MpServerConfig.DEFAULT, false, 0);
            var expected = "event=" + MpEvents.STUDIO_EVENT_REJECTED
                + " player=" + OWNER + " studio=" + OWNER
                + " reason=" + MpReasons.BAD_VERSION;
            assertLine(capture.lines(), expected);
        }
    }

    @Test
    void captures_studio_event_rejected_unknown_agent_with_plot() {
        var dir = fakeDir(OWNER);
        StudioRelay.acceptState(OWNER, minimalState(), dir,
            MpServerConfig.DEFAULT, true, 0);
        try (var capture = MpLog.capture()) {
            StudioRelay.acceptEvent(OWNER,
                new PublicEvent.Say("bob", null, null, 0), dir,
                MpServerConfig.DEFAULT, true, 0);
            var expected = "event=" + MpEvents.STUDIO_EVENT_REJECTED
                + " player=" + OWNER + " studio=" + OWNER + " plot=1"
                + " reason=" + MpReasons.UNKNOWN_AGENT;
            assertLine(capture.lines(), expected);
        }
    }

    @Test
    void captures_studio_event_rejected_rate_limited() {
        var dir = fakeDir(OWNER);
        var cfg = new MpServerConfig(true, 128, true, true, true, true, true,
            12, 1, 10);
        StudioRelay.acceptState(OWNER, minimalState(), dir, cfg, true, 0);
        try (var capture = MpLog.capture()) {
            StudioRelay.acceptEvent(OWNER,
                new PublicEvent.Say("alex", null, null, 0), dir, cfg, true, 0);
            StudioRelay.acceptEvent(OWNER,
                new PublicEvent.Say("alex", null, null, 0), dir, cfg, true, 0);
            StudioRelay.acceptEvent(OWNER,
                new PublicEvent.Say("alex", null, null, 0), dir, cfg, true, 0);
            var expected = "event=" + MpEvents.STUDIO_EVENT_REJECTED
                + " player=" + OWNER + " studio=" + OWNER + " plot=1"
                + " reason=" + MpReasons.RATE_LIMITED;
            assertLine(capture.lines(), expected);
        }
    }

    @Test
    void flood_of_refusals_logs_one_line_per_sender_and_reason_per_window() {
        var prefix = "event=" + MpEvents.PUBLIC_STATE_REJECTED
            + " player=" + STRANGER + " studio=" + STRANGER;
        var noPlot = prefix + " rev=1 reason=" + MpReasons.NO_PLOT;
        try (var capture = MpLog.capture()) {
            // 1,000 states in one second from a sender without a plot.
            for (int i = 0; i < 1000; i++) send(STRANGER, i * 1_000_000L);
            assertEquals(List.of(noPlot, prefix + " plot=-1 rev=1 reason="
                + MpReasons.RATE_LIMITED), capture.lines());
            // A second sender has its own bucket and its own line.
            assertEquals(StudioRelay.StateResult.NO_PLOT,
                send(UUID.randomUUID(), 999_000_000L));
            assertEquals(3, capture.lines().size());
            // The reason is logged again after five seconds, not before.
            send(STRANGER, StudioRelay.WINDOW_NANOS - 1);
            assertEquals(3, capture.lines().size());
            send(STRANGER, StudioRelay.WINDOW_NANOS);
            assertEquals(noPlot, capture.lines().get(3));
        }
    }

    @Test
    void captures_relay_sent_state_whole_line_with_bytes_and_sent_recipients() {
        var dir = fakeDir(OWNER);
        StudioRelay.acceptState(OWNER, minimalState(), dir,
            MpServerConfig.DEFAULT, true, 0);
        // Two viewers; the sink refuses one, so recipients must count one.
        var refused = UUID.randomUUID();
        var sink = new RecordingSink();
        sink.blocked.add(refused);
        var bytes = PublicJson.toJson(minimalState()).toString()
            .getBytes(StandardCharsets.UTF_8).length;
        try (var capture = MpLog.capture()) {
            StudioRelay.relayState(new FixedViewers(List.of(VIEWER, refused)),
                sink, StudioId.of(OWNER), dir);
            var expected = "event=" + MpEvents.RELAY_SENT
                + " player=" + OWNER + " studio=" + OWNER + " plot=1 rev=1"
                + " recipients=1 bytes=" + bytes;
            assertLine(capture.lines(), expected);
        }
    }

    @Test
    void captures_relay_sent_event_whole_line_with_bytes_and_sent_recipients() {
        var dir = fakeDir(OWNER);
        StudioRelay.acceptState(OWNER,
            state(List.of(agent("alex")), policy(false), 1, true),
            dir, MpServerConfig.DEFAULT, true, 0);
        // Three viewers; the sink refuses one, so recipients must count two (not the three attempts).
        var second = UUID.randomUUID();
        var refused = UUID.randomUUID();
        var sink = new RecordingSink();
        sink.blocked.add(refused);
        var event = new PublicEvent.Say("alex", null, null, 0);
        var bytes = PublicJson.toJson(event).toString()
            .getBytes(StandardCharsets.UTF_8).length;
        try (var capture = MpLog.capture()) {
            StudioRelay.relayEvent(new FixedViewers(List.of(VIEWER, refused, second)), sink,
                StudioId.of(OWNER), event, dir);
            var expected = "event=" + MpEvents.RELAY_SENT
                + " player=" + OWNER + " studio=" + OWNER + " plot=1"
                + " recipients=2 bytes=" + bytes;
            assertLine(capture.lines(), expected);
            assertEquals(List.of(VIEWER, second), sink.events);
        }
    }

    @Test
    void captures_presence_online_and_offline() {
        var dir = fakeDir(OWNER);
        var sink = new RecordingSink();
        try (var capture = MpLog.capture()) {
            StudioRelay.onJoin(new FixedViewers(List.of()), sink, OWNER, "Bob",
                dir, MpServerConfig.DEFAULT);
            assertLine(capture.lines(), "event=" + MpEvents.PRESENCE
                + " player=" + OWNER + " studio=" + OWNER + " plot=1 online=true");
            StudioRelay.onDisconnect(new FixedViewers(List.of()), sink, OWNER,
                "Bob", dir, MpServerConfig.DEFAULT);
            assertLine(capture.lines(), "event=" + MpEvents.PRESENCE
                + " player=" + OWNER + " studio=" + OWNER + " plot=1 online=false");
        }
    }

    @Test
    void speech_text_is_cleared_when_off_or_absent_and_kept_when_on() {
        // Distinct marker for the speech text. Each case must produce the
        // event's own relay_sent line and leave the marker out of it.
        var sayMarker = "SAY_MARKER_4b7a";
        var dir = fakeDir(OWNER);

        // sayText off: cleared on the wire, never logged.
        StudioRelay.acceptState(OWNER,
            state(List.of(agent("alex")), policy(false), 1, true),
            dir, MpServerConfig.DEFAULT, true, 0);
        var offSink = new RecordingSink();
        try (var capture = MpLog.capture()) {
            StudioRelay.relayEvent(new FixedViewers(List.of(VIEWER)), offSink,
                StudioId.of(OWNER),
                new PublicEvent.Say("alex", null, sayMarker, sayMarker.length()),
                dir);
            assertTrue(capture.lines().stream().anyMatch(
                l -> l.startsWith("event=" + MpEvents.RELAY_SENT)),
                "sayText-off event must log its own relay_sent");
            assertNull(((PublicEvent.Say) offSink.eventPayloads.get(0).event()).text());
            for (String line : capture.lines()) assertFalse(line.contains(sayMarker), line);
        }

        // sayText on: text kept on the wire, still never logged.
        StudioRelay.reset();
        StudioRelay.acceptState(OWNER,
            state(List.of(agent("alex")), policy(true), 1, true),
            dir, MpServerConfig.DEFAULT, true, 0);
        var onSink = new RecordingSink();
        try (var capture = MpLog.capture()) {
            StudioRelay.relayEvent(new FixedViewers(List.of(VIEWER)), onSink,
                StudioId.of(OWNER),
                new PublicEvent.Say("alex", null, sayMarker, sayMarker.length()),
                dir);
            assertTrue(capture.lines().stream().anyMatch(
                l -> l.startsWith("event=" + MpEvents.RELAY_SENT)),
                "sayText-on event must log its own relay_sent");
            assertEquals(sayMarker,
                ((PublicEvent.Say) onSink.eventPayloads.get(0).event()).text());
            for (String line : capture.lines()) assertFalse(line.contains(sayMarker), line);
        }

        // No stored state: cleared by default, never logged.
        StudioRelay.reset();
        var absentSink = new RecordingSink();
        try (var capture = MpLog.capture()) {
            StudioRelay.relayEvent(new FixedViewers(List.of(VIEWER)), absentSink,
                StudioId.of(OWNER),
                new PublicEvent.Say("alex", null, sayMarker, sayMarker.length()),
                dir);
            assertTrue(capture.lines().stream().anyMatch(
                l -> l.startsWith("event=" + MpEvents.RELAY_SENT)),
                "no-state event must log its own relay_sent");
            assertNull(((PublicEvent.Say) absentSink.eventPayloads.get(0).event()).text());
            for (String line : capture.lines()) assertFalse(line.contains(sayMarker), line);
        }
    }

    @Test
    void telemetry_with_optin_activity_state_contains_no_activity_text() {
        var marker = "ACTIVITY_MARKER_7c1a";
        var dir = fakeDir(OWNER);
        var optIn = state(
            List.of(new dev.agentcraft.mp.state.PublicAgent("alex", "Alex",
                "default", dev.agentcraft.mp.state.AgentStateWire.IDLE,
                dev.agentcraft.mp.state.StationWire.DESK, true, false, false,
                marker)),
            new dev.agentcraft.mp.state.PublicPolicy(true, true, false, false),
            1, true);
        try (var capture = MpLog.capture()) {
            StudioRelay.acceptState(OWNER, optIn, dir,
                MpServerConfig.DEFAULT, true, 0);
            var sink = new RecordingSink();
            StudioRelay.relayState(new FixedViewers(List.of(VIEWER)), sink,
                StudioId.of(OWNER), dir);
            assertTrue(capture.lines().stream().anyMatch(
                l -> l.startsWith("event=" + MpEvents.RELAY_SENT)),
                "expected a relay_sent line");
            for (String line : capture.lines())
                assertFalse(line.contains(marker), line);
        }
    }
}
