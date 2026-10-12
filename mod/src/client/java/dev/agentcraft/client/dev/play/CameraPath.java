package dev.agentcraft.client.dev.play;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.agentcraft.client.dev.DevBridge.DevException;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.mp.dev.MpDevCommands;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.StudioId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.world.level.Level;

/**
 * A camera move over time, evaluated every rendered frame of a dev.play shot (eye position, yaw, pitch, FOV).
 *
 * <p>Two kinds:
 * <ul>
 * <li>{@code keys}: keyframes {@code {t, x,y,z, yaw,pitch | lookAt:{x,y,z}, fov?}} or
 * {@code {t, anchor:"cam_room"}} (eye anchors; other anchors are feet positions + 1.62), joined by a
 * time-based (non-uniform) Catmull-Rom spline. Yaw takes the shortest arc between keys. With
 * {@code easeEnds} (default true) the camera starts and stops smoothly (zero velocity at the first
 * and last key); a key may also set {@code ease: "inOut"|"in"|"out"|"linear"} for the segment that
 * starts at it (a time remap inside that segment, e.g. to make a hold). When every key looks at a
 * point (or the path has {@code lookAt}), the look target is splined instead of yaw/pitch, so the
 * camera keeps a subject exactly centred.</li>
 * <li>{@code orbit}: {@code {center:{x,z}, radius, y, from, to (degrees, 0 = south of the centre,
 * increasing counter-clockwise seen from above like yaw), lookAt?:{x,y,z} (default the centre at
 * y), fov?, ease?:"inOut"}}: an exact circle (or arc) at constant height.</li>
 * </ul>
 */
final class CameraPath {
	static final double EYE_HEIGHT = 1.62;

	record Pose(double x, double y, double z, double yaw, double pitch, double fov) {
	}

	private interface Eval {
		Pose at(double t);
	}

	private final Eval eval;
	final double start;
	final double end;
	final String kind;

	private CameraPath(String kind, double start, double end, Eval eval) {
		this.kind = kind;
		this.start = start;
		this.end = end;
		this.eval = eval;
	}

	/** Pose at time {@code t} seconds (clamped to the path). */
	Pose at(double t) {
		return eval.at(Math.max(start, Math.min(end, t)));
	}

	// ------------------------------------------------------------------ parsing

	static CameraPath parse(Fields f, double duration, double defaultFov) {
		String type = f.optStr("type", f.has("keys") ? "keys" : "orbit").toLowerCase(Locale.ROOT);
		return switch (type) {
			case "keys" -> parseKeys(f, duration, defaultFov);
			case "orbit" -> parseOrbit(f, duration, defaultFov);
			default -> throw new DevException("field 'camera.type' must be keys|orbit (got '" + type + "')");
		};
	}

	private static CameraPath parseOrbit(Fields f, double duration, double defaultFov) {
		Fields c = f.obj("center");
		double cx = c.num("x");
		double cz = c.num("z");
		double radius = f.num("radius", 0.5, 2000);
		double y = f.num("y", Level.MIN_ENTITY_SPAWN_Y, Level.MAX_ENTITY_SPAWN_Y - 1);
		double from = f.num("from", -36_000, 36_000);
		double to = f.num("to", -36_000, 36_000);
		double fov = f.optNum("fov", defaultFov, 30, 110);
		Fields look = f.optObj("lookAt");
		double lx = look != null ? look.num("x") : cx;
		double ly = look != null ? look.num("y") : c.optNum("y", y, -20_000_000, 20_000_000);
		double lz = look != null ? look.num("z") : cz;
		Ease ease = Ease.parse(f.optStr("ease", "inOut"), "camera.ease");
		double t0 = f.optNum("start", 0, 0, duration);
		double t1 = f.optNum("end", duration, t0, 1e6);
		return new CameraPath("orbit", t0, t1, t -> {
			double u = t1 > t0 ? ease.apply((t - t0) / (t1 - t0)) : 0;
			double a = Math.toRadians(from + (to - from) * u);
			// angle 0 = +Z (south) of the centre, like yaw 0 looking south
			double x = cx - Math.sin(a) * radius;
			double z = cz + Math.cos(a) * radius;
			double[] yp = lookAngles(x, y, z, lx, ly, lz);
			return new Pose(x, y, z, yp[0], yp[1], fov);
		});
	}

	private static final class Key {
		double t;
		double x, y, z;
		double yaw, pitch;
		double lx, ly, lz;
		boolean hasLook;
		double fov;
		Ease ease;
	}

