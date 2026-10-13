package dev.agentcraft.client.decisions;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.agentcraft.block.DecisionPodiumBlock;
import dev.agentcraft.block.ModBlocks;
import dev.agentcraft.block.entity.ModBlockEntities;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.foreman.Foreman;
import dev.agentcraft.client.foreman.ForemanListener;
import dev.agentcraft.client.foreman.ForemanState;
import dev.agentcraft.client.foreman.Protocol.Decision;
import dev.agentcraft.client.foreman.Protocol.DecisionKind;
import dev.agentcraft.client.hq.HqWorldDriver;
import dev.agentcraft.client.hud.Keys;
import dev.agentcraft.client.ui.Guard;
import dev.agentcraft.client.world.ServerTasks;
import dev.agentcraft.client.world.StationInteractions;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Decision Podium + decision GUI. Open decisions come in {@link DecisionQueue} order; the screen is
 * {@link DecisionScreen} (keys 1-9, Tab through the queue). Opened with {@code J} (rebindable), a
 * right-click on the podium, the HUD badge hint, or {@code /decide} in the console. The podium's
 * {@code open} block state follows "any decision open" (set on the integrated server, only when it
 * differs) and its renderer shows the waiting count and the first question above the desk.
 *
 * <p>QA: {@code dev.screen {open:"decision"}} (queue head), {@code dev.decision {decisionId?|kind?}} opens a
 * specific one, {@code dev.decisions} reports the queue and the open screen's state.
 */
public final class DecisionsFeature {
	/** Decisions answered from this client that the Foreman has not confirmed yet (id -> since ms). */
	private static final Map<String, Long> ANSWERING = new HashMap<>();
	/** Last answers sent from this client (for dev.decisions). */
	private static final Map<String, String> ANSWERS = new HashMap<>();

	private DecisionsFeature() {
	}

	public static void init() {
		BlockEntityRenderers.register(ModBlockEntities.DECISION_PODIUM, ctx -> new DecisionPodiumRenderer());
		Keys.ensureRegistered();
		DevBridge.registerScreen("decision", mc -> new DecisionScreen(null, null));
		StationInteractions.onUse(ModBlocks.DECISION_PODIUM, (player, pos, state, be) -> openQueue(null, null));
		ClientTickEvents.END_CLIENT_TICK.register(mc -> Guard.run("decisions.tick", () -> {
			if (mc.player == null) {
				return;
			}
			while (Keys.decisions.consumeClick()) {
				if (mc.gui.screen() == null) {
					openQueue(null, null);
				}
			}
		}));
		Foreman.addListener(new ForemanListener() {
			@Override
			public void onDecision(@Nullable Decision previous, Decision decision) {
				if (!decision.isOpen()) {
					ANSWERING.remove(decision.id());
				}
			}

			@Override
			public void onSnapshot(ForemanState state) {
				ANSWERING.keySet().removeIf(id -> state.decision(id) == null || !state.decision(id).isOpen());
			}
		});
		registerDev();
	}

	// ------------------------------------------------------------------ API for other features

	/** Open the decision screen at {@code decisionId} (null = the queue head). */
	public static void openQueue(@Nullable String decisionId, @Nullable Screen parent) {
		Minecraft mc = Minecraft.getInstance();
		mc.gui.setScreen(new DecisionScreen(decisionId, parent));
	}

	/** An answer was sent from this client (HUD/podium can stop counting it right away). */
	public static void markAnswering(String id) {
		ANSWERING.put(id, System.currentTimeMillis());
	}

	public static void unmarkAnswering(String id) {
		ANSWERING.remove(id);
	}

	public static boolean isAnswering(String id) {
		Long t = ANSWERING.get(id);
		return t != null && System.currentTimeMillis() - t < 15_000;
	}

	public static void recordAnswer(String id, @Nullable String option, @Nullable String text) {
		ANSWERS.put(id, (option == null ? "" : option) + (text == null ? "" : " | " + text));
	}

	/** Open decisions that are not being answered right now (the HUD badge count). */
	public static int waitingCount() {
		int n = 0;
		for (Decision d : DecisionQueue.open()) {
			if (!isAnswering(d.id())) {
				n++;
			}
		}
		return n;
	}

