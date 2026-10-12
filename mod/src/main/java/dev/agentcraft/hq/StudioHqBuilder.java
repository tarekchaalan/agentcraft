package dev.agentcraft.hq;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.Cast;
import dev.agentcraft.block.DecisionPodiumBlock;
import dev.agentcraft.block.FacingEntityBlock;
import dev.agentcraft.block.LampStatus;
import dev.agentcraft.block.MergeStationBlock;
import dev.agentcraft.block.ModBlocks;
import dev.agentcraft.block.MonitorBlock;
import dev.agentcraft.block.PanelBlock;
import dev.agentcraft.block.StatusLampBlock;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.world.HqSite;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypeIds;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BellAttachType;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.jspecify.annotations.Nullable;

/**
 * The real AgentCraft HQ ("Warm Studio"): a long timber-framed studio hall with a steep slate roof
 * and an exposed-truss vault, fronted by an octagonal Goal Atrium under a copper dome with a glazed
 * lantern (the entrance rotunda), in a landscaped hollow ringed by wooded hills.
 *
 * <pre>
 *            x=-24 ........................... 0 ............................ x=24
 *   z=-10    north wall: 3 desk bays | big north window | 3 desk bays   (monitors face south, chairs in front)
 *            Library (west end)  Lounge (sw)  aisle  Meeting (se)  Test bench / merge / terminals (east end)
 *   z=6      south wall (terraces outside) ----+  opening  +----
 *   z=7..23                     Goal Atrium (octagon, centre 0,15): task wall W, podium + user E, hologram centre
 *   z=24                                    entrance (portico, steps, path south to the gate)
 * </pre>
 *
 * Floor blocks at y=65 (the hall sits one block above the meadow on a mud-brick plinth); agents
 * stand at y=66. Seats follow the agents' seat contract: the chair/armchair block is at the
 * station anchor itself ({@code desk_<id>}, {@code lounge*}, {@code meeting*}), with a free floor
 * cell on its right-hand side for the agent to step in from. Everything inside {@link #SITE} is
 * planned in memory and applied as a {@link Plan} diff (deterministic, idempotent, cells the player
 * changed since the last build are kept), and nothing outside it is touched.
 */
public final class StudioHqBuilder implements HqBuilder {
	public static final String ID = "studio";
	/** Binding of the cupola's status beacon lamps (driven by the client's HqWorldDriver). */
	public static final String BEACON = "beacon";

	// ------------------------------------------------------------------ dimensions
	static final int GROUND = 64;
	static final int FLOOR = 65;
	static final int FEET = 66;
	/** Hall exterior walls. */
	static final int HX = 24;
	static final int HZN = -10;
	static final int HZS = 6;
	/** Top wall row (66..71), frieze at 72. */
	static final int WALL_TOP = 71;
	static final int FRIEZE = 72;
	/** Roof eave height and ridge line. */
	static final int EAVE_Y = 72;
	static final int RIDGE_Z = -2;
	/** Goal Atrium (octagon) centre cell. */
	static final int AX = 0;
	static final int AZ = 15;
	static final int ATRIUM_TOP = 76;
	/** Spring line of the dome (the timber ring on the wall tops). */
	static final int SPRING = 77;
	/** Site box: rebuilt completely, never anything outside it. */
	static final int[] SITE = {-46, 60, -36, 46, 100, 54};
	/**
	 * The site box in a natural world: the same footprint, reaching deeper, so a valley under the
	 * meadow is filled rather than roofed over (the finder avoids ground more than ~10 below, and the
	 * box takes 16).
	 */
	static final int[] NATURAL_SITE = {SITE[0], GROUND - 16, SITE[2], SITE[3], SITE[4], SITE[5]};
	/**
	 * Survival: the plan box grows by this much on every side, for the spawn-proof light lattice
	 * only ({@code HqLandscape.spawnProofing}); those columns are otherwise left to the world.
	 */
	static final int LIGHT_MARGIN = 18;

	/** Desk bay centres (x), west to east. */
	static final int[] DESK_X = {-15, -11, -7, 7, 11, 15};
	/**
	 * Who works in which bay (west to east): the archivist and the researcher next to the library,
	 * the lead next to the atrium, the designer and the reviewer, the tester next to the test bench.
	 * Cast members not listed here (a changed cast) fill the remaining bays in cast order.
	 */
	static final List<String> DESK_ORDER = List.of("tove", "juniper", "marlow", "wren", "rowan", "kit");
	/**
	 * Chair column of a desk relative to the bay centre: the agent sits at the left third of its
	 * 3-wide screen, so a camera from the right sees the agent, its nameplate and the whole log side
	 * by side instead of the plate covering the screen.
	 */
	static final int CHAIR_DX = -1;

	static final Direction N = Direction.NORTH;
	static final Direction S = Direction.SOUTH;
	static final Direction E = Direction.EAST;
	static final Direction W = Direction.WEST;

	// ------------------------------------------------------------------ materials
	static final BlockState AIR = Blocks.AIR.defaultBlockState();
	static final BlockState PLASTER = ModBlocks.PLASTER_PANEL.defaultBlockState();
	static final BlockState PLASTER_FRAME = ModBlocks.PLASTER_FRAME.defaultBlockState();
	static final BlockState WALNUT = ModBlocks.WALNUT_PANEL.defaultBlockState();
	static final BlockState WALNUT_TRIM = ModBlocks.WALNUT_TRIM.defaultBlockState();
	static final BlockState TILE = ModBlocks.TERRACOTTA_TILE.defaultBlockState();
	static final BlockState PARQUET = ModBlocks.OAK_PARQUET.defaultBlockState();
	static final BlockState GLOW_PANEL = ModBlocks.GLOW_PANEL.defaultBlockState();
	static final BlockState PLINTH = Blocks.MUD_BRICKS.defaultBlockState();
	static final BlockState FLOOR_WOOD = Blocks.BIRCH_PLANKS.defaultBlockState();
	static final BlockState DARK_PLANKS = Blocks.DARK_OAK_PLANKS.defaultBlockState();
	static final BlockState GLASS_PANE = Blocks.GLASS_PANE.defaultBlockState();
	/**
	 * One roof language with the dome and the portico (judges: the blue-black deepslate wing roof was
	 * off-palette): cut-copper tiles framed by a walnut ridge, verges and eaves, like the dome's ribs.
	 */
	static final Block ROOF_STAIRS = Blocks.CUT_COPPER_STAIRS.waxed().unaffected();
	static final Block ROOF_SLAB = Blocks.CUT_COPPER_SLAB.waxed().unaffected();
	static final BlockState ROOF_FULL = Blocks.DARK_OAK_PLANKS.defaultBlockState();
	static final Block ROOF_TRIM = Blocks.DARK_OAK_STAIRS;
	static final Block CEIL_STAIRS = Blocks.PALE_OAK_STAIRS;
	static final BlockState CEIL_FULL = Blocks.PALE_OAK_PLANKS.defaultBlockState();
	static final BlockState COPPER = Blocks.CUT_COPPER.waxed().unaffected().defaultBlockState();
	static final Block COPPER_STAIRS = Blocks.CUT_COPPER_STAIRS.waxed().unaffected();
	static final Block COPPER_SLAB = Blocks.CUT_COPPER_SLAB.waxed().unaffected();
	static final Block COPPER_CHAIN = Blocks.COPPER_CHAIN.waxed().unaffected();
	static final Block COPPER_BULB = Blocks.COPPER_BULB.waxed().unaffected();
	static final BlockState LANTERN = St.lantern(Blocks.LANTERN, false);
	static final BlockState LANTERN_HANGING = St.lantern(Blocks.LANTERN, true);
	static final Block SOFA = Blocks.WOOL_STAIRS.pick(DyeColor.BROWN);

	/** Timber frame: walnut-dark stripped dark oak (posts, beams, pilasters, king posts). */
	static BlockState post() {
		return St.log(Blocks.STRIPPED_DARK_OAK_LOG, Direction.Axis.Y);
	}

	static BlockState beam(Direction.Axis axis) {
		return St.log(Blocks.STRIPPED_DARK_OAK_LOG, axis);
	}

	/** A signal bulb (lit by {@code HqWorldDriver} while its station needs you). */
	static BlockState bulb() {
		return COPPER_BULB.defaultBlockState().setValue(CopperBulbBlock.LIT, false);
	}

	@Override
	public String id() {
		return ID;
	}

	@Override
	public String description() {
		return "the Warm Studio HQ: timber hall + copper-domed Goal Atrium in a wooded hollow";
	}

	@Override
	public void build(ServerLevel level, Anchors.Builder a) {
		build(level, a, Options.DEFAULT);
	}

	@Override
	public @Nullable String build(ServerLevel level, Anchors.Builder a, Options options) {
		long t0 = System.nanoTime();
		// the plan is drawn at the design origin (ground y=64) and placed at the world's HQ site:
		// the origin itself in the studio meadow, the chosen flat ground near the spawn in a natural world
		HqSite site = options.site();
		boolean natural = site != null;
		int ox = natural ? site.x() : 0;
		int oy = natural ? site.y() - GROUND : 0;
		int oz = natural ? site.z() : 0;
		int[] box = natural ? NATURAL_SITE : SITE;
		if (options.survival()) {
			box = new int[] {box[0] - LIGHT_MARGIN, box[1], box[2] - LIGHT_MARGIN, box[3] + LIGHT_MARGIN, box[4], box[5] + LIGHT_MARGIN};
		}
		Plan p = new Plan(box[0], box[1], box[2], box[3], box[4], box[5], GROUND, y -> y > GROUND ? AIR
			: y == GROUND ? Blocks.GRASS_BLOCK.defaultBlockState() : y >= GROUND - 3 ? Blocks.DIRT.defaultBlockState() : Blocks.STONE.defaultBlockState(),
			ox, oy, oz);
		p.setSurvival(options.survival());
		a.offset(ox, oy, oz);
		long tSample0 = System.nanoTime();
		if (natural) {
			HqTerrain.sample(level, p);
		}
		long tSample = System.nanoTime() - tSample0;
		long tPlan0 = System.nanoTime();
		HqLandscape.ground(p);
		hallShell(p);
		hallRoof(p);
		atriumShell(p);
		dome(p);
		floors(p);
		desks(p, a);
		library(p, a);
		workshop(p, a);
		lounge(p, a);
		meeting(p, a);
		atrium(p, a);
		hallLighting(p);
		HqLandscape.exterior(p);
		conservatory(p);
		if (options.survival()) {
			entranceDoors(p);
			backDoor(p);
			survivalCorner(p);
			bedroom(p);
			cropFarm(p);
			animalFarm(p);
			HqLandscape.farmClearing(p);
			HqLandscape.spawnProofing(p);
		}
		cameras(a);
		p.settleGrass();
		long tPlan = System.nanoTime() - tPlan0;
		int[] worldBox = p.worldBox();
		BlockState[] previous = PlanStore.load(level.getServer(), ID, worldBox, p.size());
		Plan.Stats st = p.apply(level, previous, options.force());
		PlanStore.save(level.getServer(), ID, worldBox, p.cells());
		int stocked = options.survival() ? stockPens(level, p) : 0;
		a.bounds(-HX + 1, FLOOR, HZN + 1, HX - 1, FLOOR + 16, AZ + 8);
		a.spot(AnchorNames.ENTRANCE, AX, FEET, AZ + 7, 180);
		a.put(AnchorNames.SPAWN, AX + 0.5, FEET, AZ + 7.5, 180, 0);
		AgentCraft.LOGGER.info(
			"Studio HQ at {}{}: terrain read in {} ms, planned in {} ms, applied {} cells in {} ms ({} changed, {} connections, {} bindings, {} kept, {} foreign replaced, {} drops removed, previous plan {}); total {} ms",
			HqTerrain.describe(site), options.survival() ? " (survival)" : "", tSample / 1_000_000,
			tPlan / 1_000_000, st.cells(), st.micros() / 1000, st.changed(), st.connected(), st.bound(), st.kept(), st.foreign(), st.items(),
			previous == null ? "none" : "known", (System.nanoTime() - t0) / 1_000_000);
		if (st.changed() + st.connected() < 40 && !st.sample().isEmpty()) {
			AgentCraft.LOGGER.info("Studio HQ: changed cells: {}", st.sample());
		}
		if (!st.keptSample().isEmpty()) {
			AgentCraft.LOGGER.info("Studio HQ: kept your changes at {}", st.keptSample());
		}
		StringBuilder r = new StringBuilder(String.format(Locale.ROOT, "%d blocks updated", st.changed() + st.connected()));
		if (st.kept() > 0) {
			r.append(String.format(Locale.ROOT, "; kept %d block%s you changed since the last build (/agentcraft hq force resets them)", st.kept(),
				st.kept() == 1 ? "" : "s"));
		}
		if (st.foreign() > 0) {
			r.append(String.format(Locale.ROOT, "; replaced %d block%s that were not part of the HQ%s", st.foreign(), st.foreign() == 1 ? "" : "s",
				options.force() ? " (force)" : " (first studio build here, or another HQ builder ran since the last one)"));
		}
		if (st.items() > 0) {
			r.append(String.format(Locale.ROOT, "; removed %d dropped item%s", st.items(), st.items() == 1 ? "" : "s"));
		}
		if (stocked > 0) {
			r.append(String.format(Locale.ROOT, "; put %d animal%s in the pens", stocked, stocked == 1 ? "" : "s"));
		}
		return r.toString();
	}