	private static CameraPath parseKeys(Fields f, double duration, double defaultFov) {
		JsonElement raw = f.json().get("keys");
		if (raw == null || !raw.isJsonArray() || raw.getAsJsonArray().isEmpty()) {
			throw new DevException("field 'camera.keys' must be a non-empty array of keyframes");
		}
		JsonArray arr = raw.getAsJsonArray();
		Fields pathLook = f.optObj("lookAt");
		double pathFov = f.optNum("fov", defaultFov, 30, 110);
		boolean easeEnds = f.optBool("easeEnds", true);
		// MP-12: an optional studio on the camera JSON (absent or "own" = the local studio); keys resolve there.
		StudioId studio = MpDevCommands.resolveStudio(f.optStr("studio", null));
		List<Key> keys = new ArrayList<>();
		for (int i = 0; i < arr.size(); i++) {
			if (!arr.get(i).isJsonObject()) {
				throw new DevException("camera.keys[" + i + "] must be an object");
			}
			JsonObject ko = resolveAnchor(arr.get(i).getAsJsonObject(), i, studio);
			Fields k = Fields.of(ko);
			Key key = new Key();
			key.t = k.num("t", 0, 1e6);
			key.x = k.num("x");
			key.y = k.num("y", Level.MIN_ENTITY_SPAWN_Y, Level.MAX_ENTITY_SPAWN_Y - 1);
			key.z = k.num("z");
			key.fov = k.optNum("fov", pathFov, 30, 110);
			key.ease = Ease.parse(k.optStr("ease", "none"), "camera.keys[" + i + "].ease");
			Fields look = k.optObj("lookAt");
			if (look == null && pathLook != null) {
				look = pathLook;
			}
			if (look != null) {
				key.hasLook = true;
				key.lx = look.num("x");
				key.ly = look.num("y");
				key.lz = look.num("z");
				double[] yp = lookAngles(key.x, key.y, key.z, key.lx, key.ly, key.lz);
				key.yaw = yp[0];
				key.pitch = yp[1];
			} else {
				key.yaw = k.num("yaw", -1e6, 1e6);
				key.pitch = k.num("pitch", -90, 90);
			}
			if (!keys.isEmpty() && key.t <= keys.getLast().t) {
				throw new DevException("camera.keys[" + i + "].t must be greater than the previous key's t (" + keys.getLast().t + ")");
			}
			keys.add(key);
		}
		boolean lookMode = keys.stream().allMatch(k -> k.hasLook);
		// shortest-arc yaw: unwrap each key relative to the previous one
		for (int i = 1; i < keys.size(); i++) {
			double prev = keys.get(i - 1).yaw;
			double d = ((keys.get(i).yaw - prev) % 360 + 540) % 360 - 180;
			keys.get(i).yaw = prev + d;
		}
		int n = keys.size();
		double[] ts = new double[n];
		double[][] ch = new double[n][];
		for (int i = 0; i < n; i++) {
			Key k = keys.get(i);
			ts[i] = k.t;
			ch[i] = lookMode ? new double[] {k.x, k.y, k.z, k.lx, k.ly, k.lz, k.fov} : new double[] {k.x, k.y, k.z, k.yaw, k.pitch, k.fov};
		}
		Spline spline = new Spline(ts, ch, easeEnds);
		double t0 = ts[0];
		double t1 = ts[n - 1];
		final boolean looking = lookMode;
		return new CameraPath("keys", t0, t1, t -> {
			int seg = spline.segment(t);
			double u = spline.localU(seg, t);
			Ease e = keys.get(seg).ease;
			if (e != Ease.NONE) {
				u = e.apply(u);
			}
			double[] v = spline.eval(seg, u);
			if (looking) {
				double[] yp = lookAngles(v[0], v[1], v[2], v[3], v[4], v[5]);
				return new Pose(v[0], v[1], v[2], yp[0], yp[1], v[6]);
			}
			return new Pose(v[0], v[1], v[2], v[3], Math.max(-90, Math.min(90, v[4])), v[5]);
		});
	}

	/** {@code anchor:"name"} fills x/y/z/yaw/pitch (explicit fields win); non-camera anchors are feet positions. */
	private static JsonObject resolveAnchor(JsonObject key, int index, StudioId studio) {
		Fields f = Fields.of(key);
		if (!f.has("anchor")) {
			return key;
		}
		String name = f.nonBlank("anchor");
		Anchor a = Anchors.forStudio(studio).get(name);
		if (a == null) {
			throw new DevException("camera.keys[" + index + "]: unknown anchor '" + name + "' (see dev.anchors)");
		}
		JsonObject out = key.deepCopy();
		out.remove("anchor");
		double eyeY = name.startsWith(AnchorNames.CAM_PREFIX) ? a.y() : a.y() + EYE_HEIGHT;
		if (!out.has("x")) {
			out.addProperty("x", a.x());
		}
		if (!out.has("y")) {
			out.addProperty("y", eyeY);
		}
		if (!out.has("z")) {
			out.addProperty("z", a.z());
		}
		if (!out.has("lookAt")) {
			if (!out.has("yaw")) {
				out.addProperty("yaw", a.yaw());
			}
			if (!out.has("pitch")) {
				out.addProperty("pitch", a.pitch());
			}
		}
		return out;
	}

