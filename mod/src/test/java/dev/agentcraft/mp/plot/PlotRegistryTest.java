package dev.agentcraft.mp.plot;

import static org.junit.jupiter.api.Assertions.*;

import dev.agentcraft.layout.Anchor;
import dev.agentcraft.layout.AnchorNames;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.MpReasons;
import dev.agentcraft.mp.MpServerConfig;
import dev.agentcraft.mp.Plot;
import dev.agentcraft.mp.PlotDirectory;
import dev.agentcraft.mp.PlotGrid;
import dev.agentcraft.mp.Plots;
import dev.agentcraft.mp.StudioId;
import dev.agentcraft.mp.net.HelloS2C;
import dev.agentcraft.mp.net.MpPayloads;
import dev.agentcraft.mp.server.plot.PlotCommands;
import dev.agentcraft.mp.server.plot.PlotFeature;
import dev.agentcraft.mp.server.plot.PlotRebuilds;
import dev.agentcraft.mp.server.plot.PlotRegistry;
import dev.agentcraft.mp.server.plot.PlotStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.server.players.CachedUserNameToIdResolver;
import net.minecraft.server.players.NameAndId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Registry, persistence and profile-cache rules. No running server. */
class PlotRegistryTest {
    private static final MpServerConfig ENABLED = new MpServerConfig(true, 128, true, false, true, true, true, 12, 4, 10);

    @Test
    void allocation_follows_the_spiral_and_a_second_call_does_not_log(@TempDir Path dir) {
        PlotRegistry registry = new PlotRegistry();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        try (var capture = MpLog.capture()) {
            Plot a = registry.allocateStored(dir, StudioId.of(first), 128).plot();
            Plot b = registry.allocateStored(dir, StudioId.of(second), 128).plot();
            var again = registry.allocateStored(dir, StudioId.of(first), 128);
            assertEquals(0, a.index());
            assertEquals(BlockPos.ZERO, a.origin());
            assertEquals(1, b.index());
            assertEquals(PlotGrid.originOf(1, 128), b.origin());
            assertEquals(0, a.origin().getX() % 16);
            assertEquals(0, b.origin().getZ() % 16);
            assertFalse(a.box().intersects(b.box()));
            assertEquals(a, registry.plotAt(new BlockPos(0, 64, 0)).orElseThrow());
            assertTrue(registry.plotAt(new BlockPos(64, 64, 0)).isEmpty());
            assertFalse(again.created());
            assertEquals(a, again.plot());
            List<String> allocated = capture.lines().stream().filter(line -> line.startsWith("event=plot_allocated ")).toList();
            assertEquals(2, allocated.size());
            assertTrue(allocated.get(0).contains("index=0"));
            assertTrue(allocated.get(0).contains("origin=0,0,0"));
        }
    }

    @Test
    void an_overlapping_origin_is_skipped(@TempDir Path dir) {
        PlotRegistry registry = new PlotRegistry();
        UUID sitting = UUID.randomUUID();
        registry.replaceAll(List.of(new Plot(0, StudioId.of(sitting), PlotGrid.originOf(1, 128))));
        Plot next = registry.allocateStored(dir, StudioId.of(UUID.randomUUID()), 128).plot();
        assertEquals(2, next.index());
        assertEquals(PlotGrid.originOf(2, 128), next.origin());
    }

    @Test
    void a_full_grid_logs_grid_full_and_adds_nothing(@TempDir Path dir) {
        int stride = 1_048_576;
        int full = 0;
        while (full < 100_000) {
            try {
                PlotGrid.originOf(full, stride);
                full++;
            } catch (IllegalArgumentException e) {
                break;
            }
        }
        assertTrue(full < 100_000, "expected an origin past the world border");
        List<Plot> seeded = new ArrayList<>();
        for (int i = 0; i < full; i++) seeded.add(new Plot(i, StudioId.of(UUID.randomUUID()), PlotGrid.originOf(i, stride)));
        PlotRegistry registry = new PlotRegistry();
        registry.replaceAll(seeded);
        UUID late = UUID.randomUUID();
        try (var capture = MpLog.capture()) {
            var result = registry.allocateStored(dir, StudioId.of(late), stride);
            assertNull(result.plot());
            assertEquals(MpReasons.GRID_FULL, result.failure());
            assertEquals(full, registry.all().size());
            assertTrue(registry.plotOf(StudioId.of(late)).isEmpty());
            assertTrue(capture.lines().stream().anyMatch(line ->
                line.startsWith("event=plot_allocation_failed ") && line.contains("reason=grid_full")));
        }
    }

