package dev.agentcraft.client.taskwall;

import dev.agentcraft.client.monitor.DisplayDraw;
import dev.agentcraft.client.mp.StudioView;
import dev.agentcraft.mp.state.TaskStatusWire;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.core.BlockPos;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * Cached remote Task Wall layout for one board panel: the four lanes with their public counts, the
 * red blocked count the Todo header carries, and title-only cards when the owner opted task titles
 * in. It reads no {@code ForemanState} and holds no {@code Task}; the own studio keeps
 * {@link TaskBoard}. Rebuilt only when the public view or the panel geometry changes.
 */
final class RemoteBoard {
	static final int CARD_PAD = 5;
	static final int MAX_TITLE_LINES = 2;

	final BlockPos origin;
	long lastUsedNanos;
	int ppb;
	int panelW;
	int panelH;
	@Nullable RemoteBoardView view;
	/** The stable studio snapshot the current view was built from, so a steady frame allocates nothing. */
	@Nullable StudioView source;

	float pw, ph, ix0, iy0, ix1, iy1, cardsTop;
	final List<TaskBoard.Column> columns = new ArrayList<>(4);
	final List<Card> cards = new ArrayList<>();
	final DisplayDraw.Rects lanes = new DisplayDraw.Rects();
	final DisplayDraw.Rects veil = new DisplayDraw.Rects();
	final DisplayDraw.Rects badge = new DisplayDraw.Rects();
	long layoutNanos;

	/** One title-only card: its rectangle and wrapped title lines, blocked in the error ink. */
	record Card(float x, float y, float w, float h, List<FormattedCharSequence> lines, boolean blocked) {
	}

	RemoteBoard(BlockPos origin) {
		this.origin = origin;
	}

	/** Build from the studio only when its snapshot or the panel size changed. Returns true when rebuilt. */
	boolean syncSource(StudioView v, int panelW, int panelH) {
		if (v == source && panelW == this.panelW && panelH == this.panelH) {
			return false;
		}
		source = v;
		return sync(RemoteBoardView.of(v.publicState(), v.online()), panelW, panelH);
	}

	/** Rebuild when the public view or the panel size changed. Returns true when it rebuilt. */
	boolean sync(RemoteBoardView v, int panelW, int panelH) {
		int ppb = TaskBoard.density(panelW, panelH);
		boolean resized = ppb != this.ppb || panelW != this.panelW || panelH != this.panelH;
		if (!resized && v.equals(view)) {
			return false;
		}
		view = v;
		this.ppb = ppb;
		this.panelW = panelW;
		this.panelH = panelH;
		long t0 = System.nanoTime();
		layout(v);
		layoutNanos = System.nanoTime() - t0;
		return true;
	}

	private void layout(RemoteBoardView v) {
		Font font = Minecraft.getInstance().font;
		pw = panelW * ppb;
		ph = panelH * ppb;
		float trim = ppb * TaskBoard.TRIM / 16f;
		ix0 = trim + TaskBoard.PAD;
		iy0 = trim + TaskBoard.PAD - 1;
		ix1 = pw - trim - TaskBoard.PAD;
		iy1 = ph - trim - TaskBoard.PAD + 1;
		cardsTop = iy0 + TaskBoard.HEADER_H + 3;
		columns.clear();
		cards.clear();
		List<RemoteBoardView.Column> cols = v.columns();
		float laneW = (ix1 - ix0 - TaskBoard.GAP * 3) / 4f;
		for (int i = 0; i < 4 && i < cols.size(); i++) {
			RemoteBoardView.Column rc = cols.get(i);
			TaskBoard.Column col = new TaskBoard.Column(col(rc.lane()));
			col.x = ix0 + i * (laneW + TaskBoard.GAP);
			col.w = laneW;
			col.ax = col.x;
			col.aw = laneW;
			col.placed = true;
			col.count = rc.count();
			col.blocked = rc.blocked();
			col.family = family(rc.lane());
			String label = rc.lane().name();
			String cs = Integer.toString(rc.count());
			// an explicit "1 blocked" red count in the Todo header (a compact form on a narrow lane)
			String bl = rc.blocked() > 0 ? rc.blocked() + " blocked" : "";
			if (!bl.isEmpty() && font.width(label) + font.width(cs) + font.width(bl) + 24 > col.w) {
				bl = rc.blocked() + "!";
			}
			col.blockedSeq = TaskBoard.seq(bl);
			col.blockedW = font.width(bl);
			col.label = TaskBoard.seq(label);
			col.countSeq = TaskBoard.seq(cs);
			col.countW = font.width(cs);
			columns.add(col);
			layoutCards(font, v, rc.lane(), col, laneW);
		}
	}

	private void layoutCards(Font font, RemoteBoardView v, RemoteBoardView.Lane lane, TaskBoard.Column col, float laneW) {
		List<RemoteBoardView.Card> laneCards = new ArrayList<>();
		for (RemoteBoardView.Card c : v.cards()) {
			if (c.lane() == lane) {
				laneCards.add(c);
			}
		}
		float inner = laneW - 2 * CARD_PAD;
		float y = cardsTop;
		int shown = 0;
		for (int k = 0; k < laneCards.size(); k++) {
			RemoteBoardView.Card c = laneCards.get(k);
			String text = c.title() == null || c.title().isBlank() ? c.id() : c.title();
			List<String> wrapped = new TaskBoard.Title(font, text).wrap(font, (int) inner, (int) inner, MAX_TITLE_LINES);
			float h = CARD_PAD * 2 + wrapped.size() * TaskBoard.LINE;
			boolean more = k + 1 < laneCards.size();
			float reserve = more ? TaskBoard.CHIP_H + TaskBoard.GAP : 0;
			if (y + h > iy1 - reserve) {
				break;
			}
			List<FormattedCharSequence> lines = new ArrayList<>(wrapped.size());
			for (String l : wrapped) {
				lines.add(TaskBoard.seq(l));
			}
			cards.add(new Card(col.ax, y, laneW, h, lines, c.status() == TaskStatusWire.BLOCKED));
			y += h + TaskBoard.GAP;
			shown++;
		}
		if (shown < laneCards.size()) {
			int hidden = laneCards.size() - shown;
			col.hidden.clear();
			for (int k = shown; k < laneCards.size(); k++) {
				col.hidden.add(laneCards.get(k).id());
			}
			String more = "+" + hidden + " more";
			col.chip = TaskBoard.seq(more);
			col.chipW = font.width(more) + 10;
			col.chipY = Math.min(y, iy1 - TaskBoard.CHIP_H);
		}
	}

	private static TaskBoard.Col col(RemoteBoardView.Lane lane) {
		return switch (lane) {
			case TODO -> TaskBoard.Col.TODO;
			case DOING -> TaskBoard.Col.DOING;
			case REVIEW -> TaskBoard.Col.REVIEW;
			case DONE -> TaskBoard.Col.DONE;
		};
	}

	private static String family(RemoteBoardView.Lane lane) {
		return switch (lane) {
			case TODO -> "idle";
			case DOING -> "working";
			case REVIEW -> "thinking";
			case DONE -> "done";
		};
	}
}
