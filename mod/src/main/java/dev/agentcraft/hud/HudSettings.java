package dev.agentcraft.hud;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * The in-game overlay's client-side settings, {@code <gameDir>/agentcraft/hud.json} (no Foreman config): the style,
 * where it sits, its size, peek-on-change, auto-hide when idle and hide in combat. Pure and tolerant: an unknown or
 * malformed value keeps its default, a broken file never breaks the HUD. Changed with the console's {@code /hud},
 * the chat command {@code /achud} ({@link HudCommand}), the HUD style key (Options > Controls, unbound by default)
 * or {@code dev.hud.set}.
 *
 * <pre>
 * { "version": 1, "style": "panel", "position": "top_center", "size": "m", "peek": true, "autoHide": false,
 *   "hideInCombat": false }
 * </pre>
 *
 * <p>The defaults are the HUD as it always was: the goal panel at the top centre, no auto-hide.
 */
public final class HudSettings {
	public static final String FILE = "hud.json";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

	/** What the overlay looks like. */
	public enum Style {
		/** Nothing (toasts and the decisions key still work). */
		OFF("Off"),
		/** One compact ink line: mini progress bar, %, the open task counts; clay when a decision waits. */
		PILL("Pill"),
		/** The pill's look, a bit taller: goal title and %, a bar, who is working, the next decision. */
		PILL_PLUS("Pill+"),
		/** The full goal panel with the decisions badge (the original HUD). */
		PANEL("Panel");

		private final String label;

		Style(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

		public String wire() {
			return name().toLowerCase(Locale.ROOT);
		}

		public Style next() {
			return values()[(ordinal() + 1) % values().length];
		}
	}

	/** Where the overlay sits. */
	public enum Position {
		TOP_CENTER("Top centre"), TOP_RIGHT("Top right"), TOP_LEFT("Top left"), BOTTOM_LEFT("Bottom left"), BOTTOM_RIGHT("Bottom right"),
		RIGHT_MIDDLE("Right middle");

		private final String label;

		Position(String label) {
			this.label = label;
		}

		public String label() {
			return label;
		}

		public String wire() {
			return name().toLowerCase(Locale.ROOT);
		}

		public boolean left() {
			return this == TOP_LEFT || this == BOTTOM_LEFT;
		}

		public boolean bottom() {
			return this == BOTTOM_LEFT || this == BOTTOM_RIGHT;
		}

		public boolean center() {
			return this == TOP_CENTER;
		}
	}

	/** One step smaller / larger than the GUI scale ({@link HudLayout#scale}). */
	public enum Size {
		S, M, L;

		public String wire() {
			return name().toLowerCase(Locale.ROOT);
		}

		public String label() {
			return name();
		}
	}

	private Style style = Style.PANEL;
	private Position position = Position.TOP_CENTER;
	private Size size = Size.M;
	private boolean peek = true;
	private boolean autoHide;
	private boolean hideInCombat;
	private boolean dirty;

	public Style style() {
		return style;
	}

	public Position position() {
		return position;
	}

	public Size size() {
		return size;
	}

	public boolean peek() {
		return peek;
	}

	public boolean autoHide() {
		return autoHide;
	}

	public boolean hideInCombat() {
		return hideInCombat;
	}

	public boolean dirty() {
		return dirty;
	}

	public void setStyle(Style s) {
		dirty |= s != style;
		style = s;
	}

	/** The cycle key: Off -> Pill -> Pill+ -> Panel -> Off. */
	public Style cycleStyle() {
		setStyle(style.next());
		return style;
	}

	public void setPosition(Position p) {
		dirty |= p != position;
		position = p;
	}

	public void setSize(Size s) {
		dirty |= s != size;
		size = s;
	}

	public void setPeek(boolean on) {
		dirty |= on != peek;
		peek = on;
	}

	public void setAutoHide(boolean on) {
		dirty |= on != autoHide;
		autoHide = on;
	}

	public void setHideInCombat(boolean on) {
		dirty |= on != hideInCombat;
		hideInCombat = on;
	}

	/** Copies every value of {@code o} (a QA run restores what it found). */
	public void copyFrom(HudSettings o) {
		setStyle(o.style);
		setPosition(o.position);
		setSize(o.size);
		setPeek(o.peek);
		setAutoHide(o.autoHide);
		setHideInCombat(o.hideInCombat);
	}

