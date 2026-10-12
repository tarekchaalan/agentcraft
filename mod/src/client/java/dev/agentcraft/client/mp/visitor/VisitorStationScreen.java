package dev.agentcraft.client.mp.visitor;

import dev.agentcraft.client.mp.StudioView;
import dev.agentcraft.client.mp.Studios;
import dev.agentcraft.client.mp.visitor.VisitorGate.Station;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.Panels;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import dev.agentcraft.mp.StudioId;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The read-only visitor panel for a station in someone else's studio (MP-11). It shows the owner's
 * name, the station kind and what the public state allows, from {@link VisitorGate} only: no
 * message box, no buttons, no log tail, no task or goal text unless the owner opted in. The world
 * keeps running behind it, like {@code AgentCardScreen}.
 *
 * <p>It follows the studio view while open: a state update redraws, and the layout leaving range
 * closes the panel.
 *
 * <p>The panel never grows past the screen: a body with more rows than fit (a task wall can carry
 * 34) is cut to the rows that fit, the last one saying how many are left out. The header and the
 * footer are always drawn.
 */
public final class VisitorStationScreen extends Screen {
	private static final int W = 260;
	private static final int ROW = 10;
	/** The gap kept between the panel and the top and bottom of the screen. */
	private static final int MARGIN = 4;

	private final StudioId studioId;
	private final Station kind;
	private int x0;
	private int y0;
	private int h;
	private List<String> lines = List.of();
	private VisitorGate.Fit fit = new VisitorGate.Fit(0, 0, false);

	public VisitorStationScreen(StudioId studioId, Station kind) {
		super(Component.literal("Visitor"));
		this.studioId = studioId;
		this.kind = kind;
	}

	/** The station this panel was opened for (QA / dev). */
	public Station station() {
		return kind;
	}

	/** The studio this panel shows (QA / dev). */
	public StudioId studioId() {
		return studioId;
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		// no dimming, no blur: the HQ stays alive behind the panel
	}

	@Override
	protected void init() {
		layout();
	}

	@Override
	protected void repositionElements() {
		layout();
	}

	private @Nullable StudioView view() {
		return Studios.view(studioId).orElse(null);
	}

	private void layout() {
		StudioView view = view();
		lines = VisitorGate.stationLines(view == null ? null : view.publicState(), kind);
		Kit.Padding pp = Kit.padding("panel_paper");
		// everything but the body: padding, the two header rows and the footer
		int chrome = pp.top() + 14 + 16 + 12 + 12 + pp.bottom();
		int available = VisitorGate.rowsAvailable(this.height - 2 * MARGIN, chrome, ROW);
		fit = VisitorGate.fit(lines.size(), available);
		// an empty body keeps one blank row, as long as the screen has room for it
		int rows = Math.max(Math.min(1, available), fit.rows());
		h = chrome + (rows * ROW);
		x0 = (this.width - W) / 2;
		y0 = Math.max(MARGIN, (this.height - h) / 2);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float a) {
		StudioView view = view();
		if (view == null) {
			onClose(); // the studio left range: nothing left to show
			return;
		}
		layout();
		Panels.panel(g, x0, y0, W, h);
		Kit.Padding pp = Kit.padding("panel_paper");
		int ix = x0 + pp.left();
		int iw = W - pp.left() - pp.right();
		int y = y0 + pp.top();
		int ink = UiStyle.color("paper.text");
		int muted = UiStyle.color("paper.muted");

		// header: owner name and the online flag, then the station kind
		Panels.text(g, font, TextUtil.ellipsize(font, view.ownerName(), iw - 60), ix, y + 1, ink);
		String online = view.online() ? "online" : "offline";
		Panels.text(g, font, online, ix + iw - font.width(online), y + 1, muted);
		y += 14;
		Panels.text(g, font, kind.label(), ix, y, UiStyle.color("paper.path"));
		y += 16;

		// body: public state only, cut to the rows that fit the screen
		for (String line : lines.subList(0, fit.shown())) {
			Panels.text(g, font, TextUtil.ellipsize(font, line, iw), ix, y, ink);
			y += ROW;
		}
		if (fit.indicator()) {
			Panels.text(g, font, VisitorGate.moreLine(fit.remaining()), ix, y, muted);
		}

		// footer: the panel is read-only and Esc closes it
		int fy = y0 + h - pp.bottom() - 6;
		String hint = "Read-only visitor view";
		Panels.text(g, font, hint, ix, fy, muted);
		String esc = "Esc close";
		Panels.text(g, font, esc, ix + iw - font.width(esc), fy, muted);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.button() == 0 && (event.x() < x0 || event.x() > x0 + W || event.y() < y0 || event.y() > y0 + h)) {
			onClose();
			return true;
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isEscape()) {
			onClose();
			return true;
		}
		return super.keyPressed(event);
	}
}
