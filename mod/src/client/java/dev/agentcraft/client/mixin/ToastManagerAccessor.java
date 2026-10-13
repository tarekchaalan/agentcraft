package dev.agentcraft.client.mixin;

import java.util.List;
import net.minecraft.client.gui.components.toasts.ToastManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The HUD layout: the vanilla toasts on screen (the elements are {@link ToastInstanceAccessor}s), so the overlay keeps clear of them. */
@Mixin(ToastManager.class)
public interface ToastManagerAccessor {
	@Accessor("visibleToasts")
	List<?> agentcraft$visibleToasts();
}
