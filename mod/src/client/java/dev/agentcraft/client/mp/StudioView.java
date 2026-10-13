package dev.agentcraft.client.mp;

import dev.agentcraft.layout.Anchors.Layout;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.state.PublicStudioState;
import org.jspecify.annotations.Nullable;

public record StudioView(StudioId id, boolean own, String ownerName, boolean online, Layout layout,
        @Nullable PublicStudioState publicState, int slot) {}
