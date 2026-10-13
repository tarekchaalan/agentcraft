package dev.agentcraft;

import dev.agentcraft.block.ModBlocks;
import dev.agentcraft.block.ModItems;
import dev.agentcraft.block.entity.ModBlockEntities;
import dev.agentcraft.command.AgentCraftCommands;
import dev.agentcraft.entity.ModEntities;
import dev.agentcraft.hq.HqFeature;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.world.HqWorld;
import dev.agentcraft.mp.MpServerConfig;
import dev.agentcraft.mp.net.MpPayloads;
import dev.agentcraft.mp.server.StudioRange;
import dev.agentcraft.mp.server.world.ServerWorldFeature;
import dev.agentcraft.mp.server.plot.PlotFeature;
import dev.agentcraft.mp.server.layout.LayoutSyncFeature;
import dev.agentcraft.mp.server.relay.RelayFeature;
import dev.agentcraft.mp.server.intent.WorldIntentFeature;
import dev.agentcraft.mp.server.protect.PlotProtectionFeature;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common (both sides) entrypoint: registries (blocks, block entities, items + creative tab, the agent
 * entity type), the HQ world rules, the anchor registry and the {@code /agentcraft} command. Client
 * features are wired in {@code dev.agentcraft.client.ClientFeatures}.
 */
public class AgentCraft implements ModInitializer {
	public static final String MOD_ID = "agentcraft";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		MpServerConfig.init();
		MpPayloads.register();
		StudioRange.init();
		ServerWorldFeature.init();
		PlotFeature.init();
		LayoutSyncFeature.init();
		RelayFeature.init();
		WorldIntentFeature.init();
		PlotProtectionFeature.init();
		ModBlocks.init();
		ModBlockEntities.init();
		ModItems.init();
		ModEntities.init();
		HqWorld.init();
		Anchors.init();
		AgentCraftCommands.init();
		HqFeature.init();
		LOGGER.info("AgentCraft common init done ({} blocks, cast {})", ModBlocks.all().size(), Cast.ids());
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
