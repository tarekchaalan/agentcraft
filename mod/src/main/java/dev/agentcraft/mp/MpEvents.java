package dev.agentcraft.mp;

import java.util.Map;

public final class MpEvents {
    private MpEvents() {}
    public static final String CONFIG_LOADED = "config_loaded";
    public static final String CONFIG_INVALID = "config_invalid";
    public static final String HELLO_SENT = "hello_sent";
    public static final String HELLO_RECEIVED = "hello_received";
    public static final String MODE_CHANGED = "mode_changed";
    public static final String FAKE_STUDIO = "fake_studio";
    public static final String WORLD_RULES_APPLIED = "world_rules_applied";
    public static final String CREATIVE_FORCED = "creative_forced";
    public static final String AUTOWORLD_SKIPPED = "autoworld_skipped";
    public static final String PLOT_BUILT = "plot_built";
    public static final String PLOT_BUILD_FAILED = "plot_build_failed";
    public static final String PLOT_ALLOCATED = "plot_allocated";
    public static final String PLOT_ALLOCATION_FAILED = "plot_allocation_failed";
    public static final String PLOT_COMMAND = "plot_command";
    public static final String LAYOUT_SENT = "layout_sent";
    public static final String LAYOUT_APPLIED = "layout_applied";
    public static final String LAYOUT_REMOVED = "layout_removed";
    public static final String PUBLIC_STATE_SENT = "public_state_sent";
    public static final String PUBLIC_STATE_SKIPPED = "public_state_skipped";
    public static final String POLICY_CHANGED = "policy_changed";
    public static final String PUBLIC_STATE_REJECTED = "public_state_rejected";
    public static final String STUDIO_EVENT_REJECTED = "studio_event_rejected";
    public static final String RELAY_SENT = "relay_sent";
    public static final String PRESENCE = "presence";
    public static final String REMOTE_STUDIO_ADDED = "remote_studio_added";
    public static final String REMOTE_STUDIO_REMOVED = "remote_studio_removed";
    public static final String WORLD_INTENT_APPLIED = "world_intent_applied";
    public static final String WORLD_INTENT_REJECTED = "world_intent_rejected";
    public static final String AGENTS_STUDIO_ATTACHED = "agents_studio_attached";
    public static final String AGENTS_STUDIO_DETACHED = "agents_studio_detached";
    public static final String VISITOR_READONLY = "visitor_readonly";
    public static final String PLOT_EDIT_REFUSED = "plot_edit_refused";
    public static final Map<String, String> CATALOG = Map.ofEntries(
        Map.entry(CONFIG_LOADED, "info"),
        Map.entry(CONFIG_INVALID, "warn"),
        Map.entry(HELLO_SENT, "info"),
        Map.entry(HELLO_RECEIVED, "info"),
        Map.entry(MODE_CHANGED, "info"),
        Map.entry(FAKE_STUDIO, "info"),
        Map.entry(WORLD_RULES_APPLIED, "info"),
        Map.entry(CREATIVE_FORCED, "info"),
        Map.entry(AUTOWORLD_SKIPPED, "info"),
        Map.entry(PLOT_BUILT, "info"),
        Map.entry(PLOT_BUILD_FAILED, "error"),
        Map.entry(PLOT_ALLOCATED, "info"),
        Map.entry(PLOT_ALLOCATION_FAILED, "error"),
        Map.entry(PLOT_COMMAND, "info"),
        Map.entry(LAYOUT_SENT, "debug"),
        Map.entry(LAYOUT_APPLIED, "debug"),
        Map.entry(LAYOUT_REMOVED, "debug"),
        Map.entry(PUBLIC_STATE_SENT, "debug"),
        Map.entry(PUBLIC_STATE_SKIPPED, "debug"),
        Map.entry(POLICY_CHANGED, "info"),
        Map.entry(PUBLIC_STATE_REJECTED, "warn"),
        Map.entry(STUDIO_EVENT_REJECTED, "warn"),
        Map.entry(RELAY_SENT, "debug"),
        Map.entry(PRESENCE, "info"),
        Map.entry(REMOTE_STUDIO_ADDED, "info"),
        Map.entry(REMOTE_STUDIO_REMOVED, "info"),
        Map.entry(WORLD_INTENT_APPLIED, "debug"),
        Map.entry(WORLD_INTENT_REJECTED, "warn"),
        Map.entry(AGENTS_STUDIO_ATTACHED, "debug"),
        Map.entry(AGENTS_STUDIO_DETACHED, "debug"),
        Map.entry(VISITOR_READONLY, "debug"),
        Map.entry(PLOT_EDIT_REFUSED, "info"));
}
