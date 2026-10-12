package dev.agentcraft.hq;

import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.StudioId;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

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
	 * How a build was asked for: {@code force} = reset every cell of the site to the plan, even the
	 * ones the player changed since the last build (builders that keep such cells honour it).
	 */
	record Options(boolean force, BlockPos origin, StudioId studio) {
		public Options { origin = origin.immutable(); java.util.Objects.requireNonNull(studio); }
		public Options(boolean force) { this(force, BlockPos.ZERO, StudioId.LOCAL); }
		public static final Options DEFAULT = new Options(false);
	}

	/**
	 * Builds with options and returns a one-line report for the player (null = nothing to say).
	 * The default ignores the options.
	 */
	default @org.jspecify.annotations.Nullable String build(ServerLevel level, Anchors.Builder anchors, Options options) {
		build(level, anchors);
		return null;
	}
}
