package dev.agentcraft.mp.server.world;

import dev.agentcraft.mp.MpEvents;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.MpReasons;
import dev.agentcraft.mp.MpServerConfig;
import dev.agentcraft.world.HqWorld;
import java.util.List;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import org.jspecify.annotations.Nullable;

/**
 * Multiplayer world check: an enabled dedicated server must run a superflat world whose grass top is
 * at {@link HqWorld#SURFACE_Y}. The check reads the persisted generator settings, not blocks, so an
 * already-built plot or an unloaded chunk cannot make it lie. Registered before every other
 * server-start feature; a bad world logs {@code config_invalid} and then throws, stopping the server
 * before any rule, plot or player is applied.
 */
public final class ServerWorldFeature {
    private ServerWorldFeature() {
    }

    public static void init() {
        ServerLifecycleEvents.SERVER_STARTED.register(ServerWorldFeature::checkSurface);
    }

    /** Enabled dedicated servers only; public as the game-test seam and the SERVER_STARTED entry point. */
    public static void checkSurface(MinecraftServer server) {
        if (!server.isDedicatedServer() || !MpServerConfig.current().enabled()) {
            return;
        }
        String problem = surfaceProblem(server.overworld().getChunkSource().getGenerator(), server.overworld());
        if (problem == null) {
            return;
        }
        // value is one of the three closed shapes; reason is the frozen constant.
        MpLog.event(MpEvents.CONFIG_INVALID, "key", "world.surface", "value", problem, "reason", MpReasons.SURFACE_NOT_64);
        throw new IllegalStateException("AgentCraft multiplayer requires a superflat world with grass at y="
            + HqWorld.SURFACE_Y + " (found " + problem + ")");
    }

    /** Pure rule over the live generator/height; null when flat with its top layer grass at y=64. */
    public static @Nullable String surfaceProblem(ChunkGenerator generator, LevelHeightAccessor height) {
        if (!(generator instanceof FlatLevelSource flat)) {
            return surfaceProblem(false, 0, false);
        }
        List<BlockState> layers = flat.settings().getLayers();
        int topY = height.getMinY() + layers.size() - 1;
        boolean topIsGrass = !layers.isEmpty() && layers.get(layers.size() - 1).is(Blocks.GRASS_BLOCK);
        return surfaceProblem(true, topY, topIsGrass);
    }

    /** Pure rule over the three primitives; exposed so unit and game tests can assert it directly. */
    public static @Nullable String surfaceProblem(boolean flat, int topY, boolean topIsGrass) {
        if (!flat) {
            return "not_flat";
        }
        if (topY != HqWorld.SURFACE_Y) {
            return "top_y_" + topY;
        }
        if (!topIsGrass) {
            return "top_not_grass";
        }
        return null;
    }
}
