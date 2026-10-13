package dev.agentcraft.hq;

import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.world.HqSite;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jspecify.annotations.Nullable;

/**
 * Builds an HQ into the world and describes it with anchors. Registered in {@link HqBuilders};
 * {@code /agentcraft hq [id]} runs one and publishes its anchors ({@link Anchors#publish}).
 *
 * <p>Contract: write every required anchor from {@link dev.agentcraft.layout.AnchorNames}
 * (desk_/monitor_ per cast agent, the shared stations with enough slots, task_wall,
 * decision_podium, goal_atrium, entrance, spawn) and set {@link Anchors.Builder#bounds} to the
 * walkable HQ region. Bind station block entities ({@code StationBlockEntity#setBinding}) as you
 * place them. Must be deterministic and idempotent (running it twice gives the same world).
 */
public interface HqBuilder {
	String id();

	String description();

	/** Runs on the server thread. */
	void build(ServerLevel level, Anchors.Builder anchors);

	/**
	 * How a build was asked for.
	 *
	 * @param force reset every cell of the site to the plan, even the ones the player changed since
	 *     the last build (builders that keep such cells honour it).
	 * @param site where to build in a world with its own terrain: the plan's origin is placed there
	 *     and the ground is blended into the surroundings. Null = the studio world's flat meadow at
	 *     the origin (builders that cannot move ignore it).
	 * @param survival the world is played in survival: builders add what that needs (doors, a bed and
	 *     tools, lighting that keeps mobs from spawning on the site).
	 * @param origin where the studio meadow is built when there is no {@code site}: {@link BlockPos#ZERO}
	 *     in singleplayer, a plot's origin on a multiplayer server (x and z multiples of 16). A plot is
	 *     just another place for the plan: the same build, translated.
	 * @param studio whose studio this is: {@link StudioId#LOCAL} in singleplayer (its plan file is the
	 *     world's), a plot owner on a multiplayer server (each plot keeps its own plan file).
	 */
	record Options(boolean force, @Nullable HqSite site, boolean survival, BlockPos origin, StudioId studio) {
		public Options {
			origin = origin.immutable();
			Objects.requireNonNull(studio);
		}

		public static final Options DEFAULT = new Options(false);

		public Options(boolean force) {
			this(force, null, false);
		}

		/** Singleplayer: the studio meadow at the origin ({@code site} null), or a natural world's site. */
		public Options(boolean force, @Nullable HqSite site, boolean survival) {
			this(force, site, survival, BlockPos.ZERO, StudioId.LOCAL);
		}

		/** A multiplayer plot: the studio meadow at the plot's origin, owned by {@code studio}. */
		public Options(boolean force, BlockPos origin, StudioId studio) {
			this(force, null, false, origin, studio);
		}
	}

	/**
	 * Builds with options and returns a one-line report for the player (null = nothing to say).
	 * The default ignores the options.
	 */
	default @Nullable String build(ServerLevel level, Anchors.Builder anchors, Options options) {
		build(level, anchors);
		return null;
	}
}
