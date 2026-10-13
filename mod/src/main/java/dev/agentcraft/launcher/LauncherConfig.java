package dev.agentcraft.launcher;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * The Foreman launcher's settings: the {@code launcher} section of the Foreman's {@code <home>/config.json} (the file
 * the Foreman reads too; the mod only reads it), with overrides from the environment or {@code -D} properties (a Prism
 * instance carries those in its JVM arguments, so nothing edits config.json):
 *
 * <pre>
 * "launcher": {
 *   "enabled": true,          AGENTCRAFT_LAUNCHER=0|1             (-Dagentcraft.launcher)
 *   "foremanDir": "~/code/x", AGENTCRAFT_FOREMAN_DIR=PATH          (-Dagentcraft.foreman.dir)
 *   "nodePath": "/opt/...",   AGENTCRAFT_NODE=PATH                 (-Dagentcraft.node)
 *   "stopOnExit": false       AGENTCRAFT_LAUNCHER_STOP_ON_EXIT=0|1 (-Dagentcraft.launcher.stop.on.exit)
 * }
 * </pre>
 *
 * Also read: {@code repos} (a launcher-started everyday game never runs its Foreman from a repository the agents work
 * in). Never holds a secret. Pure (parsing only).
 *
 * @param problems what was wrong in the file (wrong types are ignored with a note; the defaults apply)
 */
public record LauncherConfig(boolean enabled, @Nullable String foremanDir, @Nullable String nodePath, boolean stopOnExit,
	@Nullable String foremanDirOverride, List<String> repos, List<String> problems) {

	public static final LauncherConfig DEFAULT = new LauncherConfig(true, null, null, false, null, List.of(), List.of());

	/**
	 * Parses config.json's text (null = no file) and applies the overrides ({@code env} maps an AGENTCRAFT_* name to its
	 * value, env var or -D property, null when unset).
	 */
	public static LauncherConfig parse(@Nullable String json, Function<String, @Nullable String> env) {
		List<String> problems = new ArrayList<>();
		boolean enabled = true;
		boolean stopOnExit = false;
		String foremanDir = null;
		String nodePath = null;
		List<String> repos = new ArrayList<>();
		if (json != null && !json.isBlank()) {
			JsonObject root = null;
			try {
				JsonElement e = JsonParser.parseString(json);
				if (e.isJsonObject()) {
					root = e.getAsJsonObject();
				} else {
					problems.add("config.json is not a JSON object");
				}
			} catch (RuntimeException e) {
				problems.add("config.json is not valid JSON");
			}
			if (root != null) {
				JsonElement l = root.get("launcher");
				if (l != null && !l.isJsonNull()) {
					if (!l.isJsonObject()) {
						problems.add("launcher must be an object");
					} else {
						JsonObject o = l.getAsJsonObject();
						enabled = bool(o, "enabled", true, problems);
						stopOnExit = bool(o, "stopOnExit", false, problems);
						foremanDir = str(o, "foremanDir", problems);
						nodePath = str(o, "nodePath", problems);
						for (String k : o.keySet()) {
							if (!List.of("enabled", "foremanDir", "nodePath", "stopOnExit").contains(k)) {
								problems.add("launcher." + k + ": unknown key (enabled, foremanDir, nodePath, stopOnExit)");
							}
						}
					}
				}
				JsonElement r = root.get("repos");
				if (r != null && r.isJsonArray()) {
					for (JsonElement e : r.getAsJsonArray()) {
						if (e.isJsonPrimitive()) {
							repos.add(e.getAsString());
						}
					}
				}
			}
		}
		Boolean envEnabled = flag(env.apply("AGENTCRAFT_LAUNCHER"));
		if (envEnabled != null) {
			enabled = envEnabled;
		}
		Boolean envStop = flag(env.apply("AGENTCRAFT_LAUNCHER_STOP_ON_EXIT"));
		if (envStop != null) {
			stopOnExit = envStop;
		}
		String envNode = env.apply("AGENTCRAFT_NODE");
		if (envNode != null && !envNode.isBlank()) {
			nodePath = envNode.strip();
		}
		String override = env.apply("AGENTCRAFT_FOREMAN_DIR");
		return new LauncherConfig(enabled, foremanDir, nodePath, stopOnExit, override == null || override.isBlank() ? null : override.strip(),
			List.copyOf(repos), List.copyOf(problems));
	}

	private static boolean bool(JsonObject o, String key, boolean def, List<String> problems) {
		JsonElement e = o.get(key);
		if (e == null || e.isJsonNull()) {
			return def;
		}
		if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) {
			return e.getAsBoolean();
		}
		problems.add("launcher." + key + " must be true or false");
		return def;
	}

	private static @Nullable String str(JsonObject o, String key, List<String> problems) {
		JsonElement e = o.get(key);
		if (e == null || e.isJsonNull()) {
			return null;
		}
		if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
			String s = e.getAsString().strip();
			return s.isEmpty() ? null : s;
		}
		problems.add("launcher." + key + " must be a string");
		return null;
	}

	/** 1/true/yes/on, 0/false/no/off; null when unset or unknown. */
	static @Nullable Boolean flag(@Nullable String v) {
		if (v == null) {
			return null;
		}
		return switch (v.strip().toLowerCase(Locale.ROOT)) {
			case "1", "true", "yes", "on" -> true;
			case "0", "false", "no", "off" -> false;
			default -> null;
		};
	}
}
