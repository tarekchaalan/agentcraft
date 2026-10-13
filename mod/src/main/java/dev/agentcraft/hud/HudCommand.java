package dev.agentcraft.hud;

import dev.agentcraft.hud.HudSettings.Position;
import dev.agentcraft.hud.HudSettings.Size;
import dev.agentcraft.hud.HudSettings.Style;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The words of the HUD command (pure), shared by the console's {@code /hud} and the chat command {@code /achud}:
 *
 * <pre>
 * /hud                                  show the settings
 * /hud off|pill|pill+|panel             the style
 * /hud top_center|top_right|top_left|bottom_left|bottom_right|right_middle   the position
 * /hud s|m|l                            the size
 * /hud style|position|size &lt;value&gt;      the same, spelled out
 * /hud peek|autohide|combat on|off      peek on change, auto-hide when idle, hide in combat
 * /hud cycle                            next style (Off, Pill, Pill+, Panel)
 * /hud reset                            the defaults
 * </pre>
 *
 * Several can go in one line ({@code /hud pill bottom_left s}); the last one of a kind wins.
 */
public final class HudCommand {
	private HudCommand() {
	}

	/** What to change ({@code null} = leave as is); nothing at all = show the settings. */
	public record Change(@Nullable Style style, @Nullable Position position, @Nullable Size size, @Nullable Boolean peek, @Nullable Boolean autoHide,
		@Nullable Boolean hideInCombat, boolean cycle, boolean reset) {
		public static final Change NONE = new Change(null, null, null, null, null, null, false, false);

		/** Only shows the settings. */
		public boolean showOnly() {
			return equals(NONE);
		}
	}

	/** A parsed command: the change, or why it could not be parsed. */
	public record Parsed(@Nullable Change change, @Nullable String error) {
		public boolean ok() {
			return change != null;
		}
	}

	public static final String USAGE = "/hud [off|pill|pill+|panel] [top_center|top_right|top_left|bottom_left|bottom_right|right_middle] [s|m|l] "
		+ "[peek|autohide|combat on|off] [cycle] [reset]";

	/** Every first word {@code /hud} completes to. */
	public static final List<String> WORDS = List.of("off", "pill", "pill+", "panel", "top_center", "top_right", "top_left", "bottom_left",
		"bottom_right", "right_middle", "s", "m", "l", "peek", "autohide", "combat", "style", "position", "size", "cycle", "reset");

	/** Parses the words after {@code /hud}. */
	public static Parsed parse(@Nullable String args) {
		List<String> words = new ArrayList<>();
		if (args != null) {
			for (String w : args.strip().split("\\s+")) {
				if (!w.isEmpty()) {
					words.add(w);
				}
			}
		}
		Style style = null;
		Position position = null;
		Size size = null;
		Boolean peek = null;
		Boolean autoHide = null;
		Boolean combat = null;
		boolean cycle = false;
		boolean reset = false;
		for (int i = 0; i < words.size(); i++) {
			String w = words.get(i).toLowerCase(Locale.ROOT);
			String next = i + 1 < words.size() ? words.get(i + 1) : null;
			switch (w) {
				case "cycle", "next" -> cycle = true;
				case "reset", "default", "defaults" -> reset = true;
				case "style" -> {
					style = HudSettings.parseStyle(next);
					if (style == null) {
						return error("style: off, pill, pill+ or panel");
					}
					i++;
				}
				case "position", "pos", "at" -> {
					position = HudSettings.parsePosition(next);
					if (position == null) {
						return error("position: top_center, top_right, top_left, bottom_left, bottom_right or right_middle");
					}
					i++;
				}
				case "size" -> {
					size = HudSettings.parseSize(next);
					if (size == null) {
						return error("size: s, m or l");
					}
					i++;
				}
				case "peek", "autohide", "auto_hide", "auto-hide", "combat", "hideincombat" -> {
					Boolean on = HudSettings.parseBool(next);
					if (on == null) {
						return error(w + " on or " + w + " off");
					}
					switch (w) {
						case "peek" -> peek = on;
						case "combat", "hideincombat" -> combat = on;
						default -> autoHide = on;
					}
					i++;
				}
				default -> {
					// a bare value: a style, else a position, else a size
					Style st = HudSettings.parseStyle(w);
					Position p = st == null ? HudSettings.parsePosition(w) : null;
					Size sz = st == null && p == null ? HudSettings.parseSize(w) : null;
					if (st != null) {
						style = st;
					} else if (p != null) {
						position = p;
					} else if (sz != null) {
						size = sz;
					} else {
						return error("unknown '" + words.get(i) + "': " + USAGE);
					}
				}
			}
		}
		return new Parsed(new Change(style, position, size, peek, autoHide, combat, cycle, reset), null);
	}

	private static Parsed error(String e) {
		return new Parsed(null, e);
	}

	/** Applies a change: reset first, then the values, then the cycle. */
	public static void apply(HudSettings s, Change c) {
		if (c.reset()) {
			s.copyFrom(new HudSettings());
		}
		if (c.style() != null) {
			s.setStyle(c.style());
		}
		if (c.position() != null) {
			s.setPosition(c.position());
		}
		if (c.size() != null) {
			s.setSize(c.size());
		}
		if (c.peek() != null) {
			s.setPeek(c.peek());
		}
		if (c.autoHide() != null) {
			s.setAutoHide(c.autoHide());
		}
		if (c.hideInCombat() != null) {
			s.setHideInCombat(c.hideInCombat());
		}
		if (c.cycle()) {
			s.cycleStyle();
		}
	}

	/** One short line for the console's input preview: "hud: pill · bottom left". */
	public static String describe(Change c) {
		if (c.showOnly()) {
			return "show HUD settings";
		}
		List<String> parts = new ArrayList<>();
		if (c.reset()) {
			parts.add("reset");
		}
		if (c.style() != null) {
			parts.add(c.style().label());
		}
		if (c.position() != null) {
			parts.add(c.position().label().toLowerCase(Locale.ROOT));
		}
		if (c.size() != null) {
			parts.add("size " + c.size().label());
		}
		if (c.peek() != null) {
			parts.add("peek " + (c.peek() ? "on" : "off"));
		}
		if (c.autoHide() != null) {
			parts.add("auto-hide " + (c.autoHide() ? "on" : "off"));
		}
		if (c.hideInCombat() != null) {
			parts.add("hide in combat " + (c.hideInCombat() ? "on" : "off"));
		}
		if (c.cycle()) {
			parts.add("next style");
		}
		return "HUD: " + String.join(" · ", parts);
	}
}
