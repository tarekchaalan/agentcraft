package dev.agentcraft.client;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.world.WorldProfile;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.function.Function;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.Minecraft;
import dev.agentcraft.client.mixin.BackupConfirmScreenAccessor;
import net.minecraft.client.gui.screens.AccessibilityOnboardingScreen;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.TitleScreen;
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
 * Boots straight into the AgentCraft world without any clicks: the first time the title screen
 * appears, the selected profile's world ({@link WorldProfile}: the studio "AgentCraft HQ" by
 * default, "AgentCraft Survival" with {@code AGENTCRAFT_WORLD=survival}) is loaded if it exists,
 * or created if it does not. The studio is creative, peaceful, a superflat grass meadow with no
 * structures or decoration; survival is a normal world in survival mode. Disable with
 * AGENTCRAFT_AUTOWORLD=0.
 */
public final class AutoWorld {
	/** Seed of every studio world: the same meadow for everyone (there is nothing to see in it anyway). */
	private static final long STUDIO_SEED = "agentcraft-hq".hashCode();
	private static boolean attempted;
	/** True while AutoWorld itself is opening the world (so its confirm screens may be auto-answered). */
	private static boolean openingHq;

	private AutoWorld() {
	}

	public static void init() {
		if (!ClientEnv.AUTO_WORLD) {
			AgentCraft.LOGGER.info("AutoWorld disabled (AGENTCRAFT_AUTOWORLD=0)");
			return;
		}
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> openingHq = false);
		ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
			if (openingHq && screen instanceof BackupConfirmScreen backup) {
				// Registry content changed since the world was saved (a block/entity was renamed or removed
				// while developing). The HQ is generated, so take Fabric's backup and load it instead of
				// waiting forever on "Missing content detected!" in an unattended run.
				openingHq = false;
				AgentCraft.LOGGER.warn("AutoWorld: '{}' needs confirmation ({}); making a backup and loading it",
					WorldProfile.selected().levelName(), screen.getTitle().getString());
				client.execute(() -> ((BackupConfirmScreenAccessor) backup).agentcraft$onProceed().proceed(true, false));
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

	public static void openOrCreate(Minecraft mc) {
		WorldProfile profile = WorldProfile.selected();
		String name = profile.levelName();
		try {
			if (mc.getLevelSource().levelExists(name)) {
				AgentCraft.LOGGER.info("AutoWorld: loading existing {} world '{}'", profile.id(), name);
				openingHq = true;
				mc.createWorldOpenFlows().openWorld(name, () -> mc.gui.setScreen(new TitleScreen()));
			} else {
				long seed = seed(profile);
				AgentCraft.LOGGER.info("AutoWorld: creating {} world '{}' (seed {})", profile.id(), name, seed);
				LevelSettings settings = profile.survival()
					? new LevelSettings(name, GameType.SURVIVAL, new LevelSettings.DifficultySettings(Difficulty.NORMAL, false, false), true,
						WorldDataConfiguration.DEFAULT)
					: new LevelSettings(name, GameType.CREATIVE, new LevelSettings.DifficultySettings(Difficulty.PEACEFUL, false, false), true,
						WorldDataConfiguration.DEFAULT);
				// natural worlds get structures (villages, temples: that is the game); the meadow gets none
				WorldOptions options = new WorldOptions(seed, profile.naturalTerrain(), false);
				Function<HolderLookup.Provider, WorldDimensions> dimensions = profile.naturalTerrain()
					? WorldPresets::createNormalWorldDimensions
					: AutoWorld::meadowDimensions;
				mc.createWorldOpenFlows().createFreshLevel(name, settings, options, dimensions, new TitleScreen());
			}
		} catch (Exception e) {
			AgentCraft.LOGGER.error("AutoWorld failed; staying on the title screen", e);
			mc.gui.setScreen(new TitleScreen());
		}
	}

	/**
	 * {@code AGENTCRAFT_WORLD_SEED} (a number, or any text hashed like the world-creation screen does)
	 * when set; else the fixed studio seed, or a random seed for a natural world.
	 */
	static long seed(WorldProfile profile) {
		String s = WorldProfile.raw(WorldProfile.SEED_ENV);
		if (s != null) {
			OptionalLong parsed = WorldOptions.parseSeed(s);
			if (parsed.isPresent()) {
				return parsed.getAsLong();
			}
		}
		return profile.naturalTerrain() ? WorldOptions.randomSeed() : STUDIO_SEED;
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