    @Test
    void a_save_failure_logs_io_error_and_rolls_back(@TempDir Path dir) throws Exception {
        Path blocker = dir.resolve("not-a-directory");
        Files.writeString(blocker, "x");
        PlotRegistry registry = new PlotRegistry();
        UUID player = UUID.randomUUID();
        try (var capture = MpLog.capture()) {
            var result = registry.allocateStored(blocker, StudioId.of(player), 128);
            assertNull(result.plot());
            assertEquals(MpReasons.IO_ERROR, result.failure());
            assertTrue(registry.all().isEmpty());
            var assigned = registry.assignStored(blocker, StudioId.of(player), 0, 128);
            assertNull(assigned.plot());
            assertEquals(PlotRegistry.Refuse.IO, assigned.refuse());
            assertTrue(registry.all().isEmpty());
            assertTrue(capture.lines().stream().anyMatch(line ->
                line.startsWith("event=plot_allocation_failed ") && line.contains("reason=io_error")));
        }
    }

    @Test
    void a_frozen_registry_does_not_replace_a_corrupt_file(@TempDir Path dir) throws Exception {
        Path file = PlotStore.plotsFile(dir);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{");
        byte[] before = Files.readAllBytes(file);
        PlotStore.Loaded loaded = PlotStore.load(dir);
        assertTrue(loaded.failed());
        assertTrue(loaded.plots().isEmpty());
        PlotRegistry registry = new PlotRegistry();
        registry.freeze();
        try (var capture = MpLog.capture()) {
            var result = registry.allocateStored(dir, StudioId.of(UUID.randomUUID()), 128);
            assertEquals(MpReasons.IO_ERROR, result.failure());
            assertTrue(registry.all().isEmpty());
            assertArrayEquals(before, Files.readAllBytes(file));
            assertTrue(capture.lines().stream().anyMatch(line -> line.contains("reason=io_error")));
        }
    }

    @Test
    void a_string_index_is_not_a_path_and_negative_indexes_are_rejected(@TempDir Path dir) throws Exception {
        UUID owner = UUID.randomUUID();
        Path file = PlotStore.plotsFile(dir);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"plots\":[{\"index\":\"../x\",\"owner\":\"" + owner + "\",\"x\":0,\"y\":0,\"z\":0}]}");
        PlotStore.Loaded loaded = PlotStore.load(dir);
        assertTrue(loaded.plots().isEmpty());
        assertFalse(loaded.failed());
        assertEquals(1, loaded.skipped());
        assertFalse(Files.exists(dir.resolve("x")));
        assertTrue(PlotStore.plotDirectory(dir, -1).isEmpty());

        Path keep = dir.resolve("agentcraft").resolve("plots").resolve("keep.txt");
        Files.createDirectories(keep.getParent());
        Files.writeString(keep, "stay");
        Path secret = dir.resolve("agentcraft").resolve("secrets");
        Files.writeString(secret, "stay");
        Path plot = PlotStore.plotDirectory(dir, 0).orElseThrow();
        Files.createDirectories(plot);
        Files.writeString(plot.resolve("anchors.json"), "{}");
        assertTrue(PlotStore.deletePlotDirectory(dir, 0));
        assertFalse(Files.exists(plot));
        assertEquals("stay", Files.readString(keep));
        assertEquals("stay", Files.readString(secret));
    }

