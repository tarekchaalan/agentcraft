package dev.agentcraft.client.library;

import dev.agentcraft.client.diff.ReviewKit;
import dev.agentcraft.client.diff.SyntaxTint;
import dev.agentcraft.client.library.Markdown.Block;
import dev.agentcraft.client.library.Markdown.Span;
import dev.agentcraft.client.library.Markdown.Type;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

/**
 * Markdown blocks laid out for one width as a list of draw operations (text runs, fills, sprites,
 * live task-status dots), y-sorted so a scrolled view only walks the visible part. Built once per
 * (entry revision, width) and drawn every frame.
 */
final class MdLayout {
	static final int LINE_H = 11;

	enum OpKind {
		TEXT, FILL, SPRITE, TASKDOT
	}

	static final class Op {
		OpKind kind;
		int x;
		int y;
		int w;
		int h;
		@Nullable Component text;
		int color;
		@Nullable Identifier sprite;
		@Nullable String taskId;
	}

	private static final Pattern TASK_REF = Pattern.compile("^t\\d{1,4}$");

	final List<Op> ops = new ArrayList<>();
	int height;
	private final Font font;
	private final int width;
	private final Set<String> taskIds;
	private int y;

	private MdLayout(Font font, int width, Set<String> taskIds) {
		this.font = font;
		this.width = Math.max(60, width);
		this.taskIds = taskIds;
	}

	static MdLayout build(Font font, List<Block> blocks, int width, Set<String> taskIds) {
		MdLayout l = new MdLayout(font, width, taskIds);
		l.layout(blocks);
		return l;
	}

	private void layout(List<Block> blocks) {
		Type prev = null;
		for (Block b : blocks) {
			if (prev != null) {
				y += gapBefore(prev, b.type);
			}
			switch (b.type) {
				case H1 -> {
					inline(b.text, 0, ReviewKit.ink(), Style.EMPTY.withBold(true), 0);
					fill(0, y, Math.min(width, 40), 1, UiStyle.CLAY);
					y += 2;
				}
				case H2 -> inline(b.text, 0, ReviewKit.ink(), Style.EMPTY.withBold(true), 0);
				case H3 -> inline(b.text, 0, ReviewKit.muted(), Style.EMPTY.withBold(true), 0);
				case PARA -> inline(b.text, 0, ReviewKit.ink(), Style.EMPTY, 0);
				case BULLET -> {
					int ind = b.level * 10;
					fill(ind + 2, y + 4, 3, 3, b.level == 0 ? UiStyle.CLAY : UiStyle.BRASS);
					inline(b.text, ind + 10, ReviewKit.ink(), Style.EMPTY, 0);
				}
				case NUMBER -> {
					int ind = b.level * 10;
					int mw = Math.max(12, font.width(b.marker) + 3);
					text(ind + mw - 3 - font.width(b.marker), y + 1, Component.literal(b.marker), ReviewKit.muted());
					inline(b.text, ind + mw, ReviewKit.ink(), Style.EMPTY, 0);
				}
				case TASK -> {
					int ind = b.level * 10;
					sprite(b.checked ? Kit.CHECKBOX_CHECKED : Kit.CHECKBOX, ind, y, 10, 10);
					inline(b.text, ind + 14, b.checked ? ReviewKit.muted() : ReviewKit.ink(), Style.EMPTY, 0);
				}
				case QUOTE -> {
					int top = y;
					inline(b.text, 9, ReviewKit.muted(), Style.EMPTY, 0);
					fill(1, top, 2, y - top, UiStyle.BRASS);
				}
				case CODE -> code(b);
				case RULE -> {
					sprite(Kit.DIVIDER, 0, y + 2, width, 3);
					y += 7;
				}
				case TABLE -> table(b);
				default -> {
				}
			}
			prev = b.type;
		}
		height = y + 4;
	}

	private static boolean isList(Type t) {
		return t == Type.BULLET || t == Type.NUMBER || t == Type.TASK;
	}

	private static int gapBefore(Type prev, Type next) {
		return switch (next) {
			case H1 -> 10;
			case H2 -> 9;
			case H3 -> 7;
			default -> isList(prev) && isList(next) ? 1 : (prev == Type.H1 || prev == Type.H2 || prev == Type.H3) ? 3 : 5;
		};
	}

	// ------------------------------------------------------------------ inline text

	private record Piece(String text, Style style, int color, boolean code, boolean taskRef) {
	}

