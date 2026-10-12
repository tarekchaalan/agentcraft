package dev.agentcraft.mp.server.plot;

import dev.agentcraft.mp.MpEvents;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.MpReasons;
import dev.agentcraft.mp.Plot;
import dev.agentcraft.mp.PlotDirectory;
import dev.agentcraft.mp.PlotGrid;
import dev.agentcraft.mp.StudioId;
import java.nio.file.Path;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/** The multiplayer {@link PlotDirectory}. Mutations stay on the server thread. */
public final class PlotRegistry implements PlotDirectory {
    private final TreeMap<Integer, Plot> byIndex = new TreeMap<>();
    private final Map<StudioId, Plot> byOwner = new HashMap<>();
    private boolean frozen;

    public enum Refuse { LOCAL, HAS_PLOT, TAKEN, BAD_INDEX, OVERLAP, IO }

    public record AllocateResult(@Nullable Plot plot, boolean created, @Nullable String failure) {}

    public record AssignResult(@Nullable Plot plot, @Nullable Refuse refuse) {}

    @Override
    public Optional<Plot> plotOf(StudioId studio) {
        return Optional.ofNullable(byOwner.get(studio));
    }

    @Override
    public Optional<Plot> plotAt(BlockPos pos) {
        for (Plot plot : byIndex.values()) {
            if (plot.contains(pos)) return Optional.of(plot);
        }
        return Optional.empty();
    }

    @Override
    public Collection<Plot> all() {
        return List.copyOf(byIndex.values());
    }

    public @Nullable Plot byIndex(int index) {
        return byIndex.get(index);
    }

    /** After a failed rollback save the file and memory disagree: nothing may write the file again. */
    public void freeze() {
        frozen = true;
    }

    public boolean frozen() {
        return frozen;
    }

    public void replaceAll(List<Plot> plots) {
        byIndex.clear();
        byOwner.clear();
        for (Plot plot : plots) {
            if (byIndex.containsKey(plot.index()) || byOwner.containsKey(plot.owner())) continue;
            byIndex.put(plot.index(), plot);
            byOwner.put(plot.owner(), plot);
        }
    }

    /**
     * Lowest free spiral index at {@code stride}. An existing owner is returned without a second
     * allocation. Persistence failure rolls the new row back and leaves the file unchanged.
     */
    public AllocateResult allocateStored(Path worldRoot, StudioId studio, int stride) {
        if (studio.equals(StudioId.LOCAL)) return new AllocateResult(null, false, null);
        Plot existing = byOwner.get(studio);
        if (existing != null) return new AllocateResult(existing, false, null);
        if (frozen) {
            logFailed(studio, MpReasons.IO_ERROR);
            return new AllocateResult(null, false, MpReasons.IO_ERROR);
        }
        AllocateResult created = insertNext(studio, stride);
        if (created.failure() != null) {
            logFailed(studio, created.failure());
            return created;
        }
        if (!created.created() || created.plot() == null) return created;
        if (!PlotStore.save(worldRoot, all())) {
            take(created.plot().index());
            logFailed(studio, MpReasons.IO_ERROR);
            return new AllocateResult(null, false, MpReasons.IO_ERROR);
        }
        Plot plot = created.plot();
        MpLog.event(MpEvents.PLOT_ALLOCATED,
            "player", studio.owner(),
            "studio", studio.owner(),
            "plot", plot.index(),
            "index", plot.index(),
            "origin", plot.origin().getX() + "," + plot.origin().getY() + "," + plot.origin().getZ());
        return created;
    }

    public AssignResult assignStored(Path worldRoot, StudioId studio, int index, int stride) {
        if (frozen) return new AssignResult(null, Refuse.IO);
        AssignResult result = assign(studio, index, stride);
        if (result.plot() == null) return result;
        if (!PlotStore.save(worldRoot, all())) {
            take(result.plot().index());
            return new AssignResult(null, Refuse.IO);
        }
        return result;
    }

    /** Drops the row. The caller deletes the plot directory and unpublishes the layout. */
    public @Nullable Plot take(int index) {
        Plot plot = byIndex.remove(index);
        if (plot != null) byOwner.remove(plot.owner(), plot);
        return plot;
    }

    public void restore(Plot plot) {
        byIndex.put(plot.index(), plot);
        byOwner.put(plot.owner(), plot);
    }

    private AllocateResult insertNext(StudioId studio, int stride) {
        int index = 0;
        while (true) {
            if (byIndex.containsKey(index)) {
                if (index == Integer.MAX_VALUE) return failed(MpReasons.GRID_FULL);
                index++;
                continue;
            }
            BlockPos origin;
            try {
                origin = PlotGrid.originOf(index, stride);
            } catch (IllegalArgumentException e) {
                return failed(MpReasons.GRID_FULL);
            }
            Plot candidate = new Plot(index, studio, origin);
            boolean overlaps = false;
            for (Plot old : byIndex.values()) {
                if (old.box().intersects(candidate.box())) {
                    overlaps = true;
                    break;
                }
            }
            if (overlaps) {
                if (index == Integer.MAX_VALUE) return failed(MpReasons.GRID_FULL);
                index++;
                continue;
            }
            byIndex.put(index, candidate);
            byOwner.put(studio, candidate);
            return new AllocateResult(candidate, true, null);
        }
    }

    private AssignResult assign(StudioId studio, int index, int stride) {
        if (studio.equals(StudioId.LOCAL)) return new AssignResult(null, Refuse.LOCAL);
        if (byOwner.containsKey(studio)) return new AssignResult(null, Refuse.HAS_PLOT);
        if (index < 0) return new AssignResult(null, Refuse.BAD_INDEX);
        if (byIndex.containsKey(index)) return new AssignResult(null, Refuse.TAKEN);
        BlockPos origin;
        try {
            origin = PlotGrid.originOf(index, stride);
        } catch (IllegalArgumentException e) {
            return new AssignResult(null, Refuse.BAD_INDEX);
        }
        Plot plot = new Plot(index, studio, origin);
        for (Plot old : byIndex.values()) {
            if (old.box().intersects(plot.box())) return new AssignResult(null, Refuse.OVERLAP);
        }
        byIndex.put(index, plot);
        byOwner.put(studio, plot);
        return new AssignResult(plot, null);
    }

    private static AllocateResult failed(String reason) {
        return new AllocateResult(null, false, reason);
    }

    private static void logFailed(StudioId studio, String reason) {
        MpLog.event(MpEvents.PLOT_ALLOCATION_FAILED, "player", studio.owner(), "studio", studio.owner(), "reason", reason);
    }
}