	/** Desk bay owners, west to east (see {@link #DESK_ORDER}). */
	static List<String> deskIds() {
		List<String> cast = Cast.ids();
		List<String> out = new ArrayList<>();
		for (String id : DESK_ORDER) {
			if (cast.contains(id)) {
				out.add(id);
			}
		}
		for (String id : cast) {
			if (!out.contains(id)) {
				out.add(id);
			}
		}
		while (out.size() < DESK_X.length) {
			out.add("agent" + out.size());
		}
		return out;
	}

	// ================================================================== geometry helpers

	/** Inside the octagon's walkable interior (relative to its centre cell). */
	static boolean octIn(int dx, int dz) {
		int ax = Math.abs(dx);
		int az = Math.abs(dz);
		return ax <= 8 && az <= 8 && ax + az <= 12;
	}

	/** Part of the octagon's footprint (interior or wall). */
	static boolean octFoot(int dx, int dz) {
		int ax = Math.abs(dx);
		int az = Math.abs(dz);
		return ax <= 9 && az <= 9 && ax + az <= 14;
	}

	static boolean octWall(int dx, int dz) {
		return octFoot(dx, dz) && !octIn(dx, dz);
	}

	/** Octagonal "radius" of an atrium cell (8 = the last walkable ring). */
	static int octR(int dx, int dz) {
		int ax = Math.abs(dx);
		int az = Math.abs(dz);
		return Math.max(Math.max(ax, az), ax + az - 4);
	}

	/** Octagon of "size" a around the atrium centre (a = 9 is the wall's outer footprint). */
	static boolean octInside(int dx, int dz, int a) {
		int ax = Math.abs(dx);
		int az = Math.abs(dz);
		return ax <= a && az <= a && ax + az <= (3 * a + 1) / 2;
	}

	/** Roof top height at z (both slopes), from the eaves at z=-11 / 7 to the ridge. */
	static int roofY(int z) {
		return EAVE_Y + Math.min(z + 11, 7 - z);
	}

	static boolean isPostX(int x) {
		int ax = Math.abs(x);
		return ax == 24 || ax == 21 || ax == 17 || ax == 13 || ax == 9 || ax == 5;
	}

	static boolean fireplaceX(int x) {
		return x >= -12 && x <= -9;
	}

	// ================================================================== hall

	private static void hallShell(Plan p) {
		// plinth + subfloor under the whole hall
		p.fill(-HX, GROUND, HZN, HX, GROUND, HZS, Blocks.DIRT.defaultBlockState());
		for (int x = -HX; x <= HX; x++) {
			for (int z = HZN; z <= HZS; z++) {
				boolean edge = Math.abs(x) == HX || z == HZN || z == HZS;
				p.set(x, FLOOR, z, edge ? PLINTH : FLOOR_WOOD);
			}
		}
		// long walls
		for (int x = -HX; x <= HX; x++) {
			wallColumn(p, x, HZN, true);
			if (!octFoot(x - AX, HZS - AZ)) {
				wallColumn(p, x, HZS, false);
			}
		}
		// gable end walls (x = +-24): plinth, wall rows, frieze, then the gable triangle
		for (int z = HZN; z <= HZS; z++) {
			for (int sx : new int[] {-HX, HX}) {
				gableColumn(p, sx, z);
			}
		}
		// the truss: tie beams on corbels (north: the desk wall stays flush, so a desk is seen from the
		// side without a pilaster in the way) and on pilasters along the south windows, king posts, struts
		for (int x = -21; x <= 21; x++) {
			if (!isPostX(x)) {
				continue;
			}
			p.set(x, WALL_TOP, HZN + 1, St.stairs(Blocks.DARK_OAK_STAIRS, N, true));
			if (Math.abs(x) >= 9) {
				p.fill(x, FEET, HZS - 1, x, WALL_TOP, HZS - 1, post());
			} else {
				p.set(x, WALL_TOP, HZS - 1, St.stairs(Blocks.DARK_OAK_STAIRS, S, true));
			}
			p.fill(x, FRIEZE, HZN + 1, x, FRIEZE, HZS - 1, beam(Direction.Axis.Z));
			p.fill(x, FRIEZE + 1, RIDGE_Z, x, roofY(RIDGE_Z) - 2, RIDGE_Z, post());
			for (int i = 1; i <= 3; i++) {
				p.set(x, FRIEZE + 1 + (3 - i), RIDGE_Z - i, St.stairs(Blocks.DARK_OAK_STAIRS, S, true));
				p.set(x, FRIEZE + 1 + (3 - i), RIDGE_Z + i, St.stairs(Blocks.DARK_OAK_STAIRS, N, true));
			}
		}
		// pelmet ledges over the long walls' top windows (the hidden cove light sits above them)
		for (int x = -HX + 1; x <= HX - 1; x++) {
			if (isPostX(x)) {
				continue;
			}
			p.set(x, WALL_TOP, HZN + 1, St.slab(Blocks.DARK_OAK_SLAB, true));
			if (!octFoot(x - AX, HZS - AZ) && !fireplaceX(x)) {
				p.set(x, WALL_TOP, HZS - 1, St.slab(Blocks.DARK_OAK_SLAB, true));
			}
		}
	}

	/** One column of a long (north/south) wall at x. */
	private static void wallColumn(Plan p, int x, int z, boolean north) {
		p.set(x, FLOOR, z, PLINTH);
		if (isPostX(x)) {
			p.fill(x, FEET, z, x, WALL_TOP, z, post());
		} else {
			p.set(x, FEET, z, WALNUT_TRIM);
			int ax = Math.abs(x);
			if (north) {
				if (ax <= 3) {
					// the big north window (centre bay)
					p.fill(x, FEET + 1, z, x, WALL_TOP, z, GLASS_PANE);
				} else if (ax == 4) {
					p.fill(x, FEET + 1, z, x, WALL_TOP, z, PLASTER);
				} else {
					p.fill(x, FEET + 1, z, x, FEET + 3, z, PLASTER);
					p.fill(x, FEET + 4, z, x, WALL_TOP, z, GLASS_PANE);
				}
			} else {
				p.fill(x, FEET + 1, z, x, FEET + 4, z, GLASS_PANE);
				p.set(x, WALL_TOP, z, PLASTER_FRAME);
			}
		}
		p.set(x, FRIEZE, z, beam(Direction.Axis.X));
	}

	private static void gableColumn(Plan p, int x, int z) {
		boolean corner = z == HZN || z == HZS;
		p.set(x, FLOOR, z, PLINTH);
		if (corner || z == RIDGE_Z || z == -6 || z == 2) {
			p.fill(x, FEET, z, x, WALL_TOP, z, post());
		} else {
			p.set(x, FEET, z, WALNUT_TRIM);
			p.fill(x, FEET + 1, z, x, FEET + 2, z, PLASTER);
			p.fill(x, FEET + 3, z, x, WALL_TOP - 1, z, GLASS_PANE);
			p.set(x, WALL_TOP, z, PLASTER_FRAME);
		}
		p.set(x, FRIEZE, z, beam(Direction.Axis.Z));
		// gable triangle up to under the roof: collar beam, a round "observatory" window, a king post above it
		int top = roofY(z) - 1;
		for (int y = FRIEZE + 1; y <= top; y++) {
			p.set(x, y, z, PLASTER);
		}
		if (FRIEZE + 1 <= top) {
			p.set(x, FRIEZE + 1, z, beam(Direction.Axis.Z));
		}
		double dz = z - RIDGE_Z;
		double cy = FRIEZE + 4.5;
		for (int y = FRIEZE + 2; y <= top; y++) {
			double dy = y - cy;
			double r = Math.sqrt(dz * dz + dy * dy);
			if (r < 2.4) {
				p.set(x, y, z, GLASS_PANE);
			} else if (r < 3.2) {
				p.set(x, y, z, DARK_PLANKS);
			} else if (z == RIDGE_Z) {
				p.set(x, y, z, post());
			}
		}
	}

	private static void hallRoof(Plan p) {
		for (int x = -HX - 1; x <= HX + 1; x++) {
			for (int z = HZN - 1; z <= HZS + 1; z++) {
				if (octFoot(x - AX, z - AZ)) {
					continue;
				}
				int y = roofY(z);
				if (z == RIDGE_Z) {
					p.set(x, y, z, ROOF_FULL);
					p.set(x, y + 1, z, St.slab(Blocks.DARK_OAK_SLAB, false));
				} else {
					boolean trim = Math.abs(x) == HX + 1 || z == HZN - 1 || z == HZS + 1;
					p.set(x, y, z, St.stairs(trim ? ROOF_TRIM : ROOF_STAIRS, z < RIDGE_Z ? S : N, false));
				}
				// sloped timber ceiling underneath (inside the walls only)
				if (Math.abs(x) < HX && z > HZN && z < HZS) {
					if (z == RIDGE_Z) {
						p.set(x, y - 1, z, CEIL_FULL);
					} else {
						p.set(x, y - 1, z, St.stairs(CEIL_STAIRS, z < RIDGE_Z ? N : S, true));
					}
				}
			}
		}
		dormer(p, -15, true);
		dormer(p, 15, true);
		dormer(p, -15, false);
		dormer(p, 15, false);
		// lounge chimney: the stack rises from the fireplace breast (z 4..5) through the south slope
		int ct = roofY(4) + 4;
		p.fill(-11, FRIEZE, 4, -10, ct, 5, Blocks.MUD_BRICKS.defaultBlockState());
		p.fill(-11, ct + 1, 4, -10, ct + 1, 5, St.slab(Blocks.MUD_BRICK_SLAB, false));
		p.set(-11, ct + 1, 4, Blocks.CAMPFIRE.defaultBlockState());
	}