    @Test
    void plots_and_anchors_round_trip(@TempDir Path dir) {
        UUID owner = UUID.randomUUID();
        PlotRegistry registry = new PlotRegistry();
        Plot plot = registry.allocateStored(dir, StudioId.of(owner), 128).plot();
        Anchor spawn = new Anchor(AnchorNames.SPAWN, 0.5, 66, 22.5, 180, 0);
        Anchors.Layout layout = new Anchors.Layout("studio", 3, null, java.util.Map.of(AnchorNames.SPAWN, spawn));
        assertTrue(PlotStore.saveAnchors(dir, plot.index(), layout));

        PlotRegistry loaded = new PlotRegistry();
        PlotStore.Loaded rows = PlotStore.load(dir);
        assertFalse(rows.failed());
        loaded.replaceAll(rows.plots());
        Plot back = loaded.plotOf(StudioId.of(owner)).orElseThrow();
        assertEquals(plot.index(), back.index());
        assertEquals(plot.origin(), back.origin());
        Anchor restored = PlotStore.loadAnchors(dir, plot.index()).orElseThrow().get(AnchorNames.SPAWN);
        assertEquals(spawn.x(), restored.x());
        assertEquals(spawn.y(), restored.y());
        assertEquals(spawn.z(), restored.z());
    }

    @Test
    void hello_reads_the_allocated_index(@TempDir Path dir) {
        UUID player = UUID.randomUUID();
        PlotDirectory previous = Plots.directory();
        PlotRegistry registry = new PlotRegistry();
        Plots.install(registry);
        try {
            registry.allocateStored(dir, StudioId.of(player), 128);
            HelloS2C hello = MpPayloads.helloFor(player, ENABLED, true, true).orElseThrow();
            assertEquals(0, hello.plotIndex());
            assertTrue(MpPayloads.helloFor(player, ENABLED, false, true).isEmpty());
        } finally {
            Plots.install(previous);
        }
    }

    @Test
    void the_multiplayer_gate_needs_both_dedicated_and_enabled() {
        assertFalse(PlotFeature.multiplayer(false, true));
        assertFalse(PlotFeature.multiplayer(true, false));
        assertTrue(PlotFeature.multiplayer(true, true));
    }

    @Test
    void the_profile_cache_matches_stored_names_and_honours_expiry(@TempDir Path dir) throws Exception {
        UUID expired = UUID.randomUUID();
        Path file = dir.resolve("usercache.json");
        Files.writeString(file, "[{\"uuid\":\"" + expired + "\",\"name\":\"OldPlayer\",\"expiresOn\":\"2000-01-01 00:00:00 +0000\"}]");
        var cache = new CachedUserNameToIdResolver(null, file.toFile());
        assertTrue(cache.get(expired).isPresent(), "the expired row is still in the cache");
        assertTrue(PlotCommands.knownCached(cache, "OldPlayer").isEmpty());

        UUID fresh = UUID.randomUUID();
        cache.add(new NameAndId(fresh, "CachedOwner"));
        assertEquals(fresh, PlotCommands.knownCached(cache, "cachedowner").orElseThrow());
        assertTrue(PlotCommands.knownCached(cache, "NotAPlayer").isEmpty());
    }

    @Test
    void local_is_not_allocated_and_is_not_logged(@TempDir Path dir) {
        PlotRegistry registry = new PlotRegistry();
        try (var capture = MpLog.capture()) {
            var result = registry.allocateStored(dir, StudioId.LOCAL, 128);
            assertNull(result.plot());
            assertFalse(result.created());
            assertTrue(capture.lines().isEmpty());
            assertTrue(registry.all().isEmpty());
        }
    }

