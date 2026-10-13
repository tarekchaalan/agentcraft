package dev.agentcraft.mp;

import java.util.Objects;
import java.util.UUID;

public record StudioId(UUID owner) {
    public static final StudioId LOCAL = new StudioId(new UUID(0, 0));
    public StudioId { Objects.requireNonNull(owner); }
    public static StudioId of(UUID owner) { return new StudioId(owner); }
}