	/** Minecraft yaw/pitch (yaw 0 = +Z, pitch positive = down) from an eye looking at a point. */
	static double[] lookAngles(double x, double y, double z, double lx, double ly, double lz) {
		double dx = lx - x;
		double dy = ly - y;
		double dz = lz - z;
		double h = Math.sqrt(dx * dx + dz * dz);
		if (h < 1e-9 && Math.abs(dy) < 1e-9) {
			throw new DevException("camera looks at its own position");
		}
		return new double[] {Math.toDegrees(Math.atan2(-dx, dz)), -Math.toDegrees(Math.atan2(dy, h))};
	}

	// ------------------------------------------------------------------ easing

	enum Ease {
		NONE, LINEAR, IN, OUT, IN_OUT;

		static Ease parse(String s, String field) {
			return switch (s.toLowerCase(Locale.ROOT).replace("-", "").replace("_", "")) {
				case "none" -> NONE;
				case "linear" -> LINEAR;
				case "in" -> IN;
				case "out" -> OUT;
				case "inout", "smooth" -> IN_OUT;
				default -> throw new DevException("field '" + field + "' must be none|linear|in|out|inOut (got '" + s + "')");
			};
		}

		double apply(double u) {
			u = Math.max(0, Math.min(1, u));
			return switch (this) {
				case NONE, LINEAR -> u;
				case IN -> u * u * u;
				case OUT -> 1 - Math.pow(1 - u, 3);
				// smootherstep: zero velocity and acceleration at both ends
				case IN_OUT -> u * u * u * (u * (u * 6 - 15) + 10);
			};
		}
	}

	// ------------------------------------------------------------------ spline

	/**
	 * Cubic Hermite spline through (t_i, v_i) with Catmull-Rom tangents for non-uniform times:
	 * m_i = (v_{i+1} - v_{i-1}) / (t_{i+1} - t_{i-1}) per second. End tangents are zero with
	 * {@code easeEnds}, else one-sided differences.
	 */
	static final class Spline {
		private final double[] t;
		private final double[][] v;
		private final double[][] m;

		Spline(double[] t, double[][] v, boolean easeEnds) {
			this.t = t;
			this.v = v;
			int n = t.length;
			int dims = v[0].length;
			m = new double[n][dims];
			for (int i = 0; i < n; i++) {
				for (int d = 0; d < dims; d++) {
					if (n == 1) {
						m[i][d] = 0;
					} else if (i == 0) {
						m[i][d] = easeEnds ? 0 : (v[1][d] - v[0][d]) / (t[1] - t[0]);
					} else if (i == n - 1) {
						m[i][d] = easeEnds ? 0 : (v[n - 1][d] - v[n - 2][d]) / (t[n - 1] - t[n - 2]);
					} else {
						m[i][d] = (v[i + 1][d] - v[i - 1][d]) / (t[i + 1] - t[i - 1]);
					}
				}
			}
		}

		int segment(double time) {
			int n = t.length;
			if (n == 1 || time <= t[0]) {
				return 0;
			}
			for (int i = 0; i < n - 1; i++) {
				if (time < t[i + 1]) {
					return i;
				}
			}
			return n - 2;
		}

		double localU(int seg, double time) {
			if (t.length == 1) {
				return 0;
			}
			double span = t[seg + 1] - t[seg];
			return Math.max(0, Math.min(1, (time - t[seg]) / span));
		}

		double[] eval(int seg, double u) {
			int dims = v[0].length;
			double[] out = new double[dims];
			if (t.length == 1) {
				System.arraycopy(v[0], 0, out, 0, dims);
				return out;
			}
			double span = t[seg + 1] - t[seg];
			double u2 = u * u;
			double u3 = u2 * u;
			double h00 = 2 * u3 - 3 * u2 + 1;
			double h10 = u3 - 2 * u2 + u;
			double h01 = -2 * u3 + 3 * u2;
			double h11 = u3 - u2;
			for (int d = 0; d < dims; d++) {
				out[d] = h00 * v[seg][d] + h10 * span * m[seg][d] + h01 * v[seg + 1][d] + h11 * span * m[seg + 1][d];
			}
			return out;
		}
	}
}
