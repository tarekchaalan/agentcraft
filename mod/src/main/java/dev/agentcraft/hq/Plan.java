package dev.agentcraft.hq;

import dev.agentcraft.block.DecisionPodiumBlock;
import dev.agentcraft.block.MergeStationBlock;
import dev.agentcraft.block.MonitorBlock;
import dev.agentcraft.block.PanelBlock;
import dev.agentcraft.block.StatusLampBlock;
import dev.agentcraft.block.entity.StationBlockEntity;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.IntFunction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CopperBulbBlock;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FarmlandBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LightEngine;
import net.minecraft.world.phys.AABB;
import org.jspecify.annotations.Nullable;

/**
 * The complete desired contents of an HQ site box, built in memory and then applied to the world as
 * a diff. That is what makes an HQ builder deterministic and idempotent:
 * <ul>
 *   <li>every cell of the box has a desired state (unset cells are the meadow: air above the
 *       ground, grass/dirt below), so a second build changes nothing; nothing outside the box is
 *       ever touched;</li>
 *   <li>only cells that differ are written, with no neighbour or shape updates (fast: no physics, no
 *       drops, no fluid flow);</li>
 *   <li>connecting blocks (stairs, panes, fences, walls, AgentCraft panels) are written in a second
 *       pass with their connections computed from the finished neighbours, and compared without
 *       those connection properties in the first pass, so they never flicker on a rebuild;</li>
 *   <li>state driven live by the Foreman (lamp status, podium open, merge station active, monitor
 *       lit, signal bulbs lit) is kept from the world when the block itself is unchanged;</li>
 *   <li>with the previous build's plan at hand ({@link PlanStore}), a cell the player changed since
 *       that build (it matches neither the old plan nor the new one) is <b>kept</b>, unless the
 *       build is forced: rebuilding never wipes the player's own additions.</li>
 * </ul>
 * The ground is a heightmap ({@link #top}): {@code HqLandscape} shapes it before anything is built.
 */
final class Plan {
	/** Write without neighbour updates, shape updates, drops, block-entity side effects or onPlace. */
	static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS
		| Block.UPDATE_SKIP_BLOCK_ENTITY_SIDEEFFECTS | Block.UPDATE_SKIP_ON_PLACE;
	private static final Set<String> CONNECTION_PROPS = Set.of("shape", "north", "east", "south", "west", "up", "down", "left", "right",
		"in_wall");

	final int minX;
	final int minY;
	final int minZ;
	final int maxX;
	final int maxY;
	final int maxZ;
	/** World = design + offset: the plan is drawn at its design origin and applied at the HQ site. */
	final int ox;
	final int oy;
	final int oz;
	private final int sx;
	private final int sy;
	private final int sz;
	/** Design state per cell; null = left to the world (a column kept natural at the site's edge). */
	private final @Nullable BlockState[] cells;
	private final int[] top;
	/** Columns left entirely to the world: nothing is planned or written there. */
	private final BitSet keptColumns = new BitSet();
	/** Kept columns whose tree (if any) is felled when the plan is applied: logs, leaves and vines above the world's ground go. */
	private final BitSet canopyColumns = new BitSet();
	/** The world's own ground before the build (design y of the top ground block per column), when sampled. */
	private int @Nullable [] naturalTop;
	private boolean @Nullable [] naturalWater;
	private boolean @Nullable [] naturalClear;
	/** The plan is for survival play (farms, a bedroom, lighting; the landscape levels ground for them). */
	private boolean survival;
	/** Bindings in design coordinates, translated when applied. */
	private final Map<BlockPos, String> bindings = new LinkedHashMap<>();

	/**
	 * @param changed cells written in the first pass; {@code connected}: cells whose connections
	 *     changed in the second pass; {@code kept}: cells the player changed since the last build that
	 *     were left alone; {@code foreign}: changed cells that held something neither the old nor the
	 *     new plan has (a forced build, or no record of the previous build); {@code items}: dropped
	 *     items / orbs removed from the box.
	 */
	record Stats(int cells, int changed, int connected, int bound, int kept, int foreign, int items, long micros, List<String> sample,
		List<String> keptSample) {
	}

	/** @param ground desired state of an unset cell by y (the meadow profile); the ground top is {@code groundTop}. */
	Plan(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int groundTop, IntFunction<BlockState> ground) {
		this(minX, minY, minZ, maxX, maxY, maxZ, groundTop, ground, 0, 0, 0);
	}

