package dev.agentcraft.mp;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import net.minecraft.core.BlockPos;

public final class Plots {
    private static final Plot LOCAL = new Plot(0, StudioId.LOCAL, BlockPos.ZERO);
    public static final PlotDirectory SINGLEPLAYER = new PlotDirectory() {
        public Optional<Plot> plotOf(StudioId id) { return id.equals(StudioId.LOCAL) ? Optional.of(LOCAL) : Optional.empty(); }
        public Optional<Plot> plotAt(BlockPos pos) { return LOCAL.contains(pos) ? Optional.of(LOCAL) : Optional.empty(); }
        public Collection<Plot> all() { return List.of(LOCAL); }
    };
    private static volatile PlotDirectory directory = SINGLEPLAYER;
    private Plots() {}
    public static PlotDirectory directory() { return directory; }
    public static void install(PlotDirectory value) { directory = Objects.requireNonNull(value); }
}