	/**
	 * A gabled wall dormer centred on x = cx, its window flush with the wall below: front wall (posts +
	 * 3 panes) at the wall line, its own little roof ridged across the slope, cheeks down to the main
	 * roof, and the main roof cut away inside so its light reaches the hall.
	 */
	private static void dormer(Plan p, int cx, boolean south) {
		int zf = south ? HZS : HZN;
		int out = south ? 1 : -1; // towards the eave
		int ridge = 79;
		for (int k = -3; k <= 3; k++) {
			int x = cx + k;
			int hd = ridge - Math.abs(k);
			for (int z = zf + out; z != RIDGE_Z - out; z -= out) {
				int yr = roofY(z);
				if (yr >= hd) {
					break;
				}
				p.set(x, hd, z, k == 0 ? ROOF_FULL : St.stairs(ROOF_STAIRS, k < 0 ? E : W, false));
				if (k == 0) {
					p.set(x, hd + 1, z, St.slab(Blocks.DARK_OAK_SLAB, false));
				}
				if (z == zf || z == zf + out) {
					continue;
				}
				if (Math.abs(k) <= 1) {
					p.set(x, yr, z, AIR);
					p.set(x, yr - 1, z, AIR);
				} else if (Math.abs(k) == 2) {
					for (int y = yr + 1; y < hd; y++) {
						p.set(x, y, z, PLASTER);
					}
				}
			}
		}
		for (int k = -2; k <= 2; k++) {
			int x = cx + k;
			int hd = ridge - Math.abs(k);
			for (int y = FRIEZE + 1; y < hd; y++) {
				BlockState st;
				if (Math.abs(k) == 2) {
					st = post();
				} else if (y <= FRIEZE + 3) {
					st = GLASS_PANE;
				} else if (y == FRIEZE + 4) {
					st = beam(Direction.Axis.X);
				} else {
					st = PLASTER;
				}
				p.set(x, y, zf, st);
			}
		}
	}

	// ================================================================== atrium (octagon)

	private static void atriumShell(Plan p) {
		for (int dx = -9; dx <= 9; dx++) {
			for (int dz = -9; dz <= 9; dz++) {
				if (!octFoot(dx, dz)) {
					continue;
				}
				int x = AX + dx;
				int z = AZ + dz;
				p.set(x, GROUND, z, Blocks.DIRT.defaultBlockState());
				if (octIn(dx, dz)) {
					// clear the interior right up into the dome (the dome sets its own shell)
					for (int y = FEET; y <= 92; y++) {
						p.set(x, y, z, AIR);
					}
					continue;
				}
				p.set(x, FLOOR, z, PLINTH);
				for (int y = FEET; y <= ATRIUM_TOP; y++) {
					p.set(x, y, z, atriumWall(dx, dz, y));
				}
			}
		}
		// opening into the hall (north side, x -3..3) with a stepped arch
		for (int x = -3; x <= 3; x++) {
			for (int y = FEET; y <= FEET + 4; y++) {
				p.set(x, y, HZS, AIR);
			}
		}
		p.set(-3, FEET + 4, HZS, St.stairs(Blocks.DARK_OAK_STAIRS, E, true));
		p.set(3, FEET + 4, HZS, St.stairs(Blocks.DARK_OAK_STAIRS, W, true));
		p.fill(-2, FEET + 5, HZS, 2, FEET + 5, HZS, beam(Direction.Axis.X));
		p.fill(-4, FEET, HZS, -4, FEET + 5, HZS, post());
		p.fill(4, FEET, HZS, 4, FEET + 5, HZS, post());
		// entrance (south side, x -1..1)
		int zs = AZ + 9;
		for (int x = -1; x <= 1; x++) {
			for (int y = FEET; y <= FEET + 3; y++) {
				p.set(x, y, zs, AIR);
			}
		}
		p.set(-1, FEET + 3, zs, St.stairs(Blocks.DARK_OAK_STAIRS, E, true));
		p.set(1, FEET + 3, zs, St.stairs(Blocks.DARK_OAK_STAIRS, W, true));
		p.fill(-1, FEET + 4, zs, 1, FEET + 4, zs, beam(Direction.Axis.X));
		p.fill(-1, FEET + 5, zs, 1, FEET + 7, zs, GLASS_PANE);
		p.fill(-2, FEET, zs, -2, FEET + 7, zs, post());
		p.fill(2, FEET, zs, 2, FEET + 7, zs, post());
		for (int x = -1; x <= 1; x++) {
			p.set(x, FLOOR, zs, DARK_PLANKS);
		}
	}

	/** Wall material of an octagon wall cell at height y. */
	private static BlockState atriumWall(int dx, int dz, int y) {
		int ax = Math.abs(dx);
		int az = Math.abs(dz);
		boolean orthoX = ax == 9 && az <= 4; // west / east sides
		boolean orthoZ = az == 9 && ax <= 4; // north / south sides
		boolean cornerPost = (ax == 9 && az == 5) || (az == 9 && ax == 5);
		if (cornerPost) {
			return post();
		}
		if (y == FEET) {
			return WALNUT_TRIM;
		}
		if (orthoX || orthoZ) {
			int along = orthoX ? az : ax;
			boolean north = orthoZ && dz < 0;
			if (y == 72) {
				return beam(orthoX ? Direction.Axis.Z : Direction.Axis.X);
			}
			if (north) {
				return y == 71 ? PLASTER_FRAME : PLASTER;
			}
			// timber posts split each face into three framed panels; a drum window in the middle
			if (along == 2) {
				return post();
			}
			if (y >= 73 && y <= 75 && along <= 1) {
				return GLASS_PANE;
			}
			if (orthoX && y == FEET + 3) {
				return beam(Direction.Axis.Z); // mid-rail: two tiers of framed panels on the east/west faces
			}
			return y == 71 ? PLASTER_FRAME : PLASTER;
		}
		boolean southDiag = dz > 0;
		// south diagonals: tall faceted (zig-zag) pane windows; north diagonals (they face the hall roof): framed plaster
		if (southDiag && y >= FEET + 1 && y <= 75 && y != 72 && ax >= 6 && ax <= 8 && az >= 5 && az <= 8) {
			return GLASS_PANE;
		}
		if (y == 72) {
			return DARK_PLANKS;
		}
		return y == 71 ? PLASTER_FRAME : PLASTER;
	}

	/*
	 * The dome (chosen side by side in game from 16 variants, artifacts/shots/hq9_sheet_dome*.png:
	 * round and chamfered-square height fields looked lumpy or notched at the hips; the octagonal-ring
	 * field below gives eight clean facets with straight horizontal courses, and walnut ribs on the hips).
	 */
	/** Outer and inner (vault) radius and height of the dome, in octagonal rings ({@link #octR}). */
	static final double DOME_R = 10.6;
	static final double DOME_H = 9.0;
	static final double DOME_RI = 8.8;
	static final double DOME_HI = 7.0;
	/** Profile exponent: 2 = elliptical, larger = fuller shoulders. */
	static final double DOME_P = 2.2;
	static final BlockState DOME_SKIN = Blocks.COPPER_BLOCK.waxed().unaffected().defaultBlockState();

	/** A hip cell of the dome: where an orthogonal facet meets a diagonal one (the walnut ribs). */
	static boolean hip(int dx, int dz) {
		int ax = Math.abs(dx);
		int az = Math.abs(dz);
		int r = octR(dx, dz);
		return r >= 4 && ax + az - 4 == r && (ax == r || az == r);
	}

	/** Columns the dome covers. */
	static boolean domeFoot(int dx, int dz) {
		return octFoot(dx, dz) && octR(dx, dz) < DOME_R - 0.05;
	}

	static double profile(double r, double rad, double h) {
		double q = Math.pow(Math.min(1, r / rad), DOME_P);
		return h * Math.sqrt(Math.max(0, 1 - q));
	}

	/** Outer surface height of a dome column, in half blocks. */
	private static int domeTop2(int dx, int dz) {
		if (!domeFoot(dx, dz)) {
			return 2 * SPRING;
		}
		return (int) Math.round(2 * (SPRING + profile(octR(dx, dz), DOME_R, DOME_H)));
	}

	/** Vault (inner) surface height of a dome column, in half blocks. */
	private static int domeIn2(int dx, int dz) {
		int r = octR(dx, dz);
		if (!domeFoot(dx, dz) || r >= DOME_RI) {
			return 2 * SPRING;
		}
		int i2 = (int) Math.round(2 * (SPRING + profile(r, DOME_RI, DOME_HI)));
		// at least two blocks of shell, so no cell is both the copper skin and the vault lining
		return Math.min(i2, domeTop2(dx, dz) - 4);
	}

	private static boolean oculus(int dx, int dz) {
		return octInside(dx, dz, 2);
	}

	/**
	 * The copper dome: a shell from a height field over octagonal rings (half-block steps, so every
	 * course is a slab or a block and the eight facets keep straight courses), copper outside with
	 * walnut ribs on the hips, a plaster vault with walnut ribs inside, an oculus, and a glazed
	 * octagonal lantern (glass on all eight sides) with a stepped copper cap and finial.
	 */
	private static void dome(Plan p) {
		int topY = SPRING + (int) Math.ceil(DOME_H) + 1;
		for (int dx = -9; dx <= 9; dx++) {
			for (int dz = -9; dz <= 9; dz++) {
				if (!domeFoot(dx, dz) || oculus(dx, dz)) {
					continue;
				}
				int t2 = domeTop2(dx, dz);
				int i2 = domeIn2(dx, dz);
				boolean rib = dx == 0 || dz == 0;
				for (int y = SPRING; y <= topY; y++) {
					boolean lo = 2 * y >= i2 && 2 * y < t2;
					boolean hi = 2 * y + 1 >= i2 && 2 * y + 1 < t2;
					if (!lo && !hi) {
						continue;
					}
					boolean exterior = 2 * y + 2 >= t2 || exposedOut(dx, dz, y);
					boolean interior = (i2 > 2 * SPRING && 2 * y <= i2 && i2 < 2 * y + 2) || exposedIn(dx, dz, y);
					BlockState s;
					if (exterior && hip(dx, dz)) {
						s = lo && hi ? DARK_PLANKS : St.slab(Blocks.DARK_OAK_SLAB, !lo);
					} else if (exterior) {
						s = lo && hi ? DOME_SKIN : St.slab(COPPER_SLAB, !lo);
					} else if (interior) {
						Block slab = rib ? Blocks.DARK_OAK_SLAB : Blocks.PALE_OAK_SLAB;
						BlockState full = rib ? DARK_PLANKS : PLASTER;
						s = lo && hi ? full : lo ? St.slab(slab, false) : St.slab(slab, true);
					} else {
						s = COPPER;
					}
					p.set(AX + dx, y, AZ + dz, s);
				}
			}
		}
		// timber ring on the wall tops (the spring line) and the cornice round the drum
		for (int dx = -10; dx <= 10; dx++) {
			for (int dz = -10; dz <= 10; dz++) {
				int x = AX + dx;
				int z = AZ + dz;
				if (octWall(dx, dz)) {
					p.set(x, SPRING, z, beam(Math.abs(dx) > Math.abs(dz) ? Direction.Axis.Z : Direction.Axis.X));
					continue;
				}
				if (octFoot(dx, dz) || !octInside(dx, dz, 10)) {
					continue;
				}
				int ax = Math.abs(dx);
				int az = Math.abs(dz);
				Direction in = ax >= az ? (dx > 0 ? W : E) : (dz > 0 ? N : S);
				if (!octFoot(dx + in.getStepX(), dz + in.getStepZ())) {
					continue;
				}
				if (p.isAir(x, ATRIUM_TOP, z)) {
					p.set(x, ATRIUM_TOP, z, St.stairs(Blocks.DARK_OAK_STAIRS, in, true));
				}
			}
		}
		lantern(p);
	}

