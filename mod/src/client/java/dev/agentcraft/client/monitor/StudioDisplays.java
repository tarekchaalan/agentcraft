package dev.agentcraft.client.monitor;

import dev.agentcraft.client.mp.StudioView;
import dev.agentcraft.client.mp.Studios;
import dev.agentcraft.layout.Anchors.Layout;
import dev.agentcraft.mp.Plot;
import dev.agentcraft.mp.StudioId;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.core.BlockPos;
import org.jspecify.annotations.Nullable;

/**
 * Resolves a panel's studio once per position and caches it so the displays keep the
 * {@link Studios#at} walk (snapshots and boxes) off the steady frame.
 *
 * <p>The cache is invalidated two ways, and both are needed. The {@link Studios} listener clears it
 * the moment a view appears, goes or changes its layout or plot, so a resolved studio is correct in
 * the same client task. But {@link Studios#setPlot} and {@link Studios#setOverlay} change what
 * {@link Studios#at} returns without firing a listener, so a client tick clears the cache once a
 * second as a backstop; a caller that happened to look between the change and the next clear would
 * otherwise keep a stale resolution.
 */
public final class StudioDisplays {
	/** What places a studio in the world: the layout (its bounds) and the plot. */
	private record Footprint(Layout layout, @Nullable Plot plot) {
	}

	private static final Map<BlockPos, Optional<StudioId>> CACHE = new HashMap<>();
	private static final Map<StudioId, Footprint> FOOTPRINTS = new HashMap<>();
	private static boolean installed;

	private StudioDisplays() {
	}

	/** Install the invalidation listener and the tick backstop once (from any feature's {@code init()}). */
	public static void init() {
		if (installed) {
			return;
		}
		installed = true;
		Studios.addListener(StudioDisplays::studioChanged);
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.level != null && (mc.level.getGameTime() % 20) == 0) {
				CACHE.clear();
			}
		});
	}

	/** A view that keeps its layout and plot covers the same positions, so the cache stays; else all of it goes. */
	static void studioChanged(StudioId id, @Nullable StudioView view) {
		Footprint now = view == null ? null : new Footprint(view.layout(), Studios.plot(id).orElse(null));
		Footprint before = now == null ? FOOTPRINTS.remove(id) : FOOTPRINTS.put(id, now);
		if (now == null || !now.equals(before)) {
			CACHE.clear();
		}
	}

	/** The studio a panel at {@code pos} belongs to. Only which studio is cached, so its view is always the latest. */
	public static Optional<StudioView> at(BlockPos pos) {
		Optional<StudioId> id = CACHE.get(pos);
		if (id == null) {
			id = Studios.at(pos).map(StudioView::id);
			CACHE.put(pos.immutable(), id);
		}
		return id.isPresent() ? Studios.view(id.get()) : Optional.empty();
	}

	static boolean cached(BlockPos pos) {
		return CACHE.containsKey(pos);
	}
}
