package dev.agentcraft.client.ui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.Cast;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Colour and metric tokens of the Warm Studio UI, read from the synced art files
 * {@code assets/agentcraft/gui/ui-style.json} (monitor/paper/status/agents/ink_ui/metrics, see
 * assets-src/ui-style.md) and {@code assets/agentcraft/palette.json} ({@code colors.*}, {@code ui.*}).
 * Never hard-code a colour in a screen or renderer: ask here.
 *
 * <pre>
 * UiStyle.color("paper.text")       // 0xFF1F1E1D (ARGB, opaque)
 * UiStyle.color("monitor.diff_add")
 * UiStyle.status("working")         // status palette by family: idle thinking working waiting error done
 * UiStyle.agentOnDark("kit")        // nameplate/HUD name colour; agentOnLight for paper GUIs
 * UiStyle.metric("metrics.gui_panel_padding", 8)
 * </pre>
 *
 * <p>Dark theme (the default; {@code AGENTCRAFT_THEME=light} or {@code /theme light} for paper):
 * {@link #color} answers the {@code gui/ui-style-dark.json} value of a token when it has one, paper
 * sprites are tinted dark ({@link #paperTint}) and {@link #agentOnLight} gives the on-dark colour.
 * Light text drawn on clay buttons or ink tooltips asks {@link #base}, which ignores the theme.
 */
public final class UiStyle {
	public static final int CREAM = 0xFFF4EFE6;
	public static final int PAPER = 0xFFE9E1D3;
	public static final int CLAY = 0xFFD97757;
	public static final int CLAY_DARK = 0xFFB4553A;
	public static final int WALNUT = 0xFF3B2A20;
	public static final int BRASS = 0xFFC9A227;
	public static final int SAGE = 0xFF8FA98B;
	public static final int INK = 0xFF1F1E1D;
	public static final int TEAL = 0xFF2FA3A0;

	private static final Map<String, Integer> COLORS = new HashMap<>();
	private static final Map<String, Double> NUMBERS = new HashMap<>();
	private static final Map<String, Integer> DARK = new HashMap<>();
	private static volatile boolean dark = !"light".equalsIgnoreCase(System.getenv("AGENTCRAFT_THEME"));

	static {
		load("/assets/agentcraft/gui/ui-style.json", "", COLORS);
		load("/assets/agentcraft/palette.json", "palette.", COLORS);
		load("/assets/agentcraft/gui/ui-style-dark.json", "", DARK);
	}

	/** The dark theme is on (screens and HUD; the in-world monitors have their own look). */
	public static boolean dark() {
		return dark;
	}

	public static void setDark(boolean on) {
		dark = on;
	}

	private UiStyle() {
	}

	/** Opaque ARGB colour for a token path, e.g. "paper.text", "palette.colors.clay", "palette.ui.panel". */
	public static int color(String path) {
		Integer c = dark ? DARK.getOrDefault(path, COLORS.get(path)) : COLORS.get(path);
		if (c == null) {
			AgentCraft.LOGGER.debug("UiStyle: unknown colour token {}", path);
			return 0xFFFF00FF;
		}
		return c;
	}

	public static int color(String path, int fallback) {
		Integer c = dark ? DARK.getOrDefault(path, COLORS.get(path)) : COLORS.get(path);
		return c == null ? fallback : c;
	}

	/** {@link #base(String, int)} for a token that must exist (magenta when it does not). */
	public static int base(String path) {
		return base(path, 0xFFFF00FF);
	}

	/** The token's own (light theme) colour whatever the theme: light text on clay or ink surfaces. */
	public static int base(String path, int fallback) {
		Integer c = COLORS.get(path);
		return c == null ? fallback : c;
	}

	/**
	 * Tint for a paper sprite (panels, insets, fields, pills, plain buttons, task cards) in the dark
	 * theme, or -1 (light theme, or a sprite that keeps its colours: clay buttons, dots, icons...).
	 */
	public static int paperTint(net.minecraft.resources.Identifier sprite) {
		if (!dark || !Kit.isPaper(sprite)) {
			return -1;
		}
		return Kit.isControl(sprite) ? DARK.getOrDefault("paper_tint.control", 0xFF3A342E) : DARK.getOrDefault("paper_tint.panel", 0xFF2E2925);
	}

	/** Per-channel product of two ARGB colours (a tint applied on top of another). */
	public static int multiply(int a, int b) {
		int r = 0;
		for (int shift = 0; shift <= 24; shift += 8) {
			r |= ((((a >>> shift) & 0xFF) * ((b >>> shift) & 0xFF)) / 255) << shift;
		}
		return r;
	}

	/** Status colour by family (idle, thinking, working, waiting, error, done). */
	public static int status(String family) {
		return color("status." + family, 0xFF9C9488);
	}

	/** Agent name colour on dark surfaces (nameplates, HUD, console). Unknown agent: cream. */
	public static int agentOnDark(String agentId) {
		Integer c = COLORS.get("agents." + agentId + ".text_on_dark");
		if (c != null) {
			return c;
		}
		Cast.Member m = Cast.get(agentId);
		return m != null ? 0xFF000000 | m.textOnDark() : CREAM;
	}

	/** Agent name colour on paper GUIs. Unknown agent: ink. */
	public static int agentOnLight(String agentId) {
		return dark ? agentOnDark(agentId) : agentOnPaper(agentId);
	}

	/** Agent name colour on a paper surface whatever the theme (paper monitors and boards in the world). */
	public static int agentOnPaper(String agentId) {
		Integer c = COLORS.get("agents." + agentId + ".text_on_light");
		if (c != null) {
			return c;
		}
		Cast.Member m = Cast.get(agentId);
		return m != null ? 0xFF000000 | m.textOnLight() : INK;
	}

	public static int metric(String path, int fallback) {
		Double d = NUMBERS.get(path);
		return d == null ? fallback : (int) Math.round(d);
	}

	/** {@code argb} with its alpha replaced (0..255). */
	public static int withAlpha(int argb, int alpha) {
		return (Math.max(0, Math.min(255, alpha)) << 24) | (argb & 0xFFFFFF);
	}

	private static void load(String resource, String prefix, Map<String, Integer> colors) {
		try (InputStream in = UiStyle.class.getResourceAsStream(resource)) {
			if (in == null) {
				AgentCraft.LOGGER.warn("UiStyle: {} missing (run assets-src/sync.py)", resource);
				return;
			}
			JsonObject root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			walk(root, prefix.isEmpty() ? "" : prefix.substring(0, prefix.length() - 1), colors);
		} catch (Exception e) {
			AgentCraft.LOGGER.warn("UiStyle: could not read {}", resource, e);
		}
	}

	private static void walk(JsonElement el, String path, Map<String, Integer> colors) {
		if (el.isJsonObject()) {
			for (var e : el.getAsJsonObject().entrySet()) {
				walk(e.getValue(), path.isEmpty() ? e.getKey() : path + "." + e.getKey(), colors);
			}
		} else if (el.isJsonPrimitive()) {
			var p = el.getAsJsonPrimitive();
			if (p.isString()) {
				int c = Cast.parseColor(p.getAsString(), -1);
				if (c != -1 && p.getAsString().startsWith("#")) {
					colors.put(path, 0xFF000000 | c);
				}
			} else if (p.isNumber()) {
				NUMBERS.put(path, p.getAsDouble());
			}
		}
	}
}
