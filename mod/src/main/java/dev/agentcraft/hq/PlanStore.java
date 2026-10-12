package dev.agentcraft.hq;

import dev.agentcraft.AgentCraft;
import dev.agentcraft.mp.Plot;
import dev.agentcraft.mp.Plots;
import dev.agentcraft.mp.StudioId;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * Remembers the plan a builder applied last ({@code agentcraft-hq-plan.dat} in the world folder for
 * {@link StudioId#LOCAL}, or {@code agentcraft/plots/<index>/hq-plan.dat} for a plot): builder id, box,
 * a block-state palette and one palette index per cell. The next build compares the world against it
 * to tell the player's own changes (kept) from cells that simply still hold an older version of the
 * HQ (updated). A build by any other builder deletes it, because that build rewrites the same ground
 * without a record.
 */
final class PlanStore {
	static final String FILE = "agentcraft-hq-plan.dat";
	private static final String PLOT_PLAN = "hq-plan.dat";

	private PlanStore() {
	}

	/** The plan file of a studio, or null when the studio has no plot. Only {@code save} creates the plot's directory. */
	private static @Nullable Path file(MinecraftServer server, StudioId studio) {
		if (studio.equals(StudioId.LOCAL)) {
			return server.getWorldPath(LevelResource.ROOT).resolve(FILE);
		}
		Optional<Plot> plot = Plots.directory().plotOf(studio);
		if (plot.isEmpty()) {
			return null;
		}
		return server.getWorldPath(LevelResource.ROOT).resolve("agentcraft/plots/" + plot.get().index()).resolve(PLOT_PLAN);
	}

	/** The previous plan of {@code builder} for exactly this box, or null. */
	static BlockState @Nullable [] load(MinecraftServer server, StudioId studio, String builder, int[] box, int cells) {
		Path f = file(server, studio);
		if (f == null || !Files.exists(f)) {
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

	static void save(MinecraftServer server, StudioId studio, String builder, int[] box, BlockState[] cells) throws IOException {
		Path f = file(server, studio);
		if (f == null) {
			throw new IOException("no plot for studio");
		}
		if (!studio.equals(StudioId.LOCAL)) {
			Files.createDirectories(f.getParent());
		}
		Map<BlockState, Integer> ids = new HashMap<>();
		ListTag palette = new ListTag();
		int[] idx = new int[cells.length];
		for (int i = 0; i < cells.length; i++) {
			Integer id = ids.get(cells[i]);
			if (id == null) {
				id = ids.size();
				ids.put(cells[i], id);
				palette.add(NbtUtils.writeBlockState(cells[i]));
			}
			idx[i] = id;
		}
		CompoundTag root = new CompoundTag();
		root.putString("builder", builder);
		root.putIntArray("box", box);
		root.put("palette", palette);
		root.put("cells", new IntArrayTag(idx));
		Path tmp = f.resolveSibling(f.getFileName().toString() + ".tmp");
		NbtIo.writeCompressed(root, tmp);
		Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}

	/** Forget the stored plan unless it belongs to {@code builder} (another builder is about to rewrite the site). */
	static void invalidateUnless(MinecraftServer server, String builder) {
		invalidateUnless(server, StudioId.LOCAL, builder);
	}

	static void invalidateUnless(MinecraftServer server, StudioId studio, String builder) {
		Path f = file(server, studio);
		if (f == null || !Files.exists(f)) {
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
		Path f = file(server, StudioId.LOCAL);
		return f != null && Files.exists(f);
	}
}
