package dev.agentcraft.client.monitor;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.agentcraft.client.mp.StudioView;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.StudioId;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

/**
 * MP-09: the remote monitor's steady-frame fast path covers every input of the remote layout, so a look
 * switch or a resized panel rebuilds without waiting for the studio's next state. The layout itself
 * needs a client font; the rule that decides whether to skip it does not.
 */
class MonitorScreenRemoteSyncTest {

	private static StudioView studio(boolean online) {
		return new StudioView(StudioId.of(new UUID(0, 9)), false, "Bob", online, Anchors.Layout.EMPTY, null, 1);
	}

	/** A screen as a remote layout leaves it: built from {@code v} for "kit", dark, a 2x1 panel at 96 px per block. */
	private static MonitorScreen laidOut(StudioView v) {
		MonitorScreen m = new MonitorScreen(new BlockPos(1, 2, 3));
		m.remoteSource = v;
		m.remoteBinding = "kit";
		m.style = ScreenStyle.DARK;
		m.ppb = 96;
		m.panelW = 2;
		m.panelH = 1;
		return m;
	}

	@Test
	void a_steady_frame_is_up_to_date() {
		StudioView v = studio(true);
		assertTrue(laidOut(v).remoteUpToDate(v, "kit", ScreenStyle.DARK, 96, 2, 1));
	}

	@Test
	void a_look_switch_or_a_resized_panel_is_not_up_to_date_under_the_same_snapshot() {
		StudioView v = studio(true);
		MonitorScreen m = laidOut(v);
		// dev.displays {look: paper}
		assertFalse(m.remoteUpToDate(v, "kit", ScreenStyle.PAPER, 96, 2, 1));
		// the connected panel grew or shrank
		assertFalse(m.remoteUpToDate(v, "kit", ScreenStyle.DARK, 96, 3, 1));
		assertFalse(m.remoteUpToDate(v, "kit", ScreenStyle.DARK, 96, 2, 2));
		// and with it the pixel density
		assertFalse(m.remoteUpToDate(v, "kit", ScreenStyle.DARK, 128, 2, 1));
	}

	@Test
	void a_new_snapshot_a_new_binding_or_a_screen_never_laid_out_remote_is_not_up_to_date() {
		StudioView v = studio(true);
		MonitorScreen m = laidOut(v);
		assertFalse(m.remoteUpToDate(studio(false), "kit", ScreenStyle.DARK, 96, 2, 1));
		assertFalse(m.remoteUpToDate(v, "feed", ScreenStyle.DARK, 96, 2, 1));
		assertFalse(new MonitorScreen(new BlockPos(1, 2, 3)).remoteUpToDate(v, "kit", ScreenStyle.DARK, 96, 2, 1));
	}
}
