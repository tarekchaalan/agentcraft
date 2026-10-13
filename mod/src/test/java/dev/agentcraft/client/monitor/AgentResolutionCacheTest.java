package dev.agentcraft.client.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.agentcraft.layout.Anchors;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.junit.jupiter.api.Test;

/**
 * MP-09: the empty-binding agent cache is per panel, so an own panel and a remote panel that resolve
 * against different layouts do not evict each other every frame.
 */
class AgentResolutionCacheTest {

	private static Anchors.Layout layout(String name, long revision) {
		return new Anchors.Layout(name, revision, null, Map.of());
	}

	/** A resolver that counts how often it runs for each panel and echoes the call number + name. */
	private static AgentResolutionCache.Resolver counting(Map<BlockPos, AtomicInteger> calls) {
		return (layout, pos, facing, w, h) -> calls.computeIfAbsent(pos, k -> new AtomicInteger()).incrementAndGet() + ":" + layout.name();
	}

	private static int calls(Map<BlockPos, AtomicInteger> calls, BlockPos pos) {
		AtomicInteger n = calls.get(pos);
		return n == null ? 0 : n.get();
	}

	@Test
	void each_panel_resolves_once_and_only_re_resolves_when_its_own_layout_changes() {
		AgentResolutionCache cache = new AgentResolutionCache();
		BlockPos own = new BlockPos(1, 2, 3);
		BlockPos remote = new BlockPos(10, 2, 3);
		Anchors.Layout ownLayout = layout("hq", 7);
		Anchors.Layout remoteLayout = layout("bob", 3);
		Map<BlockPos, AtomicInteger> calls = new HashMap<>();
		AgentResolutionCache.Resolver resolver = counting(calls);

		// a panel of each layout in turn: the resolving function runs once per panel
		assertEquals("1:hq", cache.resolve(own, Direction.NORTH, 1, 1, ownLayout, resolver));
		assertEquals("1:bob", cache.resolve(remote, Direction.NORTH, 1, 1, remoteLayout, resolver));
		assertEquals(1, calls(calls, own));
		assertEquals(1, calls(calls, remote));

		// a steady frame with both layouts in view: neither panel resolves again
		assertEquals("1:hq", cache.resolve(own, Direction.NORTH, 1, 1, ownLayout, resolver));
		assertEquals("1:bob", cache.resolve(remote, Direction.NORTH, 1, 1, remoteLayout, resolver));
		assertEquals(1, calls(calls, own));
		assertEquals(1, calls(calls, remote));

		// only the own panel's layout was rebuilt: it resolves again, the remote panel does not
		assertEquals("2:hq", cache.resolve(own, Direction.NORTH, 1, 1, layout("hq", 8), resolver));
		assertEquals(2, calls(calls, own));
		assertEquals(1, calls(calls, remote));
	}

	@Test
	void a_rename_at_the_same_revision_also_re_resolves_that_panel() {
		AgentResolutionCache cache = new AgentResolutionCache();
		BlockPos pos = new BlockPos(4, 2, 5);
		Map<BlockPos, AtomicInteger> calls = new HashMap<>();
		AgentResolutionCache.Resolver resolver = counting(calls);

		assertEquals("1:hq", cache.resolve(pos, Direction.NORTH, 1, 1, layout("hq", 2), resolver));
		assertEquals("2:annex", cache.resolve(pos, Direction.NORTH, 1, 1, layout("annex", 2), resolver));
		assertEquals(2, calls(calls, pos));
	}

	@Test
	void a_panel_that_changes_width_height_or_facing_re_resolves_under_the_same_layout() {
		AgentResolutionCache cache = new AgentResolutionCache();
		BlockPos pos = new BlockPos(4, 2, 5);
		Anchors.Layout layout = layout("hq", 2);
		Map<BlockPos, AtomicInteger> calls = new HashMap<>();
		// echoes the geometry it was asked about, so a stale hit shows as the old geometry
		AgentResolutionCache.Resolver resolver = (l, p, facing, w, h) -> calls.computeIfAbsent(p, k -> new AtomicInteger()).incrementAndGet() + ":" + facing.name()
			+ ":" + w + "x" + h;

		assertEquals("1:NORTH:1x1", cache.resolve(pos, Direction.NORTH, 1, 1, layout, resolver));
		// widened to the right: the origin block and the layout are the same, the anchor may now be on it
		assertEquals("2:NORTH:2x1", cache.resolve(pos, Direction.NORTH, 2, 1, layout, resolver));
		// grown upwards
		assertEquals("3:NORTH:2x2", cache.resolve(pos, Direction.NORTH, 2, 2, layout, resolver));
		// replaced facing another way
		assertEquals("4:EAST:2x2", cache.resolve(pos, Direction.EAST, 2, 2, layout, resolver));
		// shrunk back
		assertEquals("5:EAST:1x1", cache.resolve(pos, Direction.EAST, 1, 1, layout, resolver));
		// and a steady frame with the geometry unchanged is still a hit
		assertEquals("5:EAST:1x1", cache.resolve(pos, Direction.EAST, 1, 1, layout, resolver));
		assertEquals(5, calls(calls, pos));
	}
}
