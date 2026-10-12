package dev.agentcraft.hq;

import dev.agentcraft.AgentCraft;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * Remembers the plan a builder applied last ({@code agentcraft-hq-plan.dat} in the world folder:
 * builder id, box, a block-state palette and one palette index per cell). The next build compares
 * the world against it to tell the player's own changes (kept) from cells that simply still hold
 * an older version of the HQ (updated). A build by any other builder deletes it, because that build
 * rewrites the same ground without a record.
 */
final class PlanStore {
	static final String FILE = "agentcraft-hq-plan.dat";

	private PlanStore() {
	}

	private static Path file(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(FILE);
	}

	/** The previous plan of {@code builder} for exactly this box, or null. */
	static BlockState @Nullable [] load(MinecraftServer server, String builder, int[] box, int cells) {
		Path f = file(server);
		if (!Files.exists(f)) {
			return null;
		}
		try {
			CompoundTag root = NbtIo.readCompressed(f, NbtAccounter.unlimitedHeap());
			if (!builder.equals(root.getStringOr("builder", "")) || !java.util.Arrays.equals(box, root.getIntArray("box").orElse(new int[0]))) {
				return null;
			}
			ListTag palette = root.getListOrEmpty("palette");
			BlockState[] states = new BlockState[palette.size()];
			for (int i = 0; i < states.length; i++) {
				states[i] = NbtUtils.readBlockState(BuiltInRegistries.BLOCK, palette.getCompoundOrEmpty(i));
			}
			int[] idx = root.getIntArray("cells").orElse(new int[0]);
			if (idx.length != cells) {
				return null;
			}
			BlockState[] out = new BlockState[cells];
			for (int i = 0; i < cells; i++) {
				out[i] = states[idx[i]];
			}
			return out;
		} catch (IOException | RuntimeException e) {
			AgentCraft.LOGGER.warn("Could not read {} (the next HQ build sets every cell)", f, e);
			return null;
		}
	}

	static void save(MinecraftServer server, String builder, int[] box, BlockState[] cells) {
		Map<BlockState, Integer> ids = new HashMap<>();
		ListTag palette = new ListTag();
		int[] idx = new int[cells.length];
		for (int i = 0; i < cells.length; i++) {
			// a cell the plan leaves to the world (the natural edge of a site) is remembered as air: the
			// next build skips it again before ever comparing it
			BlockState s = cells[i] == null ? Blocks.AIR.defaultBlockState() : cells[i];
			Integer id = ids.get(s);
			if (id == null) {
				id = ids.size();
				ids.put(s, id);
				palette.add(NbtUtils.writeBlockState(s));
			}
			idx[i] = id;
		}
		CompoundTag root = new CompoundTag();
		root.putString("builder", builder);
		root.putIntArray("box", box);
		root.put("palette", palette);
		root.put("cells", new IntArrayTag(idx));
		Path f = file(server);
		try {
			Path tmp = f.resolveSibling(FILE + ".tmp");
			NbtIo.writeCompressed(root, tmp);
			Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("Could not save {}", f, e);
		}
	}

	/** Forget the stored plan unless it belongs to {@code builder} (another builder is about to rewrite the site). */
	static void invalidateUnless(MinecraftServer server, String builder) {
		Path f = file(server);
		if (!Files.exists(f)) {
			return;
		}
		try {
			CompoundTag root = NbtIo.readCompressed(f, NbtAccounter.unlimitedHeap());
			if (builder.equals(root.getStringOr("builder", ""))) {
				return;
			}
		} catch (IOException | RuntimeException e) {
			// unreadable: drop it
		}
		try {
			Files.deleteIfExists(f);
		} catch (IOException e) {
			AgentCraft.LOGGER.warn("Could not delete {}", f, e);
		}
	}

	static boolean exists(MinecraftServer server) {
		return Files.exists(file(server));
	}
}
