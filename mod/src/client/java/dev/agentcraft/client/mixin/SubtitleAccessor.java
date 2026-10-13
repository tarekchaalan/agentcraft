package dev.agentcraft.client.mixin;

import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The HUD layout: one subtitle's text (vanilla's private {@code SubtitleOverlay.Subtitle}), for the subtitles' width. */
@Mixin(targets = "net.minecraft.client.gui.components.SubtitleOverlay$Subtitle")
public interface SubtitleAccessor {
	@Accessor("text")
	Component agentcraft$text();
}
