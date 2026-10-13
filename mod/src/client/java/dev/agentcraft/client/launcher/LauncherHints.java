package dev.agentcraft.client.launcher;

import dev.agentcraft.launcher.LauncherPlan.State;

/**
 * What the HUD's connection pill says while the mod is not linked to a Foreman, from the launcher's state (mod/DEV.md
 * "Foreman launcher"). Pure text, kept apart from the drawing so it can be read in one place.
 */
public final class LauncherHints {
	private LauncherHints() {
	}

	/**
	 * Whether the pill shows the launcher's state rather than "Reconnecting to the Foreman" when a link that was up is
	 * down: the launcher knows more (ours crashed, it is starting one, it was stopped).
	 */
	public static boolean overridesReconnect(State s) {
		return switch (s) {
			case CRASHED, STOPPED, STARTING, INSTALLING, NODE_MISSING, NO_SOURCE, BLOCKED -> true;
			default -> false;
		};
	}

	/** The pill's title. */
	public static String title(State s) {
		return switch (s) {
			case CHECKING -> "Looking for the Foreman…";
			case INSTALLING -> "Installing the Foreman's packages…";
			case STARTING -> "Starting the Foreman…";
			case RUNNING, RUNNING_OLDER -> "Connecting to the Foreman…";
			case CRASHED -> "The Foreman stopped";
			case NODE_MISSING -> "Foreman needs Node.js 22+";
			case NO_SOURCE, BLOCKED -> "Foreman could not start";
			case STOPPED -> "Foreman stopped";
			default -> "Foreman not running";
		};
	}

	/**
	 * The pill's second line: how to proceed in this kind of run. {@code consoleKey} is the console's key label (the
	 * console's {@code /foreman} shows the details and has start / restart / stop).
	 */
	public static String detail(State s, boolean devRun, String consoleKey) {
		String console = consoleKey + " then /foreman";
		return switch (s) {
			case CHECKING, RUNNING -> "a moment";
			case RUNNING_OLDER -> "running (older version): " + console;
			case INSTALLING -> "first use: npm ci in foreman/ (a minute or two)";
			case STARTING -> "a few seconds";
			case CRASHED, NO_SOURCE, BLOCKED -> "details: " + console;
			case NODE_MISSING -> "install it (nodejs.org), then " + console + " start";
			case STOPPED -> console + " start brings it back";
			case DISABLED -> devRun ? "start it: cd foreman; npm run start (or " + console + " start)" : "the game's launcher is off: " + console + " start";
			default -> devRun ? "start it: cd foreman; npm run start (or " + console + " start)" : console;
		};
	}

	/** The auth banner's fallback text when the Foreman sends no message of its own. */
	public static String authFailed(boolean ours) {
		return ours ? "run `claude` and /login, then /foreman restart in the console" : "run `claude` and /login, then restart the Foreman";
	}
}
