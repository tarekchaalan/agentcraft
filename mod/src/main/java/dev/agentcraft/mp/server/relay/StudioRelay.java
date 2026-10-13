package dev.agentcraft.mp.server.relay;

import dev.agentcraft.mp.MpEvents;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.MpReasons;
import dev.agentcraft.mp.MpServerConfig;
import dev.agentcraft.mp.Plot;
import dev.agentcraft.mp.PlotDirectory;
import dev.agentcraft.mp.RateBucket;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.net.PresenceS2C;
import dev.agentcraft.mp.net.StudioEventS2C;
import dev.agentcraft.mp.net.StudioStateS2C;
import dev.agentcraft.mp.state.PublicEvent;
import dev.agentcraft.mp.state.PublicJson;
import dev.agentcraft.mp.state.PublicStudioState;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-thread relay: stores the latest {@link PublicStudioState} per studio,
 * relays state, events and presence to viewers within range, and enforces
 * per-player rate limits.
 *
 * <p>Every entrypoint attributes the sender to the connection's UUID, never to
 * a payload field. Relayed states and events exclude the owner. Refusal
 * telemetry is logged here so JUnit can capture every refusal path.
 */
public final class StudioRelay {

    /** Sink through which relayed payloads are delivered. Production wraps
     *  {@code ServerPlayNetworking}; tests use a recording sink. */
    public interface SendSink {
        /** @return true when the payload was sent to {@code recipient}. */
        boolean sendState(UUID recipient, StudioStateS2C payload);
        /** @return true when the payload was sent to {@code recipient}. */
        boolean sendPresence(UUID recipient, PresenceS2C payload);
        /** @return true when the payload was sent to {@code recipient}. */
        boolean sendEvent(UUID recipient, StudioEventS2C payload);
    }

    /** Provides the UUIDs of the viewers of a studio. Production delegates to
     *  {@code StudioRange.viewersOf}; tests provide a fixed list. */
    @FunctionalInterface
    public interface ViewerSource {
        List<UUID> viewersOf(StudioId studio);
    }

    /** Whether a player is connected, as the server knows it. Production reads
     *  the player list; tests pass a fixed answer. */
    @FunctionalInterface
    public interface OnlineSource {
        boolean online(UUID player);
    }

    /** A sender's rate bucket, the rate it was built with and its last use. */
    private static final class Limited {
        final RateBucket bucket; final int rate; long usedNanos;
        Limited(int rate) { this.bucket = new RateBucket(rate); this.rate = rate; }
    }

    /** A refusal line is logged at most once per sender, event and reason in
     *  this window. A bucket unused for as long is forgotten: it is full again
     *  after two seconds, so a new one is no different. */
    public static final long WINDOW_NANOS = 5_000_000_000L;
    private record Refusal(String event, UUID player, String reason) {}

    private static final Map<StudioId, PublicStudioState> STORED = new LinkedHashMap<>();
    private static final Map<UUID, Limited> STATE_LIMITERS = new HashMap<>();
    private static final Map<UUID, Limited> EVENT_LIMITERS = new HashMap<>();
    private static final Map<Refusal, Long> LOGGED = new HashMap<>();
    private static final Map<StudioId, String> NAMES = new LinkedHashMap<>();
    private StudioRelay() {}

    public enum StateResult { ACCEPTED, UNCHANGED, NO_PLOT, BAD_VERSION, RATE_LIMITED }
    public enum EventResult { ACCEPTED, NO_PLOT, BAD_VERSION, RATE_LIMITED, UNKNOWN_AGENT }

    /* ---- test seams ---- */

    public static PublicStudioState stored(StudioId studio) { return STORED.get(studio); }
    public static String nameOf(StudioId studio) { return NAMES.getOrDefault(studio, ""); }
    public static int tracked() {
        return STATE_LIMITERS.size() + EVENT_LIMITERS.size() + LOGGED.size();
    }
    public static void reset() {
        STORED.clear(); STATE_LIMITERS.clear(); EVENT_LIMITERS.clear(); NAMES.clear();
        LOGGED.clear();
    }

    /* ---- accept ---- */