	/**
	 * Word-wrap inline markdown at x = {@code indent}..width, starting on the current line; advances
	 * {@link #y} past the last line.
	 */
	private void inline(String md, int indent, int baseColor, Style base, int firstIndent) {
		List<Piece> pieces = new ArrayList<>();
		for (Span s : Markdown.inline(md)) {
			Style st = base;
			int color = baseColor;
			boolean code = (s.style() & Markdown.CODE_SPAN) != 0;
			if ((s.style() & Markdown.BOLD) != 0) {
				st = st.withBold(true);
			}
			if ((s.style() & Markdown.ITALIC) != 0) {
				st = st.withItalic(true);
			}
			if ((s.style() & Markdown.STRIKE) != 0) {
				st = st.withStrikethrough(true);
				color = ReviewKit.muted();
			}
			if ((s.style() & Markdown.LINK) != 0) {
				st = st.withUnderlined(true);
				color = UiStyle.color("paper.link");
			}
			if (code) {
				color = UiStyle.color("paper.path");
				pieces.add(new Piece(s.text(), st, color, true, false));
				continue;
			}
			// split into words and runs of spaces
			String t = s.text();
			int i = 0;
			while (i < t.length()) {
				int j = i;
				boolean space = t.charAt(i) == ' ';
				while (j < t.length() && (t.charAt(j) == ' ') == space) {
					j++;
				}
				String w = t.substring(i, j);
				boolean ref = !space && !taskIds.isEmpty() && TASK_REF.matcher(stripPunct(w)).matches() && taskIds.contains(stripPunct(w));
				pieces.add(new Piece(space ? " " : w, st, color, false, ref));
				i = j;
			}
		}
		int x = indent + firstIndent;
		int lineY = y;
		StringBuilder run = new StringBuilder();
		Style runStyle = null;
		int runColor = 0;
		int runX = x;
		boolean pendingSpace = false;
		for (Piece p : pieces) {
			boolean isSpace = !p.code() && p.text().equals(" ");
			if (isSpace) {
				pendingSpace = x > indent;
				continue;
			}
			int spaceW = pendingSpace ? width(" ", p.style()) : 0;
			int w = width(p.text(), p.style()) + (p.code() ? 4 : 0) + (p.taskRef() ? 9 : 0);
			// punctuation glued to the previous word ("`code`,") may hang a few px into the margin
			boolean glued = !pendingSpace && !p.code() && p.text().length() <= 2 && w <= 8 && !Character.isLetterOrDigit(p.text().charAt(0));
			if (x + spaceW + w > width && x > indent && !(glued && x + w <= width + 8)) {
				flushRun(run, runStyle, runColor, runX, lineY);
				lineY += LINE_H;
				x = indent;
				runX = x;
				runStyle = null;
				spaceW = 0;
				pendingSpace = false;
			}
			if (p.code() || p.taskRef() || runStyle == null || !runStyle.equals(p.style()) || runColor != p.color()) {
				if (pendingSpace && runStyle != null && !p.code()) {
					run.append(' ');
				}
				flushRun(run, runStyle, runColor, runX, lineY);
				x += spaceW;
				runX = x;
				runStyle = p.style();
				runColor = p.color();
			} else if (pendingSpace) {
				run.append(' ');
				x += spaceW;
			}
			pendingSpace = false;
			String text = p.text();
			if (p.code()) {
				// a code span: tinted chip, broken by characters if wider than a line
				while (!text.isEmpty()) {
					int avail = width - x - 4;
					String part = font.width(text) <= avail ? text : font.plainSubstrByWidth(text, Math.max(6, avail));
					if (part.isEmpty()) {
						part = text.substring(0, 1);
					}
					int pw = font.width(part);
					fill(x, lineY, pw + 4, 10, ReviewKit.mix(UiStyle.color("palette.ui.panel_hi"), UiStyle.color("palette.ui.inset"), 0.85f));
					text(x + 2, lineY + 1, Component.literal(part).withStyle(p.style()), p.color());
					x += pw + 4;
					text = text.substring(part.length());
					if (!text.isEmpty()) {
						lineY += LINE_H;
						x = indent;
					}
				}
				runStyle = null;
				runX = x;
				continue;
			}
			if (w > width - indent) {
				// a single word wider than the line: break it by characters
				while (!text.isEmpty()) {
					String part = font.plainSubstrByWidth(text, Math.max(6, width - x));
					if (part.isEmpty()) {
						part = text.substring(0, 1);
					}
					run.append(part);
					x += font.width(part);
					text = text.substring(part.length());
					if (!text.isEmpty()) {
						flushRun(run, runStyle, runColor, runX, lineY);
						lineY += LINE_H;
						x = indent;
						runX = x;
					}
				}
				continue;
			}
			run.append(text);
			x += width(text, p.style());
			if (p.taskRef()) {
				flushRun(run, runStyle, runColor, runX, lineY);
				Op d = new Op();
				d.kind = OpKind.TASKDOT;
				d.x = x + 2;
				d.y = lineY + 1;
				d.w = 7;
				d.h = 7;
				d.taskId = stripPunct(text);
				ops.add(d);
				x += 9;
				runStyle = null;
				runX = x;
			}
		}
		flushRun(run, runStyle, runColor, runX, lineY);
		y = lineY + LINE_H;
	}

