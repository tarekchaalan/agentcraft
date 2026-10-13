package dev.agentcraft.gametest.mp.protect;

import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.MpServerConfig;
import dev.agentcraft.mp.Plot;
import dev.agentcraft.mp.PlotDirectory;
import dev.agentcraft.mp.Plots;
import dev.agentcraft.mp.StudioId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ServerboundAttackPacket;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.decoration.painting.Painting;
import net.minecraft.world.entity.decoration.painting.PaintingVariants;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.AbstractBedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Plot-protection game tests. Every action is driven through the same server
 * method the game uses ({@code ServerPlayerGameMode.destroyBlock}, {@code
 * useItemOn}, {@code useItem}, and the packet listener's {@code handleInteract}
 * and {@code handleAttack} for entities), with the item really in the player's
 * hand.
 *
 * <p>Plot 1 sits at the test structure's origin + (0,64,0), away from the
 * shared plot 0. {@link #run} wraps the whole test so config, plot directory,
 * ops, player-list entries, every changed block and every spawned entity are
 * restored in {@code finally}, whatever the test did.</p>
 */
public class PlotProtectionGameTest {

    // ---- fixtures ----

    static final class Fixture {
        final GameTestHelper helper;
        final ServerLevel level;
        final PlotDirectory oldDir;
        final MpServerConfig oldConfig;
        final List<ServerPlayer> players = new ArrayList<>();
        final List<NameAndId> opped = new ArrayList<>();
        final Map<BlockPos, BlockState> saved = new LinkedHashMap<>();
        final List<Entity> spawned = new ArrayList<>();
        final Set<UUID> preexistingItems = new HashSet<>();
        final AABB keepBox;
        Plot plot;

        Fixture(GameTestHelper helper) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.oldDir = Plots.directory();
            this.oldConfig = MpServerConfig.current();
            // The plot sits a fixed 64 blocks above the test structure. Grow
            // its box by 16 blocks so every fixture action, its neighbour
            // updates and the drops restoration creates are inside, and
            // remember the item entities already there so close() removes only
            // what this test created.
            this.keepBox = new AABB(-46, 60, -36, 47, 101, 55)
                    .move(helper.absolutePos(new BlockPos(0, 64, 0)))
                    .inflate(16.0);
            for (Entity entity : level.getEntities(EntityTypes.ITEM, keepBox, Entity::isAlive)) {
                preexistingItems.add(entity.getUUID());
            }
        }

        void config(MpServerConfig config) {
            MpServerConfig.install(config);
        }

        void enableProtection() {
            config(new MpServerConfig(true, 128, true, true, true, true, true, 12, 4, 10));
        }

        @SuppressWarnings("removal")
        ServerPlayer player() {
            ServerPlayer player = helper.makeMockServerPlayerInLevel();
            players.add(player);
            return player;
        }

        /** A player who owns plot 1. Call before {@link #at}. */
        ServerPlayer owner() {
            ServerPlayer owner = player();
            plot = new Plot(1, StudioId.of(owner.getUUID()),
                    helper.absolutePos(new BlockPos(0, 64, 0)));
            Plots.install(dirFor(plot));
            return owner;
        }

        void gamemaster(ServerPlayer player) {
            var id = player.nameAndId();
            level.getServer().getPlayerList().op(id,
                    Optional.of(LevelBasedPermissionSet.GAMEMASTER), Optional.empty());
            opped.add(id);
        }

        void lowOperator(ServerPlayer player) {
            var id = player.nameAndId();
            // GameTestServer.operatorUserPermissions() is ALL: on the op list
            // but below gamemaster.
            level.getServer().getPlayerList().op(id);
            opped.add(id);
        }

        BlockPos at(int x, int y, int z) {
            return plot.origin().offset(x, y, z);
        }

        void set(BlockPos pos, BlockState state) {
            saved.putIfAbsent(pos, level.getBlockState(pos));
            level.setBlockAndUpdate(pos, state);
        }

        /** Adds an entity the fixture removes again in {@link #close}. */
        <T extends Entity> T spawn(T entity) {
            spawned.add(entity);
            level.addFreshEntity(entity);
            return entity;
        }

        void snapshot(BlockPos from, BlockPos to) {
            for (int x = Math.min(from.getX(), to.getX()); x <= Math.max(from.getX(), to.getX()); x++) {
                for (int y = Math.min(from.getY(), to.getY()); y <= Math.max(from.getY(), to.getY()); y++) {
                    for (int z = Math.min(from.getZ(), to.getZ()); z <= Math.max(from.getZ(), to.getZ()); z++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        saved.putIfAbsent(pos, level.getBlockState(pos));
                    }
                }
            }
        }

        boolean contains(BlockPos from, BlockPos to, Block block) {
            for (int x = Math.min(from.getX(), to.getX()); x <= Math.max(from.getX(), to.getX()); x++) {
                for (int y = Math.min(from.getY(), to.getY()); y <= Math.max(from.getY(), to.getY()); y++) {
                    for (int z = Math.min(from.getZ(), to.getZ()); z <= Math.max(from.getZ(), to.getZ()); z++) {
                        if (level.getBlockState(new BlockPos(x, y, z)).is(block)) return true;
                    }
                }
            }
            return false;
        }

        void close() {
            // Empty every jukebox the fixture placed before restoring: putting
            // the saved state back removes the block entity, and vanilla makes
            // it eject its disc.
            for (Map.Entry<BlockPos, BlockState> e : saved.entrySet()) {
                if (!e.getValue().is(Blocks.JUKEBOX)
                        && level.getBlockState(e.getKey()).is(Blocks.JUKEBOX)
                        && level.getBlockEntity(e.getKey()) instanceof JukeboxBlockEntity box) {
                    box.setTheItem(ItemStack.EMPTY);
                }
            }
            for (Map.Entry<BlockPos, BlockState> e : saved.entrySet()) {
                level.setBlockAndUpdate(e.getKey(), e.getValue());
            }
            for (Entity entity : spawned) entity.discard();
            // Restoration can pop dropped items (an unsupported lily pad, a
            // bed's neighbour). Blocks first, then remove every item entity the
            // test created, leaving everything that was here at the start.
            for (Entity entity : level.getEntities(EntityTypes.ITEM, keepBox, Entity::isAlive)) {
                if (!preexistingItems.contains(entity.getUUID())) {
                    entity.discard();
                }
            }
            for (ServerPlayer player : players) {
                if (player.hasContainerOpen()) player.closeContainer();
            }
            var list = level.getServer().getPlayerList();
            for (var id : opped) list.deop(id);
            for (ServerPlayer player : players) list.remove(player);
            Plots.install(oldDir);
            MpServerConfig.install(oldConfig);
        }
    }

    private static PlotDirectory dirFor(Plot plot) {
        return new PlotDirectory() {
            public Optional<Plot> plotOf(StudioId id) {
                return id.owner().equals(plot.owner().owner()) ? Optional.of(plot) : Optional.empty();
            }
            public Optional<Plot> plotAt(BlockPos pos) {
                return plot.contains(pos) ? Optional.of(plot) : Optional.empty();
            }
            public Collection<Plot> all() {
                return List.of(plot);
            }
        };
    }

    /** Run one test with a fixture that always restores in {@code finally}. */
    private static void run(GameTestHelper helper, Consumer<Fixture> body) {
        Fixture fixture = new Fixture(helper);
        try {
            body.accept(fixture);
        } finally {
            fixture.close();
        }
    }

    // ---- driving the real server paths ----

    private static String refusalLine(ServerPlayer player, ServerPlayer owner, int plot,
                                      String action, BlockPos pos) {
        return "event=plot_edit_refused player=" + player.getUUID()
                + " studio=" + owner.getUUID()
                + " plot=" + plot
                + " action=" + action
                + " pos=" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
    }

    private static InteractionResult useOn(ServerPlayer player, ServerLevel level,
                                           BlockPos clicked, Direction face, ItemStack stack) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(clicked), face, clicked, false);
        return player.gameMode.useItemOn(player, level, stack, InteractionHand.MAIN_HAND, hit);
    }

    private static InteractionResult useItem(ServerPlayer player, ServerLevel level, ItemStack stack) {
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        return player.gameMode.useItem(player, level, stack, InteractionHand.MAIN_HAND);
    }

    private static void aimAt(ServerPlayer player, ServerLevel level, double x, double y, double z, Vec3 target) {
        player.teleportTo(level, x, y, z, Set.of(), 0.0F, 0.0F, true);
        player.lookAt(EntityAnchorArgument.Anchor.EYES, target);
    }

    /** What the entity packet handlers require: an empty hand, in reach, and a client that has loaded. */
    private static void reach(ServerPlayer player, Entity entity) {
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        player.setPos(entity.getX(), entity.getY(), entity.getZ() - 1.5);
        player.connection.handleAcceptPlayerLoad(new ServerboundPlayerLoadedPacket());
    }

    /** Right-clicks an entity through the packet handler, where Fabric fires its use callback. */
    private static void useEntity(ServerPlayer player, Entity entity) {
        reach(player, entity);
        player.connection.handleInteract(new ServerboundInteractPacket(
                entity.getId(), InteractionHand.MAIN_HAND, Vec3.ZERO, false));
    }

    /** Left-clicks an entity through the packet handler, which ends in {@code Player.attack}. */
    private static void attackEntity(ServerPlayer player, Entity entity) {
        reach(player, entity);
        player.connection.handleAttack(new ServerboundAttackPacket(entity.getId()));
    }

    /** Sand aimed at {@code target}, which rests on stone, by clicking the stone block north of it. */
    private static void placeSand(Fixture f, ServerPlayer player, BlockPos target) {
        f.set(target, Blocks.AIR.defaultBlockState());
        f.set(target.below(), Blocks.STONE.defaultBlockState());
        f.set(target.north(), Blocks.STONE.defaultBlockState());
        useOn(player, f.level, target.north(), Direction.SOUTH, new ItemStack(Items.SAND));
    }

    // ---- break ----

    @GameTest
    public void visitorBreakRefused(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            ServerPlayer owner = f.owner();
            ServerPlayer visitor = f.player();
            BlockPos inside = f.at(0, 64, 0);
            f.set(inside, Blocks.STONE.defaultBlockState());

            try (var cap = MpLog.capture()) {
                helper.assertFalse(visitor.gameMode.destroyBlock(inside), "visitor break refused");
                helper.assertTrue(f.level.getBlockState(inside).is(Blocks.STONE), "stone remains");
                helper.assertTrue(cap.lines().contains(refusalLine(visitor, owner, 1, "break", inside)),
                        "whole plot_edit_refused break line");
            }
            helper.succeed();
        });
    }

    @GameTest
    public void ownerBreakAllowed(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            ServerPlayer owner = f.owner();
            BlockPos inside = f.at(0, 64, 0);
            f.set(inside, Blocks.STONE.defaultBlockState());

            helper.assertTrue(owner.gameMode.destroyBlock(inside), "owner break allowed");
            helper.assertTrue(f.level.getBlockState(inside).isAir(), "stone gone");
            helper.succeed();
        });
    }

    @GameTest
    public void gamemasterBreakAllowed(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            f.owner();
            ServerPlayer master = f.player();
            f.gamemaster(master);
            BlockPos inside = f.at(0, 64, 0);
            f.set(inside, Blocks.STONE.defaultBlockState());

            helper.assertTrue(master.gameMode.destroyBlock(inside), "gamemaster break allowed");
            helper.assertTrue(f.level.getBlockState(inside).isAir(), "stone gone");
            helper.succeed();
        });
    }

    @GameTest
    public void lowOperatorBreakRefused(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            f.owner();
            ServerPlayer op = f.player();
            f.lowOperator(op);
            BlockPos inside = f.at(0, 64, 0);
            f.set(inside, Blocks.STONE.defaultBlockState());

            helper.assertFalse(op.gameMode.destroyBlock(inside),
                    "op below gamemaster is refused");
            helper.assertTrue(f.level.getBlockState(inside).is(Blocks.STONE), "stone remains");
            helper.succeed();
        });
    }

    @GameTest
    public void outsideBreakAllowed(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            f.owner();
            ServerPlayer visitor = f.player();
            BlockPos outside = f.at(0, 64, -40);
            f.set(outside, Blocks.STONE.defaultBlockState());

            helper.assertTrue(visitor.gameMode.destroyBlock(outside), "outside break allowed");
            helper.assertTrue(f.level.getBlockState(outside).isAir(), "stone gone");
            helper.succeed();
        });
    }

    @GameTest
    public void visitorBreakInMarginRefused(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            ServerPlayer owner = f.owner();
            ServerPlayer visitor = f.player();
            // One block outside the -z edge: protected by the one-block margin.
            BlockPos margin = f.at(0, 64, -37);
            f.set(margin, Blocks.STONE.defaultBlockState());

            try (var cap = MpLog.capture()) {
                helper.assertFalse(visitor.gameMode.destroyBlock(margin), "margin break refused");
                helper.assertTrue(f.level.getBlockState(margin).is(Blocks.STONE), "stone remains");
                helper.assertTrue(cap.lines().contains(refusalLine(visitor, owner, 1, "break", margin)),
                        "the refusal reports the margin position");
            }
            helper.succeed();
        });
    }

    @GameTest
    public void ownerBreakInMarginAllowed(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            ServerPlayer owner = f.owner();
            BlockPos margin = f.at(0, 64, -37);
            f.set(margin, Blocks.STONE.defaultBlockState());

            helper.assertTrue(owner.gameMode.destroyBlock(margin), "owner may break the margin");
            helper.assertTrue(f.level.getBlockState(margin).isAir(), "stone gone");
            helper.succeed();
        });
    }

    @GameTest
    public void cornerBreakRefused(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            f.owner();
            ServerPlayer visitor = f.player();
            BlockPos corner = f.at(46, 64, 54);
            f.set(corner, Blocks.STONE.defaultBlockState());

            helper.assertFalse(visitor.gameMode.destroyBlock(corner),
                    "inclusive corner is inside the plot");
            helper.assertTrue(f.level.getBlockState(corner).is(Blocks.STONE), "stone remains");
            helper.succeed();
        });
    }

    // ---- throttle wiring ----

    @GameTest
    public void secondRefusalWithinWindowLogsOnce(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            f.owner();
            ServerPlayer visitor = f.player();
            BlockPos inside = f.at(0, 64, 0);
            BlockPos target = inside.above();
            f.set(inside, Blocks.STONE.defaultBlockState());
            f.set(target, Blocks.AIR.defaultBlockState());

            try (var cap = MpLog.capture()) {
                helper.assertFalse(visitor.gameMode.destroyBlock(inside), "first break refused");
                helper.assertFalse(visitor.gameMode.destroyBlock(inside), "second break refused");
                helper.assertTrue(cap.lines().stream()
                                .filter(line -> line.contains("action=break")).count() == 1,
                        "the second break inside five seconds logs nothing");

                useOn(visitor, f.level, inside, Direction.UP, new ItemStack(Items.COBBLESTONE));
                helper.assertTrue(cap.lines().stream()
                                .filter(line -> line.startsWith("event=plot_edit_refused")).count() == 2,
                        "a different action logs a second line");
            }
            helper.succeed();
        });
    }

    // ---- place ----

    @GameTest
    public void visitorPlaceRefused(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            ServerPlayer owner = f.owner();
            ServerPlayer visitor = f.player();
            BlockPos clicked = f.at(0, 64, 0);
            BlockPos target = clicked.above();
            f.set(clicked, Blocks.STONE.defaultBlockState());
            f.set(target, Blocks.AIR.defaultBlockState());

            try (var cap = MpLog.capture()) {
                useOn(visitor, f.level, clicked, Direction.UP, new ItemStack(Items.COBBLESTONE));
                helper.assertTrue(f.level.getBlockState(target).isAir(), "nothing placed");
                helper.assertTrue(cap.lines().contains(refusalLine(visitor, owner, 1, "place", clicked)),
                        "whole plot_edit_refused place line");
            }
            helper.succeed();
        });
    }

    @GameTest
    public void ownerPlaceAllowed(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            ServerPlayer owner = f.owner();
            BlockPos clicked = f.at(0, 64, 0);
            BlockPos target = clicked.above();
            f.set(clicked, Blocks.STONE.defaultBlockState());
            f.set(target, Blocks.AIR.defaultBlockState());

            useOn(owner, f.level, clicked, Direction.UP, new ItemStack(Items.COBBLESTONE));
            helper.assertTrue(f.level.getBlockState(target).is(Blocks.COBBLESTONE),
                    "owner placed cobblestone");
            helper.succeed();
        });
    }

    @GameTest
    public void gamemasterPlaceAllowed(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            f.owner();
            ServerPlayer master = f.player();
            f.gamemaster(master);
            BlockPos clicked = f.at(0, 64, 0);
            BlockPos target = clicked.above();
            f.set(clicked, Blocks.STONE.defaultBlockState());
            f.set(target, Blocks.AIR.defaultBlockState());

            useOn(master, f.level, clicked, Direction.UP, new ItemStack(Items.COBBLESTONE));
            helper.assertTrue(f.level.getBlockState(target).is(Blocks.COBBLESTONE),
                    "gamemaster placed cobblestone");
            helper.succeed();
        });
    }

    @GameTest
    public void lowOperatorPlaceRefused(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            f.owner();
            ServerPlayer op = f.player();
            f.lowOperator(op);
            BlockPos clicked = f.at(0, 64, 0);
            BlockPos target = clicked.above();
            f.set(clicked, Blocks.STONE.defaultBlockState());
            f.set(target, Blocks.AIR.defaultBlockState());

            useOn(op, f.level, clicked, Direction.UP, new ItemStack(Items.COBBLESTONE));
            helper.assertTrue(f.level.getBlockState(target).isAir(),
                    "op below gamemaster cannot place");
            helper.succeed();
        });
    }

    @GameTest
    public void placeFromOutsideRefused(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            ServerPlayer owner = f.owner();
            ServerPlayer visitor = f.player();
            // Click outside the margin, face the plot: the destination lands in
            // the protected margin at z=-37 and is refused there.
            BlockPos clicked = f.at(0, 64, -38);
            BlockPos target = f.at(0, 64, -37);
            f.set(clicked, Blocks.STONE.defaultBlockState());
            f.set(target, Blocks.AIR.defaultBlockState());

            try (var cap = MpLog.capture()) {
                useOn(visitor, f.level, clicked, Direction.SOUTH, new ItemStack(Items.COBBLESTONE));
                helper.assertTrue(f.level.getBlockState(target).isAir(),
                        "a placement into the margin from outside is refused");
                helper.assertTrue(cap.lines().contains(refusalLine(visitor, owner, 1, "place", target)),
                        "the refusal reports the margin destination");
            }
            helper.succeed();
        });
    }

    @GameTest
    public void bedFromOutsideRefused(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            ServerPlayer owner = f.owner();
            ServerPlayer visitor = f.player();
            // Click the side of a block outside the margin: the bed's foot
            // would land in the protected margin at z=-37 and its head inside
            // at z=-36.
            BlockPos clicked = f.at(0, 64, -38);
            BlockPos foot = f.at(0, 64, -37);
            BlockPos head = f.at(0, 64, -36);
            f.set(clicked, Blocks.STONE.defaultBlockState());
            f.set(foot, Blocks.AIR.defaultBlockState());
            f.set(head, Blocks.AIR.defaultBlockState());
            visitor.setYRot(0.0F); // yaw 0 faces SOUTH (+z), into the plot.

            try (var cap = MpLog.capture()) {
                useOn(visitor, f.level, clicked, Direction.SOUTH, new ItemStack(Items.BED.red()));
                helper.assertTrue(f.level.getBlockState(foot).isAir(), "no bed foot in the margin");
                helper.assertTrue(f.level.getBlockState(head).isAir(), "no bed head inside the plot");
                helper.assertTrue(cap.lines().contains(refusalLine(visitor, owner, 1, "place", foot)),
                        "the refusal reports the margin destination");
            }
            helper.succeed();
        });
    }

    @GameTest
    public void boundaryBedSurvivesVisitorBreak(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            ServerPlayer owner = f.owner();
            ServerPlayer visitor = f.player();
            // The owner places a bed through the real path: its foot sits on a
            // support in the margin at z=-37 and its head lands inside at z=-36.
            BlockPos support = f.at(0, 64, -37);
            BlockPos headSupport = f.at(0, 64, -36);
            BlockPos foot = f.at(0, 65, -37);
            BlockPos head = f.at(0, 65, -36);
            f.set(support, Blocks.STONE.defaultBlockState());
            f.set(headSupport, Blocks.STONE.defaultBlockState());
            f.set(foot, Blocks.AIR.defaultBlockState());
            f.set(head, Blocks.AIR.defaultBlockState());
            owner.setYRot(0.0F); // yaw 0 faces SOUTH (+z), into the plot.
            useOn(owner, f.level, support, Direction.UP, new ItemStack(Items.BED.red()));
            helper.assertTrue(f.level.getBlockState(foot).getBlock() instanceof AbstractBedBlock,
                    "the owner placed the foot in the margin");
            helper.assertTrue(f.level.getBlockState(head).getBlock() instanceof AbstractBedBlock,
                    "the owner placed the head inside the plot");

            try (var cap = MpLog.capture()) {
                // Breaking the foot outside would remove the protected head.
                helper.assertFalse(visitor.gameMode.destroyBlock(foot), "a visitor cannot break the foot");
                helper.assertTrue(f.level.getBlockState(foot).getBlock() instanceof AbstractBedBlock,
                        "the foot survived");
                helper.assertTrue(f.level.getBlockState(head).getBlock() instanceof AbstractBedBlock,
                        "the protected head survived");
                helper.assertTrue(cap.lines().contains(refusalLine(visitor, owner, 1, "break", foot)),
                        "the refusal reports the foot position");
            }
            helper.succeed();
        });
    }

    @GameTest
    public void boundaryBedSurvivesExplosion(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            ServerPlayer owner = f.owner();
            BlockPos support = f.at(0, 64, -37);
            BlockPos headSupport = f.at(0, 64, -36);
            BlockPos foot = f.at(0, 65, -37);
            BlockPos head = f.at(0, 65, -36);
            f.set(support, Blocks.STONE.defaultBlockState());
            f.set(headSupport, Blocks.STONE.defaultBlockState());
            f.set(foot, Blocks.AIR.defaultBlockState());
            f.set(head, Blocks.AIR.defaultBlockState());
            owner.setYRot(0.0F);
            useOn(owner, f.level, support, Direction.UP, new ItemStack(Items.BED.red()));
            helper.assertTrue(f.level.getBlockState(foot).getBlock() instanceof AbstractBedBlock,
                    "the owner placed the foot in the margin");

            f.snapshot(f.at(-6, 59, -44), f.at(6, 72, -30));
            BlockPos blast = f.at(0, 65, -39);
            f.level.explode(null, blast.getX() + 0.5, blast.getY() + 0.5, blast.getZ() + 0.5,
                    4.0F, false, Level.ExplosionInteraction.TNT);
            helper.assertTrue(f.level.getBlockState(foot).getBlock() instanceof AbstractBedBlock,
                    "the margin foot survives the blast");
            helper.assertTrue(f.level.getBlockState(head).getBlock() instanceof AbstractBedBlock,
                    "the protected head survives the blast");
            helper.succeed();
        });
    }

    @GameTest
    public void blockTwoOutsideAllowed(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            f.owner();
            ServerPlayer visitor = f.player();
            // The destination is two blocks out from the -z edge, so it is
            // outside the one-block margin and must be allowed.
            BlockPos clicked = f.at(0, 64, -39);
            BlockPos target = f.at(0, 64, -38);
            f.set(clicked, Blocks.STONE.defaultBlockState());
            f.set(target, Blocks.AIR.defaultBlockState());

            useOn(visitor, f.level, clicked, Direction.SOUTH, new ItemStack(Items.COBBLESTONE));
            helper.assertTrue(f.level.getBlockState(target).is(Blocks.COBBLESTONE),
                    "a plain block two blocks outside the plot is allowed");
            helper.succeed();
        });
    }

    @GameTest
    public void outsidePlaceAllowed(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            f.owner();
            ServerPlayer visitor = f.player();
            BlockPos clicked = f.at(0, 64, -40);
            BlockPos target = f.at(0, 64, -39);
            f.set(clicked, Blocks.STONE.defaultBlockState());
            f.set(target, Blocks.AIR.defaultBlockState());

            useOn(visitor, f.level, clicked, Direction.SOUTH, new ItemStack(Items.COBBLESTONE));
            helper.assertTrue(f.level.getBlockState(target).is(Blocks.COBBLESTONE),
                    "visitor placed outside the plot");
            helper.succeed();
        });
    }

    @GameTest
    public void visitorPlaceInMarginRefused(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            ServerPlayer owner = f.owner();
            ServerPlayer visitor = f.player();
            BlockPos margin = f.at(0, 64, -37);
            BlockPos target = margin.above();
            f.set(margin, Blocks.STONE.defaultBlockState());
            f.set(target, Blocks.AIR.defaultBlockState());

            try (var cap = MpLog.capture()) {
                useOn(visitor, f.level, margin, Direction.UP, new ItemStack(Items.COBBLESTONE));
                helper.assertTrue(f.level.getBlockState(target).isAir(), "nothing placed in the margin");
                helper.assertTrue(cap.lines().contains(refusalLine(visitor, owner, 1, "place", margin)),
                        "the refusal reports the clicked margin position");
            }
            helper.succeed();
        });
    }

    @GameTest
    public void ownerPlaceInMarginAllowed(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            ServerPlayer owner = f.owner();
            BlockPos margin = f.at(0, 64, -37);
            BlockPos target = margin.above();
            f.set(margin, Blocks.STONE.defaultBlockState());
            f.set(target, Blocks.AIR.defaultBlockState());

            useOn(owner, f.level, margin, Direction.UP, new ItemStack(Items.COBBLESTONE));
            helper.assertTrue(f.level.getBlockState(target).is(Blocks.COBBLESTONE),
                    "the owner may place in the margin");
            helper.succeed();
        });
    }

    // ---- non-block item use ----

    @GameTest
    public void visitorFlintRefused(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            ServerPlayer owner = f.owner();
            ServerPlayer visitor = f.player();
            BlockPos clicked = f.at(0, 64, 0);
            BlockPos above = clicked.above();
            f.set(clicked, Blocks.STONE.defaultBlockState());
            f.set(above, Blocks.AIR.defaultBlockState());

            try (var cap = MpLog.capture()) {
                useOn(visitor, f.level, clicked, Direction.UP, new ItemStack(Items.FLINT_AND_STEEL));
                helper.assertTrue(!f.level.getBlockState(above).is(Blocks.FIRE),
                        "visitor placed no fire");
                helper.assertTrue(cap.lines().contains(refusalLine(visitor, owner, 1, "use", clicked)),
                        "whole plot_edit_refused use line");
            }
            helper.succeed();
        });
    }

    @GameTest
    public void ownerFlintAllowed(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            ServerPlayer owner = f.owner();
            BlockPos clicked = f.at(0, 64, 0);
            BlockPos above = clicked.above();
            f.set(clicked, Blocks.STONE.defaultBlockState());
            f.set(above, Blocks.AIR.defaultBlockState());

            useOn(owner, f.level, clicked, Direction.UP, new ItemStack(Items.FLINT_AND_STEEL));
            helper.assertTrue(f.level.getBlockState(above).is(Blocks.FIRE),
                    "owner placed fire");
            helper.succeed();
        });
    }

    @GameTest
    public void gamemasterFlintAllowed(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            f.owner();
            ServerPlayer master = f.player();
            f.gamemaster(master);
            BlockPos clicked = f.at(0, 64, 0);
            BlockPos above = clicked.above();
            f.set(clicked, Blocks.STONE.defaultBlockState());
            f.set(above, Blocks.AIR.defaultBlockState());

            useOn(master, f.level, clicked, Direction.UP, new ItemStack(Items.FLINT_AND_STEEL));
            helper.assertTrue(f.level.getBlockState(above).is(Blocks.FIRE), "gamemaster placed fire");
            helper.succeed();
        });
    }

    @GameTest
    public void lowOperatorFlintRefused(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            f.owner();
            ServerPlayer op = f.player();
            f.lowOperator(op);
            BlockPos clicked = f.at(0, 64, 0);
            BlockPos above = clicked.above();
            f.set(clicked, Blocks.STONE.defaultBlockState());
            f.set(above, Blocks.AIR.defaultBlockState());

            useOn(op, f.level, clicked, Direction.UP, new ItemStack(Items.FLINT_AND_STEEL));
            helper.assertTrue(!f.level.getBlockState(above).is(Blocks.FIRE),
                    "op below gamemaster placed no fire");
            helper.succeed();
        });
    }

    @GameTest
    public void outsideFlintAllowed(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            f.owner();
            ServerPlayer visitor = f.player();
            BlockPos clicked = f.at(0, 64, -40);
            BlockPos above = clicked.above();
            f.set(clicked, Blocks.STONE.defaultBlockState());
            f.set(above, Blocks.AIR.defaultBlockState());

            useOn(visitor, f.level, clicked, Direction.UP, new ItemStack(Items.FLINT_AND_STEEL));
            helper.assertTrue(f.level.getBlockState(above).is(Blocks.FIRE),
                    "visitor placed fire outside the plot");
            helper.succeed();
        });
    }

    // ---- buckets ----

    /**
     * Stone at z=-35 (inside), air at z=-36 (inside, the destination), a
     * water source at z=-37 (the margin) directly in the ray path. A filled
     * bucket uses {@code Fluid.NONE} and sees the stone; a naive
     * {@code Fluid.ANY} ray would stop at the water.
     */
    @GameTest
    public void visitorFilledBucketRefusedBehindWater(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            f.owner();
            ServerPlayer visitor = f.player();
            BlockPos stone = f.at(0, 65, -35);
            BlockPos water = f.at(0, 65, -37);
            BlockPos destination = f.at(0, 65, -36);
            BlockPos boxFrom = f.at(-2, 60, -36);
            BlockPos boxTo = f.at(2, 70, -30);
            f.set(stone, Blocks.STONE.defaultBlockState());
            f.set(destination, Blocks.AIR.defaultBlockState());
            f.set(water, Blocks.WATER.defaultBlockState());

            aimAt(visitor, f.level,
                    f.at(0, 64, 0).getX() + 0.5, f.at(0, 64, 0).getY(), water.getZ() - 1.5,
                    Vec3.atCenterOf(stone));
            InteractionResult result = useItem(visitor, f.level, new ItemStack(Items.WATER_BUCKET));
            helper.assertTrue(result instanceof InteractionResult.Fail, "filled bucket refused");
            helper.assertFalse(f.contains(boxFrom, boxTo, Blocks.WATER),
                    "no water placed inside the plot");
            helper.assertTrue(f.level.getBlockState(water).is(Blocks.WATER),
                    "the outside water is untouched");
            helper.succeed();
        });
    }

    @GameTest
    public void visitorEmptyBucketPicksUpOutsideWater(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            f.owner();
            ServerPlayer visitor = f.player();
            BlockPos stone = f.at(0, 65, -35);
            // Two blocks out from the -z edge: outside the one-block margin.
            BlockPos water = f.at(0, 65, -38);
            f.set(stone, Blocks.STONE.defaultBlockState());
            f.set(water, Blocks.WATER.defaultBlockState());

            aimAt(visitor, f.level,
                    f.at(0, 64, 0).getX() + 0.5, f.at(0, 64, 0).getY(), water.getZ() - 1.5,
                    Vec3.atCenterOf(stone));
            useItem(visitor, f.level, new ItemStack(Items.BUCKET));
            helper.assertTrue(f.level.getBlockState(water).isAir(),
                    "empty bucket picked up the water outside the margin");
            helper.succeed();
        });
    }

    @GameTest
    public void visitorEmptyBucketRefusedInside(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            ServerPlayer owner = f.owner();
            ServerPlayer visitor = f.player();
            BlockPos water = f.at(0, 65, -35);
            f.set(water, Blocks.WATER.defaultBlockState());

            aimAt(visitor, f.level,
                    f.at(0, 64, 0).getX() + 0.5, f.at(0, 64, 0).getY(), water.getZ() - 1.5,
                    Vec3.atCenterOf(water));
            try (var cap = MpLog.capture()) {
                InteractionResult result = useItem(visitor, f.level, new ItemStack(Items.BUCKET));
                helper.assertTrue(result instanceof InteractionResult.Fail, "empty bucket refused");
                helper.assertTrue(f.level.getBlockState(water).is(Blocks.WATER), "the water is still there");
                helper.assertTrue(cap.lines().contains(refusalLine(visitor, owner, 1, "use", water)),
                        "the refusal reports the water position");
            }
            helper.succeed();
        });
    }

    @GameTest
    public void ownerFilledBucketAllowed(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            ServerPlayer owner = f.owner();
            BlockPos stone = f.at(0, 65, -35);
            BlockPos water = f.at(0, 65, -37);
            BlockPos destination = f.at(0, 65, -36);
            BlockPos boxFrom = f.at(-2, 60, -36);
            BlockPos boxTo = f.at(2, 70, -30);
            f.set(stone, Blocks.STONE.defaultBlockState());
            f.set(destination, Blocks.AIR.defaultBlockState());
            f.set(water, Blocks.WATER.defaultBlockState());

            aimAt(owner, f.level,
                    f.at(0, 64, 0).getX() + 0.5, f.at(0, 64, 0).getY(), water.getZ() - 1.5,
                    Vec3.atCenterOf(stone));
            useItem(owner, f.level, new ItemStack(Items.WATER_BUCKET));
            helper.assertTrue(f.contains(boxFrom, boxTo, Blocks.WATER),
                    "owner placed water inside the plot");
            helper.succeed();
        });
    }

    // ---- place-on-water items (lily pad, frogspawn) ----

    @GameTest
    public void visitorLilyPadRefused(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            ServerPlayer owner = f.owner();
            ServerPlayer visitor = f.player();
            BlockPos water = f.at(0, 65, -35);
            BlockPos placed = water.above();
            f.set(water, Blocks.WATER.defaultBlockState());
            f.set(placed, Blocks.AIR.defaultBlockState());

            aimAt(visitor, f.level,
                    f.at(0, 64, 0).getX() + 0.5, f.at(0, 64, 0).getY(), water.getZ() - 1.5,
                    Vec3.atCenterOf(water));
            try (var cap = MpLog.capture()) {
                InteractionResult result = useItem(visitor, f.level, new ItemStack(Items.LILY_PAD));
                helper.assertTrue(result instanceof InteractionResult.Fail, "lily pad refused");
                helper.assertTrue(f.level.getBlockState(placed).isAir(), "no lily pad above the water");
                helper.assertTrue(cap.lines().contains(refusalLine(visitor, owner, 1, "place", placed)),
                        "the refusal reports the position above the water");
            }
            helper.succeed();
        });
    }

    @GameTest
    public void ownerLilyPadAllowed(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            ServerPlayer owner = f.owner();
            BlockPos water = f.at(0, 65, -35);
            BlockPos placed = water.above();
            f.set(water, Blocks.WATER.defaultBlockState());
            f.set(placed, Blocks.AIR.defaultBlockState());

            aimAt(owner, f.level,
                    f.at(0, 64, 0).getX() + 0.5, f.at(0, 64, 0).getY(), water.getZ() - 1.5,
                    Vec3.atCenterOf(water));
            useItem(owner, f.level, new ItemStack(Items.LILY_PAD));
            helper.assertTrue(f.level.getBlockState(placed).is(Blocks.LILY_PAD),
                    "the owner placed the lily pad above the water");
            helper.succeed();
        });
    }

    // ---- end portal frames (eye of ender) ----

    @GameTest
    public void visitorEnderEyeRefusedNearPlot(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            ServerPlayer owner = f.owner();
            ServerPlayer visitor = f.player();
            // Three blocks outside the -z edge: the frame and the clicked face
            // are outside the margin, but the 7x7 square at the frame's height
            // reaches the plot at z=-36.
            BlockPos frame = f.at(0, 64, -39);
            f.set(frame, Blocks.END_PORTAL_FRAME.defaultBlockState());

            try (var cap = MpLog.capture()) {
                InteractionResult result = useOn(visitor, f.level, frame, Direction.UP,
                        new ItemStack(Items.ENDER_EYE));
                helper.assertTrue(result instanceof InteractionResult.Fail, "the eye is refused");
                helper.assertFalse(f.level.getBlockState(frame).getValue(EndPortalFrameBlock.HAS_EYE),
                        "the frame still has no eye");
                helper.assertTrue(cap.lines().contains(refusalLine(visitor, owner, 1, "use", frame)),
                        "whole plot_edit_refused use line at the frame");
            }
            helper.succeed();
        });
    }

    @GameTest
    public void visitorEnderEyeAllowedFarAway(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            f.owner();
            ServerPlayer visitor = f.player();
            // Five blocks outside the -z edge: the 7x7 square stops at z=-38,
            // outside the one-block margin.
            BlockPos frame = f.at(0, 64, -41);
            f.set(frame, Blocks.END_PORTAL_FRAME.defaultBlockState());

            useOn(visitor, f.level, frame, Direction.UP, new ItemStack(Items.ENDER_EYE));
            helper.assertTrue(f.level.getBlockState(frame).getValue(EndPortalFrameBlock.HAS_EYE),
                    "the eye is inserted five blocks away");
            helper.succeed();
        });
    }

    @GameTest
    public void ownerEnderEyeAllowed(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            ServerPlayer owner = f.owner();
            // Three blocks outside: refused for a visitor, allowed for the owner.
            BlockPos frame = f.at(0, 64, -39);
            f.set(frame, Blocks.END_PORTAL_FRAME.defaultBlockState());

            useOn(owner, f.level, frame, Direction.UP, new ItemStack(Items.ENDER_EYE));
            helper.assertTrue(f.level.getBlockState(frame).getValue(EndPortalFrameBlock.HAS_EYE),
                    "the owner inserted the eye");
            helper.succeed();
        });
    }

    // ---- containers and block entities ----

    @GameTest
    public void visitorChestRefusedNoMenu(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            f.owner();
            ServerPlayer visitor = f.player();
            BlockPos chest = f.at(0, 64, 0);
            f.set(chest, Blocks.CHEST.defaultBlockState());

            useOn(visitor, f.level, chest, Direction.UP, ItemStack.EMPTY);
            helper.assertFalse(visitor.hasContainerOpen(), "no menu opened");
            helper.assertTrue(f.level.getBlockState(chest).is(Blocks.CHEST), "chest untouched");
            helper.succeed();
        });
    }

    @GameTest
    public void ownerChestOpens(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            ServerPlayer owner = f.owner();
            BlockPos chest = f.at(0, 64, 0);
            f.set(chest, Blocks.CHEST.defaultBlockState());

            useOn(owner, f.level, chest, Direction.UP, ItemStack.EMPTY);
            helper.assertTrue(owner.hasContainerOpen(), "owner opened the chest");
            helper.succeed();
        });
    }

    @GameTest
    public void visitorSignRefused(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            f.owner();
            ServerPlayer visitor = f.player();
            BlockPos sign = f.at(0, 64, 0);
            f.set(sign, Blocks.OAK_SIGN.defaultBlockState());

            InteractionResult result = useOn(visitor, f.level, sign, Direction.UP, ItemStack.EMPTY);
            helper.assertTrue(result instanceof InteractionResult.Fail,
                    "a block entity without a menu is refused");
            helper.assertFalse(visitor.hasContainerOpen(), "no menu opened");
            helper.assertTrue(f.level.getBlockEntity(sign) instanceof SignBlockEntity box
                            && box.getPlayerWhoMayEdit() == null,
                    "vanilla's openTextEdit never ran for the visitor");
            helper.succeed();
        });
    }

    @GameTest
    public void ownerSignOpensEditor(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            ServerPlayer owner = f.owner();
            BlockPos sign = f.at(0, 64, 0);
            f.set(sign, Blocks.OAK_SIGN.defaultBlockState());

            useOn(owner, f.level, sign, Direction.UP, ItemStack.EMPTY);
            if (f.level.getBlockEntity(sign) instanceof SignBlockEntity box) {
                helper.assertTrue(owner.getUUID().equals(box.getPlayerWhoMayEdit()),
                        "vanilla's openTextEdit records the owner");
            } else {
                helper.fail("the sign has no block entity");
            }
            helper.succeed();
        });
    }

    @GameTest
    public void visitorJukeboxRefused(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            f.owner();
            ServerPlayer visitor = f.player();
            BlockPos jukebox = f.at(0, 64, 0);
            f.set(jukebox, Blocks.JUKEBOX.defaultBlockState()
                    .setValue(JukeboxBlock.HAS_RECORD, true));
            if (f.level.getBlockEntity(jukebox) instanceof JukeboxBlockEntity box) {
                box.setTheItem(new ItemStack(Items.MUSIC_DISC_CAT));
            }

            InteractionResult result = useOn(visitor, f.level, jukebox, Direction.UP, ItemStack.EMPTY);
            helper.assertTrue(result instanceof InteractionResult.Fail,
                    "a jukebox with a disc is refused");
            helper.assertTrue(f.level.getBlockState(jukebox).getValue(JukeboxBlock.HAS_RECORD),
                    "the disc is not popped out");
            if (f.level.getBlockEntity(jukebox) instanceof JukeboxBlockEntity box) {
                helper.assertTrue(!box.getTheItem().isEmpty(), "the disc is still in the jukebox");
            } else {
                helper.fail("the jukebox has no block entity");
            }
            helper.succeed();
        });
    }

    @GameTest
    public void visitorDoorOpens(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            f.owner();
            ServerPlayer visitor = f.player();
            BlockPos lower = f.at(0, 64, 0);
            BlockState lowerState = Blocks.OAK_DOOR.defaultBlockState();
            f.set(lower, lowerState);
            f.set(lower.above(), lowerState.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));

            useOn(visitor, f.level, lower, Direction.UP, ItemStack.EMPTY);
            helper.assertTrue(f.level.getBlockState(lower).getValue(DoorBlock.OPEN),
                    "a visitor may open a door with an empty hand");
            helper.succeed();
        });
    }

    // ---- empty-hand use: only the walk-in blocks are allowed ----

    @GameTest
    public void visitorNoteBlockRefused(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            f.owner();
            ServerPlayer visitor = f.player();
            BlockPos note = f.at(0, 64, 0);
            BlockState before = Blocks.NOTE_BLOCK.defaultBlockState();
            f.set(note, before);

            useOn(visitor, f.level, note, Direction.UP, ItemStack.EMPTY);
            helper.assertTrue(f.level.getBlockState(note).equals(before), "the note block is unchanged");
            helper.succeed();
        });
    }

    @GameTest
    public void ownerNoteBlockChanges(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            ServerPlayer owner = f.owner();
            BlockPos note = f.at(0, 64, 0);
            BlockState before = Blocks.NOTE_BLOCK.defaultBlockState();
            f.set(note, before);

            useOn(owner, f.level, note, Direction.UP, ItemStack.EMPTY);
            helper.assertTrue(!f.level.getBlockState(note).equals(before),
                    "the owner's empty-hand click tunes the note block");
            helper.succeed();
        });
    }

    @GameTest
    public void visitorRepeaterRefused(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            f.owner();
            ServerPlayer visitor = f.player();
            BlockPos repeater = f.at(0, 64, 0);
            BlockState before = Blocks.REPEATER.defaultBlockState();
            f.set(repeater, before);

            useOn(visitor, f.level, repeater, Direction.UP, ItemStack.EMPTY);
            helper.assertTrue(f.level.getBlockState(repeater).equals(before),
                    "the repeater delay is unchanged");
            helper.succeed();
        });
    }

    @GameTest
    public void visitorCakeRefused(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            f.owner();
            ServerPlayer visitor = f.player();
            BlockPos cake = f.at(0, 64, 0);
            BlockState before = Blocks.CAKE.defaultBlockState();
            f.set(cake, before);

            useOn(visitor, f.level, cake, Direction.UP, ItemStack.EMPTY);
            helper.assertTrue(f.level.getBlockState(cake).equals(before), "the cake is unchanged");
            helper.succeed();
        });
    }

    @GameTest
    public void visitorButtonWorks(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            f.owner();
            ServerPlayer visitor = f.player();
            BlockPos button = f.at(0, 64, 0);
            f.set(button, Blocks.STONE_BUTTON.defaultBlockState());

            useOn(visitor, f.level, button, Direction.UP, ItemStack.EMPTY);
            helper.assertTrue(f.level.getBlockState(button).getValue(ButtonBlock.POWERED),
                    "a visitor may press a button with an empty hand");
            helper.succeed();
        });
    }

    // ---- configuration gates ----

    @GameTest
    public void visitorRefusedProtectionOn(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            f.owner();
            ServerPlayer visitor = f.player();
            BlockPos inside = f.at(0, 64, 0);
            f.set(inside, Blocks.STONE.defaultBlockState());

            try (var cap = MpLog.capture()) {
                helper.assertFalse(visitor.gameMode.destroyBlock(inside), "refused");
                helper.assertTrue(cap.lines().stream()
                                .anyMatch(line -> line.contains("player=" + visitor.getUUID())),
                        "a refusal line was emitted");
            }
            helper.succeed();
        });
    }

    @GameTest
    public void visitorAllowedProtectOff(GameTestHelper helper) {
        run(helper, f -> {
            f.config(new MpServerConfig(true, 128, true, true, true, true, false, 12, 4, 10));
            f.owner();
            ServerPlayer visitor = f.player();
            BlockPos inside = f.at(0, 64, 0);
            f.set(inside, Blocks.STONE.defaultBlockState());

            try (var cap = MpLog.capture()) {
                helper.assertTrue(visitor.gameMode.destroyBlock(inside),
                        "protectPlots=false allows the visitor");
                helper.assertFalse(cap.lines().stream()
                                .anyMatch(line -> line.contains("player=" + visitor.getUUID())),
                        "no refusal line");
            }
            helper.succeed();
        });
    }

    @GameTest
    public void visitorAllowedDisabled(GameTestHelper helper) {
        run(helper, f -> {
            f.config(new MpServerConfig(false, 128, true, true, true, true, true, 12, 4, 10));
            f.owner();
            ServerPlayer visitor = f.player();
            BlockPos inside = f.at(0, 64, 0);
            f.set(inside, Blocks.STONE.defaultBlockState());

            try (var cap = MpLog.capture()) {
                helper.assertTrue(visitor.gameMode.destroyBlock(inside),
                        "enabled=false allows the visitor");
                helper.assertFalse(cap.lines().stream()
                                .anyMatch(line -> line.contains("player=" + visitor.getUUID())),
                        "no refusal line");
            }
            helper.succeed();
        });
    }

    // ---- explosions ----

    @GameTest
    public void explosionKeepsProtectedBlock(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            f.owner();
            // x=46 is inside the plot, x=47 is the one-block margin and x=48 is
            // two blocks outside.
            BlockPos insideEdge = f.at(46, 64, 0);
            BlockPos marginEdge = f.at(47, 64, 0);
            BlockPos outsideEdge = f.at(48, 64, 0);
            f.set(insideEdge, Blocks.STONE.defaultBlockState());
            f.set(marginEdge, Blocks.STONE.defaultBlockState());
            f.set(outsideEdge, Blocks.STONE.defaultBlockState());
            f.snapshot(f.at(41, 59, -6), f.at(54, 70, 6));

            f.level.explode(null,
                    outsideEdge.getX() + 0.5, outsideEdge.getY() + 0.5, outsideEdge.getZ() + 0.5,
                    4.0F, false, Level.ExplosionInteraction.TNT);
            helper.assertTrue(f.level.getBlockState(insideEdge).is(Blocks.STONE),
                    "the block inside the plot survives the blast");
            helper.assertTrue(f.level.getBlockState(marginEdge).is(Blocks.STONE),
                    "the block in the margin survives the blast");
            helper.assertTrue(f.level.getBlockState(outsideEdge).isAir(),
                    "the block two blocks outside the plot is destroyed");
            helper.succeed();
        });
    }

    @GameTest
    public void fieryExplosionPlacesNoFireInside(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            f.owner();
            // A 7x7 stone floor inside the plot, air above.
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    f.set(f.at(dx, 64, dz), Blocks.STONE.defaultBlockState());
                }
            }
            BlockPos from = f.at(-5, 65, -5);
            BlockPos to = f.at(5, 72, 5);
            f.snapshot(f.at(-6, 60, -6), f.at(6, 73, 6));

            f.level.explode(null,
                    f.at(0, 66, 0).getX() + 0.5, f.at(0, 66, 0).getY() + 0.5,
                    f.at(0, 66, 0).getZ() + 0.5,
                    4.0F, true, Level.ExplosionInteraction.NONE);
            helper.assertFalse(f.contains(from, to, Blocks.FIRE),
                    "a fiery explosion places no fire inside the plot");
            helper.succeed();
        });
    }

    // ---- the column above a plot (falling blocks) ----

    @GameTest
    public void visitorSandAbovePlotRefused(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            ServerPlayer owner = f.owner();
            ServerPlayer visitor = f.player();
            // y=102 is the first block above the plot's box and its margin.
            BlockPos low = f.at(0, 102, 0);
            BlockPos top = low.atY(f.level.getMaxY());

            try (var cap = MpLog.capture()) {
                placeSand(f, visitor, low);
                placeSand(f, visitor, top);
                helper.assertTrue(f.level.getBlockState(low).isAir(), "no sand at y=102 above the plot");
                helper.assertTrue(f.level.getBlockState(top).isAir(), "no sand at the build limit above it");
                helper.assertTrue(cap.lines().contains(refusalLine(visitor, owner, 1, "place", low.north())),
                        "the refusal reports the clicked position above the plot");
            }
            helper.succeed();
        });
    }

    @GameTest
    public void ownerSandAbovePlotAllowed(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            ServerPlayer owner = f.owner();
            BlockPos low = f.at(0, 102, 0);
            BlockPos top = low.atY(f.level.getMaxY());

            placeSand(f, owner, low);
            placeSand(f, owner, top);
            helper.assertTrue(f.level.getBlockState(low).is(Blocks.SAND), "the owner placed sand at y=102");
            helper.assertTrue(f.level.getBlockState(top).is(Blocks.SAND), "and at the build limit");
            helper.succeed();
        });
    }

    @GameTest
    public void visitorSandAboveOutsideAllowed(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            f.owner();
            // Two blocks out from the -z edge: outside the column and its margin.
            BlockPos outside = f.at(0, 102, -38);

            placeSand(f, f.player(), outside);
            helper.assertTrue(f.level.getBlockState(outside).is(Blocks.SAND),
                    "a visitor may place sand above ground two blocks outside the plot");
            helper.succeed();
        });
    }

    // ---- entities ----

    private static ItemFrame frameWithItem(Fixture f) {
        ItemFrame frame = f.spawn(new ItemFrame(f.level, f.at(0, 65, 0), Direction.UP));
        frame.setItem(new ItemStack(Items.DIAMOND), false);
        return frame;
    }

    private static ArmorStand armorStand(Fixture f) {
        Vec3 feet = Vec3.atBottomCenterOf(f.at(2, 64, 0));
        return f.spawn(new ArmorStand(f.level, feet.x, feet.y, feet.z));
    }

    private static Painting painting(Fixture f) {
        return f.spawn(new Painting(f.level, f.at(4, 65, 0), Direction.NORTH, f.level.registryAccess()
                .lookupOrThrow(Registries.PAINTING_VARIANT).getOrThrow(PaintingVariants.KEBAB)));
    }

    private static Entity chestMinecart(Fixture f, BlockPos pos) {
        Entity cart = EntityTypes.CHEST_MINECART.create(f.level, EntitySpawnReason.COMMAND);
        cart.setPos(Vec3.atBottomCenterOf(pos));
        return f.spawn(cart);
    }

    /** A visitor's left-click and right-click on an entity inside the plot are refused and logged. */
    private static void visitorEntityRefused(GameTestHelper helper, Function<Fixture, Entity> make,
                                             Consumer<Entity> untouched) {
        run(helper, f -> {
            f.enableProtection();
            ServerPlayer owner = f.owner();
            ServerPlayer visitor = f.player();
            Entity entity = make.apply(f);

            try (var cap = MpLog.capture()) {
                // Twice: an armor stand outside creative breaks on the second hit.
                attackEntity(visitor, entity);
                attackEntity(visitor, entity);
                useEntity(visitor, entity);
                helper.assertTrue(entity.isAlive(), "the entity survives a visitor's attack");
                helper.assertFalse(visitor.hasContainerOpen(), "no menu opened");
                untouched.accept(entity);
                for (String action : List.of("break", "use")) {
                    helper.assertTrue(cap.lines().contains(
                                    refusalLine(visitor, owner, 1, action, entity.blockPosition())),
                            "whole plot_edit_refused " + action + " line at the entity");
                }
            }
            helper.succeed();
        });
    }

    @GameTest
    public void visitorItemFrameKeepsItem(GameTestHelper helper) {
        visitorEntityRefused(helper, PlotProtectionGameTest::frameWithItem, entity -> {
            ItemFrame frame = (ItemFrame) entity;
            helper.assertTrue(frame.getItem().is(Items.DIAMOND), "the frame still holds its item");
            helper.assertTrue(frame.getRotation() == 0, "the item is not rotated");
        });
    }

    @GameTest
    public void visitorArmorStandSurvives(GameTestHelper helper) {
        visitorEntityRefused(helper, PlotProtectionGameTest::armorStand, entity -> {});
    }

    @GameTest
    public void visitorPaintingSurvives(GameTestHelper helper) {
        visitorEntityRefused(helper, PlotProtectionGameTest::painting, entity -> {});
    }

    @GameTest
    public void visitorChestMinecartRefusedNoMenu(GameTestHelper helper) {
        visitorEntityRefused(helper, f -> chestMinecart(f, f.at(0, 64, 0)), entity -> {});
    }

    @GameTest
    public void ownerEntityActionsAllowed(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            ServerPlayer owner = f.owner();
            ItemFrame frame = frameWithItem(f);
            ArmorStand stand = armorStand(f);
            Painting painting = painting(f);
            Entity cart = chestMinecart(f, f.at(6, 64, 0));

            attackEntity(owner, frame);
            helper.assertTrue(frame.getItem().isEmpty(), "the owner took the item out of the frame");
            attackEntity(owner, stand);
            attackEntity(owner, stand);
            helper.assertFalse(stand.isAlive(), "the owner broke the armor stand");
            attackEntity(owner, painting);
            helper.assertFalse(painting.isAlive(), "the owner broke the painting");
            useEntity(owner, cart);
            helper.assertTrue(owner.hasContainerOpen(), "the owner opened the chest minecart");
            helper.succeed();
        });
    }

    @GameTest
    public void visitorUsesEntityOutside(GameTestHelper helper) {
        run(helper, f -> {
            f.enableProtection();
            f.owner();
            ServerPlayer visitor = f.player();
            // Two blocks out from the -z edge: outside the one-block margin.
            useEntity(visitor, chestMinecart(f, f.at(0, 64, -38)));
            helper.assertTrue(visitor.hasContainerOpen(),
                    "a visitor may open a chest minecart two blocks outside the plot");
            helper.succeed();
        });
    }
}
