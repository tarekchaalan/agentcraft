package dev.agentcraft.client.agents;

import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jspecify.annotations.Nullable;

/**
 * Seats come from the world at an anchor, never from hard-coded positions: when the block at a
 * station anchor's feet position ({@code desk_<id>}, a lounge sofa, a meeting chair ...) is a seat,
 * the agent sits on it facing the anchor's yaw. A seat is a bottom-half stairs block (the classic
 * Minecraft chair; its back should face away from the desk), a bottom slab, or any block whose
 * collision top is 0.3-0.7 blocks high (a custom chair/stool). Without a seat the agent stands.
 *
 * <p>Agents walk to a free cell next to the seat ({@link Seat#approach}) and then step onto the
 * sit point, which is the seat block's centre pulled a little forward (in front of a stairs back).
 * The block in front of the sitter is the desk: its top decides how far the typing arms reach.
 * Results are cached per anchor and checked again every few seconds (the HQ can be rebuilt).
 */
public final class Seats {
	/** Hip-to-thigh-underside height of the seated model (blocks, model scale 0.9375). */
	public static final double THIGH = 0.586;
	private static final int RECHECK_TICKS = 60;

	/**
	 * A seat at an anchor. {@code sit}: where the agent's feet position goes (x/z of the sit point,
	 * y = the anchor's feet height); {@code drop}: vertical model offset while seated (blocks);
	 * {@code approach}: walkable feet position next to the seat (null = walk straight to sit);
	 * {@code deskTop}: absolute y of the surface in front of the sitter (NaN = nothing there).
	 */
	public record Seat(Anchor anchor, Vec3 sit, double seatTop, double drop, @Nullable Vec3 approach, double deskTop) {
		/** The target the agent walks to: the sit point with the anchor's facing. */
		public Anchor target() {
			return new Anchor(anchor.name(), sit.x, sit.y, sit.z, anchor.yaw(), anchor.pitch());
		}
	}

	private record Cached(@Nullable Seat seat, double x, double y, double z, float yaw, long tick) {
	}

	private final Map<String, Cached> cache = new HashMap<>();

	public void clear() {
		cache.clear();
	}

	/** The seat at {@code a} (cached), or null when the agent stands there. */
	public @Nullable Seat at(BlockGetter level, Anchor a, long tick, @Nullable GridPathfinder pathfinder) {
		return at(level, a, tick, pathfinder, Anchors.current());
	}

	/** The seat at {@code a}, resolving fallback chair anchors from this studio's layout. */
	public @Nullable Seat at(BlockGetter level, Anchor a, long tick, @Nullable GridPathfinder pathfinder, Anchors.Layout layout) {
		if (!seatable(a.name())) {
			return null;
		}
		Cached c = cache.get(a.name());
		if (c != null && c.x == a.x() && c.y == a.y() && c.z == a.z() && c.yaw == a.yaw() && tick - c.tick < RECHECK_TICKS && tick >= c.tick) {
			return c.seat;
		}
		Seat s = detect(level, a, pathfinder);
		if (s == null && a.name().startsWith(AnchorNames.DESK_PREFIX)) {
			s = deskChair(level, a, pathfinder, layout);
		}
		cache.put(a.name(), new Cached(s, a.x(), a.y(), a.z(), a.yaw(), tick));
		return s;
	}

	/**
	 * A desk whose spot has no seat block can publish its chair as {@code seat_<agentId>} (feet or
	 * seat-top position + facing). It is used only when the desk is right in front of the chair
	 * (otherwise a seated agent would type in the air; it stands at {@code desk_<id>} instead).
	 */
	private static @Nullable Seat deskChair(BlockGetter level, Anchor desk, @Nullable GridPathfinder pathfinder, Anchors.Layout layout) {
		Anchor chair = layout.get("seat_" + desk.name().substring(AnchorNames.DESK_PREFIX.length()));
		if (chair == null) {
			return null;
		}
		Seat s = detect(level, new Anchor(desk.name(), chair.x(), chair.y(), chair.z(), chair.yaw(), chair.pitch()), pathfinder);
		return s != null && !Double.isNaN(s.deskTop()) ? s : null;
	}

	/** Spots where agents never sit (the user spot: waiting agents stand and face you). */
	private static boolean seatable(String anchorName) {
		return !anchorName.startsWith(AnchorNames.USER) && !anchorName.contains("@") && !anchorName.contains("~");
	}

	public static @Nullable Seat detect(BlockGetter level, Anchor a, @Nullable GridPathfinder pathfinder) {
		BlockPos p = BlockPos.containing(a.x(), a.y() + 0.01, a.z());
		BlockState s = level.getBlockState(p);
		if (s.isAir()) {
			return null;
		}
		double top;
		boolean hasBack = false;
		if (s.getBlock() instanceof StairBlock && s.getValue(StairBlock.HALF) == Half.BOTTOM) {
			top = 0.5;
			hasBack = true;
		} else if (s.getBlock() instanceof SlabBlock && s.getValue(SlabBlock.TYPE) == SlabType.BOTTOM) {
			top = 0.5;
		} else {
			VoxelShape shape = s.getCollisionShape(level, p);
			if (shape.isEmpty()) {
				return null;
			}
			top = shape.max(Direction.Axis.Y);
			if (top < 0.3 || top > 0.7) {
				return null;
			}
		}
		double rad = Math.toRadians(a.yaw());
		double fx = -Math.sin(rad);
		double fz = Math.cos(rad);
		// sit in front of a stairs back: the torso (0.23 deep) rests against the back's front face
		double pull = hasBack ? 0.13 : 0.0;
		// feet on the floor of the seat block (an anchor may give the floor or the seat top)
		double feet = p.getY();
		Vec3 sit = new Vec3(p.getX() + 0.5 + fx * pull, feet, p.getZ() + 0.5 + fz * pull);
		double seatTop = p.getY() + top;
		double drop = seatTop - THIGH - feet;
		Vec3 approach = pathfinder == null ? null : approach(pathfinder, p, fx, fz);
		double deskTop = surfaceTop(level, BlockPos.containing(sit.x + fx * 0.9, feet + 0.01, sit.z + fz * 0.9));
		return new Seat(a, sit, seatTop, drop, approach, deskTop);
	}

	/** A walkable neighbour of the seat block: the sides first, then behind, then in front. */
	private static @Nullable Vec3 approach(GridPathfinder pf, BlockPos seat, double fx, double fz) {
		int dx = (int) Math.round(fx);
		int dz = (int) Math.round(fz);
		// right-hand perpendicular of the facing (dx,dz) is (-dz, dx)
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

	/** Absolute top of the block's collision shape, or NaN when it has none. */
	private static double surfaceTop(BlockGetter level, BlockPos pos) {
		BlockState s = level.getBlockState(pos);
		if (s.isAir()) {
			return Double.NaN;
		}
		VoxelShape shape = s.getCollisionShape(level, pos);
		return shape.isEmpty() ? Double.NaN : pos.getY() + shape.max(Direction.Axis.Y);
	}
}