    /** Accept a public state from a player. Stores it and reports whether its
     *  content changed (ignoring {@code rev}); logs refusals directly. */
    public static StateResult acceptState(UUID player, PublicStudioState state,
            PlotDirectory dir, MpServerConfig config, boolean equipped, long nowNanos) {
        StudioId studio = StudioId.of(player);
        boolean token = take(STATE_LIMITERS, player,
            config.publicStatePerSecond(), nowNanos);
        // Looked up on every path, logged or not: a freed plot's state always goes.
        Optional<Plot> plot = owned(studio, dir);
        if (!token) {
            if (throttled(MpEvents.PUBLIC_STATE_REJECTED, player,
                    MpReasons.RATE_LIMITED, nowNanos)) return StateResult.RATE_LIMITED;
            MpLog.event(MpEvents.PUBLIC_STATE_REJECTED,
                "player", player, "studio", studio.owner(),
                "plot", plot.map(Plot::index).orElse(-1),
                "rev", state.rev(), "reason", MpReasons.RATE_LIMITED);
            return StateResult.RATE_LIMITED;
        }
        if (!equipped) {
            if (throttled(MpEvents.PUBLIC_STATE_REJECTED, player,
                    MpReasons.BAD_VERSION, nowNanos)) return StateResult.BAD_VERSION;
            MpLog.event(MpEvents.PUBLIC_STATE_REJECTED,
                "player", player, "studio", studio.owner(), "rev", state.rev(),
                "reason", MpReasons.BAD_VERSION);
            return StateResult.BAD_VERSION;
        }
        if (plot.isEmpty()) {
            if (throttled(MpEvents.PUBLIC_STATE_REJECTED, player,
                    MpReasons.NO_PLOT, nowNanos)) return StateResult.NO_PLOT;
            MpLog.event(MpEvents.PUBLIC_STATE_REJECTED,
                "player", player, "studio", studio.owner(), "rev", state.rev(),
                "reason", MpReasons.NO_PLOT);
            return StateResult.NO_PLOT;
        }
        PublicStudioState previous = STORED.get(studio);
        STORED.put(studio, state);
        return sameContent(previous, state) ? StateResult.UNCHANGED : StateResult.ACCEPTED;
    }

    /** Accept a public event from a player. Events require a stored state, and
     *  the named agent must be in it; otherwise they are refused. */
    public static EventResult acceptEvent(UUID player, PublicEvent event,
            PlotDirectory dir, MpServerConfig config, boolean equipped, long nowNanos) {
        StudioId studio = StudioId.of(player);
        boolean token = take(EVENT_LIMITERS, player,
            config.publicStatePerSecond(), nowNanos);
        // Looked up on every path, logged or not: a freed plot's state always goes.
        Optional<Plot> plot = owned(studio, dir);
        if (!token) {
            if (throttled(MpEvents.STUDIO_EVENT_REJECTED, player,
                    MpReasons.RATE_LIMITED, nowNanos)) return EventResult.RATE_LIMITED;
            MpLog.event(MpEvents.STUDIO_EVENT_REJECTED,
                "player", player, "studio", studio.owner(),
                "plot", plot.map(Plot::index).orElse(-1),
                "reason", MpReasons.RATE_LIMITED);
            return EventResult.RATE_LIMITED;
        }
        if (!equipped) {
            if (throttled(MpEvents.STUDIO_EVENT_REJECTED, player,
                    MpReasons.BAD_VERSION, nowNanos)) return EventResult.BAD_VERSION;
            MpLog.event(MpEvents.STUDIO_EVENT_REJECTED,
                "player", player, "studio", studio.owner(),
                "reason", MpReasons.BAD_VERSION);
            return EventResult.BAD_VERSION;
        }
        if (plot.isEmpty()) {
            if (throttled(MpEvents.STUDIO_EVENT_REJECTED, player,
                    MpReasons.NO_PLOT, nowNanos)) return EventResult.NO_PLOT;
            MpLog.event(MpEvents.STUDIO_EVENT_REJECTED,
                "player", player, "studio", studio.owner(),
                "reason", MpReasons.NO_PLOT);
            return EventResult.NO_PLOT;
        }
        PublicStudioState latest = STORED.get(studio);
        if (latest == null) {
            if (throttled(MpEvents.STUDIO_EVENT_REJECTED, player,
                    MpReasons.UNKNOWN_AGENT, nowNanos)) return EventResult.UNKNOWN_AGENT;
            MpLog.event(MpEvents.STUDIO_EVENT_REJECTED,
                "player", player, "studio", studio.owner(),
                "plot", plot.get().index(),
                "reason", MpReasons.UNKNOWN_AGENT);
            return EventResult.UNKNOWN_AGENT;
        }
        String agentId = switch (event) {
            case PublicEvent.Say s -> s.agentId();
            case PublicEvent.TaskDone t -> t.agentId();
        };
        if (latest.agents().stream().noneMatch(a -> a.id().equals(agentId))) {
            if (throttled(MpEvents.STUDIO_EVENT_REJECTED, player,
                    MpReasons.UNKNOWN_AGENT, nowNanos)) return EventResult.UNKNOWN_AGENT;
            MpLog.event(MpEvents.STUDIO_EVENT_REJECTED,
                "player", player, "studio", studio.owner(),
                "plot", plot.get().index(),
                "reason", MpReasons.UNKNOWN_AGENT);
            return EventResult.UNKNOWN_AGENT;
        }
        return EventResult.ACCEPTED;
    }