	/**
	 * A plan drawn in design coordinates (the box, {@code groundTop} and every {@code set} call) that
	 * is applied to the world at {@code (ox, oy, oz)} + design: cell (x, y, z) of the plan is world
	 * block (x + ox, y + oy, z + oz).
	 */
	Plan(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, int groundTop, IntFunction<BlockState> ground, int ox, int oy, int oz) {
		this.minX = minX;
		this.minY = minY;
		this.minZ = minZ;
		this.maxX = maxX;
		this.maxY = maxY;
		this.maxZ = maxZ;
		this.ox = ox;
		this.oy = oy;
		this.oz = oz;
		this.sx = maxX - minX + 1;
		this.sy = maxY - minY + 1;
		this.sz = maxZ - minZ + 1;
		this.cells = new BlockState[sx * sy * sz];
		this.top = new int[sx * sz];
		java.util.Arrays.fill(top, groundTop);
		for (int y = minY; y <= maxY; y++) {
			BlockState g = ground.apply(y);
			for (int x = minX; x <= maxX; x++) {
				for (int z = minZ; z <= maxZ; z++) {
					cells[index(x, y, z)] = g;
				}
			}
		}
	}

	int size() {
		return cells.length;
	}

	/** The design, one state per cell (null where the plan leaves the world alone). */
	@Nullable BlockState[] cells() {
		return cells;
	}

	/** The box this plan writes, in world coordinates ({@code minX, minY, minZ, maxX, maxY, maxZ}). */
	int[] worldBox() {
		return new int[] {minX + ox, minY + oy, minZ + oz, maxX + ox, maxY + oy, maxZ + oz};
	}

	boolean in(int x, int y, int z) {
		return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
	}

	boolean inXZ(int x, int z) {
		return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
	}

	private int index(int x, int y, int z) {
		return ((y - minY) * sz + (z - minZ)) * sx + (x - minX);
	}

	private int column(int x, int z) {
		return (z - minZ) * sx + (x - minX);
	}

	BlockState get(int x, int y, int z) {
		if (!in(x, y, z)) {
			return Blocks.AIR.defaultBlockState();
		}
		BlockState s = cells[index(x, y, z)];
		return s == null ? Blocks.AIR.defaultBlockState() : s;
	}

	boolean isAir(int x, int y, int z) {
		return get(x, y, z).isAir();
	}

	/** Writes a design cell; a kept column ({@link #keepColumn}) and anything outside the box are ignored. */
	void set(int x, int y, int z, BlockState state) {
		if (in(x, y, z) && !keptColumns.get(column(x, z))) {
			cells[index(x, y, z)] = state;
		}
	}

	// ------------------------------------------------------------------ the world's own terrain

	/**
	 * Leaves column (x, z) entirely to the world: every cell becomes "no opinion" and later writes to
	 * it are ignored. Used for the outermost columns of a site in a natural world, so the plan meets
	 * the terrain exactly where the terrain is.
	 */
	void keepColumn(int x, int z) {
		if (!inXZ(x, z)) {
			return;
		}
		keptColumns.set(column(x, z));
		for (int y = minY; y <= maxY; y++) {
			cells[index(x, y, z)] = null;
		}
	}

	boolean isKept(int x, int z) {
		return inXZ(x, z) && keptColumns.get(column(x, z));
	}

	/**
	 * The one write a kept column accepts: a single cell, for the hidden lights that keep mobs from
	 * spawning around a survival site. Everything else in the column stays the world's.
	 */
	void setInKept(int x, int y, int z, BlockState state) {
		if (in(x, y, z) && keptColumns.get(column(x, z))) {
			cells[index(x, y, z)] = state;
		}
	}

	/**
	 * Marks a kept column for felling: when the plan is applied, every log, leaf, vine or mushroom
	 * block above the world's own ground in it is removed (before the plan's own writes), the ground
	 * and everything else stay the world's. Ignored for columns the plan writes (those are rebuilt
	 * from the ground up anyway).
	 */
	void clearCanopy(int x, int z) {
		if (inXZ(x, z) && keptColumns.get(column(x, z))) {
			canopyColumns.set(column(x, z));
		}
	}

	boolean canopyCleared(int x, int z) {
		return inXZ(x, z) && canopyColumns.get(column(x, z));
	}

