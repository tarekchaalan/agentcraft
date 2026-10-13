package dev.agentcraft.client.hq;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.client.agents.GridPathfinder;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.mp.dev.MpDevCommands;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.StudioId;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * {@code dev.hq.check}: measures the HQ instead of eyeballing it.
 * <ul>
 *   <li>reachability: the agents' own A* ({@link GridPathfinder}) from {@code entrance} and from
 *       {@code lounge} to every standing anchor; a spot whose block is a seat (the agents' seat
 *       contract: bottom stairs / bottom slab / a 0.3-0.7 high block at the anchor) is checked via
 *       the free neighbour an agent steps in from (its right-hand side first, then the left,
 *       behind, in front), which must be plain floor, never the top of furniture;</li>
 *   <li>routes that climb onto furniture (a route point more than 0.3 above the floor);</li>
 *   <li>spots standing on furniture that is not a seat, shared slots closer than 1.6;</li>
 *   <li>light: block light at feet height over every walkable interior cell (min / mean, and the
 *       cells darker than {@code minLight}, default 7).</li>
 * </ul>
 */
final class HqCheck {
	/** Anchors that are points on blocks or cameras, not places an agent stands. */
	private static final Set<String> NOT_SPOTS = Set.of(AnchorNames.TASK_WALL, AnchorNames.DECISION_PODIUM, AnchorNames.GOAL_ATRIUM);

	private HqCheck() {
	}

	static void register() {
		DevBridge.register("dev.hq.check", 30_000,
			"{minLight?, studio?} -> HQ reachability (A* from entrance + lounge to every station anchor or its seat's step-in cell), furniture climbing, slot spacing, interior light"
				+ " for an optional studio UUID or 'own' (default)",
			(req, mc) -> {
				Fields f = Fields.of(req);
				int minLight = f.optInt("minLight", 7, 0, 15);
				// MP-12: resolve through Anchors.forStudio so a named studio reads its own layout.
				StudioId studio = MpDevCommands.resolveStudio(f.optStr("studio", null));
				return DevBridge.onClient(mc, () -> check(mc, minLight, studio));
			});
	}

	static boolean isSpot(String name) {
		return !name.startsWith(AnchorNames.CAM_PREFIX) && !name.startsWith(AnchorNames.MONITOR_PREFIX) && !name.startsWith("seat_")
			&& !NOT_SPOTS.contains(name) && !name.equals(AnchorNames.SPAWN);
	}

	/** Seat block at the anchor (the agents' rule), or false. User spots never sit. */
	static boolean isSeat(ClientLevel level, Anchor a) {
		if (a.name().startsWith(AnchorNames.USER) || a.name().equals("podium_user")) {
			return false;
		}
		BlockPos p = BlockPos.containing(a.x(), a.y() + 0.01, a.z());
		BlockState s = level.getBlockState(p);
		if (s.isAir()) {
			return false;
		}
		if (s.getBlock() instanceof StairBlock && s.getValue(StairBlock.HALF) == Half.BOTTOM) {
			return true;
		}
		if (s.getBlock() instanceof SlabBlock && s.getValue(SlabBlock.TYPE) == SlabType.BOTTOM) {
			return true;
		}
		VoxelShape shape = s.getCollisionShape(level, p);
		if (shape.isEmpty()) {
			return false;
		}
		double top = shape.max(Direction.Axis.Y);
		return top >= 0.3 && top <= 0.7;
	}

	/** The cell an agent steps into the seat from (right-hand side, left, behind, in front), or null. */
	static @Nullable Vec3 approach(GridPathfinder pf, Anchor a) {
		BlockPos seat = BlockPos.containing(a.x(), a.y() + 0.01, a.z());
		double rad = Math.toRadians(a.yaw());
		int dx = (int) Math.round(-Math.sin(rad));
		int dz = (int) Math.round(Math.cos(rad));
		int[][] order = {{-dz, dx}, {dz, -dx}, {-dx, -dz}, {dx, dz}};
		for (int[] o : order) {
			int x = seat.getX() + o[0];
			int z = seat.getZ() + o[1];
			for (int dy : new int[] {0, 1, -1}) {
				double f = pf.floor(x, seat.getY() + dy, z);
				if (!Double.isNaN(f)) {
					return new Vec3(x + 0.5, f, z + 0.5);
				}
			}
		}
		return null;
	}

