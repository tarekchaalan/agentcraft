package dev.agentcraft.client;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.mp.MpEvents;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.MpReasons;
import dev.agentcraft.world.HqWorld;
import java.util.List;
import java.util.Optional;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import dev.agentcraft.client.mixin.BackupConfirmScreenAccessor;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.FlatLevelSource;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.flat.FlatLayerInfo;
import net.minecraft.world.level.levelgen.flat.FlatLevelGeneratorSettings;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

/**
 * Boots straight into the "AgentCraft HQ" world without any clicks: the first time the title
 * screen appears, the world is loaded if it exists, or created (creative, peaceful, superflat
 * grass meadow with no structures/decoration) if it does not. Disable with AGENTCRAFT_AUTOWORLD=0.
 */
public final class AutoWorld {
	private static boolean attempted;
	/** True while AutoWorld itself is opening the HQ world (so its confirm screens may be auto-answered). */
	private static boolean openingHq;
	/** Set once when AutoWorld must stay out of the way for the whole client session. */
	private static boolean skipped;

	private AutoWorld() {
	}

	public static void init() {
		if (!ClientEnv.AUTO_WORLD) {
			AgentCraft.LOGGER.info("AutoWorld disabled (AGENTCRAFT_AUTOWORLD=0)");
			skip(MpReasons.DISABLED);
			return;
		}
		if (ClientEnv.quickPlayMultiplayer()) {
			// A direct multiplayer join: the local HQ world must never be created or opened.
			skip(MpReasons.QUICKPLAY_MULTIPLAYER);
			return;
		}
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> openingHq = false);
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (openingHq && screen instanceof BackupConfirmScreen backup) {
				// Registry content changed since the HQ world was saved (a block/entity was renamed or removed
				// while developing). The HQ world is generated, so take Fabric's backup and load it instead of
				// waiting forever on "Missing content detected!" in an unattended run.
				openingHq = false;
				AgentCraft.LOGGER.warn("AutoWorld: '{}' needs confirmation ({}); making a backup and loading it",
					HqWorld.LEVEL_NAME, screen.getTitle().getString());
				client.execute(() -> ((BackupConfirmScreenAccessor) backup).agentcraft$onProceed().proceed(true, false));
				return;
			}
			if (skipped) {
				return;
			}
			if (screen instanceof JoinMultiplayerScreen || screen instanceof ConnectScreen) {
				// The player picked multiplayer before AutoWorld opened the HQ world (for example after
				// a failed quick play). Cancel for the session so the deferred open cannot steal it.
				skip(MpReasons.MULTIPLAYER_SCREEN);
				attempted = true;
				return;
			}
			if (attempted) {
				return;
			}
			if (screen instanceof TitleScreen || screen instanceof AccessibilityOnboardingScreen) {
				attempted = true;
				// Never switch screens from inside another screen's init.
				client.execute(() -> openOrCreate(client));
			}
		});
	}

	/**
	 * Records the one-time skip for this session. Package-private test seam; the reason is always a
	 * {@link MpReasons} constant, never a launch argument or a server address.
	 */
	static void skip(String reason) {
		if (skipped) {
			return;
		}
		skipped = true;
		MpLog.event(MpEvents.AUTOWORLD_SKIPPED, "reason", reason);
	}

	public static void openOrCreate(Minecraft mc) {
		if (mc.gui.screen() instanceof JoinMultiplayerScreen || mc.gui.screen() instanceof ConnectScreen) {
			// The player opened multiplayer while the deferred open was queued.
			skip(MpReasons.MULTIPLAYER_SCREEN);
		}
		if (skipped) {
			return;
		}
		try {
			if (mc.getLevelSource().levelExists(HqWorld.LEVEL_NAME)) {
				AgentCraft.LOGGER.info("AutoWorld: loading existing world '{}'", HqWorld.LEVEL_NAME);
				openingHq = true;
				mc.createWorldOpenFlows().openWorld(HqWorld.LEVEL_NAME, () -> mc.gui.setScreen(new TitleScreen()));
			} else {
				AgentCraft.LOGGER.info("AutoWorld: creating world '{}'", HqWorld.LEVEL_NAME);
				LevelSettings settings = new LevelSettings(
					HqWorld.LEVEL_NAME,
					GameType.CREATIVE,
					new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false),
					true,
					WorldDataConfiguration.DEFAULT
				);
				WorldOptions options = new WorldOptions("agentcraft-hq".hashCode(), false, false);
				mc.createWorldOpenFlows().createFreshLevel(HqWorld.LEVEL_NAME, settings, options, AutoWorld::meadowDimensions, new TitleScreen());
			}
		} catch (Exception e) {
			AgentCraft.LOGGER.error("AutoWorld failed; staying on the title screen", e);
			mc.gui.setScreen(new TitleScreen());
		}
	}

	/** Normal dimensions, with the overworld replaced by a flat plains meadow (grass top at y=64). */
	private static WorldDimensions meadowDimensions(HolderLookup.Provider registries) {
		Holder<Biome> plains = registries.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.PLAINS);
		FlatLevelGeneratorSettings base = new FlatLevelGeneratorSettings(Optional.of(HolderSet.empty()), plains, List.of());
		// y=-64 bedrock, stone up to 60, dirt 61..63, grass 64.
		List<FlatLayerInfo> layers = List.of(
			new FlatLayerInfo(1, Blocks.BEDROCK),
			new FlatLayerInfo(124, Blocks.STONE),
			new FlatLayerInfo(3, Blocks.DIRT),
			new FlatLayerInfo(1, Blocks.GRASS_BLOCK)
		);
		FlatLevelGeneratorSettings flat = base.withBiomeAndLayers(layers, Optional.of(HolderSet.empty()), plains);
		return WorldPresets.createNormalWorldDimensions(registries).replaceOverworldGenerator(registries, new FlatLevelSource(flat));
	}
}
