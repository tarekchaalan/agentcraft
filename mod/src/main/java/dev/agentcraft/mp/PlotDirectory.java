package dev.agentcraft.mp;

import java.util.Collection;
import java.util.Optional;
import net.minecraft.core.BlockPos;

public interface PlotDirectory {
    Optional<Plot> plotOf(StudioId studio);
    Optional<Plot> plotAt(BlockPos pos);
    Collection<Plot> all();
}