	/** Called by the podium renderer (client thread) with the podium's state: tell the driver what the
	 * podium should show right now. The renderer's {@code wantOpen} counts only decisions not being
	 * answered, so it can request closed while {@code compute} still sees an open decision. The
	 * driver folds this into the same {@code WorldIntent.podiumOpen} and clears it once the computed
	 * value agrees, so there is one writer. No world write happens here (A-18). */
	static void syncPodium(BlockPos pos, BlockState state, boolean wantOpen) {
		if (!state.hasProperty(DecisionPodiumBlock.OPEN)) {
			return;
		}
		HqWorldDriver.setPodiumOverride(wantOpen);
	}

	// ------------------------------------------------------------------ dev

	private static void registerDev() {
		DevBridge.register("dev.decision", 10_000,
			"{decisionId?, kind?: question|permission|merge, preview?: bool (sample permission, nothing sent)} - open the decision screen at that decision (default: the queue head)", (req, mc) -> {
				Fields f = Fields.of(req);
				String id = f.has("decisionId") ? f.nonBlank("decisionId") : null;
				String kind = f.has("kind") ? f.nonBlank("kind").toLowerCase(Locale.ROOT) : null;
				boolean preview = f.optBool("preview", false);
				return DevBridge.onClient(mc, () -> {
					if (preview) {
						mc.gui.setScreen(DecisionScreen.preview(dev.agentcraft.client.permissions.PermissionsFeature.sample()));
						return state(mc);
					}
					String target = id;
					if (target == null && kind != null) {
						DecisionKind k = switch (kind) {
							case "question" -> DecisionKind.QUESTION;
							case "permission" -> DecisionKind.PERMISSION;
							case "merge" -> DecisionKind.MERGE;
							default -> throw new DevBridge.DevException("kind must be question, permission or merge");
						};
						Decision d = DecisionQueue.firstOfKind(k);
						if (d == null) {
							throw new DevBridge.DevException("no open " + kind + " decision");
						}
						target = d.id();
					}
					if (target != null && (Foreman.state() == null || Foreman.state().decision(target) == null)) {
						throw new DevBridge.DevException("no decision " + target);
					}
					openQueue(target, null);
					return state(mc);
				});
			});
		DevBridge.register("dev.decisions", 10_000, "{} - the decision queue (HUD order) and the open decision screen's state", (req, mc) -> DevBridge
			.onClient(mc, () -> state(mc)));
	}

	static JsonObject state(Minecraft mc) {
		JsonObject o = new JsonObject();
		JsonArray q = new JsonArray();
		for (Decision d : DecisionQueue.open()) {
			JsonObject e = new JsonObject();
			e.addProperty("id", d.id());
			e.addProperty("kind", d.kind().wire());
			e.addProperty("agentId", d.agentId());
			e.addProperty("question", d.question());
			JsonArray opts = new JsonArray();
			d.options().forEach(opts::add);
			e.add("options", opts);
			e.addProperty("answering", isAnswering(d.id()));
			q.add(e);
		}
		o.add("queue", q);
		o.addProperty("waiting", waitingCount());
		JsonObject answers = new JsonObject();
		ANSWERS.forEach(answers::addProperty);
		o.add("sentAnswers", answers);
		if (mc.gui.screen() instanceof DecisionScreen ds) {
			JsonObject sc = new JsonObject();
			sc.addProperty("current", ds.currentId());
			sc.addProperty("highlight", ds.highlight());
			sc.addProperty("textFocused", ds.textFocused());
			sc.addProperty("requestChanges", ds.requestChangesMode());
			sc.addProperty("answerText", ds.answerText());
			sc.addProperty("status", ds.status());
			sc.addProperty("lastAnswer", ds.lastAnswer());
			sc.addProperty("armed", ds.armed());
			sc.addProperty("preview", ds.isPreview());
			o.add("screen", sc);
		} else {
			o.add("screen", null);
		}
		// the podium bubble reserves screen space in the nameplate layout: plates overlapping it (0 when settled)
		JsonObject pod = new JsonObject();
		pod.addProperty("reserved", dev.agentcraft.client.agents.PlateLayout.reservedCount());
		pod.addProperty("plateOverlaps", dev.agentcraft.client.agents.PlateLayout.reservedOverlaps());
		o.add("podium", pod);
		return o;
	}
}
