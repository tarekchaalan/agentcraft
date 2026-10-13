package dev.agentcraft.gametest.mp.world;

import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.MpServerConfig;
import dev.agentcraft.mp.server.world.ServerWorldFeature;
import dev.agentcraft.world.HqWorld;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.gamerules.GameRule;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * MP-01 multiplayer mode on the shared game-test server: the config-driven rule profile, the
 * {@code worldRules} and {@code forceCreative} flags, and the surface refusal. Every test installs a
 * config, acts, asserts and restores its rules and config inside one server-thread method.
 */
public final class WorldModeGameTest {
	private static final int RULE_COUNT = 19;
	private static final MpServerConfig MULTIPLAYER =
		new MpServerConfig(true, 128, true, true, true, true, true, 12, 4, 10);

	private static final List<GameRule<?>> MULTIPLAYER_RULES = List.of(
		GameRules.ADVANCE_TIME, GameRules.ADVANCE_WEATHER, GameRules.KEEP_INVENTORY,
		GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, GameRules.SPAWN_MOBS, GameRules.SPAWN_MONSTERS,
		GameRules.SPAWN_PATROLS, GameRules.SPAWN_PHANTOMS, GameRules.SPAWN_WANDERING_TRADERS,
		GameRules.SPAWN_WARDENS, GameRules.MOB_GRIEFING, GameRules.SPREAD_VINES,
		GameRules.MAX_SNOW_ACCUMULATION_HEIGHT, GameRules.RESPAWN_RADIUS, GameRules.LOCATOR_BAR,
		GameRules.SHOW_ADVANCEMENT_MESSAGES, GameRules.COMMAND_BLOCK_OUTPUT, GameRules.LOG_ADMIN_COMMANDS,
		GameRules.MAX_BLOCK_MODIFICATIONS);

	@GameTest(maxTicks = 60)
	public void rulesApplyOnANonHqLevelAndLogAdminCommands(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		GameRules rules = server.getGameRules();
		List<Runnable> restore = snapshotRules(rules, server);
		MpServerConfig previous = MpServerConfig.install(MULTIPLAYER);
		try {
			helper.assertTrue(HqWorld.multiplayer(server), "installed config makes the game-test server multiplayer");
			// Move the two asserted rules away from the profile, so a no-op profile fails the assertions.
			rules.set(GameRules.ADVANCE_TIME, true, server);
			rules.set(GameRules.LOG_ADMIN_COMMANDS, false, server);

			try (MpLog.Capture capture = MpLog.capture()) {
				int count = HqWorld.applyServerRules(server);
				helper.assertValueEqual(count, RULE_COUNT, "rules written");
				helper.assertTrue(capture.lines().contains("event=world_rules_applied rules=" + RULE_COUNT),
					"world_rules_applied captured: " + capture.lines());
			}
			helper.assertFalse(rules.get(GameRules.ADVANCE_TIME), "ADVANCE_TIME off after the profile");
			helper.assertValueEqual(rules.get(GameRules.MAX_BLOCK_MODIFICATIONS).intValue(), 1_000_000, "MAX_BLOCK_MODIFICATIONS");
			helper.assertTrue(rules.get(GameRules.LOG_ADMIN_COMMANDS), "LOG_ADMIN_COMMANDS stays on");
		} finally {
			restoreRules(restore);
			MpServerConfig.install(previous);
		}
		helper.succeed();
	}

	@GameTest(maxTicks = 60)
	public void worldRulesFalseChangesNothing(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		GameRules rules = server.getGameRules();
		List<Runnable> restore = snapshotRules(rules, server);
		MpServerConfig previous = MpServerConfig.install(
			new MpServerConfig(true, 128, true, true, true, false, true, 12, 4, 10));
		try {
			rules.set(GameRules.LOG_ADMIN_COMMANDS, true, server);
			try (MpLog.Capture capture = MpLog.capture()) {
				helper.assertValueEqual(HqWorld.applyServerRules(server), 0, "rules written with worldRules off");
				helper.assertTrue(capture.lines().stream().noneMatch(l -> l.startsWith("event=world_rules_applied")),
					"no world_rules_applied with the flag off: " + capture.lines());
			}
			helper.assertTrue(rules.get(GameRules.LOG_ADMIN_COMMANDS), "no rule changed");
		} finally {
			restoreRules(restore);
			MpServerConfig.install(previous);
		}
		helper.succeed();
	}

	@GameTest(maxTicks = 60)
	public void forceCreativeHonoursTheFlag(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		FakePlayer player = FakePlayer.get(helper.getLevel());
		MpServerConfig previous = MpServerConfig.install(MULTIPLAYER);
		try {
			player.setGameMode(GameType.SURVIVAL);
			try (MpLog.Capture capture = MpLog.capture()) {
				HqWorld.onPlayerJoin(server, player);
				helper.assertValueEqual(player.gameMode(), GameType.CREATIVE, "forced game mode");
				helper.assertTrue(capture.lines().contains(
						"event=creative_forced player=" + player.getUUID() + " from_mode=survival"),
					"creative_forced captured: " + capture.lines());
			}

			MpServerConfig.install(new MpServerConfig(true, 128, true, true, false, true, true, 12, 4, 10));
			player.setGameMode(GameType.SURVIVAL);
			try (MpLog.Capture capture = MpLog.capture()) {
				HqWorld.onPlayerJoin(server, player);
				helper.assertValueEqual(player.gameMode(), GameType.SURVIVAL, "no forcing with the flag off");
				helper.assertTrue(capture.lines().stream().noneMatch(l -> l.startsWith("event=creative_forced")),
					"no creative_forced with the flag off: " + capture.lines());
			}
		} finally {
			player.setGameMode(GameType.CREATIVE);
			MpServerConfig.install(previous);
		}
		helper.succeed();
	}

	@GameTest(maxTicks = 60)
	public void surfaceCheckRefusesTheNonFlatGameTestWorld(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		MpServerConfig previous = MpServerConfig.install(MULTIPLAYER);
		try {
			helper.assertValueEqual(
				ServerWorldFeature.surfaceProblem(server.overworld().getChunkSource().getGenerator(), server.overworld()),
				"top_y_3", "the game-test world is sandstone at y=3");
			try (MpLog.Capture capture = MpLog.capture()) {
				boolean refused = false;
				try {
					ServerWorldFeature.checkSurface(server);
				} catch (IllegalStateException expected) {
					refused = true;
				}
				helper.assertTrue(refused, "a non-conforming world refuses startup");
				helper.assertTrue(capture.lines().contains(
						"event=config_invalid key=world.surface value=top_y_3 reason=surface_not_64"),
					"config_invalid captured: " + capture.lines());
			}
		} finally {
			MpServerConfig.install(previous);
		}
		helper.succeed();
	}

	private static List<Runnable> snapshotRules(GameRules rules, MinecraftServer server) {
		List<Runnable> restore = new ArrayList<>();
		for (GameRule<?> rule : MULTIPLAYER_RULES) {
			restore.add(snapshotRule(rules, rule, server));
		}
		return restore;
	}

	private static <T> Runnable snapshotRule(GameRules rules, GameRule<T> rule, MinecraftServer server) {
		T value = rules.get(rule);
		return () -> rules.set(rule, value, server);
	}

	private static void restoreRules(List<Runnable> restore) {
		restore.forEach(Runnable::run);
	}
}
