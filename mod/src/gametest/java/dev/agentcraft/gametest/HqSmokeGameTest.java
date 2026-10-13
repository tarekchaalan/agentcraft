package dev.agentcraft.gametest;

import dev.agentcraft.block.ModBlocks;
import dev.agentcraft.block.entity.StationBlockEntity;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

public final class HqSmokeGameTest {
	/** Tove's status lamp in the hall's north wall: the first desk bay of {@code StudioHqBuilder.desks}. */
	private static final BlockPos TOVE_LAMP = new BlockPos(-15, 69, -10);

	@GameTest(maxTicks = 20)
	public void hqCommandBuildsPlotZeroAndPublishes69Anchors(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		long revisionBefore = Anchors.current().revision();

		// performPrefixedCommand returns void and reports a failure only to the silenced source, so the
		// command's own result comes back through the source callback: the anchor count, or 0 when the
		// builder failed. A command that never ran leaves -1.
		int[] result = {-1};
		server.getCommands().performPrefixedCommand(
			server.createCommandSourceStack().withSuppressedOutput().withCallback((success, value) -> result[0] = success ? value : -1),
			"agentcraft hq"
		);
		helper.assertValueEqual(result[0], 69, "/agentcraft hq result");

		Anchors.Layout layout = Anchors.current();
		helper.assertValueEqual(layout.revision(), revisionBefore + 1, "layout revision after this command");
		helper.assertValueEqual(layout.anchors().size(), 69, "published anchor count");

		Anchor spawn = layout.get(AnchorNames.SPAWN);
		if (spawn == null) {
			helper.fail("Plot 0 layout did not publish the spawn anchor");
		}
		helper.assertValueEqual(spawn.x(), 0.5, "plot 0 spawn x");
		helper.assertValueEqual(spawn.y(), 66.0, "plot 0 spawn y");
		helper.assertValueEqual(spawn.z(), 22.5, "plot 0 spawn z");

		// The anchors come from constants, so they do not show that anything was built. A station block
		// with its binding does: absolute world coordinates, not positions relative to the test structure.
		ServerLevel overworld = server.overworld();
		helper.assertTrue(overworld.getBlockState(TOVE_LAMP).is(ModBlocks.STATUS_LAMP), "tove's status lamp is placed at " + TOVE_LAMP.toShortString());
		helper.assertTrue(
			overworld.getBlockEntity(TOVE_LAMP) instanceof StationBlockEntity lamp && "agent:tove".equals(lamp.binding()),
			"tove's status lamp is bound to agent:tove"
		);
		helper.succeed();
	}
}
