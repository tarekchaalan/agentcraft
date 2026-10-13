package dev.agentcraft.client.mixin;

import net.minecraft.client.gui.Hud;
import net.minecraft.client.gui.components.SubtitleOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The HUD layout: the subtitle overlay (which subtitles show), so the overlay keeps clear of them. */
@Mixin(Hud.class)
public interface HudAccessor {
	@Accessor("subtitleOverlay")
	SubtitleOverlay agentcraft$subtitleOverlay();
}
