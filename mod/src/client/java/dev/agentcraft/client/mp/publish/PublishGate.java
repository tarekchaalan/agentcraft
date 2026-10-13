package dev.agentcraft.client.mp.publish;

import dev.agentcraft.client.mp.MpMode;

/** Whether this client may publish. Singleplayer is silent: no send and no publish log. */
public enum PublishGate {
    QUIET, REFUSED, OPEN;

    public enum Action { NONE, REFUSE, SEND }

    public static PublishGate of(MpMode mode) {
        return switch (mode) {
            case SINGLEPLAYER -> QUIET;
            case REMOTE_VANILLA -> REFUSED;
            case MULTIPLAYER -> OPEN;
        };
    }

    /** {@code canSend} is the live play-channel check. Singleplayer never sends, even when it is true. */
    public static Action action(MpMode mode, boolean canSend) {
        return switch (of(mode)) {
            case QUIET -> Action.NONE;
            case REFUSED -> Action.REFUSE;
            case OPEN -> canSend ? Action.SEND : Action.REFUSE;
        };
    }
}
