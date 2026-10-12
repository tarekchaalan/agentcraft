package dev.agentcraft.client.agents;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.agentcraft.Cast;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol;
import dev.agentcraft.client.foreman.Protocol.AgentSay;
import dev.agentcraft.client.ui.Kit;
import dev.agentcraft.client.ui.TextUtil;
import dev.agentcraft.client.ui.UiStyle;
import dev.agentcraft.client.ui.WorldUi;
import dev.agentcraft.mp.state.PublicEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.FormattedCharSequence;
import org.jspecify.annotations.Nullable;

/**
 * The speech bubble of one agent ({@code agent.say}): the kit {@code bubble} nine-slice with its
 * tail pointing down at the nameplate, ink text wrapped to at most three lines (then an ellipsis),
 * and the addressee first ("@Juniper", "@Alex") in that agent's paper colour, so you can tell who
 * talks to whom. It pops in, stays for a time that grows with the text (3.5-11 s), then fades out.
 * Drawn in plate space right above the plate; {@link PlateLayout} reserves its height, so the
 * plates around it move out of its way instead of being covered.
 */
public final class SpeechBubble {
	/** Max text width inside the bubble (GUI px). */
	public static final int MAX_TEXT = 148;
	public static final int MAX_LINES = 3;
	/** Gap between the plate top and the tail tip (px). */
	public static final int GAP = 1;
	public static final int TAIL_H = 5;
	private static final int IN_TICKS = 3;
	private static final int OUT_TICKS = 8;

	/** What is being said. {@code to}: agent id, "user", "all" or null. */
	public record Line(String text, @Nullable String to, long ts, boolean remote, String ownerName, Map<String, String> publicNames) {
	}

	/** Laid-out bubble (px): {@code width}/{@code height} of the body, without the tail. */
	public record Layout(List<FormattedCharSequence> lines, int[] lineWidths, int width, int height) {
	}

	private @Nullable Line line;
	private int start;
	private int end;
	private @Nullable Layout layout;

	void show(AgentSay say, int age) {
		String text = say.text().replaceAll("\\s+", " ").trim();
		if (text.isEmpty()) {
			return;
		}
		boolean showing = visible();
		show(new Line(text, say.to(), say.ts(), false, "", Map.of()), age,
			duration(text.length()), showing);
	}

	/** Remote public event: hidden text is represented by an ellipsis but keeps the sender's length timing. */
	void showRemote(PublicEvent.Say say, String ownerName, Map<String, String> publicNames, int age) {
		boolean showing = visible();
		String text;
		if (say.text() == null) {
			text = "…";
		} else {
			text = say.text().replaceAll("\\s+", " ").trim();
			if (text.isEmpty()) {
				return;
			}
		}
		show(new Line(text, say.to(), 0, true, ownerName, Map.copyOf(publicNames)), age,
			duration(say.length()), showing);
	}

	static int duration(int characters) {
		return Math.max(70, Math.min(220, 50 + (int) (characters * 1.15)));
	}

	int durationTicks() {
		return end - start;
	}

	private void show(Line next, int age, int duration, boolean showing) {
		line = next;
		layout = null;
		start = showing ? age - IN_TICKS : age;
		end = age + duration;
	}

	/** Show a say that arrived before this agent existed in the world, with the time already gone by. */
	void showLate(AgentSay say, int age, int ticksAgo) {
		show(say, age);
		start -= ticksAgo;
		end -= ticksAgo;
		if (end <= age) {
			line = null;
		}
	}

	void tick(int age) {
		if (line != null && age > end + OUT_TICKS) {
			line = null;
			layout = null;
		}
	}

	public boolean visible() {
		return line != null;
	}

	public @Nullable Line current() {
		return line;
	}

	void clear() {
		line = null;
		layout = null;
	}

	/** 0 (hidden) .. 1 (fully shown), partial-tick accurate. */
	float visibility(int age, float pt) {
		if (line == null) {
			return 0f;
		}
		float t = age + pt;
		if (t < start + IN_TICKS) {
			return Math.max(0f, (t - start) / IN_TICKS);
		}
		if (t > end) {
			return Math.max(0f, 1f - (t - end) / OUT_TICKS);
		}
		return 1f;
	}

	/** The bubble's layout (render thread), cached until the text changes. */
	@Nullable Layout layout() {
		Line l = line;
		if (l == null) {
			return null;
		}
		if (layout == null) {
			layout = build(Minecraft.getInstance().font, l);
		}
		return layout;
	}

