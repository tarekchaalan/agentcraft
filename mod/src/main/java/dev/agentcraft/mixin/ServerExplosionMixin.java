package dev.agentcraft.mixin;

import dev.agentcraft.mp.server.protect.PlotProtectionFeature;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps an explosion from changing any block inside a plot.
 *
 * <p>{@code interactWithBlocks} removes the positions inside a protected plot
 * from the blocks about to be destroyed. {@code createFire} receives the same
 * list and is filtered too, because {@code explode()} skips
 * {@code interactWithBlocks} entirely for {@link net.minecraft.world.level.Explosion.BlockInteraction#KEEP}
 * but still calls {@code createFire}.</p>
 */
@Mixin(ServerExplosion.class)
public class ServerExplosionMixin {
    @Shadow @Final private ServerLevel level;

    @Inject(method = "interactWithBlocks", at = @At("HEAD"))
    private void removeProtectedBlocks(List<BlockPos> targetBlocks, CallbackInfo ci) {
        targetBlocks.removeIf(pos -> PlotProtectionFeature.explosionProtects(level, pos));
    }

    @Inject(method = "createFire", at = @At("HEAD"))
    private void removeProtectedFire(List<BlockPos> targetBlocks, CallbackInfo ci) {
        targetBlocks.removeIf(pos -> PlotProtectionFeature.explosionProtects(level, pos));
    }
}
