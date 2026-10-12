package dev.agentcraft.hq;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.world.HqSite;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.VegetationBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.jspecify.annotations.Nullable;

/**
 * The world's own terrain, for building the HQ into a natural world: reads the ground under every
 * column of a site ({@link #sample}) and picks where the HQ goes ({@link #findSite}: the flattest,
 * driest ground near the world spawn). Chunks that do not exist yet are generated on the way
 * (server thread).
 */
final class HqTerrain {
	/** Footprint the site finder judges, in design coordinates: the hall, the atrium, the court, the pond and the garden. */
	private static final int CORE_X0 = -32;
	private static final int CORE_X1 = 32;
	private static final int CORE_Z0 = -16;
	private static final int CORE_Z1 = 48;
	/** Spacing of the height lattice and of the candidate origins (both multiples of it, so columns are shared). */
	private static final int STEP = 8;
	/** How far from the spawn candidates are tried, in rings: first the near ring, the far one only when nothing near is good enough. */
	private static final int[] RADII = {64, 128};

	private HqTerrain() {
	}

	/**
	 * World y of the ground in a column, whether it lies under water (then {@code top} is the water
	 * surface), and whether the cell above the top is air or a plant (a hidden light may take it).
	 */
	record Column(int top, boolean water, boolean clearAbove) {
	}