    /* ---- relay ---- */

    /** Accepts a public state and relays it only when the content changed.
     *  The receiver calls this; the decision lives here so JUnit can prove
     *  that an unchanged state is suppressed. Stores the newest revision. */
    public static StateResult acceptAndRelayState(UUID player, PublicStudioState state,
            PlotDirectory dir, MpServerConfig config, boolean equipped,
            long nowNanos, ViewerSource viewers, SendSink sink) {
        StateResult result = acceptState(player, state, dir, config, equipped, nowNanos);
        if (result == StateResult.ACCEPTED)
            relayState(viewers, sink, StudioId.of(player), dir);
        return result;
    }

    /** Relays the stored state to viewers, excluding the owner. Logs
     *  {@code relay_sent} only when at least one viewer received it. */
    public static void relayState(ViewerSource viewers, SendSink sink,
            StudioId studio, PlotDirectory dir) {
        Optional<Plot> plot = owned(studio, dir);
        PublicStudioState state = STORED.get(studio);
        if (state == null) return;
        List<UUID> viewerUuids = viewers.viewersOf(studio).stream()
            .filter(uuid -> !uuid.equals(studio.owner())).toList();
        if (viewerUuids.isEmpty()) return;
        StudioStateS2C payload = new StudioStateS2C(studio, state);
        int bytes = PublicJson.toJson(state).toString()
            .getBytes(StandardCharsets.UTF_8).length;
        int sent = 0;
        for (UUID uuid : viewerUuids) if (sink.sendState(uuid, payload)) sent++;
        if (sent == 0) return;
        MpLog.event(MpEvents.RELAY_SENT,
            "player", studio.owner(), "studio", studio.owner(),
            "plot", plot.map(Plot::index).orElse(-1),
            "rev", state.rev(), "recipients", sent, "bytes", bytes);
    }

    /** Relays an event to viewers, excluding the owner. Clears {@code Say.text}
     *  unless the studio's stored state has {@code sayText} on (deny by
     *  default), and {@code Say.to} unless it is {@code "user"} or an agent of
     *  that state. Logs {@code relay_sent} only when something was sent. */
    public static void relayEvent(ViewerSource viewers, SendSink sink,
            StudioId studio, PublicEvent event, PlotDirectory dir) {
        Optional<Plot> plot = owned(studio, dir);
        List<UUID> viewerUuids = viewers.viewersOf(studio).stream()
            .filter(uuid -> !uuid.equals(studio.owner())).toList();
        if (viewerUuids.isEmpty()) return;
        PublicEvent out = event;
        if (event instanceof PublicEvent.Say say) {
            PublicStudioState latest = STORED.get(studio);
            String to = say.to();
            boolean known = to == null || to.equals("user") || (latest != null
                && latest.agents().stream().anyMatch(a -> a.id().equals(to)));
            boolean text = latest != null && latest.policy().sayText();
            if (!known || !text) out = new PublicEvent.Say(say.agentId(),
                known ? to : null, text ? say.text() : null, say.length());
        }
        StudioEventS2C payload = new StudioEventS2C(studio, out);
        int bytes = PublicJson.toJson(out).toString()
            .getBytes(StandardCharsets.UTF_8).length;
        int sent = 0;
        for (UUID uuid : viewerUuids) if (sink.sendEvent(uuid, payload)) sent++;
        if (sent == 0) return;
        MpLog.event(MpEvents.RELAY_SENT,
            "player", studio.owner(), "studio", studio.owner(),
            "plot", plot.map(Plot::index).orElse(-1),
            "recipients", sent, "bytes", bytes);
    }

    /* ---- presence / lifecycle ---- */

    /** A viewer entered range of a plot. Sends presence (the owner's
     *  connectivity from {@code online}, not the stored state) and the
     *  retained state if there is one. Skips the owner. */
    public static void onEntered(ViewerSource viewers, SendSink sink,
            OnlineSource online, UUID viewerUuid, Plot plot, PlotDirectory dir,
            MpServerConfig config) {
        StudioId studio = plot.owner();
        if (viewerUuid.equals(studio.owner())) return;
        if (owned(studio, dir).isEmpty()) return; // freed plot: nothing to send
        String name = NAMES.getOrDefault(studio, "");
        sink.sendPresence(viewerUuid,
            new PresenceS2C(studio, name, online.online(studio.owner())));
        PublicStudioState state = STORED.get(studio);
        if (state != null)
            sink.sendState(viewerUuid, new StudioStateS2C(studio, state));
    }

