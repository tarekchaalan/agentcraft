package dev.agentcraft.hud;

import dev.agentcraft.hud.HudSettings.Style;
import org.jspecify.annotations.Nullable;

/**
 * Whether the in-game overlay shows, and if not why (pure); the reason is what {@code dev.hud.state hidden} reports.
 */
public final class HudVisibility {
	/** Hurt this recently counts as combat. */
	public static final long COMBAT_MS = 5_000;
	/** A hostile mob within this many blocks counts as combat. */
	public static final double HOSTILE_RANGE = 12.0;

	public static final String NO_WORLD = "no_world";
	public static final String F1 = "f1";
	public static final String OFF = "off";
	public static final String NO_DATA = "no_data";
	public static final String COMBAT = "combat";
	public static final String IDLE = "idle";
	public static final String NO_ROOM = "no_room";

	private HudVisibility() {
	}

	/** In combat: hurt in the last {@link #COMBAT_MS} ({@code lastHurtAt} 0 = never) or a hostile mob nearby. */
	public static boolean inCombat(long now, long lastHurtAt, boolean hostileNear) {
		return hostileNear || lastHurtAt > 0 && now - lastHurtAt >= 0 && now - lastHurtAt < COMBAT_MS;
	}

	/** Idle: no active goal (planning or working), nothing needs the player and no peek showing. */
	public static boolean idle(boolean activeGoal, boolean needsPlayer, boolean peeking) {
		return !activeGoal && !needsPlayer && !peeking;
	}

	/**
	 * Why the overlay is hidden, null = it shows (placement may still find no room: {@link #NO_ROOM}). In order: not in
	 * a world, F1, style Off, no Foreman data yet, combat (when hide-in-combat is on), idle (when auto-hide is on).
	 */
	public static @Nullable String hiddenReason(boolean inWorld, boolean f1, Style style, boolean hasData, boolean hideInCombat, boolean inCombat,
		boolean autoHide, boolean idle) {
		if (!inWorld) {
			return NO_WORLD;
		}
		if (f1) {
			return F1;
		}
		if (style == Style.OFF) {
			return OFF;
		}
		if (!hasData) {
			return NO_DATA;
		}
		if (hideInCombat && inCombat) {
			return COMBAT;
		}
		if (autoHide && idle) {
			return IDLE;
		}
		return null;
	}
}
