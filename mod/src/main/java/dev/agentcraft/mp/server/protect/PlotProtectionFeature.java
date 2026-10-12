package dev.agentcraft.mp.server.protect;

import dev.agentcraft.mp.MpEvents;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.MpServerConfig;
import dev.agentcraft.mp.Plot;
import dev.agentcraft.mp.Plots;
import java.util.Optional;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.EnderEyeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PlaceOnWaterBlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Refuses block break, block placement and "use" inside a plot for anyone but
 * the plot's owner and gamemasters, when a dedicated server has
 * {@code enabled && protectPlots} in the live config.
 *
 * <p>A position is protected when it is in a plot's column or beside it, i.e.
 * within the plot's horizontal extent or one block around it: a margin so a
 * block just outside cannot pull a protected partner down (a bed's other half, a
 * torch on a wall block, sand on a support, a door's upper half) and an
 * explosion cannot take the boundary block that holds the protected one up. The
 * owner and gamemasters may edit the margin of their plot as they may edit the
 * plot. The column runs from one block below the plot's box to the top of the
 * world, because sand, gravel, concrete powder or an anvil placed above a plot
 * falls into it with no player event.</p>
 *
 * <p>The use rule for a player who may not edit the plot (the coordinator's
 * decisions under D-MP05): a right-click with a non-empty hand is refused when
 * the clicked block or the destination it would change is protected
 * ({@code action=place} for a {@link BlockItem}, otherwise {@code action=use}).
 * With an empty hand only a door, trapdoor, fence gate, button or lever is
 * allowed, so a visitor can walk in; every other block is refused, because each
 * of them changes state on an empty-hand click. The non-empty hand is refused
 * even on those blocks: an iron door passes the click on to the item, which
 * would then be placed inside. AgentCraft's own stations are handled
 * client-side and return FAIL there, so they never reach the server and need no
 * special case (audit A-11).</p>
 *
 * <p>Using or attacking an entity whose position is protected is refused for the
 * same players, whatever they hold, unless the entity is another player.</p>
 *
 * <p>Explosion protection lives in {@code ServerExplosionMixin}, which calls
 * {@link #explosionProtects} before blocks are destroyed and before fire is
 * placed.</p>
 */
public final class PlotProtectionFeature {
    private static boolean registered;
    private static final RefusalThrottle THROTTLE = new RefusalThrottle();

    private PlotProtectionFeature() {}

    public static void init() {
        if (registered) return;
        registered = true;

        // ---- break (survival START_DESTROY_BLOCK) ----
        AttackBlockCallback.EVENT.register((player, level, hand, pos, direction) -> {
            if (!active(level)) return InteractionResult.PASS;
            Optional<Plot> foreign = foreignPlotAt(level, player, pos);
            if (foreign.isEmpty()) return InteractionResult.PASS;
            refuse(player, foreign.get(), "break", pos);
            return InteractionResult.FAIL;
        });

        // ---- break (creative instant break and every destroyBlock call) ----
        PlayerBlockBreakEvents.BEFORE.register((level, player, pos, state, blockEntity) -> {
            if (!active(level)) return true;
            Optional<Plot> foreign = foreignPlotAt(level, player, pos);
            if (foreign.isEmpty()) return true;
            refuse(player, foreign.get(), "break", pos);
            return false;
        });

        // ---- right-click a block: containers, placement and non-block use ----
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (!active(level)) return InteractionResult.PASS;
            BlockPos clicked = hitResult.getBlockPos();
            Direction face = hitResult.getDirection();
            ItemStack held = player.getItemInHand(hand);
            Optional<Foreign> foreign = foreignPlotForUse(level, player, clicked, face, held, hitResult, hand);
            if (foreign.isEmpty()) return InteractionResult.PASS;
            Foreign refusal = foreign.get();

            if (held.isEmpty()) {
                // An empty hand may only work a block a visitor needs to walk
                // in. Every other block (cake, note blocks, repeaters, pots,
                // candles, respawn anchors, ...) changes state and is refused.
                if (visitorMayUseEmptyHand(level.getBlockState(clicked))) {
                    return InteractionResult.PASS;
                }
                refuse(player, refusal.plot(), "use", refusal.pos());
                return InteractionResult.FAIL;
            }

            // A non-empty hand is always refused, even on a door or button: an
            // iron door passes the click on to the held item, which would then
            // be placed inside the plot.
            String action = held.getItem() instanceof BlockItem ? "place" : "use";
            refuse(player, refusal.plot(), action, refusal.pos());
            return InteractionResult.FAIL;
        });

        // ---- buckets and lily pads used through the useItem path ----
        UseItemCallback.EVENT.register((player, level, hand) -> {
            if (!active(level)) return InteractionResult.PASS;
            ItemStack held = player.getItemInHand(hand);

            if (held.getItem() instanceof BucketItem bucket) {
                // Same ray as BucketItem.use: the item's own fluid context
                // (SOURCE_ONLY for empty, NONE for filled).
                BlockHitResult hit = pickFluid(level, player, bucket.getFluidContext());
                if (hit.getType() != HitResult.Type.BLOCK) return InteractionResult.PASS;

                BlockPos pos = hit.getBlockPos();
                BlockPos placed = pos.relative(hit.getDirection());
                Optional<Plot> foreign = foreignPlotAt(level, player, pos);
                BlockPos offending = pos;
                if (foreign.isEmpty()) {
                    foreign = foreignPlotAt(level, player, placed);
                    offending = placed;
                }
                if (foreign.isEmpty()) return InteractionResult.PASS;
                refuse(player, foreign.get(), "use", offending);
                return InteractionResult.FAIL;
            }

            // PlaceOnWaterBlockItem (lily pad, frogspawn) returns PASS from
            // useOn and places the block above the water from use(): the same
            // SOURCE_ONLY ray, and the block that would change is the one above
            // the hit position.
            if (held.getItem() instanceof PlaceOnWaterBlockItem) {
                BlockHitResult hit = pickFluid(level, player, ClipContext.Fluid.SOURCE_ONLY);
                if (hit.getType() != HitResult.Type.BLOCK) return InteractionResult.PASS;

                BlockPos water = hit.getBlockPos();
                BlockPos placed = water.above();
                Optional<Plot> foreign = foreignPlotAt(level, player, water);
                if (foreign.isEmpty()) foreign = foreignPlotAt(level, player, placed);
                if (foreign.isEmpty()) return InteractionResult.PASS;
                refuse(player, foreign.get(), "place", placed);
                return InteractionResult.FAIL;
            }

            return InteractionResult.PASS;
        });

        // ---- right-click and left-click an entity: chest minecarts and boats,
        // item frames, armor stands, paintings. The catalog has no action for an
        // attack; "break" is what a left-click does to them. ----
        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) ->
                refuseEntity(player, level, entity, "use"));
        AttackEntityCallback.EVENT.register((player, level, hand, entity, hitResult) ->
                refuseEntity(player, level, entity, "break"));

        // Bound the throttle: a player on disconnect, everything on stop.
        // DISCONNECT can fire off the server thread, so hop back before mutating.
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                server.execute(() -> THROTTLE.forget(handler.getPlayer().getUUID())));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> THROTTLE.clear());
    }

    // ---- public helper for the explosion mixin ----

    /** True when a block at this position in a plot's column or its margin must survive an explosion. */
    public static boolean explosionProtects(ServerLevel level, BlockPos pos) {
        if (!active(level)) return false;
        return protectedPlotAt(pos).isPresent();
    }

    /** The item's own point-of-view ray: view vector, block interaction range and fluid context. */
    private static BlockHitResult pickFluid(Level level, Player player, ClipContext.Fluid fluid) {
        Vec3 from = player.getEyePosition();
        Vec3 to = from.add(Player.calculateViewVector(player.getXRot(), player.getYRot())
                .scale(player.blockInteractionRange()));
        return level.clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, fluid, player));
    }

    // ---- internals ----

    static boolean active(Level level) {
        if (!(level instanceof ServerLevel serverLevel)) return false;
        return serverLevel.getServer().isDedicatedServer()
                && MpServerConfig.current().enabled()
                && MpServerConfig.current().protectPlots();
    }

    /** A refusal's plot and the position its telemetry reports. */
    private record Foreign(Plot plot, BlockPos pos) {}

    /**
     * The first foreign plot the use would touch, with the position to log. An
     * empty hand only acts on the clicked block; otherwise the face neighbour
     * and a {@link BlockItem}'s resolved destination are checked in turn.
     */
    private static Optional<Foreign> foreignPlotForUse(Level level, Player player, BlockPos clicked,
                                                       Direction face, ItemStack held,
                                                       BlockHitResult hitResult, InteractionHand hand) {
        Optional<Plot> clickedPlot = foreignPlotAt(level, player, clicked);
        if (clickedPlot.isPresent()) return Optional.of(new Foreign(clickedPlot.get(), clicked));
        if (held.isEmpty()) return Optional.empty();

        // An eye of ender changes blocks the margin cannot see. On an end
        // portal frame, vanilla EnderEyeItem.useOn replaces the frame's 3x3
        // interior with portal blocks, so a cell up to three blocks away can
        // land inside the plot even when the frame and the clicked face are
        // both outside the margin. Three blocks is the largest distance
        // between a frame block and a cell of the portal it encloses, so scan
        // the 7x7 square at the frame's height before letting the eye through.
        if (held.getItem() instanceof EnderEyeItem
                && level.getBlockState(clicked).getBlock() instanceof EndPortalFrameBlock) {
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos cell = clicked.offset(dx, 0, dz);
                    Optional<Plot> portalPlot = foreignPlotAt(level, player, cell);
                    if (portalPlot.isPresent()) {
                        return Optional.of(new Foreign(portalPlot.get(), clicked));
                    }
                }
            }
        }

        BlockPos adjacent = clicked.relative(face);
        Optional<Plot> adjacentPlot = foreignPlotAt(level, player, adjacent);
        if (adjacentPlot.isPresent()) return Optional.of(new Foreign(adjacentPlot.get(), adjacent));

        // A BlockItem can place further away than the adjacent block
        // (scaffolding extends along its row); resolve its real destination. The
        // margin in foreignPlotAt then refuses a bed's head at
        // pos.relative(FACING) and a door, double plant, small dripleaf or
        // pitcher crop top at pos.above(), even when the destination is outside.
        if (held.getItem() instanceof BlockItem blockItem) {
            BlockPlaceContext updated = blockItem.updatePlacementContext(
                    new BlockPlaceContext(player, hand, held, hitResult));
            if (updated != null) {
                BlockPos destination = updated.getClickedPos();
                Optional<Plot> destinationPlot = foreignPlotAt(level, player, destination);
                if (destinationPlot.isPresent()) {
                    return Optional.of(new Foreign(destinationPlot.get(), destination));
                }
            }
        }
        return Optional.empty();
    }

    /** FAIL when the entity stands on a protected position of a plot the player may not edit. */
    private static InteractionResult refuseEntity(Player player, Level level, Entity entity, String action) {
        if (!active(level) || entity instanceof Player) return InteractionResult.PASS;
        BlockPos pos = entity.blockPosition();
        Optional<Plot> foreign = foreignPlotAt(level, player, pos);
        if (foreign.isEmpty()) return InteractionResult.PASS;
        refuse(player, foreign.get(), action, pos);
        return InteractionResult.FAIL;
    }

    /** The empty-hand blocks a visitor may still work, so they can walk in. */
    private static boolean visitorMayUseEmptyHand(BlockState state) {
        Block block = state.getBlock();
        return block instanceof DoorBlock
                || block instanceof TrapDoorBlock
                || block instanceof FenceGateBlock
                || block instanceof ButtonBlock
                || block instanceof LeverBlock;
    }

    /**
     * The plot whose column holds the position, else the first whose column it
     * touches sideways (the margin). A column has no upper end and starts one
     * block below the plot's box.
     */
    private static Optional<Plot> protectedPlotAt(BlockPos pos) {
        Plot beside = null;
        for (Plot plot : Plots.directory().all()) {
            int bottom = Mth.floor(plot.box().minY);
            if (pos.getY() < bottom - 1) continue;
            BlockPos floor = pos.atY(bottom);
            if (plot.contains(floor)) return Optional.of(plot);
            if (beside != null) continue;
            for (Direction side : Direction.Plane.HORIZONTAL) {
                if (plot.contains(floor.relative(side))) beside = plot;
            }
        }
        return Optional.ofNullable(beside);
    }

    /** The plot that protects the position (in its column or beside it) and that this player may not edit. */
    private static Optional<Plot> foreignPlotAt(Level level, Player player, BlockPos pos) {
        Optional<Plot> plot = protectedPlotAt(pos);
        if (plot.isPresent() && !mayEdit(player, plot.get())) return plot;
        return Optional.empty();
    }

    static boolean mayEdit(Player player, Plot plot) {
        if (plot.owner().owner().equals(player.getUUID())) return true;
        // Gamemaster level, not "is on the op list at any level".
        return player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    static void refuse(Player player, Plot plot, String action, BlockPos pos) {
        if (!THROTTLE.shouldLog(player.getUUID(), action, plot.index())) return;
        MpLog.event(MpEvents.PLOT_EDIT_REFUSED,
                "player", player.getUUID(),
                "studio", plot.owner().owner(),
                "plot", plot.index(),
                "action", action,
                "pos", pos.getX() + "," + pos.getY() + "," + pos.getZ());
    }
}