    @Test
    void plot_origin_bounds_reject_integer_min_and_accept_the_boundary(@TempDir Path dir) throws Exception {
        // Integer.MIN_VALUE must be rejected on both axes: Math.abs is negative but MIN % 16 == 0.
        assertTrue(loadOne(dir, Integer.MIN_VALUE, 0).plots().isEmpty());
        assertTrue(loadOne(dir, 0, Integer.MIN_VALUE).plots().isEmpty());
        assertEquals(1, loadOne(dir, Integer.MIN_VALUE, 0).skipped());
        assertEquals(1, loadOne(dir, 0, Integer.MIN_VALUE).skipped());
        // The signed boundaries themselves are valid: ±30_000_000 are multiples of 16.
        assertEquals(-30_000_000, loadOne(dir, -30_000_000, 0).plots().get(0).origin().getX());
        assertEquals(30_000_000, loadOne(dir, 30_000_000, 0).plots().get(0).origin().getX());
        assertEquals(-30_000_000, loadOne(dir, 0, -30_000_000).plots().get(0).origin().getZ());
        assertEquals(30_000_000, loadOne(dir, 0, 30_000_000).plots().get(0).origin().getZ());
        // One step outside is rejected on both axes in both directions.
        assertTrue(loadOne(dir, 30_000_016, 0).plots().isEmpty());
        assertTrue(loadOne(dir, -30_000_016, 0).plots().isEmpty());
        assertTrue(loadOne(dir, 0, 30_000_016).plots().isEmpty());
        assertTrue(loadOne(dir, 0, -30_000_016).plots().isEmpty());
    }

