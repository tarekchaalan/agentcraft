package dev.agentcraft.mp.publish;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.ForemanStates;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.mp.MpMode;
import dev.agentcraft.client.mp.publish.PolicyStore;
import dev.agentcraft.client.mp.publish.PublishFeature;
import dev.agentcraft.client.mp.publish.PublishScheduler;
import dev.agentcraft.client.mp.publish.Redactor;
import dev.agentcraft.mp.MpEvents;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.MpReasons;
import dev.agentcraft.mp.Plot;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.state.PublicEvent;
import dev.agentcraft.mp.state.PublicJson;
import dev.agentcraft.mp.state.PublicPolicy;
import dev.agentcraft.mp.state.PublicStudioState;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublishSchedulerTest {
    private static final UUID PLAYER = UUID.nameUUIDFromBytes("publish-player".getBytes());
    private static final UUID STUDIO = UUID.nameUUIDFromBytes("publish-studio".getBytes());

    @Test void two_changes_in_one_flush_send_the_later_state_once() {
        Harness harness = new Harness(4);
        harness.scheduler.markDirty();
        harness.patch("thinking");
        harness.scheduler.markDirty();
        harness.scheduler.flush(0, true);
        assertEquals(1, harness.states.size());
        assertEquals("thinking", harness.states.get(0).agents().stream().filter(agent -> agent.id().equals("marlow")).findFirst().orElseThrow().state().wire());
        assertEquals(1, harness.states.get(0).rev());
    }

    @Test void unchanged_projection_is_not_sent_and_does_not_spend_a_state_window_slot() {
        Harness harness = new Harness(1);
        harness.scheduler.markDirty();
        harness.scheduler.flush(0, true);
        try (var capture = MpLog.capture()) {
            harness.scheduler.markDirty();
            harness.scheduler.flush(1_000_000_000L, true);
            harness.patch("thinking");
            harness.scheduler.markDirty();
            harness.scheduler.flush(1_000_000_000L, true);
            assertEquals(List.of(
                "event=" + MpEvents.PUBLIC_STATE_SKIPPED + " player=" + PLAYER + " studio=" + STUDIO + " plot=4 rev=1 reason=" + MpReasons.UNCHANGED,
                sentLine(harness.states.getLast())), capture.lines());
        }
        assertEquals(List.of(1, 2), harness.states.stream().map(PublicStudioState::rev).toList());
    }

    @Test void state_window_allows_rate_sends_then_waits_until_oldest_is_one_second_old() {
        Harness harness = new Harness(2);
        harness.sendChanged(0, "thinking");
        harness.sendChanged(0, "editing");
        harness.sendChanged(0, "reading");
        assertEquals(2, harness.states.size());
        assertTrue(harness.scheduler.isDirty());
        assertEquals("editing", agentState(harness.states.getLast()));
        harness.scheduler.flush(999_999_999L, true);
        assertEquals(2, harness.states.size());
        harness.scheduler.flush(1_000_000_000L, true);
        assertEquals(3, harness.states.size());
        assertEquals("reading", agentState(harness.states.getLast()));
    }

    @Test void event_window_allows_rate_sends_then_allows_next_at_exactly_one_second() {
        Harness harness = new Harness(2, () -> SAY_TEXT_ON); // the says below carry text: it is kept only while shared
        PublicEvent one = say("one");
        PublicEvent two = say("two");
        PublicEvent three = say("three");
        try (var capture = MpLog.capture()) {
            harness.scheduler.offer(one, 0, true);
            harness.scheduler.offer(two, 0, true);
            harness.scheduler.offer(three, 0, true);
            assertEquals(List.of(one, two), harness.events);
            assertEquals(1, capture.lines().size());
            assertEquals("event=" + MpEvents.PUBLIC_STATE_SKIPPED + " player=" + PLAYER + " studio=" + STUDIO
                + " plot=4 rev=0 reason=" + MpReasons.RATE_LIMITED, capture.lines().getFirst());
            harness.scheduler.offer(three, 1_000_000_000L, true);
            assertEquals(List.of(one, two, three), harness.events);
        }
    }

    @Test void reconnect_and_changed_rate_start_fresh_windows_for_states_and_events() {
        Harness harness = new Harness(1);
        harness.sendChanged(0, "thinking");
        harness.sendChanged(0, "editing");
        assertEquals(1, harness.states.size());
        harness.scheduler.reconnect();
        harness.scheduler.flush(1, true);
        assertEquals(2, harness.states.size());
        assertEquals(1, harness.states.getLast().rev());

        harness.scheduler.offer(say("event-one"), 1, true);
        harness.scheduler.offer(say("event-blocked"), 1, true);
        assertEquals(1, harness.events.size());
        harness.rate.set(2);
        // a changed rate starts both windows empty: two more of each fit at once, a third does not
        harness.scheduler.offer(say("event-after-rate-change"), 1, true);
        harness.scheduler.offer(say("second-after-rate-change"), 1, true);
        assertEquals(3, harness.events.size());
        harness.scheduler.offer(say("third-after-rate-change"), 1, true);
        assertEquals(3, harness.events.size());
        harness.patch("reading");
        harness.scheduler.markDirty();
        harness.scheduler.flush(1, true);
        assertEquals(3, harness.states.size());
        assertEquals("reading", agentState(harness.states.getLast()));
        harness.patch("editing");
        harness.scheduler.markDirty();
        harness.scheduler.flush(1, true);
        assertEquals(4, harness.states.size());
        harness.patch("testing");
        harness.scheduler.markDirty();
        harness.scheduler.flush(1, true);
        assertEquals(4, harness.states.size());
        harness.scheduler.reconnect();
        harness.scheduler.offer(say("event-after-reconnect"), 1, true);
        assertEquals(4, harness.events.size());
    }

    @Test void zero_rate_sends_nothing_and_logs_not_multiplayer_once_per_streak() {
        Harness harness = new Harness(0);
        harness.scheduler.markDirty();
        try (var capture = MpLog.capture()) {
            harness.scheduler.flush(0, true);
            harness.scheduler.flush(1, true);
            harness.scheduler.offer(say("hidden"), 2, true);
            harness.scheduler.offer(say("hidden-again"), 3, true);
            assertTrue(harness.states.isEmpty());
            assertTrue(harness.events.isEmpty());
            assertEquals(List.of("event=" + MpEvents.PUBLIC_STATE_SKIPPED + " player=" + PLAYER + " studio=" + STUDIO
                + " plot=4 rev=0 reason=" + MpReasons.NOT_MULTIPLAYER), capture.lines());
        }
    }

    @Test void reconnect_clears_held_events() {
        Harness harness = new Harness(1);
        harness.sendChanged(0, "thinking");
        harness.patch("editing");
        harness.scheduler.markDirty();
        harness.scheduler.offer(say("must-clear"), 0, true);
        assertEquals(1, harness.states.size());
        harness.scheduler.reconnect();
        harness.scheduler.flush(1, true);
        assertEquals(2, harness.states.size());
        assertTrue(harness.events.isEmpty());
    }

    @Test void say_after_snapshot_sends_state_then_event_without_manual_flush() {
        ForemanState state = ForemanStates.fromSnapshot(com.google.gson.JsonParser.parseString("""
            {"agents":[{"id":"new-agent","name":"New Agent","skin":"new-agent","state":"idle","station":"desk","activity":""}]}
            """).getAsJsonObject());
        Harness harness = new Harness(state, 4);
        var order = new ArrayList<String>();
        harness.recordOutput(order);
        var listener = PublishFeature.listener(harness.scheduler, () -> true, () -> true, () -> PublicPolicy.DEFAULT, () -> state);
        listener.onSnapshot(state);
        listener.onSay(new Protocol.AgentSay("new-agent", "hello", "user", 1));
        assertEquals(List.of("state", "event"), order);
        assertEquals("new-agent", ((PublicEvent.Say) harness.events.getFirst()).agentId());
    }

    @Test void policy_state_precedes_say_and_newly_enabled_text_is_in_the_event(@TempDir Path dir) {
        PolicyStore store = PolicyStore.load(dir.resolve("agentcraft-public.json"));
        Harness harness = new Harness(4, store::current);
        var order = new ArrayList<String>();
        harness.recordOutput(order);
        PublishFeature.applyPolicy(store, "sayText", true, () -> MpMode.MULTIPLAYER,
            () -> PLAYER, () -> STUDIO, harness.scheduler);
        var listener = PublishFeature.listener(harness.scheduler, () -> true, () -> true, store::current, () -> harness.state);
        listener.onSay(new Protocol.AgentSay("marlow", "newly enabled text", "user", 1));
        assertEquals(List.of("state", "event"), order);
        assertTrue(harness.states.getFirst().policy().sayText());
        assertEquals("newly enabled text", ((PublicEvent.Say) harness.events.getFirst()).text());
    }

    @Test void event_held_behind_rate_limited_state_is_sent_after_that_state() {
        Harness harness = new Harness(1);
        var order = new ArrayList<String>();
        harness.recordOutput(order);
        harness.sendChanged(0, "thinking");
        order.clear();
        harness.patch("editing");
        harness.scheduler.markDirty();
        harness.scheduler.offer(say("after-state"), 0, true);
        assertEquals(List.of(), order);
        harness.scheduler.flush(1_000_000_000L, true);
        assertEquals(List.of("state", "event"), order);
    }

    @Test void held_say_loses_its_text_when_say_text_is_switched_off_before_it_is_sent() {
        var policy = new AtomicReference<>(new PublicPolicy(false, true, false, false));
        Harness harness = new Harness(1, policy::get);
        harness.sendChanged(0, "thinking");
        harness.patch("editing");
        harness.scheduler.markDirty();
        harness.scheduler.offer(say("held-secret"), 0, true);
        assertTrue(harness.events.isEmpty());
        policy.set(PublicPolicy.DEFAULT);
        harness.scheduler.flush(1_000_000_000L, true);
        assertEquals(1, harness.events.size());
        PublicEvent.Say sent = (PublicEvent.Say) harness.events.getFirst();
        assertNull(sent.text());
        assertEquals("held-secret".length(), sent.length());
        assertEquals("marlow", sent.agentId());
        assertFalse(harness.states.getLast().policy().sayText());
    }

    @Test void say_addressee_is_sent_only_while_it_is_user_or_an_agent_in_the_state_last_sent() {
        Harness harness = new Harness(agents("marlow", "wren", "kit"), 4);
        for (String state : List.of("thinking", "editing", "reading", "testing")) harness.sendChanged(0, state);
        harness.patch("idle");
        harness.scheduler.markDirty(); // a fifth state in one second waits, and the says wait behind it
        for (String to : List.of("user", "wren", "kit", "all")) harness.scheduler.offer(new PublicEvent.Say("marlow", to, null, 5), 0, true);
        assertTrue(harness.events.isEmpty());
        harness.state = agents("marlow", "wren"); // kit leaves before the held says go out
        harness.scheduler.flush(1_000_000_000L, true);
        assertEquals(List.of("marlow", "wren"), harness.states.getLast().agents().stream().map(agent -> agent.id()).toList());
        assertEquals(java.util.Arrays.asList("user", "wren", null, null),
            harness.events.stream().map(event -> ((PublicEvent.Say) event).to()).toList());
    }

    private static ForemanState agents(String... ids) {
        StringBuilder agents = new StringBuilder();
        for (String id : ids) agents.append(agents.isEmpty() ? "" : ",")
            .append("{\"id\":\"%1$s\",\"name\":\"%1$s\",\"skin\":\"%1$s\",\"state\":\"idle\",\"station\":\"desk\",\"activity\":\"\"}".formatted(id));
        return ForemanStates.fromSnapshot(com.google.gson.JsonParser.parseString("{\"agents\":[" + agents + "]}").getAsJsonObject());
    }

    @Test void full_held_event_fifo_drops_newest_and_preserves_order() {
        Harness harness = new Harness(2, () -> SAY_TEXT_ON);
        var order = new ArrayList<String>();
        harness.recordOutput(order);
        harness.sendChanged(0, "thinking");
        harness.sendChanged(0, "editing");
        order.clear();
        harness.patch("reading");
        harness.scheduler.markDirty();
        PublicEvent oldest = say("oldest");
        PublicEvent next = say("next");
        PublicEvent newest = say("newest-dropped");
        try (var capture = MpLog.capture()) {
            harness.scheduler.offer(oldest, 0, true);
            harness.scheduler.offer(next, 0, true);
            harness.scheduler.offer(newest, 0, true);
            assertTrue(harness.events.isEmpty());
            assertEquals(2, capture.lines().size());
            assertTrue(capture.lines().stream().allMatch(line -> line.endsWith("reason=" + MpReasons.RATE_LIMITED)));
            harness.scheduler.flush(1_000_000_000L, true);
            assertEquals(List.of("state", "event", "event"), order);
        }
        assertEquals(List.of(oldest, next), harness.events);
    }

    @Test void skipped_telemetry_captures_the_entire_line() {
        Harness harness = new Harness(4);
        try (var capture = MpLog.capture()) {
            harness.scheduler.markDirty();
            harness.scheduler.flush(0, false);
            harness.scheduler.flush(1, false);
            assertEquals(List.of("event=" + MpEvents.PUBLIC_STATE_SKIPPED + " player=" + PLAYER + " studio=" + STUDIO
                + " plot=4 rev=0 reason=" + MpReasons.NOT_MULTIPLAYER), capture.lines());
        }
    }

    @Test void sent_telemetry_captures_every_field_and_exact_encoded_bytes() {
        Harness harness = new Harness(4);
        harness.scheduler.markDirty();
        PublicStudioState expected = new PublicStudioState(1, true, Redactor.redact(harness.state, PublicPolicy.DEFAULT).agents(),
            Redactor.redact(harness.state, PublicPolicy.DEFAULT).counts(), Redactor.redact(harness.state, PublicPolicy.DEFAULT).goal(),
            Redactor.redact(harness.state, PublicPolicy.DEFAULT).ci(), PublicPolicy.DEFAULT, null);
        int bytes = PublicJson.toJson(expected).toString().getBytes(StandardCharsets.UTF_8).length;
        try (var capture = MpLog.capture()) {
            harness.scheduler.flush(0, true);
            String line = "event=" + MpEvents.PUBLIC_STATE_SENT + " player=" + PLAYER + " studio=" + STUDIO + " plot=4 rev=1 agents="
                + expected.agents().size() + " bytes=" + bytes + " policy=0";
            assertEquals(List.of(line), capture.lines());
        }
    }

    @Test void private_callbacks_do_not_publish() {
        Harness harness = new Harness(4);
        var listener = PublishFeature.listener(harness.scheduler, () -> true, () -> true, () -> PublicPolicy.DEFAULT, () -> harness.state);
        listener.onLog("marlow", List.of(new Protocol.LogEntry(1, Protocol.LogKind.TEXT, "SECRET_LOG_LINE")));
        listener.onChange(1);
        listener.onFeed(new Protocol.FeedItem(1, Protocol.FeedKind.MESSAGE, "SECRET_FEED_LINE", "marlow", null));
        listener.onMemory(null, new Protocol.MemoryEntry("m", "shared", "SECRET_MEMORY", "SECRET_MEMORY_BODY", 1, null));
        listener.onNotify(new Protocol.Notify(Protocol.NotifyLevel.INFO, "SECRET_NOTIFY", null, 1));
        listener.onStatus(harness.state.status());
        harness.scheduler.flush(0, true);
        assertTrue(harness.states.isEmpty());
        assertTrue(harness.events.isEmpty());
    }

    @Test void quiet_tracking_drops_says_without_a_log() {
        Harness harness = new Harness(4);
        var listener = PublishFeature.listener(harness.scheduler, () -> false, () -> true,
            () -> new PublicPolicy(false, true, false, false), () -> harness.state);
        try (var capture = MpLog.capture()) {
            listener.onSay(new Protocol.AgentSay("marlow", "SECRET_QUIET_SAY", "user", 1));
            listener.onSnapshot(harness.state);
            harness.scheduler.flush(0, true);
            assertTrue(harness.events.isEmpty());
            assertTrue(harness.states.isEmpty());
            assertTrue(capture.lines().isEmpty());
        }
    }

    @Test void own_plot_becoming_known_or_moving_sends_the_current_state_again() {
        Harness harness = new Harness(8);
        harness.plot.set(Optional.empty());
        harness.sendChanged(0, "thinking"); // a server refuses this one without a reply: the studio has no plot
        harness.scheduler.flush(1, true);
        assertEquals(1, harness.states.size());
        // none to some, then another index, then another origin
        var plots = List.of(plot(4, BlockPos.ZERO), plot(5, BlockPos.ZERO), plot(5, new BlockPos(128, 0, 0)));
        for (int i = 0; i < plots.size(); i++) {
            harness.plot.set(plots.get(i));
            harness.scheduler.flush(2 + 2 * i, true);
            harness.scheduler.flush(3 + 2 * i, true);
            assertEquals(2 + i, harness.states.size());
        }
        harness.plot.set(Optional.empty()); // losing the plot sends nothing
        harness.scheduler.flush(8, true);
        assertEquals(List.of(1, 2, 3, 4), harness.states.stream().map(PublicStudioState::rev).toList());
        assertEquals(1, harness.states.stream().map(PublishSchedulerTest::content).distinct().count());
    }

    @Test void idle_state_is_sent_again_once_per_resend_interval_and_not_before() {
        long interval = PublishScheduler.RESEND_NANOS;
        long tick = 50_000_000L;
        assertTrue(interval >= 15_000_000_000L && interval <= 30_000_000_000L); // a few a minute, seen within half a minute
        Harness harness = new Harness(4);
        harness.sendChanged(0, "thinking");
        for (long now = tick; now < interval; now += tick) harness.scheduler.flush(now, true);
        harness.scheduler.flush(interval - 1, true);
        assertEquals(1, harness.states.size());
        for (long now = interval; now < 2 * interval; now += tick) harness.scheduler.flush(now, true);
        assertEquals(2, harness.states.size());
        harness.sendChanged(2 * interval - tick, "editing"); // a real send restarts the interval
        for (long now = 2 * interval; now < 3 * interval - tick; now += tick) harness.scheduler.flush(now, true);
        assertEquals(3, harness.states.size());
        harness.scheduler.flush(3 * interval - tick, true);
        assertEquals(List.of(1, 2, 3, 4), harness.states.stream().map(PublicStudioState::rev).toList());
        assertEquals(content(harness.states.get(0)), content(harness.states.get(1)));
        assertEquals(content(harness.states.get(2)), content(harness.states.get(3)));
    }

    @Test void resend_waits_for_the_state_window_and_goes_before_a_later_event() {
        Harness harness = new Harness(1);
        var order = new ArrayList<String>();
        harness.sendChanged(0, "thinking");
        harness.recordOutput(order);
        harness.plot.set(plot(5, BlockPos.ZERO));
        harness.scheduler.flush(999_999_998L, true);
        harness.scheduler.offer(say("after-resend"), 999_999_999L, true);
        assertEquals(List.of(), order);
        assertTrue(harness.scheduler.isDirty());
        harness.scheduler.flush(1_000_000_000L, true);
        harness.scheduler.flush(1_000_000_001L, true);
        assertEquals(List.of("state", "event"), order);
    }

    @Test void resend_shares_only_what_the_policy_shares_when_it_is_sent() {
        var policy = new AtomicReference<>(new PublicPolicy(true, true, true, true));
        Harness harness = new Harness(4, policy::get);
        harness.sendChanged(0, "thinking");
        PublicStudioState first = harness.states.getFirst();
        assertTrue(first.agents().stream().anyMatch(agent -> agent.activity() != null));
        assertNotNull(first.goal().text());
        assertFalse(first.tasks().isEmpty());
        policy.set(PublicPolicy.DEFAULT); // nothing marks the state dirty here: only a resend can send it
        harness.scheduler.flush(PublishScheduler.RESEND_NANOS, true); // the interval
        harness.plot.set(plot(5, BlockPos.ZERO));
        harness.scheduler.flush(PublishScheduler.RESEND_NANOS + 1, true); // the plot
        assertEquals(3, harness.states.size());
        for (PublicStudioState resent : harness.states.subList(1, 3)) {
            assertEquals(PublicPolicy.DEFAULT, resent.policy());
            assertTrue(resent.agents().stream().allMatch(agent -> agent.activity() == null));
            assertNull(resent.goal().text());
            assertNull(resent.tasks());
        }
    }

    @Test void nothing_is_sent_or_logged_outside_multiplayer_however_much_time_passes() {
        Harness never = new Harness(4);
        Harness left = new Harness(4);
        left.sendChanged(0, "thinking"); // was in multiplayer once, so there is a sent state to send again
        try (var capture = MpLog.capture()) {
            for (long n = 1; n <= 6; n++) {
                for (Harness harness : List.of(never, left)) {
                    harness.plot.set(plot((int) n, BlockPos.ZERO));
                    harness.scheduler.flush(n * PublishScheduler.RESEND_NANOS, false);
                    PublishFeature.tick(harness.scheduler, MpMode.SINGLEPLAYER, true, true);
                    assertFalse(harness.scheduler.isDirty());
                }
            }
            assertTrue(capture.lines().isEmpty());
        }
        assertTrue(never.states.isEmpty());
        assertEquals(1, left.states.size());
    }

    private static Optional<Plot> plot(int index, BlockPos origin) { return Optional.of(new Plot(index, StudioId.of(STUDIO), origin)); }

    /** A sent state without its {@code rev}. */
    private static PublicStudioState content(PublicStudioState state) {
        return new PublicStudioState(0, state.foremanOnline(), state.agents(), state.counts(), state.goal(), state.ci(),
            state.policy(), state.tasks());
    }

    private static String agentState(PublicStudioState state) {
        return state.agents().stream().filter(agent -> agent.id().equals("marlow")).findFirst().orElseThrow().state().wire();
    }

    private static String sentLine(PublicStudioState state) {
        return "event=" + MpEvents.PUBLIC_STATE_SENT + " player=" + PLAYER + " studio=" + STUDIO + " plot=4 rev=" + state.rev()
            + " agents=" + state.agents().size() + " bytes=" + PublicJson.toJson(state).toString().getBytes(StandardCharsets.UTF_8).length
            + " policy=" + PolicyStore.bits(state.policy());
    }

    private static final PublicPolicy SAY_TEXT_ON = new PublicPolicy(false, true, false, false);

    private static PublicEvent say(String text) { return new PublicEvent.Say("marlow", "user", text, text.length()); }

    private final class Harness {
        ForemanState state; // a test may replace it: the scheduler reads it on every flush
        final List<PublicStudioState> states = new ArrayList<>();
        final List<PublicEvent> events = new ArrayList<>();
        final AtomicInteger rate;
        final AtomicReference<PublicPolicy> policy;
        final AtomicReference<Optional<Plot>> plot = new AtomicReference<>(plot(4, BlockPos.ZERO));
        final PublishScheduler scheduler;
        List<String> outputOrder;

        Harness(int rate) { this(ForemanStates.showcase(), rate, () -> PublicPolicy.DEFAULT); }
        Harness(int rate, java.util.function.Supplier<PublicPolicy> policy) { this(ForemanStates.showcase(), rate, policy); }
        Harness(ForemanState state, int rate) { this(state, rate, () -> PublicPolicy.DEFAULT); }

        Harness(ForemanState state, int initialRate, java.util.function.Supplier<PublicPolicy> policy) {
            this.state = state;
            this.rate = new AtomicInteger(initialRate);
            this.policy = new AtomicReference<>(policy.get());
            scheduler = new PublishScheduler(() -> this.state, policy::get, this.rate::get, () -> PLAYER, () -> STUDIO, plot::get,
                new PublishScheduler.Out() {
                    @Override public void state(PublicStudioState state) {
                        states.add(state);
                        if (outputOrder != null) outputOrder.add("state");
                    }
                    @Override public void event(PublicEvent event) {
                        events.add(event);
                        if (outputOrder != null) outputOrder.add("event");
                    }
                });
        }

        void recordOutput(List<String> order) {
            outputOrder = order;
        }

        void patch(String agentState) {
            JsonObject set = new JsonObject();
            set.addProperty("state", agentState);
            state.patch("agent", "marlow", set);
        }

        void sendChanged(long now, String agentState) {
            patch(agentState);
            scheduler.markDirty();
            scheduler.flush(now, true);
        }
    }
}
