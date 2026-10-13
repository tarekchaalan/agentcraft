package dev.agentcraft.client.hud;

import com.google.gson.JsonObject;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.client.decisions.DecisionsFeature;
import dev.agentcraft.client.dev.DevBridge;
import dev.agentcraft.client.dev.Fields;
import dev.agentcraft.client.foreman.Protocol.Notify;
import dev.agentcraft.client.foreman.Protocol.NotifyLevel;
import dev.agentcraft.client.ui.Guard;
import dev.agentcraft.client.ui.GuardedHud;
import dev.agentcraft.hud.HudCommand;
import dev.agentcraft.hud.HudPeek;
import dev.agentcraft.hud.HudSettings;
import java.util.List;
import java.util.Locale;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * HUD: the Foreman connection pill / auth banner ({@link ConnectionBanner}), the overlay in the chosen style (Off,
 * Pill, Pill+, Panel: {@link HudOverlay}, {@link PillStyle}, {@link GoalBar}; settings {@link HudConfig}, changed
 * with the console's {@code /hud}, the chat command {@code /achud} or the HUD style key), paper toasts for
 * {@code notify} ({@link Toasts}) and the decision bell / done chime ({@link HudSounds}).
 *
 * <p>QA: {@code dev.toast {text, level?, decisionId?}} shows a toast without the Foreman, {@code dev.hud.state}
 * reports what the HUD shows (style, position, size, rect, peek, hidden reason, overlaps, waiting count, toasts,
 * sounds), {@code dev.hud.set} changes the overlay settings, {@code dev.hud.peek} shows a peek,
 * {@code dev.hud.vanilla} puts vanilla's HUD extras on screen for layout checks.
 */
public final class HudFeature {
	/** dev.hud.vanilla's toast: 10 s. */
	private static final net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId QA_TOAST =
		new net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId(10_000L);

	private HudFeature() {
	}

	public static void init() {
		Keys.ensureRegistered();
		HudElementRegistry.addLast(AgentCraft.id("hud/connection"), GuardedHud.of("hud.connection", new ConnectionBanner()));
		// the overlay in its style; keeps the old element id and guard kind
		HudElementRegistry.addLast(AgentCraft.id("hud/goal"), GuardedHud.of("hud.goal", new HudOverlay()));
		HudElementRegistry.addLast(AgentCraft.id("hud/toasts"), GuardedHud.of("hud.toasts", new Toasts()));
		Toasts.init();
		HudPeeks.init();
		HudSounds.init();
		ClientTickEvents.END_CLIENT_TICK.register(mc -> Guard.run("hud.overlay", () -> {
			HudCombat.tick(mc);
			while (Keys.hudStyle != null && Keys.hudStyle.consumeClick()) {
				cycleStyle(mc);
			}
		}));
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(command()));
		// QA: the vanilla key binds screen, to check the AgentCraft category (dev.screen {open:"keybinds"})
		DevBridge.registerScreen("keybinds", mc -> new net.minecraft.client.gui.screens.options.controls.KeyBindsScreen(null, mc.options));
		DevBridge.register("dev.toast", 10_000, "{text, level?: info|warn|need_user, decisionId?} - show an in-game toast (no Foreman needed)",
			(req, mc) -> {
				Fields f = Fields.of(req);
				String text = f.nonBlank("text");
				String level = f.has("level") ? f.nonBlank("level").toLowerCase(Locale.ROOT) : "info";
				String did = f.has("decisionId") ? f.nonBlank("decisionId") : null;
				NotifyLevel lv = switch (level) {
					case "info" -> NotifyLevel.INFO;
					case "warn" -> NotifyLevel.WARN;
					case "need_user" -> NotifyLevel.NEED_USER;
					default -> throw new DevBridge.DevException("level must be info, warn or need_user");
				};
				return DevBridge.onClient(mc, () -> {
					Toasts.push(new Notify(lv, text, did, System.currentTimeMillis()));
					return hudState();
				});
			});
		DevBridge.register("dev.hud.state", 10_000, "{} - what the AgentCraft HUD shows: style, position, size, rect, peek, hidden reason, overlaps, "
			+ "decisions waiting, toasts, sounds", (req, mc) -> DevBridge.onClient(mc, HudFeature::hudState));
		DevBridge.register("dev.hud.set", 10_000, "{style?: off|pill|pill_plus|panel, position?: top_center|top_right|top_left|bottom_left|"
			+ "bottom_right|right_middle, size?: s|m|l, peek?, autoHide?, hideInCombat?, cycle?: bool (the cycle key), clearChat?: bool, "
			+ "clearPeek?: bool} - change the overlay settings (saved to <gameDir>/agentcraft/hud.json like /hud); replies dev.hud.state",
			(req, mc) -> {
				Fields f = Fields.of(req);
				HudSettings.Style style = f.has("style") ? parsed("style", HudSettings.parseStyle(f.nonBlank("style"))) : null;
				HudSettings.Position pos = f.has("position") ? parsed("position", HudSettings.parsePosition(f.nonBlank("position"))) : null;
				HudSettings.Size size = f.has("size") ? parsed("size", HudSettings.parseSize(f.nonBlank("size"))) : null;
				Boolean peek = f.has("peek") ? f.optBool("peek", true) : null;
				Boolean autoHide = f.has("autoHide") ? f.optBool("autoHide", false) : null;
				Boolean combat = f.has("hideInCombat") ? f.optBool("hideInCombat", false) : null;
				boolean cycle = f.optBool("cycle", false);
				boolean clearChat = f.optBool("clearChat", false);
				boolean clearPeek = f.optBool("clearPeek", false);
				return DevBridge.onClient(mc, () -> {
					HudSettings s = HudConfig.get();
					if (style != null) {
						s.setStyle(style);
					}
					if (pos != null) {
						s.setPosition(pos);
					}
					if (size != null) {
						s.setSize(size);
					}
					if (peek != null) {
						s.setPeek(peek);
					}
					if (autoHide != null) {
						s.setAutoHide(autoHide);
					}
					if (combat != null) {
						s.setHideInCombat(combat);
					}
					if (cycle) {
						cycleStyle(mc);
					}
					HudConfig.save();
					if (clearChat) {
						mc.gui.hud.getChat().clearMessages(false);
					}
					if (clearPeek) {
						HudPeeks.clear();
					}
					return hudState();
				});
			});
		DevBridge.register("dev.hud.vanilla", 10_000, "{subtitles?: bool (the Show Subtitles option, this session, not saved), toast?: text (a "
			+ "vanilla system toast in the top right, 10 s)} - put vanilla's HUD extras on screen for the layout checks; the sidebar comes from "
			+ "/scoreboard objectives setdisplay sidebar, more toasts from /advancement or /recipe, subtitles from /playsound; replies dev.hud.state",
			(req, mc) -> {
				Fields f = Fields.of(req);
				Boolean subtitles = f.has("subtitles") ? f.optBool("subtitles", true) : null;
				String toast = f.has("toast") ? f.nonBlank("toast") : null;
				return DevBridge.onClient(mc, () -> {
					if (subtitles != null) {
						mc.options.showSubtitles().set(subtitles);
					}
					if (toast != null) {
						net.minecraft.client.gui.components.toasts.SystemToast.addOrUpdate(mc.gui.toastManager(), QA_TOAST, Component.literal(toast),
							Component.literal("AgentCraft layout check"));
					}
					return hudState();
				});
			});
		DevBridge.register("dev.hud.peek", 10_000, "{text, kind?: task_done|decision|goal_done} - show a peek on the overlay (whatever the peek "
			+ "setting); replies dev.hud.state", (req, mc) -> {
				Fields f = Fields.of(req);
				String text = f.nonBlank("text");
				String kind = f.has("kind") ? f.nonBlank("kind").toLowerCase(Locale.ROOT) : HudPeek.TASK_DONE;
				if (!List.of(HudPeek.TASK_DONE, HudPeek.DECISION, HudPeek.GOAL_DONE).contains(kind)) {
					throw new DevBridge.DevException("kind must be task_done, decision or goal_done");
				}
				return DevBridge.onClient(mc, () -> {
					HudPeeks.force(kind, text);
					return hudState();
				});
			});
		DevBridge.register("dev.hud.guiScale", 10_000, "{scale: 0 (auto) - 6} - change the GUI scale for this session (layout checks; not saved)",
			(req, mc) -> {
				int scale = Fields.of(req).optInt("scale", 3, 0, 6);
				return DevBridge.onClient(mc, () -> {
					mc.options.guiScale().set(scale);
					JsonObject o = new JsonObject();
					o.addProperty("guiScale", mc.getWindow().getGuiScale());
					o.addProperty("guiWidth", mc.getWindow().getGuiScaledWidth());
					o.addProperty("guiHeight", mc.getWindow().getGuiScaledHeight());
					return o;
				});
			});
	}

	private static <T> T parsed(String field, @Nullable T v) {
		if (v == null) {
			throw new DevBridge.DevException("unknown " + field);
		}
		return v;
	}

	// ------------------------------------------------------------------ /achud (client chat command, no permission needed)

	/**
	 * {@code /achud [words]}: the same words as the console's {@code /hud} ({@link HudCommand}). Not {@code /hud} (other
	 * mods use it) and not under {@code /agentcraft} (the server's operator command: a client root of that name would
	 * shadow it).
	 */
	private static LiteralArgumentBuilder<FabricClientCommandSource> command() {
		return LiteralArgumentBuilder.<FabricClientCommandSource>literal("achud").executes(ctx -> {
			ctx.getSource().sendFeedback(Component.literal(applyCommand(HudCommand.Change.NONE)));
			ctx.getSource().sendFeedback(Component.literal(HudCommand.USAGE.replaceFirst("/hud", "/achud")));
			return 1;
		}).then(RequiredArgumentBuilder.<FabricClientCommandSource, String>argument("args", StringArgumentType.greedyString()).suggests((ctx, b) -> {
			// complete the last word
			String in = b.getRemaining();
			int sp = in.lastIndexOf(' ');
			String last = in.substring(sp + 1).toLowerCase(Locale.ROOT);
			var sb = b.createOffset(b.getStart() + sp + 1);
			for (String w : HudCommand.WORDS) {
				if (w.startsWith(last)) {
					sb.suggest(w);
				}
			}
			return sb.buildFuture();
		}).executes(ctx -> {
			HudCommand.Parsed p = HudCommand.parse(StringArgumentType.getString(ctx, "args"));
			if (!p.ok()) {
				ctx.getSource().sendError(Component.literal("/achud: " + p.error()));
				return 0;
			}
			ctx.getSource().sendFeedback(Component.literal(applyCommand(p.change())));
			return 1;
		}));
	}

	/** Applies a {@code /hud} / {@code /achud} change, saves it, and returns the line that says the settings now. */
	public static String applyCommand(HudCommand.Change c) {
		HudSettings s = HudConfig.get();
		HudCommand.apply(s, c);
		HudConfig.save();
		return "AgentCraft HUD: " + s.describe();
	}

	/** The cycle key: next style, saved, and said on the action bar. */
	static void cycleStyle(Minecraft mc) {
		HudSettings s = HudConfig.get();
		HudSettings.Style st = s.cycleStyle();
		HudConfig.save();
		if (mc.player != null) {
			mc.gui.hud.setOverlayMessage(Component.literal("AgentCraft HUD: " + st.label()), false);
		}
	}

	static JsonObject hudState() {
		JsonObject o = new JsonObject();
		HudOverlay.state(o);
		o.addProperty("waiting", DecisionsFeature.waitingCount());
		var mc = Minecraft.getInstance();
		o.addProperty("hudHidden", mc.gui.hud.isHidden());
		o.addProperty("toastsActive", Toasts.active());
		o.addProperty("toastsShown", Toasts.shown());
		o.addProperty("goalBarBottom", GoalBar.bottom);
		o.addProperty("goalBarRight", GoalBar.right);
		o.addProperty("pillLeft", ConnectionBanner.pillLeft);
		o.addProperty("pillTop", ConnectionBanner.pillTop);
		o.addProperty("pillBottom", ConnectionBanner.pillBottom);
		// something the overlay drew last frame intersected the connection pill (should never be true)
		o.addProperty("goalBarPillClash", GoalBar.pillClash);
		o.addProperty("soundsEnabled", HudSounds.enabled());
		o.addProperty("forcedMute", HudSounds.forcedMute());
		o.addProperty("bells", HudSounds.bells());
		o.addProperty("chimes", HudSounds.chimes());
		o.addProperty("lastSound", HudSounds.lastEvent());
		o.addProperty("consoleKey", Keys.label(Keys.console));
		o.addProperty("decisionsKey", Keys.label(Keys.decisions));
		o.addProperty("terminalKey", Keys.label(Keys.terminal));
		o.addProperty("hudStyleKey", Keys.label(Keys.hudStyle));
		// what Options > Controls shows for them (proves the lang keys resolve)
		JsonObject names = new JsonObject();
		for (var k : new net.minecraft.client.KeyMapping[] {Keys.console, Keys.terminal, Keys.decisions, Keys.hudStyle}) {
			if (k != null) {
				names.addProperty(k.getName(), net.minecraft.client.resources.language.I18n.get(k.getName()) + " [" + k.getCategory().label().getString() + "]");
			}
		}
		o.add("keyNames", names);
		return o;
	}
}
