package dev.agentcraft.mp.relay;

import static org.junit.jupiter.api.Assertions.*;

import dev.agentcraft.mp.MpReasons;
import dev.agentcraft.mp.MpServerConfig;
import dev.agentcraft.mp.Plot;
import dev.agentcraft.mp.PlotDirectory;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.net.PresenceS2C;
import dev.agentcraft.mp.net.StudioEventS2C;
import dev.agentcraft.mp.net.StudioStateS2C;
import dev.agentcraft.mp.server.relay.StudioRelay;
import dev.agentcraft.mp.state.AgentStateWire;
import dev.agentcraft.mp.state.Counts;
import dev.agentcraft.mp.state.GoalStatusWire;
import dev.agentcraft.mp.state.GoalSummary;
import dev.agentcraft.mp.state.PublicAgent;
import dev.agentcraft.mp.state.PublicEvent;
import dev.agentcraft.mp.state.PublicPolicy;
import dev.agentcraft.mp.state.PublicStudioState;
import dev.agentcraft.mp.state.StationWire;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import net.minecraft.core.BlockPos;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StudioRelayAcceptTest {
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();
    private static final UUID VIEWER = UUID.randomUUID();

    static PlotDirectory fakeDir(UUID owner) {
        return new PlotDirectory() {
            public Optional<Plot> plotOf(StudioId id) {
                return id.owner().equals(owner)
                    ? Optional.of(new Plot(1, id, BlockPos.ZERO))
                    : Optional.empty();
            }
            public Optional<Plot> plotAt(BlockPos pos) { return Optional.empty(); }
            public Collection<Plot> all() {
                return List.of(new Plot(1, StudioId.of(owner), BlockPos.ZERO));
            }
        };
    }

    /** Records every payload the relay hands to the sink. */
    static final class RecordingSink implements StudioRelay.SendSink {
        final List<UUID> states = new ArrayList<>();
        final List<UUID> presences = new ArrayList<>();
        final List<UUID> events = new ArrayList<>();
        final List<StudioStateS2C> statePayloads = new ArrayList<>();
        final List<PresenceS2C> presencePayloads = new ArrayList<>();
        final List<StudioEventS2C> eventPayloads = new ArrayList<>();
        /** Recipients that canSend refuses; they are counted but never sent. */
        final java.util.Set<UUID> blocked = new java.util.HashSet<>();

        public boolean sendState(UUID recipient, StudioStateS2C payload) {
            if (blocked.contains(recipient)) return false;
            states.add(recipient); statePayloads.add(payload); return true;
        }
        public boolean sendPresence(UUID recipient, PresenceS2C payload) {
            if (blocked.contains(recipient)) return false;
            presences.add(recipient); presencePayloads.add(payload); return true;
        }
        public boolean sendEvent(UUID recipient, StudioEventS2C payload) {
            if (blocked.contains(recipient)) return false;
            events.add(recipient); eventPayloads.add(payload); return true;
        }
    }

    static final class FixedViewers implements StudioRelay.ViewerSource {
        final List<UUID> viewers;
        FixedViewers(List<UUID> viewers) { this.viewers = viewers; }
        public List<UUID> viewersOf(StudioId studio) { return viewers; }
    }

    /** Connectivity source with a fixed answer for every player. */
    static StudioRelay.OnlineSource alwaysOnline(boolean online) {
        return player -> online;
    }

    static PublicStudioState state(List<PublicAgent> agents, PublicPolicy policy,
            int rev, boolean online) {
        return new PublicStudioState(rev, online, agents,
            new Counts(0, 0, 0, 0, 0, 0, 0),
            new GoalSummary(GoalStatusWire.NONE, 0f, null),
            List.of(), policy, null);
    }

    static PublicAgent agent(String id) {
        return new PublicAgent(id, "Name-" + id, "default",
            AgentStateWire.IDLE, StationWire.DESK, true, false, false, null);
    }

    static PublicPolicy policy(boolean sayText) {
        return new PublicPolicy(false, sayText, false, false);
    }

    static PublicStudioState minimalState() {
        return state(List.of(agent("alex")), policy(false), 1, true);
    }

    /** One state from {@code player} at {@code nanos}; only OWNER has a plot. */
    static StudioRelay.StateResult send(UUID player, long nanos) {
        return StudioRelay.acceptState(player, minimalState(), fakeDir(OWNER),
            MpServerConfig.DEFAULT, true, nanos);
    }

    @BeforeEach @AfterEach
    void clean() { StudioRelay.reset(); }

    @Test
    void every_packet_takes_a_token_before_any_other_check() {
        // Rate 4: eight tokens, no refill at one timestamp; no plot, no hello.
        var say = new PublicEvent.Say("alex", null, null, 0);
        for (int i = 0; i < 8; i++) {
            assertEquals(StudioRelay.StateResult.NO_PLOT, send(STRANGER, 0));
            assertEquals(StudioRelay.EventResult.BAD_VERSION, StudioRelay.acceptEvent(
                OWNER, say, fakeDir(OWNER), MpServerConfig.DEFAULT, false, 0));
        }
        assertEquals(StudioRelay.StateResult.RATE_LIMITED, send(STRANGER, 0));
        assertEquals(StudioRelay.EventResult.RATE_LIMITED, StudioRelay.acceptEvent(
            OWNER, say, fakeDir(OWNER), MpServerConfig.DEFAULT, false, 0));
    }

    @Test
    void bucket_survives_a_reconnect_and_is_dropped_once_idle() {
        var dir = fakeDir(OWNER);
        var none = new FixedViewers(List.of());
        var sink = new RecordingSink();
        for (int i = 0; i < 8; i++) send(OWNER, 0);
        StudioRelay.onDisconnect(none, sink, OWNER, "Bob", dir, MpServerConfig.DEFAULT);
        StudioRelay.onJoin(none, sink, OWNER, "Bob", dir, MpServerConfig.DEFAULT);
        assertEquals(StudioRelay.StateResult.RATE_LIMITED, send(OWNER, 0));
        // A window later the idle senders are forgotten: what is held is the
        // newcomer's bucket and the throttle entry of its refusal.
        for (int i = 0; i < 100; i++) send(UUID.randomUUID(), 0);
        send(STRANGER, StudioRelay.WINDOW_NANOS);
        assertEquals(2, StudioRelay.tracked());
    }

    @Test
    void state_of_a_freed_plot_is_dropped_and_not_served_again() {
        var dir = fakeDir(OWNER);
        var plot = dir.plotOf(StudioId.of(OWNER)).orElseThrow();
        var none = new FixedViewers(List.of());
        var sink = new RecordingSink();
        send(OWNER, 0);
        // Freed: entering range of the old plot sends nothing and drops the state.
        StudioRelay.onEntered(none, sink, alwaysOnline(true), VIEWER, plot,
            fakeDir(STRANGER), MpServerConfig.DEFAULT);
        assertTrue(sink.presences.isEmpty() && sink.states.isEmpty());
        assertNull(StudioRelay.stored(StudioId.of(OWNER)));
        // Allocated again: presence, but no state from before.
        StudioRelay.onEntered(none, sink, alwaysOnline(true), VIEWER, plot, dir,
            MpServerConfig.DEFAULT);
        assertEquals(1, sink.presences.size());
        assertTrue(sink.states.isEmpty());
    }

    @Test
    void rate_limited_packet_after_a_free_drops_the_state_logged_or_not() {
        var dir = fakeDir(OWNER);
        var freed = fakeDir(STRANGER);
        var plot = dir.plotOf(StudioId.of(OWNER)).orElseThrow();
        var say = new PublicEvent.Say("alex", null, null, 0);
        var cfg = MpServerConfig.DEFAULT;
        // Eight tokens a bucket. Spending a ninth logs the refusal, so the
        // refusal after the free is throttled; spending eight leaves it logged.
        for (int spent : new int[] {8, 9}) for (boolean event : new boolean[] {false, true}) {
            StudioRelay.reset();
            for (int i = 0; i < spent; i++) {
                send(OWNER, 0);
                StudioRelay.acceptEvent(OWNER, say, dir, cfg, true, 0);
            }
            // Freed: the next packet is over the rate, and the state still goes.
            if (event) assertEquals(StudioRelay.EventResult.RATE_LIMITED,
                StudioRelay.acceptEvent(OWNER, say, freed, cfg, true, 0));
            else assertEquals(StudioRelay.StateResult.RATE_LIMITED,
                StudioRelay.acceptState(OWNER, minimalState(), freed, cfg, true, 0));
            assertNull(StudioRelay.stored(StudioId.of(OWNER)), spent + " " + event);
            // Allocated again: range entry sends presence and no state from before.
            var sink = new RecordingSink();
            StudioRelay.onEntered(new FixedViewers(List.of()), sink, alwaysOnline(true),
                VIEWER, plot, dir, cfg);
            assertEquals(1, sink.presences.size());
            assertTrue(sink.states.isEmpty(), spent + " " + event);
        }
    }

    @Test
    void event_relay_for_a_studio_without_a_plot_drops_its_state() {
        send(OWNER, 0);
        StudioRelay.relayEvent(new FixedViewers(List.of()), new RecordingSink(),
            StudioId.of(OWNER), new PublicEvent.TaskDone("alex"), fakeDir(STRANGER));
        assertNull(StudioRelay.stored(StudioId.of(OWNER)));
    }

    @Test
    void relay_say_to_is_kept_only_for_user_or_an_agent_of_the_studio() {
        var dir = fakeDir(OWNER);
        StudioRelay.acceptState(OWNER,
            state(List.of(agent("alex"), agent("tove")), policy(true), 1, true),
            dir, MpServerConfig.DEFAULT, true, 0);
        var sink = new RecordingSink();
        for (String to : new String[] {"user", "tove", "bob", "all"})
            StudioRelay.relayEvent(new FixedViewers(List.of(VIEWER)), sink,
                StudioId.of(OWNER), new PublicEvent.Say("alex", to, "hi", 2), dir);
        assertEquals(java.util.Arrays.asList("user", "tove", null, null),
            sink.eventPayloads.stream().map(p -> ((PublicEvent.Say) p.event()).to()).toList());
        assertEquals("hi", ((PublicEvent.Say) sink.eventPayloads.get(2).event()).text());
    }

    @Test
    void accepts_state_from_owner_with_plot() {
        var dir = fakeDir(OWNER);
        var result = StudioRelay.acceptState(OWNER, minimalState(), dir,
            MpServerConfig.DEFAULT, true, 0);
        assertEquals(StudioRelay.StateResult.ACCEPTED, result);
        assertNotNull(StudioRelay.stored(StudioId.of(OWNER)));
    }

    @Test
    void refuses_state_when_no_plot() {
        var dir = fakeDir(OWNER);
        var result = StudioRelay.acceptState(STRANGER, minimalState(), dir,
            MpServerConfig.DEFAULT, true, 0);
        assertEquals(StudioRelay.StateResult.NO_PLOT, result);
        assertNull(StudioRelay.stored(StudioId.of(STRANGER)));
    }

    @Test
    void refuses_state_when_not_equipped() {
        var dir = fakeDir(OWNER);
        var result = StudioRelay.acceptState(OWNER, minimalState(), dir,
            MpServerConfig.DEFAULT, false, 0);
        assertEquals(StudioRelay.StateResult.BAD_VERSION, result);
        assertNull(StudioRelay.stored(StudioId.of(OWNER)));
    }

    @Test
    void refuses_state_when_rate_limited() {
        var dir = fakeDir(OWNER);
        var cfg = new MpServerConfig(true, 128, true, true, true, true, true,
            12, 4, 10);
        // Explicit timestamp 0 for every call: the bucket starts with 2×rate=8
        // tokens and never refills. Distinct content so each is a real change.
        for (int i = 0; i < 8; i++) {
            var next = state(List.of(agent("a" + i)), policy(false), i + 1, true);
            assertEquals(StudioRelay.StateResult.ACCEPTED,
                StudioRelay.acceptState(OWNER, next, dir, cfg, true, 0),
                "accept " + i);
        }
        assertEquals(StudioRelay.StateResult.RATE_LIMITED,
            StudioRelay.acceptState(OWNER,
                state(List.of(agent("refused")), policy(false), 9, true),
                dir, cfg, true, 0));
    }

    @Test
    void rate_bucket_rebuilds_when_config_rate_changes() {
        var dir = fakeDir(OWNER);
        var slow = new MpServerConfig(true, 128, true, true, true, true, true,
            12, 1, 10);
        var fast = new MpServerConfig(true, 128, true, true, true, true, true,
            12, 50, 10);
        // Slow rate: 2 tokens, third call refused.
        StudioRelay.acceptState(OWNER, minimalState(), dir, slow, true, 0);
        StudioRelay.acceptState(OWNER, minimalState(), dir, slow, true, 0);
        assertEquals(StudioRelay.StateResult.RATE_LIMITED,
            StudioRelay.acceptState(OWNER, minimalState(), dir, slow, true, 0));
        // A higher rate rebuilds the bucket, so the next call is accepted.
        assertEquals(StudioRelay.StateResult.UNCHANGED,
            StudioRelay.acceptState(OWNER, minimalState(), dir, fast, true, 0));
    }

    @Test
    void state_is_attributed_to_sender_not_payload() {
        var dir = fakeDir(OWNER);
        var result = StudioRelay.acceptState(OWNER, minimalState(), dir,
            MpServerConfig.DEFAULT, true, 0);
        assertEquals(StudioRelay.StateResult.ACCEPTED, result);
        assertEquals(minimalState(), StudioRelay.stored(StudioId.of(OWNER)));
        assertNull(StudioRelay.stored(StudioId.LOCAL));
    }

    @Test
    void unchanged_state_is_stored_with_latest_rev_but_not_relayed() {
        // The relay decision lives in acceptAndRelayState; assert the sink.
        var dir = fakeDir(OWNER);
        var sink = new RecordingSink();
        var viewers = new FixedViewers(List.of(VIEWER));
        try (var capture = dev.agentcraft.mp.MpLog.capture()) {
            // First state reaches the viewer.
            assertEquals(StudioRelay.StateResult.ACCEPTED,
                StudioRelay.acceptAndRelayState(OWNER, minimalState(), dir,
                    MpServerConfig.DEFAULT, true, 0, viewers, sink));
            assertEquals(1, sink.statePayloads.size());
            // Same content under a new rev: stored, not relayed, no telemetry.
            var sameContent = state(List.of(agent("alex")), policy(false), 2, true);
            assertEquals(StudioRelay.StateResult.UNCHANGED,
                StudioRelay.acceptAndRelayState(OWNER, sameContent, dir,
                    MpServerConfig.DEFAULT, true, 1, viewers, sink));
            assertEquals(1, sink.statePayloads.size());
            assertEquals(2, StudioRelay.stored(StudioId.of(OWNER)).rev());
            assertEquals(1, capture.lines().stream()
                .filter(l -> l.startsWith("event=" + dev.agentcraft.mp.MpEvents.RELAY_SENT))
                .count());
            // Changed content is sent again, carrying the newest stored rev.
            var changed = state(List.of(agent("tove")), policy(false), 3, true);
            assertEquals(StudioRelay.StateResult.ACCEPTED,
                StudioRelay.acceptAndRelayState(OWNER, changed, dir,
                    MpServerConfig.DEFAULT, true, 2, viewers, sink));
            assertEquals(2, sink.statePayloads.size());
            assertEquals(3, sink.statePayloads.get(1).state().rev());
        }
    }

    @Test
    void refused_state_leaves_previous_state_in_place() {
        var dir = fakeDir(OWNER);
        StudioRelay.acceptState(OWNER, minimalState(), dir,
            MpServerConfig.DEFAULT, true, 0);
        var cfg = new MpServerConfig(true, 128, true, true, true, true, true,
            12, 1, 10);
        // Exhaust the bucket (2 tokens) then refuse.
        StudioRelay.acceptState(OWNER, state(List.of(agent("a")), policy(false), 2, true),
            dir, cfg, true, 0);
        StudioRelay.acceptState(OWNER, state(List.of(agent("b")), policy(false), 3, true),
            dir, cfg, true, 0);
        var refused = StudioRelay.acceptState(
            OWNER, state(List.of(agent("c")), policy(false), 4, true),
            dir, cfg, true, 0);
        assertEquals(StudioRelay.StateResult.RATE_LIMITED, refused);
        // The last stored state is still the one that was accepted.
        assertEquals("b", StudioRelay.stored(StudioId.of(OWNER)).agents().get(0).id());
    }

    @Test
    void refuses_event_when_no_plot() {
        var dir = fakeDir(OWNER);
        var result = StudioRelay.acceptEvent(STRANGER,
            new PublicEvent.Say("alex", null, null, 0), dir,
            MpServerConfig.DEFAULT, true, 0);
        assertEquals(StudioRelay.EventResult.NO_PLOT, result);
    }

    @Test
    void refuses_event_with_no_stored_state() {
        var dir = fakeDir(OWNER);
        var result = StudioRelay.acceptEvent(OWNER,
            new PublicEvent.Say("alex", null, null, 0), dir,
            MpServerConfig.DEFAULT, true, 0);
        assertEquals(StudioRelay.EventResult.UNKNOWN_AGENT, result);
    }

    @Test
    void refuses_event_when_agent_unknown() {
        var dir = fakeDir(OWNER);
        StudioRelay.acceptState(OWNER, minimalState(), dir,
            MpServerConfig.DEFAULT, true, 0);
        var result = StudioRelay.acceptEvent(OWNER,
            new PublicEvent.Say("bob", null, null, 0), dir,
            MpServerConfig.DEFAULT, true, 1);
        assertEquals(StudioRelay.EventResult.UNKNOWN_AGENT, result);
    }

    @Test
    void accepts_event_when_agent_matches() {
        var dir = fakeDir(OWNER);
        StudioRelay.acceptState(OWNER, minimalState(), dir,
            MpServerConfig.DEFAULT, true, 0);
        var result = StudioRelay.acceptEvent(OWNER,
            new PublicEvent.Say("alex", null, null, 0), dir,
            MpServerConfig.DEFAULT, true, 1);
        assertEquals(StudioRelay.EventResult.ACCEPTED, result);
    }

    @Test
    void refuses_event_when_rate_limited() {
        var dir = fakeDir(OWNER);
        var cfg = new MpServerConfig(true, 128, true, true, true, true, true,
            12, 4, 10);
        StudioRelay.acceptState(OWNER, minimalState(), dir, cfg, true, 0);
        for (int i = 0; i < 8; i++)
            assertEquals(StudioRelay.EventResult.ACCEPTED,
                StudioRelay.acceptEvent(OWNER,
                    new PublicEvent.Say("alex", null, null, 0), dir, cfg, true, 0));
        assertEquals(StudioRelay.EventResult.RATE_LIMITED,
            StudioRelay.acceptEvent(OWNER,
                new PublicEvent.Say("alex", null, null, 0), dir, cfg, true, 0));
    }

    @Test
    void state_and_event_have_independent_rate_limiters() {
        var dir = fakeDir(OWNER);
        var cfg = new MpServerConfig(true, 128, true, true, true, true, true,
            12, 2, 10);
        StudioRelay.acceptState(OWNER, minimalState(), dir, cfg, true, 0);
        for (int i = 0; i < 4; i++)
            StudioRelay.acceptState(OWNER, minimalState(), dir, cfg, true, 0);
        assertEquals(StudioRelay.StateResult.RATE_LIMITED,
            StudioRelay.acceptState(OWNER, minimalState(), dir, cfg, true, 0));
        assertEquals(StudioRelay.EventResult.ACCEPTED,
            StudioRelay.acceptEvent(OWNER,
                new PublicEvent.Say("alex", null, null, 0), dir, cfg, true, 0));
    }

    @Test
    void relay_uses_connection_studio_for_forged_looking_state() {
        // A state whose agent ids/names look like another studio's still goes
        // out with studio = the sender's own id (payload has no studio field).
        var dir = fakeDir(OWNER);
        StudioRelay.acceptState(OWNER,
            state(List.of(agent("local"), agent("other")), policy(false), 1, true),
            dir, MpServerConfig.DEFAULT, true, 0);
        var sink = new RecordingSink();
        StudioRelay.relayState(new FixedViewers(List.of(VIEWER)), sink,
            StudioId.of(OWNER), dir);
        assertEquals(1, sink.statePayloads.size());
        assertEquals(StudioId.of(OWNER), sink.statePayloads.get(0).studio());
    }

    @Test
    void relay_never_sends_to_the_owner() {
        var dir = fakeDir(OWNER);
        StudioRelay.acceptState(OWNER, minimalState(), dir,
            MpServerConfig.DEFAULT, true, 0);
        var sink = new RecordingSink();
        StudioRelay.relayState(new FixedViewers(List.of(OWNER, VIEWER)), sink,
            StudioId.of(OWNER), dir);
        assertEquals(List.of(VIEWER), sink.states);
    }

    @Test
    void relay_counts_only_successful_sends() {
        var dir = fakeDir(OWNER);
        StudioRelay.acceptState(OWNER, minimalState(), dir,
            MpServerConfig.DEFAULT, true, 0);
        var sink = new RecordingSink();
        sink.blocked.add(VIEWER);
        try (var capture = dev.agentcraft.mp.MpLog.capture()) {
            StudioRelay.relayState(new FixedViewers(List.of(OWNER, VIEWER)), sink,
                StudioId.of(OWNER), dir);
            // The only viewer is blocked, so nothing is sent and nothing logged.
            assertTrue(sink.states.isEmpty());
            assertTrue(capture.lines().stream().noneMatch(
                l -> l.startsWith("event=relay_sent")));
        }
    }

    @Test
    void relay_say_text_cleared_when_saytext_off() {
        var dir = fakeDir(OWNER);
        StudioRelay.acceptState(OWNER,
            state(List.of(agent("alex")), policy(false), 1, true),
            dir, MpServerConfig.DEFAULT, true, 0);
        var sink = new RecordingSink();
        StudioRelay.relayEvent(new FixedViewers(List.of(VIEWER)), sink,
            StudioId.of(OWNER),
            new PublicEvent.Say("alex", null, "secret", 6), dir);
        var say = (PublicEvent.Say) sink.eventPayloads.get(0).event();
        assertNull(say.text());
    }

    @Test
    void relay_say_text_kept_when_saytext_on() {
        var dir = fakeDir(OWNER);
        StudioRelay.acceptState(OWNER,
            state(List.of(agent("alex")), policy(true), 1, true),
            dir, MpServerConfig.DEFAULT, true, 0);
        var sink = new RecordingSink();
        StudioRelay.relayEvent(new FixedViewers(List.of(VIEWER)), sink,
            StudioId.of(OWNER),
            new PublicEvent.Say("alex", null, "hello", 5), dir);
        var say = (PublicEvent.Say) sink.eventPayloads.get(0).event();
        assertEquals("hello", say.text());
    }

    @Test
    void relay_event_without_stored_state_clears_text() {
        // relayEvent is deny-by-default: no stored state means no text.
        var dir = fakeDir(OWNER);
        var sink = new RecordingSink();
        StudioRelay.relayEvent(new FixedViewers(List.of(VIEWER)), sink,
            StudioId.of(OWNER),
            new PublicEvent.Say("alex", null, "secret", 6), dir);
        var say = (PublicEvent.Say) sink.eventPayloads.get(0).event();
        assertNull(say.text());
    }

    @Test
    void range_entry_sends_presence_online_by_connectivity_and_skips_owner() {
        var dir = fakeDir(OWNER);
        var plot = dir.plotOf(StudioId.of(OWNER)).orElseThrow();
        var sink = new RecordingSink();
        // Connectivity comes from the passed source, not a cache; no state yet.
        StudioRelay.onJoin(new FixedViewers(List.of()), sink, OWNER, "Bob", dir,
            MpServerConfig.DEFAULT);
        StudioRelay.onEntered(new FixedViewers(List.of()), sink,
            alwaysOnline(true), VIEWER, plot, dir, MpServerConfig.DEFAULT);
        assertEquals(1, sink.presencePayloads.size());
        var presence = sink.presencePayloads.get(0);
        assertTrue(presence.online());
        assertEquals("Bob", presence.ownerName());
        // No stored state: no state send.
        assertTrue(sink.states.isEmpty());
        // The owner itself is skipped.
        sink.presences.clear(); sink.presencePayloads.clear();
        StudioRelay.onEntered(new FixedViewers(List.of()), sink,
            alwaysOnline(true), OWNER, plot, dir, MpServerConfig.DEFAULT);
        assertTrue(sink.presences.isEmpty());
    }

    @Test
    void range_entry_online_flag_comes_from_the_source_not_a_cache() {
        // The owner was never seen by the join handler, but the source says
        // online: presence must report online.
        var dir = fakeDir(OWNER);
        var plot = dir.plotOf(StudioId.of(OWNER)).orElseThrow();
        var sink = new RecordingSink();
        StudioRelay.onEntered(new FixedViewers(List.of()), sink,
            alwaysOnline(true), VIEWER, plot, dir, MpServerConfig.DEFAULT);
        assertTrue(sink.presencePayloads.get(0).online());
    }

    @Test
    void range_entry_after_leave_is_offline_with_retained_state() {
        var dir = fakeDir(OWNER);
        var plot = dir.plotOf(StudioId.of(OWNER)).orElseThrow();
        var sink = new RecordingSink();
        StudioRelay.onJoin(new FixedViewers(List.of()), sink, OWNER, "Bob", dir,
            MpServerConfig.DEFAULT);
        StudioRelay.acceptState(OWNER, minimalState(), dir,
            MpServerConfig.DEFAULT, true, 0);
        StudioRelay.onDisconnect(new FixedViewers(List.of()), sink, OWNER, "Bob",
            dir, MpServerConfig.DEFAULT);
        sink.states.clear(); sink.presencePayloads.clear(); sink.presences.clear();
        StudioRelay.onEntered(new FixedViewers(List.of()), sink,
            alwaysOnline(false), VIEWER, plot, dir, MpServerConfig.DEFAULT);
        assertFalse(sink.presencePayloads.get(0).online());
        assertEquals(1, sink.states.size());
        assertFalse(sink.statePayloads.get(0).state().foremanOnline());
    }

    @Test
    void range_entry_after_rejoin_is_online_again() {
        var dir = fakeDir(OWNER);
        var plot = dir.plotOf(StudioId.of(OWNER)).orElseThrow();
        var sink = new RecordingSink();
        StudioRelay.onJoin(new FixedViewers(List.of()), sink, OWNER, "Bob", dir,
            MpServerConfig.DEFAULT);
        StudioRelay.onDisconnect(new FixedViewers(List.of()), sink, OWNER, "Bob",
            dir, MpServerConfig.DEFAULT);
        StudioRelay.onJoin(new FixedViewers(List.of()), sink, OWNER, "Bob", dir,
            MpServerConfig.DEFAULT);
        sink.presencePayloads.clear();
        StudioRelay.onEntered(new FixedViewers(List.of()), sink,
            alwaysOnline(true), VIEWER, plot, dir, MpServerConfig.DEFAULT);
        assertTrue(sink.presencePayloads.get(0).online());
    }
}