	/** What felling removes: the parts of a tree (or huge mushroom) above the ground. */
	private static boolean canopy(BlockState s) {
		return s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(Blocks.VINE) || s.is(Blocks.BEE_NEST) || s.is(Blocks.COCOA)
			|| s.is(Blocks.MANGROVE_ROOTS) || s.is(Blocks.BROWN_MUSHROOM_BLOCK) || s.is(Blocks.RED_MUSHROOM_BLOCK) || s.is(Blocks.MUSHROOM_STEM);
	}

	/**
	 * Fells the trees in the marked columns ({@link #clearCanopy}): world writes with shape updates
	 * on (so leaves left standing outside the clearing learn they lost their trunk and decay) but
	 * without drops. Returns the number of blocks removed.
	 */
	private int fell(ServerLevel level, BlockPos.MutableBlockPos m, List<String> sample) {
		if (canopyColumns.isEmpty() || naturalTop == null) {
			return 0;
		}
		int felled = 0;
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int c = canopyColumns.nextSetBit(0); c >= 0; c = canopyColumns.nextSetBit(c + 1)) {
			int x = minX + c % sx;
			int z = minZ + c / sx;
			for (int y = Math.max(minY, naturalTop[c] + 1); y <= maxY; y++) {
				m.set(x + ox, y + oy, z + oz);
				BlockState cur = level.getBlockState(m);
				if (canopy(cur)) {
					note(sample, m, cur, air);
					level.setBlock(m, air, Block.UPDATE_ALL | Block.UPDATE_SUPPRESS_DROPS);
					felled++;
				}
			}
		}
		return felled;
	}

	void setSurvival(boolean survival) {
		this.survival = survival;
	}

	boolean survival() {
		return survival;
	}

	/**
	 * The world's ground as it was before the build, sampled per column in design coordinates:
	 * {@code tops[column]} = design y of the highest ground block (or of the water surface where the
	 * column is under water), {@code water[column]} = the column is under water,
	 * {@code clear[column]} = the cell above the top is air or a plant (something a light block may
	 * replace). All arrays are indexed like {@link #column}, i.e. {@code (z - minZ) * sizeX + (x - minX)}.
	 */
	void setNatural(int[] tops, boolean[] water, boolean[] clear) {
		if (tops.length != sx * sz || water.length != sx * sz || clear.length != sx * sz) {
			throw new IllegalArgumentException("natural terrain sample has " + tops.length + " columns, the plan " + (sx * sz));
		}
		this.naturalTop = tops;
		this.naturalWater = water;
		this.naturalClear = clear;
	}

	boolean hasNatural() {
		return naturalTop != null;
	}

	/** Design y of the world's own ground top in column (x, z); the meadow default outside the box or without a sample. */
	int naturalTop(int x, int z) {
		return naturalTop != null && inXZ(x, z) ? naturalTop[column(x, z)] : StudioHqBuilder.GROUND;
	}

	boolean naturalWater(int x, int z) {
		return naturalWater != null && inXZ(x, z) && naturalWater[column(x, z)];
	}

	boolean naturalClearAbove(int x, int z) {
		return naturalClear != null && inXZ(x, z) && naturalClear[column(x, z)];
	}

	int sizeX() {
		return sx;
	}

	int sizeZ() {
		return sz;
	}

	void set(int x, int y, int z, Block block) {
		set(x, y, z, block.defaultBlockState());
	}

	/** Only where the plan currently has air. */
	void setIfAir(int x, int y, int z, BlockState state) {
		if (isAir(x, y, z)) {
			set(x, y, z, state);
		}
	}

	void fill(int x0, int y0, int z0, int x1, int y1, int z1, BlockState state) {
		for (int x = Math.min(x0, x1); x <= Math.max(x0, x1); x++) {
			for (int y = Math.min(y0, y1); y <= Math.max(y0, y1); y++) {
				for (int z = Math.min(z0, z1); z <= Math.max(z0, z1); z++) {
					set(x, y, z, state);
				}
			}
		}
	}

	void fill(int x0, int y0, int z0, int x1, int y1, int z1, Block block) {
		fill(x0, y0, z0, x1, y1, z1, block.defaultBlockState());
	}

	/** Ground top (the grass block's y) of column (x, z); outside the box: the meadow default. */
	int top(int x, int z) {
		return inXZ(x, z) ? top[(z - minZ) * sx + (x - minX)] : StudioHqBuilder.GROUND;
	}

	void setTop(int x, int z, int y) {
		if (inXZ(x, z)) {
			top[(z - minZ) * sx + (x - minX)] = y;
		}
	}

	/** Binding for the station block entity at design cell (x, y, z), applied after the blocks. */
	void bind(int x, int y, int z, String binding) {
		bindings.put(new BlockPos(x, y, z), binding);
	}

	/** The highest non-air design cell of column (x, z), or {@code minY - 1} when the column is all air or kept. */
	int highest(int x, int z) {
		if (!inXZ(x, z) || keptColumns.get(column(x, z))) {
			return minY - 1;
		}
		for (int y = maxY; y >= minY; y--) {
			BlockState s = cells[index(x, y, z)];
			if (s != null && !s.isAir()) {
				return y;
			}
		}
		return minY - 1;
	}

	/**
	 * Vanilla's grass rule applied to the plan (SpreadingSnowyBlock): grass under a full fluid or a
	 * block that stops light from its top face becomes dirt, so the world never "decays" between two
	 * builds (which would make a rebuild change cells).
	 */
	void settleGrass() {
		BlockState grass = Blocks.GRASS_BLOCK.defaultBlockState();
		for (int y = minY; y < maxY; y++) {
			for (int z = minZ; z <= maxZ; z++) {
				for (int x = minX; x <= maxX; x++) {
					BlockState s = cells[index(x, y, z)];
					if (s == null) {
						continue;
					}
					boolean isGrass = s.is(Blocks.GRASS_BLOCK);
					if (!isGrass && !s.is(Blocks.DIRT)) {
						continue;
					}
					BlockState above = cells[index(x, y + 1, z)];
					if (above == null) {
						continue;
					}
					boolean lives = !above.getFluidState().isFull()
						&& LightEngine.getLightDampeningInto(grass, above, Direction.UP, above.getLightDampening()) < 15;
					// grass that cannot live decays to dirt; dirt that grass could live on is eventually
					// grown over by a neighbour: both are settled here, so a rebuild never finds them changed
					if (isGrass && !lives) {
						cells[index(x, y, z)] = Blocks.DIRT.defaultBlockState();
					} else if (!isGrass && lives) {
						cells[index(x, y, z)] = grass;
					}
				}
			}
		}
	}

	// ------------------------------------------------------------------ apply

	private static boolean connecting(BlockState s) {
		Block b = s.getBlock();
		return b instanceof StairBlock || b instanceof CrossCollisionBlock || b instanceof WallBlock || b instanceof FenceGateBlock
			|| b instanceof PanelBlock;
	}

	/**
	 * Properties the Foreman drives (or that a redstone-less bulb toggles), and the ones a player
	 * toggles by using the block (a door swung open, a furnace burning): never part of the design.
	 */
	private static boolean drivenProp(Block b, Property<?> p) {
		return b instanceof StatusLampBlock && p == StatusLampBlock.STATUS || b instanceof DecisionPodiumBlock && p == DecisionPodiumBlock.OPEN
			|| b instanceof MergeStationBlock && p == MergeStationBlock.ACTIVE || b instanceof MonitorBlock && p == MonitorBlock.LIT
			|| b instanceof CopperBulbBlock && (p == CopperBulbBlock.LIT || p == CopperBulbBlock.POWERED)
			|| b instanceof DoorBlock && (p == DoorBlock.OPEN || p == DoorBlock.POWERED)
			|| b instanceof FenceGateBlock && (p == FenceGateBlock.OPEN || p == FenceGateBlock.POWERED)
			|| b instanceof AbstractFurnaceBlock && p == AbstractFurnaceBlock.LIT
			|| b instanceof CropBlock && p == CropBlock.AGE
			|| b instanceof FarmlandBlock && p == FarmlandBlock.MOISTURE;
	}

	/** Same block and same design: ignores connection properties and Foreman-driven properties. */
	static boolean sameDesign(BlockState a, BlockState b) {
		if (a == b) {
			return true;
		}
		if (a.getBlock() != b.getBlock()) {
			return false;
		}
		Block blk = b.getBlock();
		for (Property<?> p : b.getProperties()) {
			if (CONNECTION_PROPS.contains(p.getName()) || drivenProp(blk, p)) {
				continue;
			}
			if (!a.getValue(p).equals(b.getValue(p))) {
				return false;
			}
		}
		return true;
	}

	/** Same block and same properties, ignoring connection properties. */
	private static boolean sameIgnoringConnections(BlockState a, BlockState b) {
		if (a.getBlock() != b.getBlock()) {
			return false;
		}
		for (Property<?> p : b.getProperties()) {
			if (!CONNECTION_PROPS.contains(p.getName()) && !a.getValue(p).equals(b.getValue(p))) {
				return false;
			}
		}
		return true;
	}

	/** Keep the live, Foreman-driven property values of an unchanged station block. */
	private static BlockState keepDriven(BlockState current, BlockState desired) {
		if (current.getBlock() != desired.getBlock()) {
			return desired;
		}
		Block b = desired.getBlock();
		if (b instanceof StatusLampBlock) {
			return desired.setValue(StatusLampBlock.STATUS, current.getValue(StatusLampBlock.STATUS));
		}
		if (b instanceof DecisionPodiumBlock) {
			return desired.setValue(DecisionPodiumBlock.OPEN, current.getValue(DecisionPodiumBlock.OPEN));
		}
		if (b instanceof MergeStationBlock) {
			return desired.setValue(MergeStationBlock.ACTIVE, current.getValue(MergeStationBlock.ACTIVE));
		}
		if (b instanceof MonitorBlock) {
			return desired.setValue(MonitorBlock.LIT, current.getValue(MonitorBlock.LIT));
		}
		if (b instanceof CopperBulbBlock) {
			return desired.setValue(CopperBulbBlock.LIT, current.getValue(CopperBulbBlock.LIT));
		}
		if (b instanceof DoorBlock) {
			return desired.setValue(DoorBlock.OPEN, current.getValue(DoorBlock.OPEN)).setValue(DoorBlock.POWERED, current.getValue(DoorBlock.POWERED));
		}
		if (b instanceof AbstractFurnaceBlock) {
			return desired.setValue(AbstractFurnaceBlock.LIT, current.getValue(AbstractFurnaceBlock.LIT));
		}
		if (b instanceof FenceGateBlock) {
			return desired.setValue(FenceGateBlock.OPEN, current.getValue(FenceGateBlock.OPEN)).setValue(FenceGateBlock.POWERED,
				current.getValue(FenceGateBlock.POWERED));
		}
		if (b instanceof CropBlock) {
			return desired.setValue(CropBlock.AGE, current.getValue(CropBlock.AGE));
		}
		if (b instanceof FarmlandBlock) {
			return desired.setValue(FarmlandBlock.MOISTURE, current.getValue(FarmlandBlock.MOISTURE));
		}
		return desired;
	}

	/**
	 * Applies the plan to the world (server thread).
	 *
	 * @param previous the plan applied by the last build of this builder (same box), or null when
	 *     there is no record of it: then every cell is set to the plan.
	 * @param force set every cell to the plan even where the player changed it.
	 */
	Stats apply(ServerLevel level, BlockState @Nullable [] previous, boolean force) {
		long t0 = System.nanoTime();
		int changed = 0;
		int kept = 0;
		int foreign = 0;
		List<String> sample = new ArrayList<>();
		List<String> keptSample = new ArrayList<>();
		BitSet keptCells = new BitSet();
		List<BlockPos> deferred = new ArrayList<>();
		boolean guard = previous != null && previous.length == cells.length && !force;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		// the world's trees in the farm clearing come down first, so a light written above a felled trunk's ground stays
		changed += fell(level, m, sample);
		// chunks and block positions are world coordinates; cells are indexed in design coordinates
		int wMinX = minX + ox;
		int wMaxX = maxX + ox;
		int wMinZ = minZ + oz;
		int wMaxZ = maxZ + oz;
		for (int cx = wMinX >> 4; cx <= wMaxX >> 4; cx++) {
			for (int cz = wMinZ >> 4; cz <= wMaxZ >> 4; cz++) {
				LevelChunk chunk = level.getChunk(cx, cz);
				int x0 = Math.max(wMinX, cx << 4);
				int x1 = Math.min(wMaxX, (cx << 4) + 15);
				int z0 = Math.max(wMinZ, cz << 4);
				int z1 = Math.min(wMaxZ, (cz << 4) + 15);
				for (int y = minY; y <= maxY; y++) {
					for (int wz = z0; wz <= z1; wz++) {
						for (int wx = x0; wx <= x1; wx++) {
							int i = index(wx - ox, y, wz - oz);
							BlockState want = cells[i];
							if (want == null) {
								continue; // the world's own: never compared, never written
							}
							m.set(wx, y + oy, wz);
							BlockState cur = chunk.getBlockState(m);
							if (cur != want && !sameDesign(cur, want)) {
								if (guard && !sameDesign(cur, previous[i])) {
									// the player changed this cell after the last build: leave it (terrain that only
									// reacted to a player's block, e.g. grass turned to dirt under it, is kept silently)
									if (!(natural(cur) && natural(previous[i]))) {
										kept++;
									}
									keptCells.set(i);
									if (keptSample.size() < 8) {
										keptSample.add(m.toShortString() + " " + cur.getBlock().getDescriptionId().replace("block.minecraft.", ""));
									}
									continue;
								}
								if (!cur.isAir() && (previous == null || !sameDesign(cur, previous[i])) && !natural(cur)) {
									foreign++;
								}
							}
							if (connecting(want)) {
								deferred.add(m.immutable());
								want = keepDriven(cur, want);
								if (!sameIgnoringConnections(cur, want)) {
									note(sample, m, cur, want);
									level.setBlock(m, want, FLAGS);
									changed++;
								}
								continue;
							}
							want = keepDriven(cur, want);
							if (cur != want) {
								note(sample, m, cur, want);
								level.setBlock(m, want, FLAGS);
								changed++;
							}
						}
					}
				}
			}
		}
		// connections from the finished neighbours
		int connected = 0;
		for (BlockPos p : deferred) {
			BlockState cur = level.getBlockState(p);
			BlockState want = Block.updateFromNeighbourShapes(keepDriven(cur, cells[index(p.getX() - ox, p.getY() - oy, p.getZ() - oz)]), level, p);
			if (cur != want) {
				note(sample, p, cur, want);
				level.setBlock(p, want, FLAGS);
				connected++;
			}
		}
		int bound = 0;
		for (var e : bindings.entrySet()) {
			BlockPos d = e.getKey();
			if (in(d.getX(), d.getY(), d.getZ()) && keptCells.get(index(d.getX(), d.getY(), d.getZ()))) {
				continue;
			}
			BlockPos p = d.offset(ox, oy, oz);
			if (level.getBlockEntity(p) instanceof StationBlockEntity be) {
				if (!be.binding().equals(e.getValue())) {
					bound++;
				}
				be.setBinding(e.getValue());
			}
		}
		int items = clearDrops(level);
		return new Stats(cells.length, changed, connected, bound, kept, foreign, items, (System.nanoTime() - t0) / 1000, sample, keptSample);
	}

	/** Terrain, plants and fluids: replacing them is not "replacing something the player built". */
	private static boolean natural(BlockState s) {
		return s.is(Blocks.GRASS_BLOCK) || s.is(Blocks.DIRT) || s.is(Blocks.STONE) || s.is(Blocks.SHORT_GRASS) || s.is(Blocks.TALL_GRASS)
			|| s.is(Blocks.WATER) || s.is(Blocks.DIRT_PATH) || s.getBlock() instanceof net.minecraft.world.level.block.VegetationBlock
			|| s.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock;
	}

	/**
	 * Removes dropped items and experience orbs inside the box (a switch from another builder can
	 * leave items behind, e.g. carpets that lost their floor). Players, agents and other mobs stay.
	 */
	private int clearDrops(ServerLevel level) {
		AABB box = new AABB(minX + ox, minY + oy, minZ + oz, maxX + ox + 1, maxY + oy + 1, maxZ + oz + 1);
		int n = 0;
		for (ItemEntity e : level.getEntitiesOfClass(ItemEntity.class, box)) {
			e.discard();
			n++;
		}
		for (ExperienceOrb e : level.getEntitiesOfClass(ExperienceOrb.class, box)) {
			e.discard();
			n++;
		}
		return n;
	}

	private static void note(List<String> sample, BlockPos p, BlockState from, BlockState to) {
		if (sample.size() < 6) {
			sample.add(p.toShortString() + " " + from + " -> " + to);
		}
	}
}
