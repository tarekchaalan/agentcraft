package dev.agentcraft.client.mixin;

import java.util.List;
import net.minecraft.client.gui.components.SubtitleOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The HUD layout: the subtitles vanilla drew last frame (the elements are {@link SubtitleAccessor}s). */
@Mixin(SubtitleOverlay.class)
public interface SubtitleOverlayAccessor {
	@Accessor("audibleSubtitles")
	List<?> agentcraft$audibleSubtitles();
}
