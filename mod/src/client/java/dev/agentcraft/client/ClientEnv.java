package dev.agentcraft.client;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

/**
 * Environment switches for the client. All are read from environment variables (or the
 * equivalent -D system property, e.g. -Dagentcraft.mute=0) once at startup.
 *
 * <pre>
 * AGENTCRAFT_DEV_PORT   DevBridge port (default 7879), 127.0.0.1 only
 * AGENTCRAFT_DEV        1 enables the DevBridge, 0 disables it
 * AGENTCRAFT_MUTE       1 forces master+music volume to 0 at startup; 0 keeps your volume
 * AGENTCRAFT_FOCUS      0 = the window opens WITHOUT taking focus; 1 = normal focus
 * AGENTCRAFT_AUTOWORLD  1 = create/load the AgentCraft world on startup; 0 = title screen
 * AGENTCRAFT_WORLD      which world: studio ("AgentCraft HQ", default) or survival ("AgentCraft Survival")
 * AGENTCRAFT_WORLD_SEED seed of a world created on this start (number or text); see dev.agentcraft.world.WorldProfile
 * AGENTCRAFT_SHOTS_DIR  where dev.screenshot writes PNGs (default &lt;repo&gt;/artifacts/shots)
 * </pre>
 *
 * Defaults depend on how the game runs. In a development run ({@code gradlew runClient}) the
 * DevBridge, mute, no-focus and AutoWorld are on, as the dev tools expect. A built jar installed
 * in a normal launcher (Prism, the vanilla launcher) is someone's everyday game: all four default
 * off (focus on), so the mod never opens a world, silences the game or exposes the DevBridge
 * unless asked to.
 */
public final class ClientEnv {
	private ClientEnv() {
	}

	/** True in {@code gradlew runClient}; false for a jar in a normal launcher (and outside the Fabric launcher, e.g. in unit tests). */
	public static final boolean DEV_RUN = devRun();

	private static boolean devRun() {
		try {
			return FabricLoader.getInstance().isDevelopmentEnvironment();
		} catch (Throwable t) {
			return false; // no Fabric launcher: a plain JVM (the mod's unit tests)
		}
	}

	public static final int DEV_PORT = intValue("AGENTCRAFT_DEV_PORT", 7879);
	public static final boolean DEV_BRIDGE = flag("AGENTCRAFT_DEV", DEV_RUN);
	public static final boolean MUTE = flag("AGENTCRAFT_MUTE", DEV_RUN);
	public static final boolean TAKE_FOCUS = flag("AGENTCRAFT_FOCUS", !DEV_RUN);
	public static final boolean AUTO_WORLD = flag("AGENTCRAFT_AUTOWORLD", DEV_RUN);

	/**
	 * True when this client was launched with {@code --quickPlayMultiplayer <address>}. The launch
	 * arguments come from Fabric Loader (sanitized); they are never logged, and the parser is a pure
	 * function so it can be unit-tested without a client. A launcher that hides its arguments must
	 * never break startup, so a failure returns false.
	 */
	public static boolean quickPlayMultiplayer() {
		try {
			return quickPlayMultiplayer(FabricLoader.getInstance().getLaunchArguments(true));
		} catch (Throwable t) {
			return false;
		}
	}

	/** Pure parser seam: only the exact multiplayer quick-play option with a nonblank value counts. */
	public static boolean quickPlayMultiplayer(String[] args) {
		if (args == null) {
			return false;
		}
		for (int i = 0; i < args.length; i++) {
			String arg = args[i];
			if (arg == null) {
				continue;
			}
			if (arg.equals("--quickPlayMultiplayer")) {
				return i + 1 < args.length && args[i + 1] != null && !args[i + 1].isBlank();
			}
			if (arg.startsWith("--quickPlayMultiplayer=")) {
				return !arg.substring("--quickPlayMultiplayer=".length()).isBlank();
			}
		}
		return false;
	}

	public static String raw(String envName) {
		String prop = System.getProperty(envName.toLowerCase(Locale.ROOT).replace('_', '.'));
		if (prop != null && !prop.isBlank()) {
			return prop.trim();
		}
		String env = System.getenv(envName);
		return env == null || env.isBlank() ? null : env.trim();
	}

	public static boolean flag(String envName, boolean def) {
		String v = raw(envName);
		if (v == null) {
			return def;
		}
		return switch (v.toLowerCase(Locale.ROOT)) {
			case "1", "true", "yes", "on" -> true;
			case "0", "false", "no", "off" -> false;
			default -> def;
		};
	}

	public static int intValue(String envName, int def) {
		String v = raw(envName);
		if (v == null) {
			return def;
		}
		try {
			return Integer.parseInt(v);
		} catch (NumberFormatException e) {
			return def;
		}
	}

	/**
	 * Directory screenshots are written to. In the dev layout the game dir is
	 * &lt;repo&gt;/mod/run, so the default is &lt;repo&gt;/artifacts/shots.
	 */
	public static Path shotsDir() {
		String override = raw("AGENTCRAFT_SHOTS_DIR");
		if (override != null) {
			return Path.of(override).toAbsolutePath().normalize();
		}
		Path gameDir = Minecraft.getInstance().gameDirectory.toPath().toAbsolutePath().normalize();
		Path repo = gameDir.getParent() != null ? gameDir.getParent().getParent() : null;
		if (repo != null && Files.isDirectory(repo.resolve("mod")) && Files.isDirectory(repo.resolve("docs"))) {
			return repo.resolve("artifacts").resolve("shots");
		}
		return gameDir.resolve("agentcraft-shots");
	}
}
