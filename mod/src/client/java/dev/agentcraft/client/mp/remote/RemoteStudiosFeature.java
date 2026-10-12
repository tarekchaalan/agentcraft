package dev.agentcraft.client.mp.remote;

import dev.agentcraft.client.mp.MpMode;
import dev.agentcraft.client.mp.StudioView;
import dev.agentcraft.client.mp.Studios;
import dev.agentcraft.mp.MpEvents;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.net.PresenceS2C;
import dev.agentcraft.mp.net.StudioEventS2C;
import dev.agentcraft.mp.net.StudioStateS2C;
import dev.agentcraft.mp.state.PublicEvent;
import dev.agentcraft.mp.state.PublicStudioState;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Applies remote-studio state, events and presence received from the server
 * into the client {@link Studios} registry. Own-studio payloads are ignored
 * and multiplayer gating is enforced at receive time.
 */
public final class RemoteStudiosFeature {
    /** Remote studios seen this connection, mapped to their local slot. */
    private static final Set<StudioId> SEEN = new HashSet<>();
    /** The slot each tracked remote studio had, remembered for removal. */
    private static final Map<StudioId, Integer> SLOTS = new HashMap<>();
    private RemoteStudiosFeature() {}

    /** Registers the receivers and the registry/mode listeners. Runs once. */
    public static void init() {
        MpMode.addListener(RemoteStudiosFeature::onModeChanged);
        Studios.addListener((id, view) -> observe(MpMode.current(), id, view));

        ClientPlayNetworking.registerGlobalReceiver(StudioStateS2C.TYPE,
            (payload, context) -> context.client().execute(() ->
                applyState(MpMode.current(), Studios.own().id(),
                    payload.studio(), payload.state())));

        ClientPlayNetworking.registerGlobalReceiver(PresenceS2C.TYPE,
            (payload, context) -> context.client().execute(() ->
                applyPresence(MpMode.current(), Studios.own().id(),
                    payload.studio(), payload.ownerName(), payload.online())));

        ClientPlayNetworking.registerGlobalReceiver(StudioEventS2C.TYPE,
            (payload, context) -> context.client().execute(() ->
                applyEvent(MpMode.current(), Studios.own().id(),
                    payload.studio(), payload.event())));
    }

    /** Leaves multiplayer: forget tracked studios without logging. */
    static void onModeChanged(MpMode mode) {
        if (mode != MpMode.MULTIPLAYER) {
            SEEN.clear();
            SLOTS.clear();
        }
    }

    /** Logs add/remove telemetry once per remote-studio lifecycle. A view that
     *  is absent, own, or outside multiplayer changes nothing. */
    static void observe(MpMode mode, StudioId id, StudioView view) {
        if (mode != MpMode.MULTIPLAYER) return;
        if (id.equals(Studios.own().id())) return;
        if (view == null) {
            Integer slot = SLOTS.remove(id);
            if (SEEN.remove(id) && slot != null) {
                MpLog.event(MpEvents.REMOTE_STUDIO_REMOVED,
                    "player", Studios.own().id().owner(),
                    "studio", id.owner(), "slot", slot);
            }
        } else if (!view.own() && SEEN.add(id)) {
            SLOTS.put(id, view.slot());
            MpLog.event(MpEvents.REMOTE_STUDIO_ADDED,
                "player", Studios.own().id().owner(),
                "studio", id.owner(), "slot", view.slot());
        }
    }

    /** Applies a remote state; ignores the own studio and non-multiplayer. */
    static void applyState(MpMode mode, StudioId own, StudioId studio,
            PublicStudioState state) {
        if (mode != MpMode.MULTIPLAYER || studio.equals(own)) return;
        Studios.updateState(studio, state);
    }

    /** Applies remote presence; ignores the own studio and non-multiplayer. */
    static void applyPresence(MpMode mode, StudioId own, StudioId studio,
            String ownerName, boolean online) {
        if (mode != MpMode.MULTIPLAYER || studio.equals(own)) return;
        Studios.updatePresence(studio, ownerName, online);
    }

    /** Fires a remote event to listeners; ignores own studio and
     *  non-multiplayer. */
    static void applyEvent(MpMode mode, StudioId own, StudioId studio,
            PublicEvent event) {
        if (mode != MpMode.MULTIPLAYER || studio.equals(own)) return;
        Studios.fireEvent(studio, event);
    }
}
