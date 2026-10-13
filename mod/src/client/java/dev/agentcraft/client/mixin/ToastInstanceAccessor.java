package dev.agentcraft.client.mixin;

import net.minecraft.client.gui.components.toasts.Toast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The HUD layout: one vanilla toast on screen (the private {@code ToastManager.ToastInstance}): its toast, slot and slide. */
@Mixin(targets = "net.minecraft.client.gui.components.toasts.ToastManager$ToastInstance")
public interface ToastInstanceAccessor {
	@Accessor("toast")
	Toast agentcraft$toast();

	@Accessor("firstSlotIndex")
	int agentcraft$firstSlotIndex();

	@Accessor("visiblePortion")
	float agentcraft$visiblePortion();

	@Accessor("hasFinishedRendering")
	boolean agentcraft$hasFinishedRendering();
}