    @Test
    void a_rejected_row_does_not_reserve_its_index(@TempDir Path dir) throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        Path file = PlotStore.plotsFile(dir);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"plots\":["
            + "{\"index\":0,\"owner\":\"" + a + "\",\"x\":0,\"y\":0,\"z\":0},"
            + "{\"index\":1,\"owner\":\"" + a + "\",\"x\":128,\"y\":0,\"z\":0},"
            + "{\"index\":1,\"owner\":\"" + b + "\",\"x\":128,\"y\":0,\"z\":0}]}");
        PlotStore.Loaded loaded = PlotStore.load(dir);
        assertFalse(loaded.failed());
        assertEquals(2, loaded.plots().size());
        assertEquals(1, loaded.skipped());
        assertTrue(loaded.plots().stream().anyMatch(plot -> plot.index() == 1 && plot.owner().equals(StudioId.of(b))),
            "the valid third row is accepted after the second is rejected for its owner");
        assertTrue(loaded.plots().stream().anyMatch(plot -> plot.index() == 0 && plot.owner().equals(StudioId.of(a))));
    }

    @Test
    void a_stored_origin_that_overlaps_an_accepted_row_is_skipped(@TempDir Path dir) throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        String first = "{\"index\":0,\"owner\":\"" + a + "\",\"x\":0,\"y\":0,\"z\":0}";
        // A distinct index and a distinct owner, 16 blocks from the first origin: the same ground.
        String overlapping = "{\"index\":1,\"owner\":\"" + b + "\",\"x\":16,\"y\":0,\"z\":0}";
        Path file = PlotStore.plotsFile(dir);
        Files.createDirectories(file.getParent());
        Files.writeString(file, "{\"plots\":[" + first + "," + overlapping + "]}");
        PlotStore.Loaded two = PlotStore.load(dir);
        assertFalse(two.failed());
        assertEquals(1, two.skipped());
        assertEquals(List.of(new Plot(0, StudioId.of(a), BlockPos.ZERO)), two.plots(), "the first row wins");

        // The skipped row reserves neither its index nor its owner, and a neighbour at the normal
        // stride still loads.
        Files.writeString(file, "{\"plots\":[" + first + "," + overlapping + ","
            + "{\"index\":1,\"owner\":\"" + b + "\",\"x\":128,\"y\":0,\"z\":0},"
            + "{\"index\":2,\"owner\":\"" + c + "\",\"x\":128,\"y\":0,\"z\":128}]}");
        PlotStore.Loaded four = PlotStore.load(dir);
        assertFalse(four.failed());
        assertEquals(1, four.skipped());
        assertEquals(List.of(
            new Plot(0, StudioId.of(a), BlockPos.ZERO),
            new Plot(1, StudioId.of(b), PlotGrid.originOf(1, 128)),
            new Plot(2, StudioId.of(c), PlotGrid.originOf(2, 128))), four.plots());
        PlotRegistry registry = new PlotRegistry();
        registry.replaceAll(four.plots());
        assertEquals(StudioId.of(a), registry.plotAt(new BlockPos(16, 64, 0)).orElseThrow().owner());
        assertEquals(PlotGrid.originOf(1, 128), registry.plotOf(StudioId.of(b)).orElseThrow().origin());
    }

    @Test
    void a_missing_or_clean_registry_file_does_not_refuse_the_start(@TempDir Path dir) throws Exception {
        assertEquals(Optional.empty(), PlotStore.startRefusal(PlotStore.load(dir), 128), "a missing file is an empty registry");
        assertEquals(Optional.empty(), PlotStore.startRefusal(loadText(dir, "{\"plots\":[]}"), 128));
        String two = "{\"plots\":[" + row(0, UUID.randomUUID(), 0, 0) + "," + row(1, UUID.randomUUID(), 128, 0) + "]}";
        assertEquals(Optional.empty(), PlotStore.startRefusal(loadText(dir, two), 128));
    }

    @Test
    void a_file_that_cannot_be_used_refuses_the_start_and_is_left_alone(@TempDir Path dir) throws Exception {
        Path file = PlotStore.plotsFile(writeText(dir, "{"));
        byte[] before = Files.readAllBytes(file);
        assertEquals(Optional.of("plots.json could not be read or is not JSON; no row was loaded"),
            PlotStore.startRefusal(PlotStore.load(dir), 128));
        assertArrayEquals(before, Files.readAllBytes(file), "loading and deciding write nothing");
        for (String shape : List.of("[]", "{}", "{\"plots\":{}}", "")) {
            assertEquals(Optional.of("plots.json is not an object with a \"plots\" array; no row was loaded"),
                PlotStore.startRefusal(loadText(dir, shape), 128), shape);
        }
        // A valid file padded past the cap: the size alone refuses it.
        assertEquals(Optional.of("plots.json is larger than 1048576 bytes; no row was loaded"),
            PlotStore.startRefusal(loadText(dir, "{\"plots\":[]}" + " ".repeat(1_048_576)), 128));
    }

    @Test
    void any_rejected_row_refuses_the_start_with_its_count(@TempDir Path dir) throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        String first = row(0, a, 0, 0);
        String one = "1 of 2 rows in plots.json were rejected: row 1 (%s); rows are counted from 0";
        assertEquals(Optional.of(one.formatted("invalid")), PlotStore.startRefusal(loadText(dir, "{\"plots\":[" + first + "," + row(1, b, 136, 0) + "]}"), 128), "an origin off the 16 grid");
        assertEquals(Optional.of(one.formatted("duplicate_index")), PlotStore.startRefusal(loadText(dir, "{\"plots\":[" + first + "," + row(0, b, 128, 0) + "]}"), 128), "a duplicate index");
        assertEquals(Optional.of(one.formatted("duplicate_owner")), PlotStore.startRefusal(loadText(dir, "{\"plots\":[" + first + "," + row(1, a, 128, 0) + "]}"), 128), "a duplicate owner");
        assertEquals(Optional.of(one.formatted("overlap")), PlotStore.startRefusal(loadText(dir, "{\"plots\":[" + first + "," + row(1, b, 16, 0) + "]}"), 128), "an overlapping origin");
        String message = PlotStore.startRefusal(loadText(dir, "{\"plots\":[" + first + ",7," + row(1, b, 128, 0) + "," + row(1, c, 128, 128) + "]}"), 128).orElseThrow();
        assertEquals("2 of 4 rows in plots.json were rejected: row 1 (invalid), row 3 (duplicate_index); rows are counted from 0", message);
        for (UUID owner : List.of(a, b, c)) assertFalse(message.contains(owner.toString()), "the reason names no owner");
    }

    @Test
    void a_refused_start_names_each_rejected_row_and_its_reason(@TempDir Path dir) throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        // Row 1 repeats index 0; row 4 is a new index and owner 16 blocks from row 0: the same ground.
        String text = "{\"plots\":[" + row(0, a, 0, 0) + "," + row(0, b, 128, 0) + "," + row(1, b, 128, 0) + ","
            + row(2, UUID.randomUUID(), 128, 128) + "," + row(3, UUID.randomUUID(), 16, 0) + "]}";
        PlotStore.Loaded loaded = loadText(dir, text);
        assertEquals(List.of(new PlotStore.Rejected(1, PlotStore.Reason.DUPLICATE_INDEX), new PlotStore.Rejected(4, PlotStore.Reason.OVERLAP)),
            loaded.rejected());
        assertEquals(2, loaded.skipped());
        assertEquals(3, loaded.plots().size());
        assertEquals(Optional.of("2 of 5 rows in plots.json were rejected: row 1 (duplicate_index), row 4 (overlap); rows are counted from 0"),
            PlotStore.startRefusal(loaded, 128));
    }

    @Test
    void a_refused_start_names_at_most_eight_rows(@TempDir Path dir) throws Exception {
        String valid = row(0, UUID.randomUUID(), 0, 0);
        String eight = "row 1 (invalid), row 2 (invalid), row 3 (invalid), row 4 (invalid), row 5 (invalid), row 6 (invalid),"
            + " row 7 (invalid), row 8 (invalid)";
        assertEquals(Optional.of("8 of 9 rows in plots.json were rejected: " + eight + "; rows are counted from 0"),
            PlotStore.startRefusal(loadText(dir, "{\"plots\":[" + valid + ",7".repeat(8) + "]}"), 128), "eight rows are all named");
        assertEquals(Optional.of("11 of 12 rows in plots.json were rejected: " + eight + " and 3 more; rows are counted from 0"),
            PlotStore.startRefusal(loadText(dir, "{\"plots\":[" + valid + ",7".repeat(11) + "]}"), 128));
    }

    @Test
    void a_refused_start_repeats_nothing_from_the_file(@TempDir Path dir) throws Exception {
        // Text in every field a hostile file controls, with a line break that would forge a log line.
        String odd = "{\"index\":\"idx-INJECT\",\"owner\":\"Steve\\nevent=plot_allocated INJECT\",\"x\":0,\"y\":0,\"z\":0,\"INJECT\":\"INJECT\"}";
        String badOwner = "{\"index\":1,\"owner\":\"Steve\\nevent=plot_allocated INJECT\",\"x\":128,\"y\":0,\"z\":0}";
        String message = PlotStore.startRefusal(loadText(dir, "{\"plots\":[" + row(0, UUID.randomUUID(), 0, 0) + "," + odd + "," + badOwner + "]}"), 128).orElseThrow();
        assertEquals("2 of 3 rows in plots.json were rejected: row 1 (invalid), row 2 (invalid); rows are counted from 0", message);
        for (String text : List.of("INJECT", "Steve", "event=", "\n")) assertFalse(message.contains(text), text);
    }

    @Test
    void a_stored_origin_off_the_current_stride_refuses_the_start(@TempDir Path dir) throws Exception {
        PlotRegistry registry = new PlotRegistry();
        for (int i = 0; i < 3; i++) assertTrue(registry.allocateStored(dir, StudioId.of(UUID.randomUUID()), 128).created());
        assertNotNull(registry.assignStored(dir, StudioId.of(UUID.randomUUID()), 7, 128).plot());
        // Every path that stores an origin (allocate and assign) leaves the file on the grid of its stride.
        assertEquals(Optional.empty(), PlotStore.startRefusal(PlotStore.load(dir), 128));
        // Plot 0 is at the origin at every stride; the other three would be drawn elsewhere by a client.
        String tail = "): plots.json was written with another plotStride; restore the old value or move the plots";
        assertEquals(Optional.of("3 of 4 plots are not where plotStride 256 puts their index (plot 1, plot 2, plot 7" + tail),
            PlotStore.startRefusal(PlotStore.load(dir), 256));
        // More than eight are cut the same way as rejected rows.
        for (int i = 0; i < 7; i++) assertTrue(registry.allocateStored(dir, StudioId.of(UUID.randomUUID()), 128).created());
        assertEquals(Optional.of("10 of 11 plots are not where plotStride 256 puts their index"
            + " (plot 1, plot 2, plot 3, plot 4, plot 5, plot 6, plot 7, plot 8 and 2 more" + tail),
            PlotStore.startRefusal(PlotStore.load(dir), 256));
        // A hand-moved origin is the same disagreement, and so is a stride the grid refuses.
        String moved = "{\"plots\":[" + row(0, UUID.randomUUID(), 0, 0) + "," + row(1, UUID.randomUUID(), 1280, 0) + "]}";
        assertEquals(Optional.of("1 of 2 plots are not where plotStride 128 puts their index (plot 1" + tail),
            PlotStore.startRefusal(loadText(dir, moved), 128));
        assertTrue(PlotStore.startRefusal(loadText(dir, "{\"plots\":[" + row(0, UUID.randomUUID(), 0, 0) + "]}"), 8).isPresent());
        assertEquals(Optional.empty(), PlotStore.startRefusal(loadText(dir, "{\"plots\":[" + row(0, UUID.randomUUID(), 0, 0) + "]}"), 256));
    }

    @Test
    void a_save_the_loader_would_refuse_is_not_written(@TempDir Path dir) {
        List<Plot> plots = new ArrayList<>();
        for (int i = 0; i < 100; i++) plots.add(new Plot(i, StudioId.of(UUID.randomUUID()), PlotGrid.originOf(i, 128)));
        assertTrue(PlotStore.save(dir, plots));
        // About 80 bytes a row: 14,000 rows are past the 1,048,576 bytes the loader accepts.
        for (int i = 100; i < 14_000; i++) plots.add(new Plot(i, StudioId.of(UUID.randomUUID()), PlotGrid.originOf(i, 128)));
        assertFalse(PlotStore.save(dir, plots), "a file that would refuse the next start is not written");
        PlotStore.Loaded loaded = PlotStore.load(dir);
        assertEquals(100, loaded.plots().size(), "the previous file stays");
        assertEquals(Optional.empty(), PlotStore.startRefusal(loaded, 128));
    }

    private static String row(int index, UUID owner, int x, int z) {
        return "{\"index\":" + index + ",\"owner\":\"" + owner + "\",\"x\":" + x + ",\"y\":0,\"z\":" + z + "}";
    }

    private static PlotStore.Loaded loadText(Path dir, String text) throws Exception {
        return PlotStore.load(writeText(dir, text));
    }

    /** Writes {@code text} as the registry file and returns {@code dir}. */
    private static Path writeText(Path dir, String text) throws Exception {
        Path file = PlotStore.plotsFile(dir);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return dir;
    }

    private static PlotStore.Loaded loadOne(Path dir, int x, int z) throws Exception {
        UUID owner = UUID.randomUUID();
        assertTrue(PlotStore.save(dir, List.of(new Plot(0, StudioId.of(owner), new BlockPos(x, 0, z)))));
        return PlotStore.load(dir);
    }

    @Test
    void rebuild_window_ends_after_exactly_1200_ticks() {
        UUID id = UUID.randomUUID();
        PlotRebuilds.clear();
        try {
            PlotRebuilds.note(id, 1000);
            assertFalse(PlotRebuilds.allowed(id, 1000 + 1199), "1199 ticks is still inside the window");
            assertTrue(PlotRebuilds.allowed(id, 1000 + 1200), "1200 ticks ends the window");
            assertEquals(1, PlotRebuilds.remainingSeconds(id, 1000 + 1199));
            assertEquals(0, PlotRebuilds.remainingSeconds(id, 1000 + 1200));
        } finally {
            PlotRebuilds.clear();
        }
    }
}