	private static Layout build(Font font, Line l) {
		String prefix = prefix(l);
		String all = prefix.isEmpty() ? l.text() : prefix + " " + l.text();
		List<String> plain = TextUtil.wrapPlain(font, all, MAX_TEXT);
		if (plain.size() > MAX_LINES) {
			StringBuilder rest = new StringBuilder(plain.get(MAX_LINES - 1));
			for (int i = MAX_LINES; i < plain.size(); i++) {
				rest.append(' ').append(plain.get(i));
			}
			List<String> cut = new ArrayList<>(plain.subList(0, MAX_LINES - 1));
			cut.add(TextUtil.ellipsize(font, rest.toString(), MAX_TEXT));
			plain = cut;
		}
		int ink = UiStyle.color("paper.text", UiStyle.INK);
		int toColor = toColor(l.to());
		List<FormattedCharSequence> lines = new ArrayList<>(plain.size());
		int[] widths = new int[plain.size()];
		int w = 0;
		for (int i = 0; i < plain.size(); i++) {
			String s = plain.get(i);
			MutableComponent c;
			if (i == 0 && !prefix.isEmpty() && s.startsWith(prefix)) {
				c = Component.literal(prefix).withColor(toColor & 0xFFFFFF).append(Component.literal(s.substring(prefix.length())).withColor(ink & 0xFFFFFF));
			} else {
				c = Component.literal(s).withColor(ink & 0xFFFFFF);
			}
			lines.add(c.getVisualOrderText());
			widths[i] = font.width(s);
			w = Math.max(w, widths[i]);
		}
		Kit.Padding pad = Kit.padding("bubble");
		int width = Math.max(24, w + pad.left() + pad.right());
		int height = pad.top() + 9 + 10 * (lines.size() - 1) + pad.bottom();
		return new Layout(lines, widths, width, height);
	}

	/** "@Juniper" / "@Alex" / "" (said to the room). */
	static String prefix(@Nullable String to) {
		if (to == null || to.equals("all") || to.isEmpty()) {
			return "";
		}
		if (to.equals("user")) {
			var p = Minecraft.getInstance().player;
			return "@" + (p != null ? p.getGameProfile().name() : "you");
		}
		ForemanState st = Foreman.state();
		Protocol.Agent a = st == null ? null : st.agent(to);
		return "@" + (a != null ? a.name() : to);
	}

	static String prefix(Line line) {
		if (!line.remote()) {
			return prefix(line.to());
		}
		String to = line.to();
		if (to == null || to.equals("all") || to.isEmpty()) {
			return "";
		}
		if (to.equals("user")) {
			return "@" + (line.ownerName().isBlank() ? "owner" : line.ownerName());
		}
		return "@" + line.publicNames().getOrDefault(to, to);
	}

	private static int toColor(@Nullable String to) {
		if (to == null || to.equals("user") || to.equals("all")) {
			return UiStyle.color("paper.link", UiStyle.CLAY_DARK);
		}
		return Cast.get(to) != null ? UiStyle.agentOnLight(to) : UiStyle.color("paper.link", UiStyle.CLAY_DARK);
	}

	/** Total stacked height above the plate (body + tail + gap), px. */
	static int stackHeight(Layout l) {
		return l.height() + TAIL_H - 1 + GAP;
	}

	/**
	 * Draw the bubble in plate space with its tail tip {@code GAP} px above {@code plateTop}.
	 * {@code vis} 0..1 pops it in (scale) and fades it out (translucent while not fully shown).
	 */
	static void submit(PoseStack ps, SubmitNodeCollector c, Layout l, float plateTop, float vis, int light) {
		Kit.Padding pad = Kit.padding("bubble");
		float tip = plateTop - GAP;
		float y1 = tip - TAIL_H + 1; // body bottom (the tail's first row overlaps the border)
		float y0 = y1 - l.height();
		float x0 = -l.width() / 2f;
		boolean solid = vis >= 0.999f;
		int alpha = solid ? 255 : Math.max(8, (int) (vis * 255));
		int tint = (alpha << 24) | 0xFFFFFF;
		ps.pushPose();
		if (!solid) {
			float sc = 0.86f + 0.14f * vis;
			ps.translate(0, tip, 0);
			ps.scale(sc, sc, 1f);
			ps.translate(0, -tip, 0);
		}
		WorldUi.Layer layer = solid ? WorldUi.Layer.SOLID : WorldUi.Layer.BASE;
		WorldUi.submitNineSlice(ps, c, layer, Kit.BUBBLE, x0, y0, l.width(), l.height(), tint, light);
		WorldUi.submitSprite(ps, c, layer, Kit.BUBBLE_TAIL, -4.5f, y1 - 1, 9, TAIL_H, solid ? 0f : 0.05f, tint, light);
		float ty = y0 + pad.top();
		int ink = UiStyle.withAlpha(UiStyle.color("paper.text", UiStyle.INK), alpha);
		int inner = l.width() - pad.left() - pad.right();
		for (int i = 0; i < l.lines().size(); i++) {
			float tx = x0 + pad.left() + (inner - l.lineWidths()[i]) / 2f;
			WorldUi.submitText(ps, c, l.lines().get(i), tx, ty + i * 10, ink, light);
		}
		ps.popPose();
	}
}
