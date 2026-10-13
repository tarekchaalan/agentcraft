package dev.agentcraft.client.monitor;

import static org.junit.jupiter.api.Assertions.*;

import dev.agentcraft.client.mp.StudioView;
import dev.agentcraft.client.mp.Studios;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.PlotGrid;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.state.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** MP-09: a studio event empties the position cache only when it can move a position to another studio. */
class StudioDisplaysTest {
	private static final BlockPos IN_A = new BlockPos(1005, 64, 5), IN_B = new BlockPos(2005, 64, 5), FAR = new BlockPos(3005, 64, 5);
	private final StudioId a = StudioId.of(UUID.randomUUID()), b = StudioId.of(UUID.randomUUID());
	private boolean bridged = true;

	private static PublicStudioState state(int rev) {
		return new PublicStudioState(rev, true, List.of(), new Counts(2, 1, 4, 3, 5, 6, 7),
			new GoalSummary(GoalStatusWire.NONE, 0f, null), List.of(), PublicPolicy.DEFAULT, null);
	}

	/** A plotless studio whose layout bounds start at {@code minX}. */
	private static StudioView view(StudioId id, int minX) {
		var layout = new Anchors.Layout("studio", 1, new Anchors.Bounds(minX, 60, 0, minX + 10, 70, 10), Map.of());
		return new StudioView(id, false, "Bob", true, layout, state(1), 0);
	}

	private static StudioId at(BlockPos pos) {
		return StudioDisplays.at(pos).map(StudioView::id).orElse(null);
	}

	/** {@code StudioDisplays.init} also registers a client tick, so the test registers the listener alone. */
	@BeforeEach
	void bridge() {
		Studios.addListener((id, view) -> { if (bridged) StudioDisplays.studioChanged(id, view); });
		Studios.put(view(a, 1000));
	}

	/** {@code Studios} cannot drop a listener; the removals of the reset empty the cache before it goes quiet. */
	@AfterEach
	void reset() {
		Studios.reset();
		bridged = false;
	}

	@Test
	void a_state_or_presence_update_keeps_every_entry_and_the_next_read_sees_it() {
		Studios.put(view(b, 2000));
		assertEquals(a, at(IN_A));
		assertEquals(b, at(IN_B));
		assertNull(at(FAR));
		PublicStudioState next = state(2);
		Studios.updateState(a, next);
		Studios.updatePresence(a, "Ann", false);
		assertTrue(StudioDisplays.cached(IN_A) && StudioDisplays.cached(IN_B) && StudioDisplays.cached(FAR));
		StudioView seen = StudioDisplays.at(IN_A).orElseThrow();
		assertSame(next, seen.publicState());
		assertFalse(seen.online());
	}

	@Test
	void a_membership_layout_or_plot_change_empties_the_cache() {
		assertEquals(a, at(IN_A));
		assertNull(at(IN_B));
		Studios.put(view(b, 2000)); // membership: a new studio may cover a position that resolved to nothing
		assertFalse(StudioDisplays.cached(IN_A));
		assertEquals(b, at(IN_B));
		assertEquals(a, at(IN_A));
		Studios.put(view(a, 3000)); // layout: A moved, so its old position must stop resolving to it
		assertNull(at(IN_A));
		assertEquals(a, at(FAR));
		BlockPos inPlot = PlotGrid.originOf(5, 128).offset(0, 64, 0);
		assertNull(at(inPlot));
		Studios.setPlot(b, 5, 128); // plot: setPlot fires no listener, B's next event carries the new plot
		Studios.updateState(b, state(2));
		assertNull(at(IN_B));
		assertEquals(b, at(inPlot));
		Studios.remove(b);
		assertNull(at(inPlot));
		assertEquals(a, at(FAR));
	}
}
