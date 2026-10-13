package dev.agentcraft.client.hud;

import com.mojang.blaze3d.platform.InputConstants;
import dev.agentcraft.AgentCraft;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;

/**
 * AgentCraft key mappings (Options > Controls > Key Binds > AgentCraft, rebindable like any vanilla
 * key): the console ({@code `}), the console from a terminal you look at (Enter), the decision
 * queue ({@code J}) and the HUD style cycle (unbound by default; {@code /achud} does the same).
 * Registered once, from whichever feature initialises first.
 */
public final class Keys {
	public static KeyMapping console;
	public static KeyMapping terminal;
	public static KeyMapping decisions;
	public static KeyMapping hudStyle;
	private static boolean registered;

	private Keys() {
	}

	public static synchronized void ensureRegistered() {
		if (registered) {
			return;
		}
		registered = true;
		KeyMapping.Category cat;
		try {
			cat = KeyMapping.Category.register(AgentCraft.id("agentcraft"));
		} catch (IllegalArgumentException alreadyThere) {
			// another feature registered the AgentCraft category first: categories are records (equal by id)
			cat = new KeyMapping.Category(AgentCraft.id("agentcraft"));
		}
		console = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.agentcraft.console", InputConstants.Type.KEYBOARD, InputConstants.KEY_GRAVE,
			cat, 1));
		terminal = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.agentcraft.console_terminal", InputConstants.Type.KEYBOARD,
			InputConstants.KEY_RETURN, cat, 2));
		decisions = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.agentcraft.decisions", InputConstants.Type.KEYBOARD, InputConstants.KEY_J, cat,
			3));
		hudStyle = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.agentcraft.hud_style", InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(),
			cat, 4));
	}

	/**
	 * Short label of the key a mapping is bound to ("J", "Enter", "Backtick"). Punctuation keys whose
	 * glyph is only a pixel or two in the Minecraft font (` ' , . ; :) are spelled out, so a keycap
	 * never looks empty.
	 */
	public static String label(KeyMapping k) {
		if (k == null || k.isUnbound()) {
			return "?";
		}
		return readable(k.getTranslatedKeyMessage().getString());
	}

	/** Spell out tiny punctuation glyphs; cut long names to 9 characters. */
	public static String readable(String s) {
		String word = switch (s) {
			case "`" -> "Backtick";
			case "'" -> "Quote";
			case "´" -> "Accent";
			case "," -> "Comma";
			case "." -> "Period";
			case ";" -> "Semicolon";
			case ":" -> "Colon";
			case "|" -> "Bar";
			default -> s;
		};
		return word.length() > 9 ? word.substring(0, 9) : word;
	}

	public static boolean matches(KeyMapping k, KeyEvent e) {
		return k != null && k.matches(e);
	}
}