	// ------------------------------------------------------------------ parsing single values (also DevBridge, HudCommand)

	/** "pill_plus", "pill+", "Pill+" -> PILL_PLUS; null when unknown. */
	public static @Nullable Style parseStyle(@Nullable String s) {
		String n = norm(s);
		if (n == null) {
			return null;
		}
		if (n.equals("pill+") || n.equals("pillplus")) {
			return Style.PILL_PLUS;
		}
		return byName(Style.values(), n);
	}

	public static @Nullable Position parsePosition(@Nullable String s) {
		String n = norm(s);
		if (n == null) {
			return null;
		}
		if (n.equals("top_centre") || n.equals("top") || n.equals("center") || n.equals("centre")) {
			return Position.TOP_CENTER;
		}
		return byName(Position.values(), n);
	}

	public static @Nullable Size parseSize(@Nullable String s) {
		String n = norm(s);
		if (n == null) {
			return null;
		}
		return switch (n) {
			case "small" -> Size.S;
			case "medium" -> Size.M;
			case "large" -> Size.L;
			default -> byName(Size.values(), n);
		};
	}

	/** "on", "true", "yes", "1" / "off", "false", "no", "0"; null when neither. */
	public static @Nullable Boolean parseBool(@Nullable String s) {
		String n = norm(s);
		if (n == null) {
			return null;
		}
		return switch (n) {
			case "on", "true", "yes", "1" -> Boolean.TRUE;
			case "off", "false", "no", "0" -> Boolean.FALSE;
			default -> null;
		};
	}

	private static @Nullable String norm(@Nullable String s) {
		if (s == null || s.isBlank()) {
			return null;
		}
		return s.strip().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
	}

	private static <E extends Enum<E>> @Nullable E byName(E[] values, @Nullable String n) {
		if (n == null) {
			return null;
		}
		for (E e : values) {
			if (e.name().toLowerCase(Locale.ROOT).equals(n)) {
				return e;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------ JSON

	public JsonObject toJson() {
		JsonObject o = new JsonObject();
		o.addProperty("version", 1);
		o.addProperty("style", style.wire());
		o.addProperty("position", position.wire());
		o.addProperty("size", size.wire());
		o.addProperty("peek", peek);
		o.addProperty("autoHide", autoHide);
		o.addProperty("hideInCombat", hideInCombat);
		return o;
	}

	/** Parses {@link #toJson} output; anything missing or malformed keeps its default. */
	public static HudSettings fromJson(@Nullable JsonElement el) {
		HudSettings h = new HudSettings();
		if (el == null || !el.isJsonObject()) {
			return h;
		}
		JsonObject o = el.getAsJsonObject();
		Style st = parseStyle(str(o, "style"));
		if (st != null) {
			h.style = st;
		}
		Position p = parsePosition(str(o, "position"));
		if (p != null) {
			h.position = p;
		}
		Size sz = parseSize(str(o, "size"));
		if (sz != null) {
			h.size = sz;
		}
		h.peek = bool(o, "peek", h.peek);
		h.autoHide = bool(o, "autoHide", h.autoHide);
		h.hideInCombat = bool(o, "hideInCombat", h.hideInCombat);
		return h;
	}

	private static @Nullable String str(JsonObject o, String k) {
		JsonElement e = o.get(k);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
	}

	private static boolean bool(JsonObject o, String k, boolean def) {
		JsonElement e = o.get(k);
		return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean() ? e.getAsBoolean() : def;
	}

	/** Reads {@code file}; a missing or unreadable file gives the defaults. */
	public static HudSettings load(Path file) {
		if (!Files.isRegularFile(file)) {
			return new HudSettings();
		}
		try {
			return fromJson(JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)));
		} catch (IOException | RuntimeException e) {
			return new HudSettings();
		}
	}

	/** Writes {@code file} atomically (temp file + move). */
	public void save(Path file) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, GSON.toJson(toJson()), StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		dirty = false;
	}

	/** One line for the action bar, {@code /hud} and {@code /achud}: "Panel · top centre · M · peek on". */
	public String describe() {
		return style.label() + " · " + position.label().toLowerCase(Locale.ROOT) + " · " + size.label() + " · peek " + (peek ? "on" : "off")
			+ (autoHide ? " · auto-hide" : "") + (hideInCombat ? " · hidden in combat" : "");
	}
}