	/** True when the cell (dx, y, dz) of the dome has open sky beside it. */
	private static boolean exposedOut(int dx, int dz, int y) {
		for (Direction d : Direction.Plane.HORIZONTAL) {
			int nx = dx + d.getStepX();
			int nz = dz + d.getStepZ();
			if (!domeFoot(nx, nz)) {
				if (y > SPRING || !octFoot(nx, nz)) {
					return true;
				}
				continue;
			}
			if (!oculus(nx, nz) && domeTop2(nx, nz) <= 2 * y + 1) {
				return true;
			}
		}
		return false;
	}

	/** True when the cell (dx, y, dz) of the dome has the vault's air beside it. */
	private static boolean exposedIn(int dx, int dz, int y) {
		for (Direction d : Direction.Plane.HORIZONTAL) {
			int nx = dx + d.getStepX();
			int nz = dz + d.getStepZ();
			if (!domeFoot(nx, nz)) {
				continue;
			}
			if (oculus(nx, nz)) {
				if (2 * y + 1 < lanternBase() * 2) {
					return true;
				}
				continue;
			}
			int i2 = domeIn2(nx, nz);
			if (i2 > 2 * SPRING && i2 > 2 * y) {
				return true;
			}
		}
		return false;
	}

	/** Base course of the lantern: where the dome surface meets the lantern ring. */
	static int lanternBase() {
		return (int) Math.floor(SPRING + profile(3.0, DOME_R, DOME_H) + 0.5);
	}

	private static void lantern(Plan p) {
		int b = lanternBase();
		for (int dx = -4; dx <= 4; dx++) {
			for (int dz = -4; dz <= 4; dz++) {
				int x = AX + dx;
				int z = AZ + dz;
				int ax = Math.abs(dx);
				int az = Math.abs(dz);
				boolean ring = octInside(dx, dz, 3) && !octInside(dx, dz, 2);
				boolean inner = octInside(dx, dz, 2);
				if (ring) {
					p.set(x, b, z, COPPER);
					boolean postCell = (ax == 3 && az == 2) || (ax == 2 && az == 3);
					for (int y = b + 1; y <= b + 2; y++) {
						p.set(x, y, z, postCell ? COPPER : GLASS_PANE);
					}
					// the status beacon: a band of lamps under the cap, bound to the whole studio's state
					// (clay + breathing while anything waits on you), readable from across the meadow
					if (postCell) {
						p.set(x, b + 3, z, COPPER);
					} else {
						p.set(x, b + 3, z, ModBlocks.STATUS_LAMP.defaultBlockState().setValue(StatusLampBlock.STATUS, LampStatus.IDLE));
						p.bind(x, b + 3, z, BEACON);
					}
				} else if (inner) {
					for (int y = b - 2; y <= b + 3; y++) {
						p.set(x, y, z, AIR);
					}
				}
				// stepped cap
				if (octInside(dx, dz, 4) && !octInside(dx, dz, 3)) {
					p.set(x, b + 4, z, St.slab(COPPER_SLAB, false));
				} else if (octInside(dx, dz, 3)) {
					// the lantern's ceiling: a glowing skylight panel, seen from the atrium through the oculus
					p.set(x, b + 4, z, octInside(dx, dz, 1) ? GLOW_PANEL : COPPER);
				}
				if (octInside(dx, dz, 3) && !octInside(dx, dz, 2)) {
					p.set(x, b + 5, z, St.slab(COPPER_SLAB, false));
				} else if (octInside(dx, dz, 2)) {
					p.set(x, b + 5, z, COPPER);
				}
				if (octInside(dx, dz, 2) && !octInside(dx, dz, 1)) {
					p.set(x, b + 6, z, St.slab(COPPER_SLAB, false));
				} else if (octInside(dx, dz, 1)) {
					p.set(x, b + 6, z, COPPER);
				}
			}
		}
		p.set(AX, b + 7, AZ, St.slab(COPPER_SLAB, false));
		p.set(AX, b + 8, AZ, Blocks.LIGHTNING_ROD.waxed().unaffected().defaultBlockState());
		// the lantern glows at night: hidden light inside the glass (round the chandelier's chain)
		p.set(AX + 1, b + 2, AZ, St.light(14));
		p.set(AX - 1, b + 2, AZ, St.light(14));
		p.set(AX, b + 2, AZ + 1, St.light(14));
		p.set(AX, b + 2, AZ - 1, St.light(14));
	}

	// ================================================================== floors

	private static void floors(Plan p) {
		// hall: birch with a walnut border and a terracotta runner on the main axis
		for (int x = -HX + 1; x <= HX - 1; x++) {
			for (int z = HZN + 1; z <= HZS - 1; z++) {
				BlockState f = FLOOR_WOOD;
				if (Math.abs(x) == HX - 1 || z == HZN + 1 || z == HZS - 1) {
					f = DARK_PLANKS;
				}
				if (Math.abs(x) <= 1 && z >= HZN + 4) {
					f = Math.abs(x) == 1 ? DARK_PLANKS : TILE;
				}
				p.set(x, FLOOR, z, f);
			}
		}
		// the opening's threshold
		for (int x = -3; x <= 3; x++) {
			p.set(x, FLOOR, HZS, Math.abs(x) <= 1 ? TILE : DARK_PLANKS);
		}
		// atrium: concentric octagonal rings
		for (int dx = -8; dx <= 8; dx++) {
			for (int dz = -8; dz <= 8; dz++) {
				if (!octIn(dx, dz)) {
					continue;
				}
				int r = octR(dx, dz);
				BlockState f;
				if (r <= 1) {
					f = DARK_PLANKS;
				} else if (r == 2) {
					f = TILE;
				} else if (r == 3) {
					f = DARK_PLANKS;
				} else if (r == 8) {
					f = DARK_PLANKS;
				} else if (r == 7) {
					f = TILE;
				} else {
					f = FLOOR_WOOD;
				}
				p.set(AX + dx, FLOOR, AZ + dz, f);
			}
		}
	}

	// ================================================================== stations

