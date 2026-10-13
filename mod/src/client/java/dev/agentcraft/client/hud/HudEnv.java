package dev.agentcraft.client.hud;

import dev.agentcraft.client.mixin.BossHealthOverlayAccessor;
import dev.agentcraft.client.mixin.ChatComponentAccessor;
import dev.agentcraft.client.mixin.HudAccessor;
import dev.agentcraft.client.mixin.SubtitleAccessor;
import dev.agentcraft.client.mixin.SubtitleOverlayAccessor;
import dev.agentcraft.client.mixin.ToastInstanceAccessor;
import dev.agentcraft.client.mixin.ToastManagerAccessor;
import dev.agentcraft.hud.HudLayout;
import dev.agentcraft.hud.HudLayout.Env;
import dev.agentcraft.hud.HudLayout.Rect;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.gui.components.LerpingBossEvent;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.numbers.NumberFormat;
import net.minecraft.network.chat.numbers.StyledFormat;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.TeamColor;

/**
 * What else is on screen this frame, for {@link HudLayout}: effect icons, boss bars, survival status rows, the chat
 * lines showing right now (the chat area counts only while it shows something: messages fade after 10 s), the
 * connection pill and auth banner drawn this frame, the scoreboard sidebar, the subtitles (option and the ones
 * showing) and the vanilla toasts in the top right. Client thread.
 */
public final class HudEnv {
	private HudEnv() {
	}

	/** The environment of the frame being drawn ({@code guiW} x {@code guiH} GUI px). */
	public static Env current(Minecraft mc, int guiW, int guiH) {
		int scale = Math.max(1, mc.getWindow().getGuiScale());
		Env e = Env.of(guiW, guiH, scale);
		if (mc.player == null) {
			return e.chat(0, 0);
		}
		int good = 0;
		int bad = 0;
		var screen = mc.gui.screen();
		if (screen == null || !screen.showsActiveEffects()) {
			for (MobEffectInstance i : mc.player.getActiveEffects()) {
				if (i.showIcon()) {
					if (i.getEffect().value().isBeneficial()) {
						good++;
					} else {
						bad++;
					}
				}
			}
		}
		int bars = 0;
		int titleW = 0;
		var events = ((BossHealthOverlayAccessor) mc.gui.hud.getBossOverlay()).agentcraft$events();
		for (LerpingBossEvent ev : events.values()) {
			bars++;
			titleW = Math.max(titleW, mc.font.width(ev.getName()));
		}
		boolean survival = mc.gameMode != null && mc.gameMode.canHurtPlayer();
		int[] chat = chatSize(mc);
		int area = Mth.ceil(ChatComponent.getHeight(mc.options.chatHeightUnfocused().get()) * mc.options.chatScale().get());
		Rect pill = ConnectionBanner.pillBottom > 0
			? new Rect(ConnectionBanner.pillLeft, ConnectionBanner.pillTop, guiW - ConnectionBanner.pillLeft, ConnectionBanner.pillBottom - ConnectionBanner.pillTop)
			: Rect.NONE;
		return e.effects(good, bad).withDemo(mc.isDemo()).boss(bars, titleW).survival(survival).chat(chat[0], chat[1]).chatArea(area).withPill(pill)
			.withSidebar(sidebar(mc, guiW, guiH)).withSubtitles(mc.options.showSubtitles().get(), subtitles(mc, guiW, guiH)).withToasts(toasts(mc, guiW))
			.withBanner(ConnectionBanner.banner);
	}

	/** The environment before the connection pill is drawn (it places itself under the effect icons with this). */
	public static Env forPill(Minecraft mc, int guiW, int guiH) {
		int scale = Math.max(1, mc.getWindow().getGuiScale());
		Env e = Env.of(guiW, guiH, scale);
		if (mc.player == null) {
			return e;
		}
		int good = 0;
		int bad = 0;
		var screen = mc.gui.screen();
		if (screen == null || !screen.showsActiveEffects()) {
			for (MobEffectInstance i : mc.player.getActiveEffects()) {
				if (i.showIcon()) {
					if (i.getEffect().value().isBeneficial()) {
						good++;
					} else {
						bad++;
					}
				}
			}
		}
		return e.effects(good, bad).withDemo(mc.isDemo());
	}

	/** Vanilla's sidebar order ({@code Hud.SCORE_DISPLAY_ORDER}): it shows the first 15. */
	private static final Comparator<PlayerScoreEntry> ORDER = Comparator.comparing(PlayerScoreEntry::value).reversed()
		.thenComparing(PlayerScoreEntry::owner, String.CASE_INSENSITIVE_ORDER);

