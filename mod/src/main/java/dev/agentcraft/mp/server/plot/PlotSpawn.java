package dev.agentcraft.mp.server.plot;

import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.storage.LevelData;

/** Per-plot respawn. The global world spawn is never moved from here. */
public final class PlotSpawn {
    private PlotSpawn() {}

    /** A plot always lives in the overworld, wherever the player currently stands. */
    public static ServerLevel homeLevel(ServerPlayer player) {
        return player.level().getServer().overworld();
    }

    public static boolean applyRespawn(ServerPlayer player, Anchors.Layout layout) {
        Anchor spawn = layout.get(AnchorNames.SPAWN);
        if (player == null || spawn == null) return false;
        LevelData.RespawnData data = LevelData.RespawnData.of(
            homeLevel(player).dimension(),
            BlockPos.containing(spawn.x(), spawn.y(), spawn.z()),
            spawn.yaw(),
            spawn.pitch());
        player.setRespawnPosition(new ServerPlayer.RespawnConfig(data, true), false);
        return true;
    }

    /** Mock players have no connection; the eight-argument teleport touches it after a successful move. */
    public static boolean teleportHome(ServerPlayer player, Anchors.Layout layout) {
        Anchor spawn = layout.get(AnchorNames.SPAWN);
        if (player == null || spawn == null) return false;
        if (player.connection == null) {
            player.absSnapTo(spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch());
            return true;
        }
        return player.teleportTo(homeLevel(player), spawn.x(), spawn.y(), spawn.z(), Set.of(), spawn.yaw(), spawn.pitch(), true);
    }
}