	private static void desks(Plan p, Anchors.Builder a) {
		List<String> ids = deskIds();
		BlockState desk = St.slab(Blocks.DARK_OAK_SLAB, true);
		int zd = HZN + 1; // -9: desk + monitor row against the north wall
		for (int i = 0; i < DESK_X.length; i++) {
			int bx = DESK_X[i];
			String id = ids.get(i);
			for (int x = bx - 1; x <= bx + 1; x++) {
				p.set(x, FEET, zd, desk);
				for (int y = FEET + 1; y <= FEET + 2; y++) {
					p.set(x, y, zd, ModBlocks.MONITOR.defaultBlockState().setValue(PanelBlock.FACING, S).setValue(MonitorBlock.LIT, true));
					p.bind(x, y, zd, id);
				}
			}
			// status lamp set into the wall above the monitor
			p.set(bx, FEET + 3, HZN, ModBlocks.STATUS_LAMP.defaultBlockState().setValue(StatusLampBlock.STATUS, LampStatus.IDLE));
			p.bind(bx, FEET + 3, HZN, "agent:" + id);
			p.set(bx - 1, FEET + 3, HZN, PLASTER_FRAME);
			p.set(bx + 1, FEET + 3, HZN, PLASTER_FRAME);
			// the chair is the desk spot (agents sit on the seat block at desk_<id>); its right-hand
			// side cell stays free so the agent can step in
			int cx = bx + CHAIR_DX;
			p.set(cx, FEET, zd + 1, St.stairs(Blocks.DARK_OAK_STAIRS, S, false));
			a.put(AnchorNames.desk(id), cx + 0.5, FEET, zd + 1.5, 180, 0);
			a.put("seat_" + id, cx + 0.5, FEET, zd + 1.5, 180, 0);
			a.put(AnchorNames.monitor(id), bx + 0.5, FEET + 2.0, zd + 0.25 + 0.002, 0, 0);
			// over the agent's right shoulder: plate beside the log, not on it (seat at the left third)
			a.cameraLookAt("desk_" + id, bx + 2.0, FEET + 2.2, zd + 3.8, bx + 0.2, FEET + 1.8, zd + 0.25);
		}
		// between the bays: walnut cabinets with a desk lantern (light pools between the desks at
		// night) and plants at the ends
		for (int x : new int[] {-17, -13, -9, -5, 5, 9, 13, 17}) {
			int ax = Math.abs(x);
			if (ax == 5) {
				p.set(x, FEET, zd, St.of(Blocks.POTTED_FLOWERING_AZALEA));
				continue;
			}
			p.set(x, FEET, zd, Blocks.CHISELED_BOOKSHELF.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, S));
			// the cabinets next to the aisle stay clear: the desk close-up looks past them
			if (ax != 9) {
				p.set(x, FEET + 1, zd, ax == 17 ? St.of(Blocks.POTTED_FERN) : LANTERN);
			}
		}
		// window seat + planters in the centre bay under the big north window
		for (int x = -3; x <= 3; x++) {
			p.set(x, FEET, HZN + 1, St.stairs(Blocks.DARK_OAK_STAIRS, N, false));
		}
		p.set(-4, FEET, HZN + 1, Blocks.MOSS_BLOCK.defaultBlockState());
		p.set(4, FEET, HZN + 1, Blocks.MOSS_BLOCK.defaultBlockState());
		p.set(-4, FEET + 1, HZN + 1, Blocks.FLOWERING_AZALEA.defaultBlockState());
		p.set(4, FEET + 1, HZN + 1, Blocks.AZALEA.defaultBlockState());
	}

	private static void library(Plan p, Anchors.Builder a) {
		int xw = -HX + 1; // -23
		BlockState archive = ModBlocks.MEMORY_ARCHIVE.defaultBlockState().setValue(FacingEntityBlock.FACING, E);
		// west wall: three archive bays between walnut pilasters, three shelves high
		for (int z = HZN + 1; z <= HZS - 1; z++) {
			boolean pil = z == -6 || z == RIDGE_Z || z == 2;
			for (int y = FEET; y <= FEET + 2; y++) {
				if (pil) {
					p.set(xw, y, z, post());
				} else if (y == FEET + 1 && (z == -4 || z == 0 || z == 4)) {
					p.set(xw, y, z, ModBlocks.MEMORY_CATALOG.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, E));
				} else {
					p.set(xw, y, z, archive);
					p.bind(xw, y, z, "shared");
				}
			}
			p.set(xw, FEET + 3, z, pil ? post() : St.stairs(Blocks.DARK_OAK_STAIRS, W, true));
		}
		// north wall shelves in the corner (x -22..-19)
		for (int x = -22; x <= -19; x++) {
			for (int y = FEET; y <= FEET + 2; y++) {
				p.set(x, y, HZN + 1, y == FEET + 1 ? Blocks.CHISELED_BOOKSHELF.defaultBlockState()
					.setValue(HorizontalDirectionalBlock.FACING, S) : Blocks.BOOKSHELF.defaultBlockState());
			}
		}
		p.set(-22, FEET + 3, HZN + 1, St.of(Blocks.POTTED_FERN));
		p.set(-19, FEET + 3, HZN + 1, LANTERN);
		// freestanding double shelf (memory archive both sides) and a catalog
		for (int z = -1; z <= 1; z++) {
			p.set(-18, FEET, z, ModBlocks.MEMORY_ARCHIVE.defaultBlockState().setValue(FacingEntityBlock.FACING, W));
			p.bind(-18, FEET, z, "shared");
			p.set(-18, FEET + 1, z, z == 0 ? LANTERN : St.candle(Blocks.CANDLE, 2, true));
		}
		p.set(-18, FEET, -2, ModBlocks.MEMORY_CATALOG.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, W));
		p.set(-18, FEET, 2, ModBlocks.MEMORY_CATALOG.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, W));
		p.set(-18, FEET + 1, -2, St.of(Blocks.POTTED_FLOWERING_AZALEA));
		// lecterns: the first reader stands west of hers facing the hall (her face and book read from
		// the hall cameras instead of her back), the second east of his, facing the shelves
		p.set(-19, FEET, -4, St.facing(Blocks.LECTERN, W));
		p.set(-20, FEET, 3, St.facing(Blocks.LECTERN, E));
		// parquet "rug" for the library floor
		for (int x = -22; x <= -19; x++) {
			for (int z = -5; z <= 4; z++) {
				p.set(x, FLOOR, z, PARQUET);
			}
		}
		// slots: two at the archive wall (far enough out that a full nameplate never pokes into the
		// shelves), two at the lecterns
		a.put(AnchorNames.slot(AnchorNames.LIBRARY, 1), -19.6, FEET, -3.5, -90, 0);
		a.put(AnchorNames.slot(AnchorNames.LIBRARY, 2), -20.4, FEET, 1.5, 90, 0);
		a.put(AnchorNames.slot(AnchorNames.LIBRARY, 3), -18.6, FEET, 3.5, 90, 0);
		a.put(AnchorNames.slot(AnchorNames.LIBRARY, 4), -20.6, FEET, -0.8, 90, 0);
	}

	private static void workshop(Plan p, Anchors.Builder a) {
		int xe = HX - 1; // 23
		BlockState bench = St.slab(Blocks.DARK_OAK_SLAB, true);
		// test bench along the east wall (z -6..-2): CI lamps set into the gable wall, a terminal, lab glassware
		for (int z = -6; z <= -2; z++) {
			p.set(xe, FEET, z, bench);
		}
		for (int i = 0; i < 3; i++) {
			int z = -5 + i;
			p.set(HX, FEET + 2, z, ModBlocks.STATUS_LAMP.defaultBlockState().setValue(StatusLampBlock.STATUS, LampStatus.IDLE));
			p.bind(HX, FEET + 2, z, "ci:#" + (i + 1));
		}
		p.set(xe, FEET + 1, -6, Blocks.BREWING_STAND.defaultBlockState());
		p.set(xe, FEET + 1, -2, LANTERN);
		p.set(xe, FEET + 1, -4, ModBlocks.CONSOLE_TERMINAL.defaultBlockState().setValue(FacingEntityBlock.FACING, W));
		a.put(AnchorNames.slot(AnchorNames.TESTBENCH, 1), 21.6, FEET, -4.6, -90, 0);
		a.put(AnchorNames.slot(AnchorNames.TESTBENCH, 2), 21.6, FEET, -2.6, -90, 0);
		a.put(AnchorNames.slot(AnchorNames.TESTBENCH, 3), 19.8, FEET, -3.6, -90, 0);
		// terminals: north wall, NE corner, facing south
		for (int x = 19; x <= 21; x += 2) {
			p.set(x, FEET, HZN + 1, ModBlocks.CONSOLE_TERMINAL.defaultBlockState().setValue(FacingEntityBlock.FACING, S));
		}
		p.set(20, FEET, HZN + 1, bench);
		p.set(22, FEET, HZN + 1, bench);
		p.set(22, FEET + 1, HZN + 1, St.of(Blocks.POTTED_FERN));
		p.set(20, FEET + 1, HZN + 1, St.candle(Blocks.CANDLE, 3, true));
		p.set(23, FEET, HZN + 1, Blocks.BARREL.defaultBlockState());
		a.put(AnchorNames.slot(AnchorNames.TERMINAL, 1), 19.5, FEET, -7.6, 180, 0);
		a.put(AnchorNames.slot(AnchorNames.TERMINAL, 2), 21.5, FEET, -7.6, 180, 0);
		// merge station: a walnut-panelled alcove in the gable bay z -1..1 with a three-part worktop, a
		// `merge` status lamp above it and two signal bulbs that light while a merge waits for you
		for (int z = -1; z <= 1; z++) {
			p.set(xe, FEET, z, ModBlocks.MERGE_STATION.defaultBlockState().setValue(FacingEntityBlock.FACING, W).setValue(MergeStationBlock.ACTIVE,
				false));
			for (int y = FEET; y <= WALL_TOP; y++) {
				p.set(HX, y, z, y == FEET ? WALNUT_TRIM : WALNUT);
			}
		}
		p.set(HX, FEET + 3, 0, ModBlocks.STATUS_LAMP.defaultBlockState().setValue(StatusLampBlock.STATUS, LampStatus.OFF));
		p.bind(HX, FEET + 3, 0, "merge");
		p.set(HX, FEET + 3, -1, bulb());
		p.set(HX, FEET + 3, 1, bulb());
		p.set(xe, WALL_TOP, -1, St.slab(Blocks.DARK_OAK_SLAB, true));
		p.set(xe, WALL_TOP, 0, St.slab(Blocks.DARK_OAK_SLAB, true));
		p.set(xe, WALL_TOP, 1, St.slab(Blocks.DARK_OAK_SLAB, true));
		p.set(xe, FEET + 1, 1, St.of(Blocks.POTTED_FLOWERING_AZALEA));
		a.put(AnchorNames.slot(AnchorNames.MERGESTATION, 1), 21.5, FEET, -0.5, -90, 0);
		a.put(AnchorNames.slot(AnchorNames.MERGESTATION, 2), 21.5, FEET, 1.4, -90, 0);
		// south-east corner: a cabinet and a floor lamp
		p.set(xe, FEET, 4, Blocks.BARREL.defaultBlockState());
		p.set(xe, FEET + 1, 4, St.of(Blocks.POTTED_FERN));
		floorLamp(p, xe, 3);
	}

	/** A floor lamp: a spruce fence post with a lantern on top (not walkable, no seat). */
	static void floorLamp(Plan p, int x, int z) {
		p.set(x, FEET, z, Blocks.SPRUCE_FENCE.defaultBlockState());
		p.set(x, FEET + 1, z, LANTERN);
	}

	private static void lounge(Plan p, Anchors.Builder a) {
		// rug
		for (int x = -14; x <= -7; x++) {
			for (int z = -3; z <= 3; z++) {
				boolean border = x == -14 || x == -7 || z == -3 || z == 3;
				p.set(x, FEET, z, St.carpet(border ? DyeColor.BROWN : DyeColor.WHITE));
			}
		}
		// fireplace on the south wall (x -12..-9), the chimney stack rises from it through the roof
		for (int x = -12; x <= -9; x++) {
			for (int y = FEET; y <= WALL_TOP; y++) {
				boolean jamb = x == -12 || x == -9;
				if (y <= FEET + 1 && !jamb) {
					p.set(x, y, HZS - 1, y == FEET ? Blocks.CAMPFIRE.defaultBlockState() : AIR);
				} else {
					p.set(x, y, HZS - 1, Blocks.MUD_BRICKS.defaultBlockState());
				}
			}
			p.set(x, FEET + 2, HZS - 2, St.slab(Blocks.MUD_BRICK_SLAB, true));
		}
		p.set(-12, FEET + 3, HZS - 2, St.candle(Blocks.CANDLE, 3, true));
		p.set(-9, FEET + 3, HZS - 2, St.of(Blocks.POTTED_FERN));
		// six leather armchairs round a low table, facing each other and the fire: a pair at the back
		// (facing the fire) and two on each side. Each chair's right-hand neighbour is free floor.
		int[][] chairs = {
			// x, z, sitter's yaw
			{-12, -2, 0}, {-9, -2, 0}, {-13, 0, -90}, {-8, 0, 90}, {-13, 2, -90}, {-8, 2, 90},
		};
		for (int i = 0; i < chairs.length; i++) {
			int[] c = chairs[i];
			Direction back = c[2] == 0 ? N : c[2] == -90 ? W : E;
			p.set(c[0], FEET, c[1], St.stairs(SOFA, back, false));
			a.put(AnchorNames.slot(AnchorNames.LOUNGE, i + 1), c[0] + 0.5, FEET, c[1] + 0.5, c[2], 0);
		}
		// low coffee table (bottom slabs: nobody walks over it) with a candle and a plant
		for (int x = -11; x <= -10; x++) {
			for (int z = 0; z <= 1; z++) {
				p.set(x, FEET, z, St.slab(Blocks.DARK_OAK_SLAB, false));
			}
		}
		p.set(-11, FEET + 1, 0, St.candle(Blocks.CANDLE, 3, true));
		p.set(-10, FEET + 1, 1, St.of(Blocks.POTTED_FLOWERING_AZALEA));
		// the aisle-side lamp stands at the fire end, so the wide shot down the aisle has no lamp post
		// in its foreground
		floorLamp(p, -14, -3);
		floorLamp(p, -7, 3);
		p.set(-14, FEET, 3, St.of(Blocks.POTTED_FERN));
	}

	private static void meeting(Plan p, Anchors.Builder a) {
		// rug + a long table (x 8..13, z 0) with three chairs a side, staggered
		for (int x = 6; x <= 15; x++) {
			for (int z = -2; z <= 2; z++) {
				boolean border = x == 6 || x == 15 || z == -2 || z == 2;
				p.set(x, FEET, z, St.carpet(border ? DyeColor.BROWN : DyeColor.LIGHT_GRAY));
			}
		}
		BlockState top = St.slab(Blocks.DARK_OAK_SLAB, true);
		for (int x = 8; x <= 13; x++) {
			p.set(x, FEET, 0, top);
		}
		p.set(10, FEET + 1, 0, LANTERN);
		p.set(12, FEET + 1, 0, St.candle(Blocks.CANDLE, 2, true));
		p.set(8, FEET + 1, 0, St.of(Blocks.POTTED_FERN));
		int n = 1;
		for (int x : new int[] {8, 10, 12}) {
			// north side: back to the north, the sitter faces the table (south); free floor west of it
			p.set(x, FEET, -1, St.stairs(Blocks.BIRCH_STAIRS, N, false));
			a.put(AnchorNames.slot(AnchorNames.MEETING, n++), x + 0.5, FEET, -0.5, 0, 0);
		}
		for (int x : new int[] {9, 11, 13}) {
			// south side: back to the south, facing north; free floor east of it
			p.set(x, FEET, 1, St.stairs(Blocks.BIRCH_STAIRS, S, false));
			a.put(AnchorNames.slot(AnchorNames.MEETING, n++), x + 0.5, FEET, 1.5, 180, 0);
		}
	}

	private static void atrium(Plan p, Anchors.Builder a) {
		// --- hologram plinth (centre): goal lamp on a copper base; the hologram is drawn by the lamp's BER
		p.set(AX, FEET, AZ, ModBlocks.STATUS_LAMP.defaultBlockState().setValue(StatusLampBlock.STATUS, LampStatus.IDLE));
		p.bind(AX, FEET, AZ, "goal:atrium");
		for (int sx = -1; sx <= 1; sx++) {
			for (int sz = -1; sz <= 1; sz++) {
				if (sx != 0 || sz != 0) {
					p.set(AX + sx, FEET, AZ + sz, St.slab(COPPER_SLAB, false));
				}
			}
		}
		a.put(AnchorNames.GOAL_ATRIUM, AX + 0.5, FEET, AZ + 0.5, 180, 0);

		// --- task wall: west side, 7 x 4 board facing east, walnut surround with a hood (a hidden light under it)
		int xb = AX - 8;
		BlockState board = ModBlocks.TASK_BOARD.defaultBlockState().setValue(PanelBlock.FACING, E);
		for (int z = AZ - 3; z <= AZ + 3; z++) {
			for (int y = FEET + 1; y <= FEET + 4; y++) {
				p.set(xb, y, z, board);
			}
			p.set(xb, FEET, z, WALNUT_TRIM);
			p.set(xb, FEET + 5, z, WALNUT);
			p.set(xb + 1, FEET + 5, z, St.slab(Blocks.DARK_OAK_SLAB, true));
		}
		for (int y = FEET; y <= FEET + 5; y++) {
			p.set(xb, y, AZ - 4, post());
			p.set(xb, y, AZ + 4, post());
		}
		a.put(AnchorNames.TASK_WALL, xb + 1 - 0.875 + 0.002, FEET + 3.0, AZ + 0.5, -90, 0);
		// warm wash: a hidden light bar under the hood along the whole board, plus a lower fill, so the
		// board is the brightest surface of the bay (judges: "darkest interior shot, no light on the board")
		for (int z = AZ - 3; z <= AZ + 3; z++) {
			p.setIfAir(xb + 1, FEET + 4, z, St.light(15));
			if ((z - AZ) % 2 == 0) {
				p.setIfAir(xb + 1, FEET + 2, z, St.light(14));
			}
		}
		// pale hood and sill instead of dark walnut: the bay reads as part of the cream atrium
		for (int z = AZ - 3; z <= AZ + 3; z++) {
			p.set(xb + 1, FEET + 5, z, St.slab(Blocks.PALE_OAK_SLAB, true));
			p.set(xb, FEET + 5, z, PLASTER_FRAME);
		}

		// --- decision podium: east side, facing west; a framed walnut niche behind it with the
		// `decisions` lamp, the bell and three signal bulbs that light while a decision waits for you
		int xp = AX + 6;
		p.set(xp, FEET, AZ, ModBlocks.DECISION_PODIUM.defaultBlockState().setValue(FacingEntityBlock.FACING, W).setValue(DecisionPodiumBlock.OPEN,
			false));
		a.put(AnchorNames.DECISION_PODIUM, xp + 0.5, FEET + 0.95, AZ + 0.5, 90, 0);
		int xa = AX + 8;
		for (int z = AZ - 2; z <= AZ + 2; z++) {
			for (int y = FEET; y <= FEET + 5; y++) {
				boolean side = Math.abs(z - AZ) == 2;
				// cut-copper pilasters frame the niche (real trim, not a glowing outline)
				p.set(xa, y, z, side ? COPPER : y == FEET ? WALNUT_TRIM : WALNUT);
			}
			p.set(xa, FEET + 6, z, beam(Direction.Axis.Z));
		}
		// hidden warm light in the niche, so the podium pops from the walnut instead of sitting in shadow
		p.setIfAir(xa - 1, FEET + 4, AZ, St.light(14));
		p.setIfAir(xa - 2, FEET + 1, AZ - 1, St.light(13));
		p.setIfAir(xa - 2, FEET + 1, AZ + 1, St.light(13));
		p.set(xa, FEET + 3, AZ, ModBlocks.STATUS_LAMP.defaultBlockState().setValue(StatusLampBlock.STATUS, LampStatus.OFF));
		p.bind(xa, FEET + 3, AZ, "decisions");
		for (int z = AZ - 1; z <= AZ + 1; z++) {
			p.set(xa, FEET + 5, z, bulb());
		}
		p.set(xa - 1, FEET + 3, AZ - 1, St.bell(W, BellAttachType.SINGLE_WALL));
		p.set(xa - 1, FEET + 2, AZ + 1, St.slab(Blocks.DARK_OAK_SLAB, true));
		p.set(xa - 1, FEET + 3, AZ + 1, LANTERN);
		// user console beside the podium
		p.set(xp + 1, FEET, AZ + 3, ModBlocks.CONSOLE_TERMINAL.defaultBlockState().setValue(FacingEntityBlock.FACING, W));
		// the player's spot in front of the podium; waiting agents stand beside it, facing that spot
		double ux = xp - 0.5;
		double uz = AZ + 0.5;
		a.put("podium_user", ux, FEET, uz, -90, 0);
		double[][] userSlots = {{xp - 1.2, AZ - 1.6}, {xp - 1.2, AZ + 2.6}, {xp - 2.9, AZ - 0.9}};
		for (int i = 0; i < userSlots.length; i++) {
			double sx = userSlots[i][0];
			double sz = userSlots[i][1];
			float yaw = (float) Math.toDegrees(Math.atan2(-(ux - sx), uz - sz));
			a.put(AnchorNames.slot(AnchorNames.USER, i + 1), sx, FEET, sz, yaw, 0);
		}

		// --- benches + planters on the diagonals
		for (int sx : new int[] {-1, 1}) {
			p.set(AX + sx * 5, FEET, AZ - 6, St.stairs(Blocks.DARK_OAK_STAIRS, sx < 0 ? W : E, false));
			p.set(AX + sx * 6, FEET, AZ - 5, St.stairs(Blocks.DARK_OAK_STAIRS, sx < 0 ? W : E, false));
			p.set(AX + sx * 4, FEET, AZ - 7, St.of(Blocks.POTTED_FERN));
			p.set(AX + sx * 6, FEET, AZ + 5, Blocks.MOSS_BLOCK.defaultBlockState());
			p.set(AX + sx * 5, FEET, AZ + 6, Blocks.MOSS_BLOCK.defaultBlockState());
			p.set(AX + sx * 6, FEET + 1, AZ + 5, Blocks.FLOWERING_AZALEA.defaultBlockState());
			p.set(AX + sx * 5, FEET + 1, AZ + 6, Blocks.AZALEA.defaultBlockState());
		}
		// --- light: a cornice ledge round the drum with a hidden light cove, a ring chandelier above the hologram
		for (int dx = -8; dx <= 8; dx++) {
			for (int dz = -8; dz <= 8; dz++) {
				if (!octIn(dx, dz) || octR(dx, dz) != 8) {
					continue;
				}
				p.set(AX + dx, ATRIUM_TOP, AZ + dz, St.slab(Blocks.DARK_OAK_SLAB, true));
				if ((dx + dz) % 3 == 0) {
					p.setIfAir(AX + dx, ATRIUM_TOP + 1, AZ + dz, St.light(12));
				}
			}
		}
		chandelier(p);
		// hidden light in the vault, so the pale coffers glow instead of fading to grey under the dome
		for (int dx = -5; dx <= 5; dx += 5) {
			for (int dz = -5; dz <= 5; dz += 5) {
				if (dx != 0 || dz != 0) {
					p.setIfAir(AX + dx, SPRING + 3, AZ + dz, St.light(13));
				}
			}
		}
		p.setIfAir(AX + 2, SPRING + 5, AZ + 2, St.light(12));
		p.setIfAir(AX - 2, SPRING + 5, AZ - 2, St.light(12));
		// hidden fill light (invisible light blocks), kept low so the lanterns make pools of light
		for (int dx = -8; dx <= 8; dx += 4) {
			for (int dz = -8; dz <= 8; dz += 4) {
				if (octIn(dx, dz) && (dx != 0 || dz != 0)) {
					p.setIfAir(AX + dx, FEET + 1, AZ + dz, St.light(12));
					p.setIfAir(AX + dx, FEET + 6, AZ + dz, St.light(10));
				}
			}
		}
		// floor lamps by the entrance
		floorLamp(p, AX - 3, AZ + 7);
		floorLamp(p, AX + 3, AZ + 7);
	}

	/**
	 * A ring chandelier hung from the lantern's cap through the oculus: a square ring of copper chain
	 * (half size 2) with eight lanterns under it, braced to the central chain.
	 */
	private static void chandelier(Plan p) {
		int ry = ATRIUM_TOP - 1; // ring height
		int top = lanternBase() + 3;
		for (int y = ry; y <= top; y++) {
			p.set(AX, y, AZ, St.chain(COPPER_CHAIN, Direction.Axis.Y));
		}
		for (int d = -2; d <= 2; d++) {
			p.set(AX + d, ry, AZ - 2, St.chain(COPPER_CHAIN, Direction.Axis.X));
			p.set(AX + d, ry, AZ + 2, St.chain(COPPER_CHAIN, Direction.Axis.X));
			p.set(AX - 2, ry, AZ + d, St.chain(COPPER_CHAIN, Direction.Axis.Z));
			p.set(AX + 2, ry, AZ + d, St.chain(COPPER_CHAIN, Direction.Axis.Z));
		}
		for (int d = -1; d <= 1; d += 2) {
			p.set(AX + d, ry, AZ, St.chain(COPPER_CHAIN, Direction.Axis.X));
			p.set(AX, ry, AZ + d, St.chain(COPPER_CHAIN, Direction.Axis.Z));
		}
		for (int dx = -2; dx <= 2; dx += 2) {
			for (int dz = -2; dz <= 2; dz += 2) {
				if (dx != 0 || dz != 0) {
					p.set(AX + dx, ry - 1, AZ + dz, LANTERN_HANGING);
				}
			}
		}
	}

	private static void hallLighting(Plan p) {
		for (int x = -21; x <= 21; x++) {
			if (!isPostX(x)) {
				continue;
			}
			for (int z : new int[] {-5, 1}) {
				if (Math.abs(x) == 5 && z == 1) {
					continue; // keeps the view from the atrium opening across the lounge clear
				}
				p.set(x, FRIEZE - 1, z, St.chain(Blocks.IRON_CHAIN, Direction.Axis.Y));
				p.set(x, FRIEZE - 2, z, LANTERN_HANGING);
			}
		}
		// hidden uplights on top of the tie beams and along the ridge, so the pale vault never goes dark
		for (int x = -21; x <= 21; x++) {
			if (isPostX(x)) {
				p.setIfAir(x, FRIEZE + 1, -7, St.light(12));
				p.setIfAir(x, FRIEZE + 1, 3, St.light(12));
			} else if (Math.floorMod(x, 4) == 1) {
				p.setIfAir(x, roofY(RIDGE_Z) - 2, RIDGE_Z, St.light(10));
			}
		}
		// hidden cove light above the pelmets: a warm wash on the sloped ceiling. Invisible light
		// blocks, not glow strips: a strip lying on the pelmet still shows its 2 px edge above the lip
		// from across the hall (tried in game: a hairline along every wall, hq9_p6_hall.png)
		for (int x = -HX + 1; x <= HX - 1; x += 2) {
			p.setIfAir(x, FRIEZE, HZN + 1, St.light(12));
			if (!octFoot(x - AX, HZS - AZ) && !fireplaceX(x)) {
				p.setIfAir(x, FRIEZE, HZS - 1, St.light(12));
			}
		}
		// ceiling lights along the ridge between the trusses (the glow panel's plaster frame + panes)
		for (int x : new int[] {-19, -11, -7, -3, 3, 7, 11, 19}) {
			p.set(x, roofY(RIDGE_Z) - 1, RIDGE_Z, GLOW_PANEL);
		}
		// invisible fill light (light blocks: no model, no collision) one block above the floor, kept
		// low so the lanterns, screens and the fire make the pools of light at night
		for (int x = -22; x <= 22; x += 4) {
			for (int z : new int[] {-7, -3, 1, 4}) {
				p.setIfAir(x, FEET + 1, z, St.light(z == 4 ? 11 : 12));
			}
		}
	}

	// ================================================================== survival

	/**
	 * Survival worlds: the entrance gets a pair of dark oak doors on a slim timber mullion, glazed
	 * above, so nothing wanders in at night. The doors sit in the entrance wall (z = AZ + 9), one
	 * block outside the agents' bounds: no agent path ever crosses them.
	 */
	private static void entranceDoors(Plan p) {
		int zs = AZ + 9;
		BlockState door = Blocks.DARK_OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, S).setValue(DoorBlock.OPEN, false)
			.setValue(DoorBlock.POWERED, false);
		for (int x : new int[] {-1, 1}) {
			// hinges on the outer posts, so both leaves swing away from the mullion
			DoorHingeSide hinge = x < 0 ? DoorHingeSide.RIGHT : DoorHingeSide.LEFT;
			p.set(x, FEET, zs, door.setValue(DoorBlock.HINGE, hinge).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
			p.set(x, FEET + 1, zs, door.setValue(DoorBlock.HINGE, hinge).setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
			p.set(x, FEET + 2, zs, GLASS_PANE); // transom under the arch stones at FEET + 3
		}
		p.fill(AX, FEET, zs, AX, FEET + 3, zs, post());
	}

	/**
	 * Survival worlds: the back door to the farms, in the north wall at x 18: the one free cell of
	 * the desk wall's inner row (between the last cabinet at 17 and the terminals from 19), and
	 * straight across the ring path from the animal farm's east gate (x 18), the crop farm's gate a
	 * short walk west. A half step down through the hedge onto a path to the ring path. One block
	 * outside the desks and the terminal slots; closed, it is a wall to the agents like the rest.
	 */
	private static void backDoor(Plan p) {
		backDoor(p, 18);
		// and one in the middle, under the big north window: the centre cell of the window seat makes
		// way for it, leaving a three-seat bench on either side
		backDoor(p, AX);
		p.set(AX, FEET, HZN + 1, AIR);
	}

	private static void backDoor(Plan p, int x) {
		door(p, x, HZN, N);
		p.set(x, FLOOR, HZN - 1, St.slab(Blocks.MUD_BRICK_SLAB, false)); // replaces the hedge cell
		for (int z = HZN - 3; z <= HZN - 2; z++) {
			p.set(x, GROUND, z, Blocks.DIRT_PATH.defaultBlockState());
			p.set(x, FLOOR, z, AIR);
		}
	}

	/**
	 * Survival worlds: the workbench corner, in the hall's two south-east bays by the merge station
	 * (between the pilasters at x 17 and 21, and east of 21), right by the bedroom door: a crafting
	 * table, two chests, a furnace and a smoker, next to the barrel that is already there.
	 */
	private static void survivalCorner(Plan p) {
		int z = HZS - 1;
		// one double chest (facing north, its left half is the eastern one)
		BlockState chest = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, N);
		p.set(18, FEET, z, Blocks.CRAFTING_TABLE.defaultBlockState());
		p.set(19, FEET, z, chest.setValue(ChestBlock.TYPE, ChestType.LEFT));
		p.set(20, FEET, z, chest.setValue(ChestBlock.TYPE, ChestType.RIGHT));
		p.set(22, FEET, z, Blocks.FURNACE.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, N).setValue(AbstractFurnaceBlock.LIT, false));
		p.set(23, FEET, z, Blocks.SMOKER.defaultBlockState().setValue(AbstractFurnaceBlock.FACING, N).setValue(AbstractFurnaceBlock.LIT, false));
	}

	// ================================================================== lean-tos on the terraces

	/** The terrace path meets the lean-to doors here (x = +-17, the terrace steps' line, see HqLandscape.paths). */
	static final int LEAN_TO_DOOR_X = 17;

	/**
	 * A timber lean-to on a terrace against the hall's south wall (z 7..12), under the eaves: plinth
	 * and floor, posts at the corners (and, glazed, in the middle of the front), framed walls, a beam
	 * ring, a copper shed roof stepping down to the south over a solid ceiling with one block of
	 * overhang on three sides, and a door onto the meadow at {@code doorX} with a step. Clears what
	 * the terrace put there first (deck furniture, planters, window boxes, hidden lights).
	 *
	 * @param glazed every wall column that is not a post is glass to the ceiling (the conservatory);
	 *     otherwise plaster with pane windows at {@code windows} (x on the front, z 9..10 on the sides)
	 */
	private static void leanTo(Plan p, int x0, int x1, BlockState floor, boolean glazed, int[] windows, int doorX) {
		int z0 = HZS + 1;
		int z1 = HZS + 6;
		int mid = (x0 + x1) / 2;
		p.fill(x0, FEET, z0, x1, FEET + 5, z1 + 1, AIR);
		for (int x = x0; x <= x1; x++) {
			for (int z = z0; z <= z1; z++) {
				p.set(x, GROUND, z, Blocks.DIRT.defaultBlockState());
				p.set(x, FLOOR, z, x == x0 || x == x1 || z == z1 ? PLINTH : floor);
			}
		}
		for (int z = z0; z <= z1; z++) {
			for (int sx : new int[] {x0, x1}) {
				boolean window = glazed ? z != z0 && z != z1 : z == 9 || z == 10;
				leanToWall(p, sx, z, z == z0 || z == z1, window, glazed);
				p.set(sx, FEET + 3, z, beam(Direction.Axis.Z));
			}
		}
		for (int x = x0 + 1; x <= x1 - 1; x++) {
			boolean post = glazed && x == mid;
			int wx = x;
			boolean window = glazed ? x != doorX : java.util.Arrays.stream(windows).anyMatch(w -> w == wx);
			leanToWall(p, x, z1, post, window, glazed);
			p.set(x, FEET + 3, z1, beam(Direction.Axis.X));
		}
		// shed roof: copper courses stepping down from under the hall's eave (its lowest course is
		// at y 72 over z 7) to the south
		for (int z = z0; z <= z1 + 1; z++) {
			int y = FEET + 5 - (z - z0) / 2;
			for (int x = x0 - 1; x <= x1 + 1; x++) {
				boolean over = x < x0 || x > x1 || z > z1;
				if (!over) {
					for (int yy = FEET + 3; yy < y; yy++) {
						p.setIfAir(x, yy, z, ROOF_FULL);
					}
				}
				p.set(x, y, z, St.stairs(ROOF_STAIRS, N, false));
			}
		}
		door(p, doorX, z1, S);
		p.set(doorX, FLOOR, z1 + 1, St.slab(Blocks.MUD_BRICK_SLAB, false));
	}

	/** One wall column of a lean-to: a post, or trim + (window or plaster) + (glass when glazed, else plaster). */
	private static void leanToWall(Plan p, int x, int z, boolean post, boolean window, boolean glazed) {
		if (post) {
			p.fill(x, FEET, z, x, FEET + 2, z, post());
			return;
		}
		p.set(x, FEET, z, WALNUT_TRIM);
		p.set(x, FEET + 1, z, window ? GLASS_PANE : PLASTER);
		p.set(x, FEET + 2, z, window && glazed ? GLASS_PANE : PLASTER);
	}

	/**
	 * Both worlds: the conservatory, a glazed lean-to on the west terrace (x -24..-14, z 7..12), the
	 * bedroom's counterpart on the other side of the atrium: terracotta tile floor, glass to the
	 * ceiling between the posts, a potting bench along the west wall, azaleas on moss in the corners,
	 * a bench and a side table by the south glass, a lantern. A door from the hall's south-west
	 * corner (by the lounge fire) and one onto the meadow where the terrace path starts. Nothing
	 * agent-related: no station, no anchor; the doors are closed blocks to the agents' paths.
	 */
	private static void conservatory(Plan p) {
		leanTo(p, -24, -14, TILE, true, new int[0], -LEAN_TO_DOOR_X);
		BlockState counter = St.slab(Blocks.DARK_OAK_SLAB, true);
		for (int z = 8; z <= 10; z++) {
			p.set(-23, FEET, z, counter);
		}
		p.set(-23, FEET + 1, 8, St.of(Blocks.POTTED_FERN));
		p.set(-23, FEET + 1, 9, St.of(Blocks.POTTED_FLOWERING_AZALEA));
		p.set(-23, FEET + 1, 10, St.candle(Blocks.CANDLE, 2, true));
		for (int[] c : new int[][] {{-23, 7}, {-15, 7}, {-15, 11}}) {
			p.set(c[0], FEET, c[1], Blocks.MOSS_BLOCK.defaultBlockState());
			p.set(c[0], FEET + 1, c[1], (c[0] + c[1]) % 2 == 0 ? Blocks.FLOWERING_AZALEA.defaultBlockState() : Blocks.AZALEA.defaultBlockState());
		}
		// bench with its back to the south glass, a side table with a candle
		p.set(-21, FEET, 11, St.stairs(Blocks.SPRUCE_STAIRS, S, false));
		p.set(-20, FEET, 11, St.stairs(Blocks.SPRUCE_STAIRS, S, false));
		p.set(-22, FEET, 11, counter);
		p.set(-22, FEET + 1, 11, St.candle(Blocks.CANDLE, 1, true));
		p.set(-19, FEET + 2, 9, LANTERN_HANGING);
		// from the hall: the south wall's pane column at x -16 becomes the doorway
		door(p, -16, HZS, S);
	}

	/**
	 * Survival worlds: the player's bedroom, a timber lean-to on the east terrace against the hall's
	 * south wall (x 14..24, z 7..12) under the eaves, with a door from the hall's south-east corner
	 * (by the workbench corner) and one onto the meadow where the terrace path starts. Nothing
	 * agent-related: no station, no anchor, and both doors are closed blocks to the agents' paths
	 * (the hall wall was one already).
	 */
	private static void bedroom(Plan p) {
		leanTo(p, 14, 24, FLOOR_WOOD, false, new int[] {15, 16, 21, 22}, LEAN_TO_DOOR_X);
		// the room: bed against the east wall, a reading lamp, a chest and books by the west wall, a rug
		BlockState bed = Blocks.BED.pick(DyeColor.BROWN).defaultBlockState().setValue(BedBlock.FACING, E).setValue(BedBlock.OCCUPIED, false);
		p.set(22, FEET, 8, bed.setValue(BedBlock.PART, BedPart.FOOT));
		p.set(23, FEET, 8, bed.setValue(BedBlock.PART, BedPart.HEAD));
		floorLamp(p, 23, 10);
		p.set(15, FEET, 8, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, E).setValue(ChestBlock.TYPE, ChestType.SINGLE));
		p.set(15, FEET, 10, Blocks.BOOKSHELF.defaultBlockState());
		p.set(15, FEET + 1, 10, Blocks.BOOKSHELF.defaultBlockState());
		p.set(21, FEET, 11, St.of(Blocks.POTTED_FLOWERING_AZALEA));
		for (int x = 17; x <= 21; x++) {
			for (int z = 8; z <= 10; z++) {
				boolean border = x == 17 || x == 21 || z == 8 || z == 10;
				p.set(x, FEET, z, St.carpet(border ? DyeColor.BROWN : DyeColor.WHITE));
			}
		}
		p.set(19, FEET + 2, 9, LANTERN_HANGING);
		// from the hall: the south wall's pane column at x 16 becomes the doorway
		door(p, 16, HZS, S);
	}

	/** A single dark oak door (lower + upper) in a wall cell, closed. */
	private static void door(Plan p, int x, int z, Direction facing) {
		BlockState door = Blocks.DARK_OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, facing).setValue(DoorBlock.OPEN, false)
			.setValue(DoorBlock.POWERED, false).setValue(DoorBlock.HINGE, DoorHingeSide.LEFT);
		p.set(x, FEET, z, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
		p.set(x, FEET + 1, z, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
	}

	// ------------------------------------------------------------------ farms (behind the hall)

	/**
	 * Survival worlds: the crop farm, west half of the strip behind the hall (north of the ring
	 * path): a one-block grass walk just inside the fence, then 19 x 11 of farmland in three fields
	 * (wheat, carrots, potatoes, planted mature), two water channels so every row is wet (waterlogged
	 * light blocks, so they never freeze), a spruce fence with lanterns, carpets on the corner and
	 * middle posts to climb it by, a gate onto the ring path, a composter and hay by the gate.
	 */
	private static void cropFarm(Plan p) {
		int[] r = HqLandscape.CROP_FARM;
		BlockState farmland = Blocks.FARMLAND.defaultBlockState().setValue(FarmlandBlock.MOISTURE, 7);
		for (int x = r[0] + 1; x <= r[2] - 1; x++) {
			for (int z = r[1] + 1; z <= r[3] - 1; z++) {
				// the meadow's tall grass stood here: its upper half would float over the crops
				p.set(x, FLOOR + 1, z, AIR);
				if (x == r[0] + 1 || x == r[2] - 1 || z == r[1] + 1 || z == r[3] - 1) {
					// the walk round the fields: grass, nothing on it, so every row can be reached without trampling
					p.set(x, GROUND, z, Blocks.GRASS_BLOCK.defaultBlockState());
					p.set(x, FLOOR, z, AIR);
					continue;
				}
				if (z == -27 || z == -21) {
					// waterlogged light blocks: water that cannot freeze in a cold biome, and light for the crops at night
					p.set(x, GROUND, z, St.unfrozenWater(10));
					p.set(x, FLOOR, z, AIR);
					continue;
				}
				Block crop = x <= -17 ? Blocks.WHEAT : x <= -10 ? Blocks.CARROTS : Blocks.POTATOES;
				p.set(x, GROUND, z, farmland);
				p.set(x, FLOOR, z, crop.defaultBlockState().setValue(CropBlock.AGE, 7));
			}
		}
		fenceRing(p, r[0], r[1], r[2], r[3]);
		fenceGate(p, -13, r[3], S);
		// carpets to climb the fence by: the four corners, the middle of the back and of both sides, and
		// either side of the gate at the front
		BlockState step = St.carpet(DyeColor.WHITE);
		int cx = -13;
		int cz = (r[1] + r[3]) / 2;
		for (int[] c : new int[][] {{r[0], r[1]}, {r[2], r[1]}, {r[0], r[3]}, {r[2], r[3]}, {cx, r[1]}, {r[0], cz}, {r[2], cz},
			{cx - 1, r[3]}, {cx + 1, r[3]}}) {
			p.set(c[0], FLOOR + 1, c[1], step);
		}
		for (int z = r[3] + 1; z <= r[3] + 2; z++) {
			p.set(-13, GROUND, z, Blocks.DIRT_PATH.defaultBlockState());
		}
		p.set(-16, FLOOR, r[3] + 1, Blocks.COMPOSTER.defaultBlockState());
		p.set(-10, FLOOR, r[3] + 1, Blocks.HAY_BLOCK.defaultBlockState());
	}

	/** Pens of the animal farm (interior cells, inclusive) and who lives there. */
	private static final int[][] PENS = {
		// x0, z0, x1, z1
		{3, -30, 12, -25},   // cows
		{14, -30, 23, -25},  // pigs
		{3, -23, 12, -18},   // sheep
		{14, -23, 23, -18},  // chickens
	};

	/**
	 * Survival worlds: the animal farm, east half of the strip behind the hall: four 10 x 6 pens in
	 * a spruce fence cross (cows and pigs at the back, sheep and chickens at the front), every pen
	 * with its own gate from outside, lanterns on the posts, and a carpet on every post where fence
	 * lines meet (the four corners, the four ends of the cross, its centre): a player can jump onto
	 * a carpet on a fence and so climb in or out of any pen anywhere, the animals cannot. Two of
	 * each animal are put in after the build ({@link #stockPens}); breeding is the player's.
	 */
	private static void animalFarm(Plan p) {
		int[] r = HqLandscape.ANIMAL_FARM;
		fenceRing(p, r[0], r[1], r[2], r[3]);
		for (int z = r[1]; z <= r[3]; z++) {
			fencePost(p, 13, z, z == -24);
		}
		for (int x = r[0]; x <= r[2]; x++) {
			fencePost(p, x, -24, false);
		}
		fenceGate(p, 7, r[3], S);
		fenceGate(p, 18, r[3], S);
		fenceGate(p, r[0], -27, W);
		fenceGate(p, r[2], -27, E);
		BlockState step = St.carpet(DyeColor.WHITE);
		for (int x : new int[] {r[0], 13, r[2]}) {
			for (int z : new int[] {r[1], -24, r[3]}) {
				p.set(x, FLOOR + 1, z, step);
			}
		}
		// nothing solid and nothing two blocks tall inside a pen: an animal standing on a block (or a
		// chicken fluttering up a tall plant) clears the fence, so the meadow's tall grass and large
		// ferns become short grass and anything else above the floor goes
		for (int[] pen : PENS) {
			for (int x = pen[0]; x <= pen[2]; x++) {
				for (int z = pen[1]; z <= pen[3]; z++) {
					if (!p.isAir(x, FLOOR + 1, z)) {
						p.set(x, FLOOR + 1, z, AIR);
						p.set(x, FLOOR, z, Blocks.SHORT_GRASS.defaultBlockState());
					}
				}
			}
		}
		for (int z = r[3] + 1; z <= r[3] + 2; z++) {
			p.set(7, GROUND, z, Blocks.DIRT_PATH.defaultBlockState());
			p.set(18, GROUND, z, Blocks.DIRT_PATH.defaultBlockState());
		}
	}

	/** A spruce fence round a rectangle, a lantern on every corner and every sixth post. */
	private static void fenceRing(Plan p, int x0, int z0, int x1, int z1) {
		for (int x = x0; x <= x1; x++) {
			boolean lit = x == x0 || x == x1 || (x - x0) % 6 == 0;
			fencePost(p, x, z0, lit);
			fencePost(p, x, z1, lit);
		}
		for (int z = z0 + 1; z <= z1 - 1; z++) {
			boolean lit = (z - z0) % 6 == 0;
			fencePost(p, x0, z, lit);
			fencePost(p, x1, z, lit);
		}
	}

	/** The farms stand on the meadow: fences, gates and the things by them sit on the grass top (GROUND), at FLOOR, not at the hall's FEET. */
	private static void fencePost(Plan p, int x, int z, boolean lantern) {
		p.set(x, GROUND, z, Blocks.GRASS_BLOCK.defaultBlockState());
		p.set(x, FLOOR, z, Blocks.SPRUCE_FENCE.defaultBlockState());
		p.set(x, FLOOR + 1, z, lantern ? LANTERN : AIR);
	}

	private static void fenceGate(Plan p, int x, int z, Direction facing) {
		p.set(x, GROUND, z, Blocks.GRASS_BLOCK.defaultBlockState());
		p.set(x, FLOOR, z, Blocks.SPRUCE_FENCE_GATE.defaultBlockState().setValue(FenceGateBlock.FACING, facing).setValue(FenceGateBlock.OPEN, false));
		p.set(x, FLOOR + 1, z, AIR);
	}

	/**
	 * Two of each animal in their pens, after the blocks are in: only what a pen is short of, so a
	 * rebuild never adds to a herd the player has bred. Returns how many were added.
	 */
	private static int stockPens(ServerLevel level, Plan p) {
		List<ResourceKey<EntityType<?>>> kinds = List.of(EntityTypeIds.COW, EntityTypeIds.PIG, EntityTypeIds.SHEEP, EntityTypeIds.CHICKEN);
		int added = 0;
		for (int i = 0; i < PENS.length; i++) {
			int[] pen = PENS[i];
			// the pens are meadow: animals stand on the grass top (GROUND) at FLOOR height, not the hall's FEET
			AABB box = new AABB(pen[0] + p.ox, FLOOR + p.oy - 1, pen[1] + p.oz, pen[2] + p.ox + 1, FLOOR + p.oy + 3, pen[3] + p.oz + 1);
			EntityType<?> kind = BuiltInRegistries.ENTITY_TYPE.getValueOrThrow(kinds.get(i));
			added += stock(level, kind, box, (pen[0] + pen[2]) / 2 + p.ox, FLOOR + p.oy, (pen[1] + pen[3]) / 2 + p.oz);
		}
		return added;
	}

	private static int stock(ServerLevel level, EntityType<?> kind, AABB box, int cx, int y, int cz) {
		int have = level.getEntities(kind, box, e -> true).size();
		int added = 0;
		for (int i = have; i < 2; i++) {
			Entity animal = kind.spawn(level, new BlockPos(cx - 1 + 2 * i, y, cz), EntitySpawnReason.EVENT);
			if (animal != null) {
				if (animal instanceof Mob mob) {
					mob.setPersistenceRequired();
				}
				added++;
			}
		}
		return added;
	}

	// ================================================================== cameras

	private static void cameras(Anchors.Builder a) {
		int az = AZ;
		// low 3/4 view from the south-south-east at golden hour: the sunset glows behind the west wing
		// with the sun itself out of frame, the garden is the foreground, the beacon cupola on top
		a.cameraLookAt("exterior_hero", 18, 72.5, 46, -3, 75.5, 10);
		a.cameraLookAt("night", 16.5, 71.5, 45.5, -2, 75.5, 11);
		// from inside the door, turned toward the podium: the hologram centred, Marlow waiting at the
		// right, the task wall out of frame (it was a sliced sliver at the left edge)
		a.cameraLookAt("entrance_atrium", AX - 1.5, FEET + 2.2, az + 6.8, AX + 2.0, FEET + 3.8, az - 2);
		// straight on and close: the board fills the frame
		a.cameraLookAt("task_wall", AX - 4.6, FEET + 3.0, az + 0.5, AX - 7.9, FEET + 3.0, az + 0.5);
		a.cameraLookAt("decision_podium", AX + 3.0, FEET + 1.5, az + 2.3, AX + 6.8, FEET + 1.9, az - 0.8);
		// standing height over the lounge toward the west end: desks, lounge, library each with its agent
		a.cameraLookAt("wide_interior", -5.0, FEET + 1.7, -4.6, -18.0, FEET + 1.0, -2.8);
		a.cameraLookAt("library", -14.0, FEET + 2.8, 4.0, -21.5, FEET + 1.2, -1.5);
		a.cameraLookAt("console", AX + 2.5, FEET + 2.2, az + 6.5, AX + 7.5, FEET + 1.0, az + 2.5);
		a.cameraLookAt("merge_station", 19.5, FEET + 1.6, 2.6, 24.0, FEET + 1.8, -0.2);
		a.cameraLookAt("testbench", 16.0, FEET + 2.6, -0.5, 23, FEET + 1.0, -4.5);
		a.cameraLookAt("lounge", -4.0, FEET + 3.0, -5.5, -11, FEET + 0.5, 1.0);
		a.cameraLookAt("hall", 0.5, FEET + 2.2, HZS + 2.0, 0.5, FEET + 2.0, HZN);
		// QA: the editing agent's desk in the showcase is Juniper's
		int jx = DESK_X[Math.max(0, deskIds().indexOf("juniper"))];
		// 3/4 front from beside the monitor (east of it, in front of the screen plane): Juniper's face,
		// turned to her screen, and the screen itself, both at about 45 degrees
		a.cameraLookAt("agent_desk", jx + 3.0, FEET + 1.9, HZN + 3.0, jx - 0.2, FEET + 1.6, HZN + 1.6);
	}
}