    /** Owner joined: records the name, broadcasts presence plus the stored
     *  state to current viewers, and logs {@code presence}. */
    public static void onJoin(ViewerSource viewers, SendSink sink,
            UUID uuid, String name, PlotDirectory dir,
            MpServerConfig config) {
        StudioId studio = StudioId.of(uuid);
        NAMES.put(studio, name);
        Optional<Plot> plot = owned(studio, dir);
        if (plot.isEmpty()) return;
        List<UUID> viewerUuids = viewers.viewersOf(studio).stream()
            .filter(v -> !v.equals(studio.owner())).toList();
        for (UUID v : viewerUuids)
            sink.sendPresence(v, new PresenceS2C(studio, name, true));
        PublicStudioState state = STORED.get(studio);
        if (state != null)
            for (UUID v : viewerUuids)
                sink.sendState(v, new StudioStateS2C(studio, state));
        MpLog.event(MpEvents.PRESENCE,
            "player", studio.owner(), "studio", studio.owner(),
            "plot", plot.map(Plot::index).orElse(-1), "online", true);
    }

    /** Owner disconnected: keeps rate state, stores an offline copy of the
     *  latest state, broadcasts offline presence plus that state to viewers,
     *  and logs {@code presence}. */
    public static void onDisconnect(ViewerSource viewers, SendSink sink,
            UUID uuid, String name, PlotDirectory dir,
            MpServerConfig config) {
        StudioId studio = StudioId.of(uuid);
        Optional<Plot> plot = owned(studio, dir);
        if (plot.isEmpty()) {
            NAMES.remove(studio);
            return;
        }
        PublicStudioState stored = STORED.get(studio);
        if (stored != null && stored.foremanOnline()) {
            stored = new PublicStudioState(stored.rev(), false,
                stored.agents(), stored.counts(), stored.goal(),
                stored.ci(), stored.policy(), stored.tasks());
            STORED.put(studio, stored);
        }
        List<UUID> viewerUuids = viewers.viewersOf(studio).stream()
            .filter(v -> !v.equals(studio.owner())).toList();
        for (UUID v : viewerUuids)
            sink.sendPresence(v, new PresenceS2C(studio, name, false));
        if (stored != null)
            for (UUID v : viewerUuids)
                sink.sendState(v, new StudioStateS2C(studio, stored));
        MpLog.event(MpEvents.PRESENCE,
            "player", studio.owner(), "studio", studio.owner(),
            "plot", plot.map(Plot::index).orElse(-1), "online", false);
    }

    /** Takes a token from the sender's bucket. A sender with no hello or no
     *  plot has a bucket too; buckets unused for a window are dropped here. */
    private static boolean take(Map<UUID, Limited> limiters, UUID player,
            int rate, long nowNanos) {
        Limited limiter = limiters.get(player);
        if (limiter == null || limiter.rate != rate) {
            limiters.values().removeIf(l -> nowNanos - l.usedNanos >= WINDOW_NANOS);
            limiter = new Limited(rate);
            limiters.put(player, limiter);
        }
        limiter.usedNanos = nowNanos;
        return limiter.bucket.tryTake(nowNanos);
    }

    /** Whether this refusal's line was already logged within the window. The
     *  refusal never depends on the answer; expired entries are dropped here. */
    private static boolean throttled(String event, UUID player, String reason,
            long nowNanos) {
        Refusal key = new Refusal(event, player, reason);
        Long last = LOGGED.get(key);
        if (last != null && nowNanos - last < WINDOW_NANOS) return true;
        if (last == null) LOGGED.values().removeIf(t -> nowNanos - t >= WINDOW_NANOS);
        LOGGED.put(key, nowNanos);
        return false;
    }

    /** The studio's plot: the only plot lookup in this class. A studio that no
     *  longer owns one loses its stored state, so a state from before a plot
     *  was freed is never served again. */
    private static Optional<Plot> owned(StudioId studio, PlotDirectory dir) {
        Optional<Plot> plot = dir.plotOf(studio);
        if (plot.isEmpty()) STORED.remove(studio);
        return plot;
    }

    /** Content equality ignoring {@code rev}; the latest revision is kept. */
    private static boolean sameContent(PublicStudioState a, PublicStudioState b) {
        if (a == null) return false;
        return a.foremanOnline() == b.foremanOnline()
            && a.agents().equals(b.agents())
            && a.counts().equals(b.counts())
            && a.goal().equals(b.goal())
            && a.ci().equals(b.ci())
            && a.policy().equals(b.policy())
            && Objects.equals(a.tasks(), b.tasks());
    }
}
