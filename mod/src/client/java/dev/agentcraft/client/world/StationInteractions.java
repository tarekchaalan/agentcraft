package dev.agentcraft.client.world;

import dev.agentcraft.block.entity.StationBlockEntity;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Right-click on a station block, handled on the client (e.g. open the console screen on a terminal,
 * the library on an archive, the decision GUI on the podium). One handler per block; the click is
 * consumed client-side and not sent to the server. Sneak-right-click with an item still places
 * blocks normally.
 *
 * <pre>
 * StationInteractions.onUse(ModBlocks.CONSOLE_TERMINAL, (player, pos, state, be) -> Minecraft.getInstance().gui.setScreen(new ConsoleScreen()));
 * </pre>
 */
public final class StationInteractions {
	@FunctionalInterface
	public interface Handler {
		void use(Player player, BlockPos pos, BlockState state, @Nullable StationBlockEntity be);
	}

	/** A gate in front of the handler: true means the click was consumed elsewhere (MP-11 visitor). */
	@FunctionalInterface
	public interface Gate {
		boolean before(Player player, BlockPos pos, BlockState state, @Nullable StationBlockEntity be);
	}

	private static final Map<Block, Handler> HANDLERS = new ConcurrentHashMap<>();
	private static volatile @Nullable Gate gate;
	private static boolean registered;

	private StationInteractions() {
	}

	/** Install the one visitor gate; null removes it. Read at click time, never cached. */
	public static void setGate(@Nullable Gate next) {
		gate = next;
	}

	public static synchronized void onUse(Block block, Handler handler) {
		HANDLERS.put(block, handler);
		if (!registered) {
			registered = true;
			UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
				if (!level.isClientSide() || player.isSecondaryUseActive()) {
					return InteractionResult.PASS;
				}
				BlockPos pos = hit.getBlockPos();
				BlockState state = level.getBlockState(pos);
				Handler h = HANDLERS.get(state.getBlock());
				if (h == null) {
					return InteractionResult.PASS;
				}
				StationBlockEntity be = level.getBlockEntity(pos) instanceof StationBlockEntity s ? s : null;
				Gate g = gate;
				if (g != null && g.before(player, pos, state, be)) {
					return InteractionResult.FAIL;
				}
				h.use(player, pos, state, be);
				return InteractionResult.FAIL;
			});
		}
	}
}
