package dev.agentcraft.mp.server.plot;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.agentcraft.mp.MpLog;
import dev.agentcraft.mp.MpReasons;
import dev.agentcraft.mp.Plot;
import dev.agentcraft.mp.StudioId;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The free/rollback paths of {@link PlotCommands}, in the package that can reach its seam. */
class PlotReleaseTest {
    @Test
    void a_failed_rollback_save_freezes_the_registry(@TempDir Path dir) {
        PlotRegistry registry = new PlotRegistry();
        UUID owner = UUID.randomUUID();
        registry.restore(new Plot(0, StudioId.of(owner), BlockPos.ZERO));
        AtomicInteger saves = new AtomicInteger();
        PlotCommands.ReleaseIo io = new PlotCommands.ReleaseIo() {
            @Override
            public boolean save(Path root, Collection<Plot> plots) {
                // The take's save succeeds; the rollback save after the failed delete fails.
                return saves.incrementAndGet() == 1;
            }

            @Override
            public boolean delete(Path root, int index) {
                return false;
            }
        };
        assertFalse(PlotCommands.release(dir, registry, 0, io), "the free is refused");
        assertTrue(registry.frozen(), "a failed rollback freezes the registry");
        assertNotNull(registry.byIndex(0), "the row stays in memory for an operator to inspect");
        assertEquals(2, saves.get(), "both saves ran");
        try (var capture = MpLog.capture()) {
            var result = registry.allocateStored(dir, StudioId.of(UUID.randomUUID()), 128);
            assertNull(result.plot(), "a frozen registry allocates nothing");
            assertEquals(MpReasons.IO_ERROR, result.failure());
            assertTrue(capture.lines().stream().anyMatch(line -> line.contains("reason=io_error")));
        }
    }

    @Test
    void a_successful_rollback_restores_the_row_without_freezing(@TempDir Path dir) {
        PlotRegistry registry = new PlotRegistry();
        UUID owner = UUID.randomUUID();
        registry.restore(new Plot(0, StudioId.of(owner), BlockPos.ZERO));
        PlotCommands.ReleaseIo io = new PlotCommands.ReleaseIo() {
            @Override
            public boolean save(Path root, Collection<Plot> plots) {
                return PlotStore.save(root, plots);
            }

            @Override
            public boolean delete(Path root, int index) {
                return false;
            }
        };
        assertFalse(PlotCommands.release(dir, registry, 0, io), "the free is refused");
        assertFalse(registry.frozen(), "a rollback that saved the file keeps the registry usable");
        assertNotNull(registry.byIndex(0), "the row is back");
        assertTrue(PlotStore.load(dir).plots().stream().anyMatch(plot -> plot.index() == 0));
    }

    @Test
    void a_traversal_failure_is_a_refused_free_with_rollback(@TempDir Path dir) throws Exception {
        PlotRegistry registry = new PlotRegistry();
        UUID owner = UUID.randomUUID();
        registry.restore(new Plot(0, StudioId.of(owner), BlockPos.ZERO));
        assertTrue(PlotStore.save(dir, registry.all()));
        Path child = PlotStore.plotDirectory(dir, 0).orElseThrow().resolve("child");
        Files.createDirectories(child);
        Files.writeString(child.resolve("f"), "x");
        assumeTrue(Files.getFileStore(child).supportsFileAttributeView("posix"),
            "POSIX permissions are required to make a directory unreadable");
        Files.setPosixFilePermissions(child, PosixFilePermissions.fromString("---------"));
        try {
            assumeTrue(!Files.isReadable(child), "a user who can read any directory (root) cannot provoke the failure");
            assertFalse(PlotStore.deletePlotDirectory(dir, 0), "an unchecked traversal failure is a failed delete");
            assertFalse(PlotCommands.release(dir, registry, 0), "the free is refused");
            assertNotNull(registry.byIndex(0), "the row is restored");
            assertFalse(registry.frozen(), "the rollback save succeeded, so the registry is not frozen");
            assertTrue(PlotStore.load(dir).plots().stream().anyMatch(plot -> plot.index() == 0), "the file keeps the row");
        } finally {
            Files.setPosixFilePermissions(child, PosixFilePermissions.fromString("rwxrwxrwx"));
        }
    }
}