	static JsonObject check(Minecraft mc, int minLight, StudioId studio) {
		JsonObject o = new JsonObject();
		ClientLevel level = mc.level;
		Anchors.Layout layout = Anchors.forStudio(studio);
		if (level == null || layout.isEmpty() || layout.bounds() == null) {
			o.addProperty("error", "no level or no layout");
			return o;
		}
		o.addProperty("studio", studio.owner().toString());
		o.addProperty("layout", layout.name());
		JsonArray failures = new JsonArray();
		JsonArray climbing = new JsonArray();
		JsonArray seats = new JsonArray();
		int spots = 0;
		double longest = 0;
		long t0 = System.nanoTime();
		GridPathfinder grid = new GridPathfinder(level, layout.bounds());
		for (String fromName : List.of(AnchorNames.ENTRANCE, AnchorNames.LOUNGE)) {
			Anchor from = layout.get(fromName);
			if (from == null) {
				failures.add("missing anchor " + fromName);
				continue;
			}
			Vec3 start = from.pos();
			if (isSeat(level, from)) {
				Vec3 ap = approach(grid, from);
				if (ap == null) {
					failures.add(fromName + " (seat with no free side)");
					continue;
				}
				start = ap;
			}
			for (Anchor a : layout.anchors().values()) {
				if (!isSpot(a.name())) {
					continue;
				}
				spots++;
				Vec3 goal = a.pos();
				if (isSeat(level, a)) {
					Vec3 ap = approach(grid, a);
					if (ap == null) {
						failures.add(fromName + " -> " + a.name() + " (seat with no free side)");
						continue;
					}
					goal = ap;
				}
				GridPathfinder pf = new GridPathfinder(level, layout.bounds());
				List<Vec3> route = pf.find(start, goal);
				if (route == null) {
					failures.add(fromName + " -> " + a.name());
					continue;
				}
				double len = 0;
				double floorY = Math.min(start.y, goal.y);
				for (int i = 0; i < route.size(); i++) {
					if (i > 0) {
						len += route.get(i).distanceTo(route.get(i - 1));
					}
					if (route.get(i).y > floorY + 0.3 && climbing.size() < 20) {
						climbing.add(String.format(Locale.ROOT, "%s -> %s climbs at %.1f %.1f %.1f", fromName, a.name(), route.get(i).x, route.get(i).y,
							route.get(i).z));
						break;
					}
				}
				longest = Math.max(longest, len);
			}
		}
		// every spot stands on its own floor cell or is a seat with a plain-floor step-in cell, and slots
		// of a shared station are >= 1.6 apart (FEATURES.md "Slot spacing")
		JsonArray misplaced = new JsonArray();
		JsonArray crowded = new JsonArray();
		for (Anchor a : layout.anchors().values()) {
			if (!isSpot(a.name())) {
				continue;
			}
			if (isSeat(level, a)) {
				Vec3 ap = approach(grid, a);
				if (ap == null || ap.y > a.y() + 0.3) {
					misplaced.add(a.name() + " (seat: step-in cell " + (ap == null ? "missing" : "on furniture") + ")");
				} else {
					seats.add(a.name());
				}
				continue;
			}
			BlockPos c = grid.cellAt(a.pos());
			if (c == null || c.getY() != (int) Math.floor(a.y() + 0.01)) {
				misplaced.add(a.name() + (c == null ? " (no floor)" : " (stands at y " + c.getY() + ")"));
			}
		}
		for (String station : AnchorNames.SHARED_STATIONS) {
			List<Anchor> slots = dev.agentcraft.client.agents.StationAssigner.slots(layout, station);
			for (int i = 0; i < slots.size(); i++) {
				for (int j = i + 1; j < slots.size(); j++) {
					Anchor p = slots.get(i);
					Anchor q = slots.get(j);
					double d = Math.hypot(p.x() - q.x(), p.z() - q.z());
					if (d < 1.6) {
						crowded.add(String.format(Locale.ROOT, "%s-%s %.2f", p.name(), q.name(), d));
					}
				}
			}
		}
		o.add("misplaced", misplaced);
		o.add("crowded", crowded);
		o.add("seats", seats);
		o.add("climbing", climbing);
		o.addProperty("routesChecked", spots);
		o.addProperty("longestRoute", Math.round(longest * 10) / 10.0);
		o.add("unreachable", failures);
		o.addProperty("pathMs", (System.nanoTime() - t0) / 1_000_000);
		// light at feet height over the walkable interior
		Anchors.Bounds b = layout.bounds();
		GridPathfinder pf = new GridPathfinder(level, b);
		int cells = 0;
		int dark = 0;
		int min = 15;
		long sum = 0;
		JsonArray darkest = new JsonArray();
		int feet = (int) Math.floor(layout.get(AnchorNames.ENTRANCE) != null ? layout.get(AnchorNames.ENTRANCE).y() + 0.01 : b.minY() + 1);
		for (int x = b.minX(); x <= b.maxX(); x++) {
			for (int z = b.minZ(); z <= b.maxZ(); z++) {
				if (Double.isNaN(pf.floor(x, feet, z))) {
					continue;
				}
				BlockPos at = new BlockPos(x, feet, z);
				if (level.getBrightness(LightLayer.SKY, at) >= 15) {
					continue; // outdoors (terrace, portico): only roofed cells count
				}
				int bl = level.getBrightness(LightLayer.BLOCK, at);
				cells++;
				sum += bl;
				min = Math.min(min, bl);
				if (bl < minLight) {
					dark++;
					if (darkest.size() < 60) {
						darkest.add(String.format(Locale.ROOT, "%d %d %d: %d", x, feet, z, bl));
					}
				}
			}
		}
		JsonObject light = new JsonObject();
		light.addProperty("walkableCells", cells);
		light.addProperty("minBlockLight", min);
		light.addProperty("meanBlockLight", cells == 0 ? 0 : Math.round(sum * 10.0 / cells) / 10.0);
		light.addProperty("below", minLight);
		light.addProperty("darkCells", dark);
		light.add("darkSample", darkest);
		o.add("light", light);
		return o;
	}
}
