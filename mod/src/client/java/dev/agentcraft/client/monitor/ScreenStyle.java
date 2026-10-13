package dev.agentcraft.client.monitor;

import dev.agentcraft.client.ui.UiStyle;

/**
 * Colours of a lit monitor screen. Two looks were built and compared side by side in game (see
 * mod/FEATURES.md "Displays"): {@link #PAPER} (warm e-ink paper, the art track's first take, tokens
 * {@code monitor.*}) and {@link #DARK} (warm charcoal glass with cream text, tokens
 * {@code monitor_dark.*}). Tokens come from ui-style.json; the fallbacks equal the shipped values.
 */
public record ScreenStyle(String id, boolean dark, int bgTop, int bgBottom, int scanline, int rule, int headerBg, int text, int muted, int tool,
	int toolArg, int result, int error, int path, int hunk, int add, int addBg, int del, int delBg, int ctx, int caret, int badgeBg, int badgeText, int attention) {

	public static final ScreenStyle PAPER = makePaper();
	public static final ScreenStyle DARK = makeDark();

	/** Agent name colour on this screen. */
	public int name(String agentId) {
		return dark ? UiStyle.agentOnDark(agentId) : UiStyle.agentOnPaper(agentId);
	}

	private static int c(String token, int fallback) {
		// the in-world monitor looks are fixed: not affected by the GUI theme
		return UiStyle.base(token, fallback);
	}

	private static ScreenStyle makePaper() {
		int bg = c("monitor.bg", 0xFFEDE5D7);
		return new ScreenStyle("paper", false, bg, bg, c("monitor.scanline", 0xFFE7DECE), c("monitor.rule", 0xFFCFC2AC), bg,
			c("monitor.text", 0xFF1F1E1D), c("monitor.muted", 0xFF655E55), c("monitor.tool", 0xFF624E16), c("monitor.tool_arg", 0xFF1F1E1D),
			c("monitor.result", 0xFF485A48), c("monitor.error", 0xFF9A2F2B), c("monitor.path", 0xFF6A5132), c("monitor.diff_hunk", 0xFF195E5D),
			c("monitor.diff_add", 0xFF445545), c("monitor.diff_add_bg", 0xFFD5D9C7), c("monitor.diff_del", 0xFF803A29),
			c("monitor.diff_del_bg", 0xFFEDCEBA), c("monitor.diff_ctx", 0xFF655E55), c("monitor.text", 0xFF1F1E1D), c("paper.text", 0xFF1F1E1D),
			UiStyle.base("palette.ui.panel", 0xFFF4EFE6), c("monitor.attention", 0xFF844331));
	}

	private static ScreenStyle makeDark() {
		return new ScreenStyle("dark", true, c("monitor_dark.bg_top", 0xFF2E2925), c("monitor_dark.bg", 0xFF26221F),
			c("monitor_dark.scanline", 0xFF221E1B), c("monitor_dark.rule", 0xFF4A423B), c("monitor_dark.header_bg", 0xFF2E2925),
			c("monitor_dark.text", 0xFFEDE5D7), c("monitor_dark.muted", 0xFFA39B8E), c("monitor_dark.tool", 0xFFE6C659),
			c("monitor_dark.tool_arg", 0xFFEDE5D7), c("monitor_dark.result", 0xFFB8CBB3), c("monitor_dark.error", 0xFFEA847B),
			c("monitor_dark.path", 0xFFD4B17D), c("monitor_dark.diff_hunk", 0xFF80D2CD), c("monitor_dark.diff_add", 0xFFB8CBB3),
			c("monitor_dark.diff_add_bg", 0xFF3A4337), c("monitor_dark.diff_del", 0xFFF4A585), c("monitor_dark.diff_del_bg", 0xFF4F2D24),
			c("monitor_dark.diff_ctx", 0xFFA39B8E), c("monitor_dark.caret", 0xFFEDE5D7), c("monitor_dark.badge_bg", 0xFFF4EFE6),
			c("monitor_dark.badge_text", 0xFF1F1E1D), c("monitor_dark.attention", 0xFFF4A585));
	}
}