	private static String stripPunct(String w) {
		int a = 0;
		int b = w.length();
		while (a < b && !Character.isLetterOrDigit(w.charAt(a))) {
			a++;
		}
		while (b > a && !Character.isLetterOrDigit(w.charAt(b - 1))) {
			b--;
		}
		return w.substring(a, b);
	}

	private void flushRun(StringBuilder run, @Nullable Style style, int color, int x, int lineY) {
		if (run.isEmpty() || style == null) {
			run.setLength(0);
			return;
		}
		String s = run.toString().stripTrailing();
		if (!s.isEmpty()) {
			text(x, lineY + 1, Component.literal(s).withStyle(style), color);
		}
		run.setLength(0);
	}

	private int width(String s, Style st) {
		return st.equals(Style.EMPTY) ? font.width(s) : font.width(Component.literal(s).withStyle(st));
	}

	// ------------------------------------------------------------------ blocks

	private void code(Block b) {
		SyntaxTint.Lang lang = b.lang.isEmpty() ? SyntaxTint.Lang.NONE : SyntaxTint.forPath("x." + b.lang);
		int pad = 5;
		int top = y;
		Op bg = fill(0, top, width, 0, ReviewKit.mix(UiStyle.color("palette.ui.panel_hi"), UiStyle.color("palette.ui.inset"), 0.85f));
		Op bar = fill(0, top, 2, 0, UiStyle.color("palette.ui.edge"));
		y += 4;
		int avail = width - 2 * pad - 2;
		for (String raw : b.lines) {
			String line = raw;
			int[] spans = SyntaxTint.spans(lang, line);
			int lead = 0;
			while (lead < line.length() && line.charAt(lead) == ' ') {
				lead++;
			}
			// wrapped rows hang under the code's own indent (+ 2 spaces), breaking after a space when possible
			int hang = Math.min(font.width(line.substring(0, lead)) + 8, avail / 2);
			int start = 0;
			boolean first = true;
			do {
				int rowAvail = first ? avail : avail - hang;
				String rest = line.substring(start);
				int end;
				if (font.width(rest) <= rowAvail) {
					end = line.length();
				} else {
					int fit = Math.max(1, font.plainSubstrByWidth(rest, rowAvail).length());
					end = start + fit;
					for (int k = end; k > start + fit * 0.55; k--) {
						char ch = line.charAt(k - 1);
						if (ch == ' ' || ch == ',' || ch == ';' || ch == '(' || ch == '{' || ch == '.') {
							end = k;
							break;
						}
					}
				}
				int s0 = start;
				if (!first) {
					while (s0 < end && line.charAt(s0) == ' ') {
						s0++;
					}
				}
				codeRun(line, spans, s0, end, pad + 2 + (first ? 0 : hang), y + 1);
				y += 10;
				start = end;
				first = false;
			} while (start < line.length());
		}
		y += 3;
		bg.h = y - top;
		bar.h = y - top;
	}

	private void codeRun(String s, int[] sp, int start, int end, int x, int ly) {
		int pos = start;
		int cx = x;
		int ink = ReviewKit.ink();
		for (int i = 0; i + 2 < sp.length && pos < end; i += 3) {
			int a = sp[i];
			int b = sp[i + 1];
			if (b <= pos) {
				continue;
			}
			if (a >= end) {
				break;
			}
			if (a > pos) {
				String plain = s.substring(pos, a);
				text(cx, ly, Component.literal(plain), ink);
				cx += font.width(plain);
				pos = a;
			}
			int e = Math.min(b, end);
			String tok = s.substring(pos, e);
			text(cx, ly, Component.literal(tok), SyntaxTint.color(sp[i + 2]));
			cx += font.width(tok);
			pos = e;
		}
		if (pos < end) {
			text(cx, ly, Component.literal(s.substring(pos, end)), ink);
		}
	}

