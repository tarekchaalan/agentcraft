package dev.agentcraft.client.mp.visitor;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.block.ModBlocks;
import dev.agentcraft.block.entity.StationBlockEntity;
import dev.agentcraft.client.agents.AgentCardScreen;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.hud.Keys;
import dev.agentcraft.client.mp.MpMode;
import dev.agentcraft.client.mp.RemoteAgentClicks;
import dev.agentcraft.client.mp.StudioView;
import dev.agentcraft.client.mp.Studios;
import dev.agentcraft.client.mp.visitor.VisitorGate.Route;
import dev.agentcraft.client.mp.visitor.VisitorGate.Station;
import dev.agentcraft.client.world.StationInteractions;
import dev.agentcraft.mp.Plot;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.state.PublicAgent;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.Event;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * MP-11's Minecraft-facing half. It installs {@link StationInteractions.Gate} so a right-click on a
 * station in a remote studio opens {@link VisitorStationScreen} instead of the station's own screen,
 * and registers the read-only remote agent card with the {@link RemoteAgentClicks} foundation seam
 * (MP-08 fires it; MP-11 never edits {@code AgentsFeature}).
 *
 * <p>Own-studio and singleplayer clicks are untouched: the gate only takes the visitor path when
 * {@link Studios#at(BlockPos)} resolves a studio that is not the viewer's own. In multiplayer a
 * station in no known studio opens nothing ({@link Route#CONSUMED}), and the terminal key follows
 * the same route. The console key and the decisions key still talk to the local Foreman.
 *
 * <p>DevBridge: {@code dev.visitor.use} opens the panel, {@code dev.visitor.click} runs the real
 * {@link UseBlockCallback} at a position, {@code dev.visitor.agent} fires the agent seam, and
 * {@code dev.screen {open:"visitor"}} shows a panel for the overlay.
 */
public final class VisitorFeature {
	private static final Map<Block, Station> STATIONS = new ConcurrentHashMap<>();
	/** A tick phase ahead of the default one, in which the console polls its terminal key. */
	private static final Identifier BEFORE_HANDLERS = AgentCraft.id("visitor_gate");

	private VisitorFeature() {
	}

	public static void init() {
		STATIONS.put(ModBlocks.DECISION_PODIUM, Station.PODIUM);
		STATIONS.put(ModBlocks.MERGE_STATION, Station.MERGE);
		STATIONS.put(ModBlocks.TASK_BOARD, Station.TASK_WALL);
		STATIONS.put(ModBlocks.MEMORY_ARCHIVE, Station.ARCHIVE);
		STATIONS.put(ModBlocks.MEMORY_CATALOG, Station.CATALOG);
		STATIONS.put(Blocks.LECTERN, Station.LECTERN);
		STATIONS.put(ModBlocks.CONSOLE_TERMINAL, Station.CONSOLE);
		StationInteractions.setGate(VisitorFeature::before);
		ClientTickEvents.END_CLIENT_TICK.addPhaseOrdering(BEFORE_HANDLERS, Event.DEFAULT_PHASE);
		ClientTickEvents.END_CLIENT_TICK.register(BEFORE_HANDLERS, VisitorFeature::gateTerminalKey);
		RemoteAgentClicks.set(VisitorFeature::openAgentCard);
		DevBridge.registerScreen("visitor", mc -> {
			for (StudioView view : Studios.all()) {
				if (!view.own()) {
					return new VisitorStationScreen(view.id(), Station.PODIUM);
				}
			}
			throw new DevBridge.DevException("no remote studio (run dev.mp.fake first)");
		});
		registerDev();
	}

	/**
	 * The gate: consume a remote click, otherwise let the station's own handler run. A remote block
	 * with a handler but no station mapping is still consumed (fail closed) and opens the generic
	 * {@link Station#STATION} panel.
	 */
	private static boolean before(Player player, BlockPos pos, BlockState state, @Nullable StationBlockEntity be) {
		Optional<StudioView> at = Studios.at(pos);
		Optional<Station> kind = VisitorGate.visitorKind(at, STATIONS.get(state.getBlock()));
		if (kind.isEmpty()) {
			// the own handler runs, except in multiplayer outside every known studio: nothing opens there
			return VisitorGate.route(multiplayer(), at) == Route.CONSUMED;
		}
		StudioView studio = at.orElseThrow();
		VisitorGate.logOpen(kind.get(), studio, player.getUUID());
		Minecraft.getInstance().gui.setScreen(new VisitorStationScreen(studio.id(), kind.get()));
		return true;
	}

	private static boolean multiplayer() {
		return MpMode.current() == MpMode.MULTIPLAYER;
	}

	/**
	 * The terminal key (Enter while looking at a console terminal) opens the own console without a
	 * click. In multiplayer, on a terminal outside the own studio, its presses are taken here, a phase
	 * ahead of the console's key handler, and gated like a click. Otherwise the key is not read.
	 */
	private static void gateTerminalKey(Minecraft mc) {
		if (!multiplayer() || Keys.terminal == null || mc.player == null || mc.level == null || mc.gui.screen() != null
			|| !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
			return;
		}
		BlockState state = mc.level.getBlockState(hit.getBlockPos());
		if (state.getBlock() != ModBlocks.CONSOLE_TERMINAL
			|| VisitorGate.route(true, Studios.at(hit.getBlockPos())) == Route.HANDLER) {
			return;
		}
		boolean pressed = false;
		while (Keys.terminal.consumeClick()) {
			pressed = true;
		}
		if (pressed) {
			before(mc.player, hit.getBlockPos(), state, null);
		}
	}

	/** The {@link RemoteAgentClicks} handler: a read-only card built from the public state only. */
	private static void openAgentCard(StudioView studio, String agentId) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null) {
			return;
		}
		// the registry's current view, and only a remote one: the own studio keeps its own card
		Optional<StudioView> view = Studios.view(studio.id());
		if (VisitorGate.route(multiplayer(), view) != Route.VISITOR) {
			return;
		}
		Optional<PublicAgent> agent = VisitorGate.findAgent(view.get(), agentId);
		if (agent.isEmpty()) {
			return;
		}
		VisitorGate.logOpen(Station.AGENT, view.get(), mc.player.getUUID());
		mc.gui.setScreen(new AgentCardScreen(view.get(), agent.get()));
	}

	// ------------------------------------------------------------------ dev

	private static void registerDev() {
		DevBridge.register("dev.visitor.use", 10_000,
			"{station: podium|merge|task_wall|archive|catalog|lectern|console|station, studio?: uuid} -> open the read-only visitor panel for a remote studio's station",
			(req, mc) -> {
				Fields f = Fields.of(req);
				Station kind = Station.byToken(f.nonBlank("station"));
				if (kind == null || kind == Station.AGENT) {
					throw new DevBridge.DevException("station must be one of the station tokens");
				}
				String studioArg = f.optStr("studio", null);
				return DevBridge.onClient(mc, () -> {
					if (mc.player == null) {
						throw new DevBridge.DevException("no player");
					}
					StudioView studio = resolveStudio(studioArg);
					if (VisitorGate.route(multiplayer(), Optional.of(studio)) != Route.VISITOR) {
						throw new DevBridge.DevException("studio is not remote; a click opens the real station");
					}
					VisitorGate.logOpen(kind, studio, mc.player.getUUID());
					mc.gui.setScreen(new VisitorStationScreen(studio.id(), kind));
					return describe(studio, kind);
				});
			});
		DevBridge.register("dev.visitor.click", 10_000,
			"{x,y,z} -> run the real UseBlockCallback at a block (the visitor click path) and report the result and screen",
			(req, mc) -> {
				Fields f = Fields.of(req);
				int x = (int) f.integer("x", -30_000_000, 30_000_000);
				int y = (int) f.integer("y", -30_000_000, 30_000_000);
				int z = (int) f.integer("z", -30_000_000, 30_000_000);
				return DevBridge.onClient(mc, () -> {
					if (mc.player == null || mc.level == null) {
						throw new DevBridge.DevException("no world");
					}
					BlockPos pos = new BlockPos(x, y, z);
					BlockHitResult hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
					InteractionResult result = UseBlockCallback.EVENT.invoker().interact(mc.player, mc.level, InteractionHand.MAIN_HAND, hit);
					JsonObject o = new JsonObject();
					o.addProperty("pos", x + "," + y + "," + z);
					o.addProperty("result", result == InteractionResult.FAIL ? "FAIL" : result == InteractionResult.PASS ? "PASS" : "SUCCESS");
					o.addProperty("screen", mc.gui.screen() == null ? null : mc.gui.screen().getClass().getSimpleName());
					Optional<StudioView> at = Studios.at(pos);
					o.addProperty("studio", at.map(v -> v.id().owner().toString()).orElse(null));
					o.addProperty("own", at.map(StudioView::own).orElse(null));
					if (mc.gui.screen() instanceof VisitorStationScreen s) {
						o.addProperty("station", s.station().token());
					}
					return o;
				});
			});
		DevBridge.register("dev.visitor.agent", 10_000,
			"{agent, studio?: uuid} -> fire the RemoteAgentClicks seam for a remote agent (the read-only card)",
			(req, mc) -> {
				Fields f = Fields.of(req);
				String agent = f.nonBlank("agent");
				String studioArg = f.optStr("studio", null);
				return DevBridge.onClient(mc, () -> {
					StudioView studio = resolveStudio(studioArg);
					RemoteAgentClicks.fire(studio, agent);
					JsonObject o = new JsonObject();
					o.addProperty("studio", studio.id().owner().toString());
					if (mc.gui.screen() instanceof AgentCardScreen card) {
						o.addProperty("screen", AgentCardScreen.class.getSimpleName());
						o.addProperty("agent", card.agentId());
						o.addProperty("visitor", card.visitorMode());
						o.addProperty("input", card.inputText());
					} else {
						o.addProperty("screen", mc.gui.screen() == null ? null : mc.gui.screen().getClass().getSimpleName());
						o.addProperty("visitor", false);
						o.addProperty("input", (String) null);
					}
					return o;
				});
			});
	}

	private static StudioView resolveStudio(@Nullable String studioArg) {
		if (studioArg != null) {
			UUID id;
			try {
				id = UUID.fromString(studioArg);
			} catch (IllegalArgumentException e) {
				throw new DevBridge.DevException("studio must be a UUID");
			}
			return Studios.view(StudioId.of(id)).orElseThrow(() -> new DevBridge.DevException("unknown studio " + studioArg));
		}
		for (StudioView view : Studios.all()) {
			if (!view.own()) {
				return view;
			}
		}
		throw new DevBridge.DevException("no remote studio (run dev.mp.fake first, or pass studio)");
	}

	private static JsonObject describe(StudioView studio, Station kind) {
		JsonObject o = new JsonObject();
		o.addProperty("screen", VisitorStationScreen.class.getSimpleName());
		o.addProperty("visitor", true);
		o.addProperty("station", kind.token());
		o.addProperty("studio", studio.id().owner().toString());
		o.addProperty("ownerName", studio.ownerName());
		o.addProperty("online", studio.online());
		o.addProperty("plot", Studios.plot(studio.id()).map(Plot::index).orElse(-1));
		o.addProperty("rev", studio.publicState() == null ? -1 : studio.publicState().rev());
		JsonArray lines = new JsonArray();
		VisitorGate.stationLines(studio.publicState(), kind).forEach(lines::add);
		o.add("lines", lines);
		return o;
	}
}
