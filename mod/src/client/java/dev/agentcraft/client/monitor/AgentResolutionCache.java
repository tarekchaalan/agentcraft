package dev.agentcraft.client.monitor;

import dev.agentcraft.layout.Anchors;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * Per-panel cache for the agent a monitor shows when its binding is empty: the binding comes from a
 * {@code monitor_<id>} anchor of the layout its studio owns, so the result depends on the panel's
 * position, its geometry (facing, width, height: the walk tests which anchor lies on the panel) <em>and</em>
 * the layout it was resolved against.
 *
 * <p>A single {@code (name, revision)} pair for the whole map is wrong once two studios are on screen:
 * the own panels read {@link Anchors#current()} and a remote studio's panels read that studio's layout,
 * so two extracts per frame would keep clearing each other's cache. Instead every entry records the
 * layout it was resolved against (name and revision) and only that panel resolves again when its
 * layout changed. An entry also records the panel geometry it was resolved for: a connected panel can
 * grow, shrink or be replaced facing another way while its origin block and its layout stay the same.
 *
 * <p>Pure logic, no block entity: {@link #resolve} takes the panel position, its geometry and a
 * {@link Resolver}, so JUnit can drive the invalidation rule directly. Pass the resolver as a method
 * reference (or another non-capturing function) so a cache hit allocates nothing.
 */
final class AgentResolutionCache {
	/** The resolving step: the agent binding for {@code layout} on this panel, else {@code "feed"}. */
	@FunctionalInterface
	interface Resolver {
		String resolve(Anchors.Layout layout, BlockPos pos, Direction facing, int width, int height);
	}

	/** A resolved panel: the binding, and the layout identity and panel geometry it was resolved against. */
	private record Entry(String agent, String layoutName, long layoutRevision, Direction facing, int width, int height) {
		boolean resolvedFor(Anchors.Layout layout, Direction facing, int width, int height) {
			return layoutRevision == layout.revision() && this.facing == facing && this.width == width && this.height == height
				&& layoutName.equals(layout.name());
		}
	}

	private final Map<BlockPos, Entry> entries = new HashMap<>();

	/**
	 * The binding for the panel at {@code pos}, cached until the layout it resolved against changes
	 * name or revision, or the panel changes facing, width or height. Only a miss runs {@code resolver}.
	 */
	String resolve(BlockPos pos, Direction facing, int width, int height, Anchors.Layout layout, Resolver resolver) {
		BlockPos key = pos.immutable();
		Entry entry = entries.get(key);
		if (entry != null && entry.resolvedFor(layout, facing, width, height)) {
			return entry.agent();
		}
		String agent = resolver.resolve(layout, key, facing, width, height);
		entries.put(key, new Entry(agent, layout.name(), layout.revision(), facing, width, height));
		return agent;
	}
}