	private void table(Block b) {
		int cols = 0;
		for (List<String> r : b.rows) {
			cols = Math.max(cols, r.size());
		}
		if (cols == 0) {
			return;
		}
		int[] w = new int[cols];
		for (int ri = 0; ri < b.rows.size(); ri++) {
			List<String> r = b.rows.get(ri);
			for (int c = 0; c < r.size(); c++) {
				String p = Markdown.plain(r.get(c));
				int cw = ri == 0 ? ReviewKit.boldWidth(font, p) : font.width(p);
				w[c] = Math.max(w[c], cw + 10);
			}
		}
		int total = 0;
		for (int c : w) {
			total += c;
		}
		if (total > width) {
			// shrink the widest columns first, keep at least 30 px each
			while (total > width) {
				int wi = 0;
				for (int c = 1; c < cols; c++) {
					if (w[c] > w[wi]) {
						wi = c;
					}
				}
				if (w[wi] <= 30) {
					break;
				}
				w[wi]--;
				total--;
			}
		}
		int edge = UiStyle.color("palette.ui.panel_edge");
		for (int ri = 0; ri < b.rows.size(); ri++) {
			List<String> r = b.rows.get(ri);
			int x = 0;
			if (ri == 0) {
				fill(0, y, Math.min(width, total), 12, UiStyle.color("palette.ui.panel_shade"));
			}
			for (int c = 0; c < cols; c++) {
				String cell = c < r.size() ? Markdown.plain(r.get(c)) : "";
				String e = TextUtil.ellipsize(font, cell, w[c] - 8);
				Component comp = ri == 0 ? Component.literal(e).withStyle(Style.EMPTY.withBold(true)) : Component.literal(e);
				text(x + 4, y + 2, comp, ri == 0 ? ReviewKit.ink() : ReviewKit.ink());
				x += w[c];
			}
			y += 12;
			fill(0, y - 1, Math.min(width, total), 1, ri == 0 ? UiStyle.color("palette.ui.edge") : edge);
		}
	}

	// ------------------------------------------------------------------ ops

	private Op text(int x, int ty, Component c, int color) {
		Op o = new Op();
		o.kind = OpKind.TEXT;
		o.x = x;
		o.y = ty;
		o.h = 9;
		o.text = c;
		o.color = color;
		ops.add(o);
		return o;
	}

	private Op fill(int x, int fy, int w, int h, int color) {
		Op o = new Op();
		o.kind = OpKind.FILL;
		o.x = x;
		o.y = fy;
		o.w = w;
		o.h = h;
		o.color = color;
		ops.add(o);
		return o;
	}

	private Op sprite(Identifier id, int x, int sy, int w, int h) {
		Op o = new Op();
		o.kind = OpKind.SPRITE;
		o.sprite = id;
		o.x = x;
		o.y = sy;
		o.w = w;
		o.h = h;
		ops.add(o);
		return o;
	}

	/** Task status dot family for a task id (live). */
	interface TaskStatus {
		@Nullable String family(String taskId);
	}

	/** Draw the ops whose rows intersect [top, top + viewH) at screen (ox, oy - top). */
	void draw(GuiGraphicsExtractor g, Font font, int ox, int oy, int top, int viewH, TaskStatus status) {
		for (Op o : ops) {
			int h = Math.max(o.h, 10);
			if (o.y + h < top || o.y > top + viewH) {
				continue;
			}
			int x = ox + o.x;
			int yy = oy + o.y - top;
			switch (o.kind) {
				case TEXT -> g.text(font, o.text, x, yy, o.color, false);
				case FILL -> g.fill(x, yy, x + o.w, yy + o.h, o.color);
				case SPRITE -> dev.agentcraft.client.ui.Panels.sprite(g, o.sprite, x, yy, o.w, o.h);
				case TASKDOT -> {
					String fam = status.family(o.taskId);
					if ("cancelled".equals(fam)) {
						// cancelled: a faded idle dot (kept for history, like the task wall hides it)
						g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, Kit.dot("idle", false), x, yy, 7, 7, 0x60FFFFFF);
					} else if (fam != null) {
						g.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, Kit.dot(fam, false), x, yy, 7, 7);
					}
				}
				default -> {
				}
			}
		}
	}
}
