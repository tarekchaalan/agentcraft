package dev.agentcraft.client.mp;

import java.util.Objects;
import java.util.function.BiConsumer;

/** Seam between the agents (which own the click) and the visitor feature (which owns the read-only card). */
public final class RemoteAgentClicks {
    private static BiConsumer<StudioView,String> handler=(studio,agentId)->{};
    private RemoteAgentClicks() {}
    /** One handler at a time; the visitor feature registers its read-only card. */
    public static void set(BiConsumer<StudioView,String> next) { handler=Objects.requireNonNull(next); }
    /** A click on a remote studio's agent (client thread). Does nothing until a handler is registered. */
    public static void fire(StudioView studio,String agentId) { handler.accept(Objects.requireNonNull(studio),Objects.requireNonNull(agentId)); }
}
