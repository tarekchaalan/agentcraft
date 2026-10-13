package dev.agentcraft.gametest;

import dev.agentcraft.block.ModBlocks;
import dev.agentcraft.block.entity.StationBlockEntity;
import dev.agentcraft.hq.HqBuilder;
import dev.agentcraft.hq.StudioHqBuilder;
import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.world.HqSite;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;

/**
 * The singleplayer natural-world build (the survival profile): the studio plan is placed at the
 * world's HQ site, its ground fitted to the terrain there, with the survival additions. The same
 * builder also places plots (a plot origin, the flat meadow); this pins the site path next to them.
 * The layout is not published, so the world spawn and the current layout stay as other tests left them.
 */
public final class SurvivalSiteGameTest {
	/** Far from the plots of the other tests, on the game-test world's flat sandstone (top y = 3). */
	private static final HqSite SITE = new HqSite(4096, 3, 4096);
	/** Tove's status lamp in the hall's north wall, in design coordinates (ground y = 64). */
	private static final BlockPos TOVE_LAMP = new BlockPos(-15, 69, -10);

	@GameTest(maxTicks = 200)
	public void survivalStudioIsBuiltOnItsSite(GameTestHelper helper) {
		ServerLevel level = helper.getLevel();
		StudioHqBuilder builder = new StudioHqBuilder();
		Anchors.Builder anchors = Anchors.builder(builder.id());
		String report = builder.build(level, anchors, new HqBuilder.Options(false, SITE, true));
		Anchors.Layout layout = anchors.build();
		helper.assertTrue(report != null && report.contains("blocks updated"), "build report: " + report);

		int dy = SITE.y() - 64;
		Anchor spawn = layout.get(AnchorNames.SPAWN);
		if (spawn == null) {
			helper.fail("the site layout has no spawn anchor");
		}
		helper.assertValueEqual(spawn.x(), SITE.x() + 0.5, "spawn x at the site");
		helper.assertValueEqual(spawn.y(), 66.0 + dy, "spawn y on the site's ground");
		helper.assertValueEqual(spawn.z(), SITE.z() + 22.5, "spawn z at the site");
		helper.assertTrue(layout.bounds() != null
			&& layout.bounds().contains((int) Math.floor(spawn.x()), (int) Math.floor(spawn.y()), (int) Math.floor(spawn.z())), "the bounds are moved with the anchors");

		BlockPos lamp = TOVE_LAMP.offset(SITE.x(), dy, SITE.z());
		helper.assertTrue(level.getBlockState(lamp).is(ModBlocks.STATUS_LAMP), "tove's status lamp is placed at " + lamp.toShortString());
		helper.assertTrue(level.getBlockEntity(lamp) instanceof StationBlockEntity be && "agent:tove".equals(be.binding()),
			"tove's status lamp is bound to agent:tove");
		helper.succeed();
	}
}