	/** The scoreboard sidebar as {@code Hud.displayScoreboardSidebar} draws it (the team-colour slot first), NONE without one. */
	static Rect sidebar(Minecraft mc, int guiW, int guiH) {
		if (mc.level == null || mc.player == null) {
			return Rect.NONE;
		}
		Scoreboard sb = mc.level.getScoreboard();
		Objective obj = null;
		PlayerTeam team = sb.getPlayersTeam(mc.player.getScoreboardName());
		if (team != null) {
			Optional<TeamColor> color = team.getColor();
			if (color.isPresent()) {
				obj = sb.getDisplayObjective(color.get().displaySlot());
			}
		}
		if (obj == null) {
			obj = sb.getDisplayObjective(DisplaySlot.SIDEBAR);
		}
		if (obj == null) {
			return Rect.NONE;
		}
		Font font = mc.font;
		NumberFormat fmt = obj.numberFormatOrDefault(StyledFormat.SIDEBAR_DEFAULT);
		int widest = font.width(obj.getDisplayName());
		int spacer = font.width(": ");
		List<PlayerScoreEntry> shown = sb.listPlayerScores(obj).stream().filter(s -> !s.isHidden()).sorted(ORDER).limit(15).toList();
		for (PlayerScoreEntry s : shown) {
			Component name = PlayerTeam.formatNameForTeam(sb.getPlayersTeam(s.owner()), s.ownerName());
			int scoreW = font.width(s.formatValue(fmt));
			widest = Math.max(widest, font.width(name) + (scoreW > 0 ? spacer + scoreW : 0));
		}
		return HudLayout.sidebar(guiW, guiH, widest, shown.size());
	}

	/** The subtitles vanilla drew last frame ({@code SubtitleOverlay}), NONE when none show or subtitles are off. */
	static Rect subtitles(Minecraft mc, int guiW, int guiH) {
		if (!mc.options.showSubtitles().get()) {
			return Rect.NONE;
		}
		List<?> shown = ((SubtitleOverlayAccessor) ((HudAccessor) mc.gui.hud).agentcraft$subtitleOverlay()).agentcraft$audibleSubtitles();
		if (shown.isEmpty()) {
			return Rect.NONE;
		}
		Font font = mc.font;
		int w = 0;
		for (Object o : shown) {
			w = Math.max(w, font.width(((SubtitleAccessor) o).agentcraft$text()));
		}
		w += font.width("<") + font.width(" ") + font.width(">") + font.width(" ");
		return HudLayout.subtitleRows(guiW, guiH, w, shown.size());
	}

	/** The vanilla toasts showing in the top right, as one rectangle (NONE = none). */
	static Rect toasts(Minecraft mc, int guiW) {
		Rect all = Rect.NONE;
		for (Object o : ((ToastManagerAccessor) mc.gui.toastManager()).agentcraft$visibleToasts()) {
			ToastInstanceAccessor t = (ToastInstanceAccessor) o;
			if (t.agentcraft$hasFinishedRendering() || t.agentcraft$visiblePortion() <= 0f) {
				continue;
			}
			Toast toast = t.agentcraft$toast();
			all = all.union(HudLayout.vanillaToast(guiW, toast.width(), toast.height(), t.agentcraft$firstSlotIndex()));
		}
		return all;
	}

	/** The chat's width and the height of the lines it shows right now (GUI px); 0, 0 = nothing shows. */
	static int[] chatSize(Minecraft mc) {
		ChatComponent chat = mc.gui.hud.getChat();
		List<GuiMessage.Line> lines = ((ChatComponentAccessor) chat).agentcraft$trimmedMessages();
		int perPage = chat.getLinesPerPage();
		int shown;
		if (chat.isChatFocused()) {
			shown = Math.min(lines.size(), perPage);
		} else {
			int now = mc.gui.hud.getGuiTicks();
			shown = 0;
			for (int i = 0; i < Math.min(lines.size(), perPage); i++) {
				if (now - lines.get(i).addedTime() < 200) {
					shown++;
				}
			}
		}
		if (shown == 0) {
			return new int[] {0, 0};
		}
		double scale = mc.options.chatScale().get();
		int entry = (int) (9 * (mc.options.chatLineSpacing().get() + 1.0));
		int w = ChatComponent.getWidth(mc.options.chatWidth().get());
		return new int[] {Mth.ceil(w * scale), Mth.ceil(shown * entry * scale) + 1};
	}
}