	/** Reads the world's ground under every column of the plan's box into the plan ({@link Plan#setNatural}). */
	static void sample(ServerLevel level, Plan p) {
		int sx = p.sizeX();
		int sz = p.sizeZ();
		int[] tops = new int[sx * sz];
		boolean[] water = new boolean[sx * sz];
		boolean[] clear = new boolean[sx * sz];
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int z = p.minZ; z <= p.maxZ; z++) {
			for (int x = p.minX; x <= p.maxX; x++) {
				Column c = column(level, x + p.ox, z + p.oz, m);
				int i = (z - p.minZ) * sx + (x - p.minX);
				tops[i] = c.top() - p.oy;
				water[i] = c.water();
				clear[i] = c.clearAbove();
			}
		}
		p.setNatural(tops, water, clear);
	}

	/**
	 * The ground of world column (wx, wz): the highest block that is neither air, plant, log,
	 * leaves, snow layer nor fluid. Under water the water surface counts instead, so a shore blends
	 * to the water level rather than to the lake bed.
	 *
	 * <p>The column is walked block by block from the top of the chunk's highest filled section:
	 * in 26.3 the motion-blocking heightmaps are client-side only ({@code Heightmap.Usage.CLIENT}),
	 * so on the server they read as empty (every column at the bottom of the world, which is where
	 * the first survival HQ was built). Loading the chunk generates it when needed.
	 */
	static Column column(ServerLevel level, int wx, int wz, BlockPos.MutableBlockPos m) {
		ChunkAccess chunk = level.getChunk(wx >> 4, wz >> 4);
		int min = level.getMinY();
		int topSection = chunk.getHighestFilledSectionIndex();
		int y = topSection < 0 ? min
			: Math.min(level.getMaxY(), SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(topSection)) + 15);
		boolean water = false;
		int surface = y;
		while (y > min) {
			m.set(wx, y, wz);
			BlockState s = chunk.getBlockState(m);
			if (!s.getFluidState().isEmpty()) {
				if (!water) {
					water = true;
					surface = y;
				}
				y--;
				continue;
			}
			if (s.isAir() || cover(s)) {
				y--;
				continue;
			}
			break;
		}
		int top = water ? surface : y;
		boolean clearAbove = false;
		if (top + 1 <= level.getMaxY()) {
			m.set(wx, top + 1, wz);
			BlockState above = chunk.getBlockState(m);
			clearAbove = above.isAir() || above.is(BlockTags.REPLACEABLE) || above.is(BlockTags.FLOWERS) || above.is(BlockTags.SAPLINGS);
		}
		return new Column(top, water, clearAbove);
	}

	/** Things that grow or lie on the ground rather than being it. */
	private static boolean cover(BlockState s) {
		return s.is(BlockTags.LOGS) || s.is(BlockTags.LEAVES) || s.is(BlockTags.REPLACEABLE) || s.is(BlockTags.REPLACEABLE_BY_TREES)
			|| s.is(BlockTags.SAPLINGS) || s.is(BlockTags.FLOWERS) || s.is(Blocks.SNOW) || s.is(Blocks.POWDER_SNOW) || s.is(Blocks.BAMBOO)
			|| s.is(Blocks.CACTUS) || s.is(Blocks.SUGAR_CANE) || s.is(Blocks.PUMPKIN) || s.is(Blocks.MELON) || s.is(Blocks.COBWEB)
			|| s.getBlock() instanceof VegetationBlock;
	}

	// ------------------------------------------------------------------ choosing the site

	/** A judged candidate origin. {@code ground} is the world y the plan's meadow (design {@code GROUND}) would sit at. */
	private record Candidate(int x, int z, int ground, double score, boolean good, String why) {
	}

	/**
	 * Where to put the HQ in a natural world: the candidate origin near the world spawn whose core
	 * footprint is flattest and driest. Candidates lie on a lattice around the spawn; each is judged
	 * on the heights of the lattice columns inside its footprint (median = the ground level, mean
	 * deviation and range = how much terrain the build has to move, water = what it would have to
	 * fill). The near ring is enough almost always; the far ring is only generated when nothing near
	 * is good, and when nothing at all is good the least bad candidate is taken with a warning.
	 */
	static HqSite findSite(ServerLevel level) {
		long t0 = System.nanoTime();
		BlockPos spawn = level.getRespawnData().pos();
		Map<Long, Column> cache = new HashMap<>();
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		Candidate best = null;
		int tried = 0;
		for (int radius : RADII) {
			List<Candidate> ring = new ArrayList<>();
			for (int dx = -radius; dx <= radius; dx += STEP) {
				for (int dz = -radius; dz <= radius; dz += STEP) {
					if (radius > RADII[0] && Math.abs(dx) <= RADII[0] && Math.abs(dz) <= RADII[0]) {
						continue; // judged in the near ring already
					}
					ring.add(judge(level, spawn.getX() + dx, spawn.getZ() + dz, spawn, cache, m));
					tried++;
				}
			}
			for (Candidate c : ring) {
				if (best == null || c.good() && !best.good() || c.good() == best.good() && c.score() < best.score()) {
					best = c;
				}
			}
			if (best != null && best.good()) {
				break;
			}
		}
		if (best == null) {
			throw new IllegalStateException("no HQ site candidates");
		}
		HqSite site = new HqSite(best.x(), best.ground(), best.z());
		String how = String.format(Locale.ROOT, "%d candidates, %d columns read, %d ms", tried, cache.size(), (System.nanoTime() - t0) / 1_000_000);
		if (best.good()) {
			AgentCraft.LOGGER.info("HQ site: {} ({} from spawn {}; {}; {})", site, distance(best, spawn), spawn.toShortString(), best.why(), how);
		} else {
			AgentCraft.LOGGER.warn("HQ site: no flat dry ground within {} blocks of spawn {}; using the least bad spot {} ({}; {})",
				RADII[RADII.length - 1], spawn.toShortString(), site, best.why(), how);
		}
		return site;
	}

	private static int distance(Candidate c, BlockPos spawn) {
		return (int) Math.round(Math.hypot(c.x() - spawn.getX(), c.z() - spawn.getZ()));
	}

	private static Candidate judge(ServerLevel level, int x, int z, BlockPos spawn, Map<Long, Column> cache, BlockPos.MutableBlockPos m) {
		List<Integer> tops = new ArrayList<>();
		int waterColumns = 0;
		int maxWater = Integer.MIN_VALUE;
		for (int dx = CORE_X0; dx <= CORE_X1; dx += STEP) {
			for (int dz = CORE_Z0; dz <= CORE_Z1; dz += STEP) {
				Column c = cached(level, x + dx, z + dz, cache, m);
				tops.add(c.top());
				if (c.water()) {
					waterColumns++;
					maxWater = Math.max(maxWater, c.top());
				}
			}
		}
		int[] hs = tops.stream().mapToInt(Integer::intValue).toArray();
		Arrays.sort(hs);
		int median = hs[hs.length / 2];
		double dev = 0;
		for (int h : hs) {
			dev += Math.abs(h - median);
		}
		dev /= hs.length;
		int range = hs[hs.length - 1] - hs[0];
		double waterShare = waterColumns / (double) hs.length;
		// the whole site box, more loosely: a cliff just outside the core would still meet the blend
		// band as a stone face, and the box only reaches 16 below the meadow
		int outerLo = Integer.MAX_VALUE;
		int outerHi = Integer.MIN_VALUE;
		int[] box = StudioHqBuilder.SITE;
		for (int dx = box[0] + 2; dx <= box[3] - 2; dx += STEP) {
			for (int dz = box[2] + 2; dz <= box[5] - 2; dz += STEP) {
				int t = cached(level, x + dx, z + dz, cache, m).top();
				outerLo = Math.min(outerLo, t);
				outerHi = Math.max(outerHi, t);
			}
		}
		// the meadow must sit above any water the plan keeps at its edge, or the water runs in
		int ground = waterColumns > 0 ? Math.max(median, maxWater + 1) : median;
		int lift = ground - median;
		int outerDrop = ground - outerLo;
		int outerRise = outerHi - ground;
		double dist = Math.hypot(x - spawn.getX(), z - spawn.getZ());
		boolean fits = ground - 20 > level.getMinY() && ground + 40 < level.getMaxY();
		boolean good = fits && waterShare <= 0.06 && range <= 20 && dev <= 3.5 && lift <= 3 && outerDrop <= 14 && outerRise <= 30;
		double score = dev + range * 0.08 + waterShare * 40 + lift * 0.5 + Math.max(0, outerDrop - 6) * 0.3 + Math.max(0, outerRise - 12) * 0.1
			+ dist / 300.0 + (fits ? 0 : 1000);
		String why = String.format(Locale.ROOT, "ground y=%d, core: mean deviation %.1f, range %d, water %.0f%%; box: %d below to %d above",
			ground, dev, range, waterShare * 100, outerDrop, outerRise);
		return new Candidate(x, z, ground, score, good, why);
	}

	private static Column cached(ServerLevel level, int wx, int wz, Map<Long, Column> cache, BlockPos.MutableBlockPos m) {
		return cache.computeIfAbsent(BlockPos.asLong(wx, 0, wz), k -> column(level, wx, wz, m));
	}

	/** For logs and QA: the plan-relative description of a site, or "origin" for the studio meadow. */
	static String describe(@Nullable HqSite site) {
		return site == null ? "origin (flat meadow)" : "site " + site;
	}
}
