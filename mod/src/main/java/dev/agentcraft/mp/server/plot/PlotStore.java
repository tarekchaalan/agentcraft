package dev.agentcraft.mp.server.plot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.AgentCraft;
import dev.agentcraft.layout.Anchors;
import dev.agentcraft.mp.Plot;
import dev.agentcraft.mp.PlotGrid;
import dev.agentcraft.mp.StudioId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;

/**
 * World-folder persistence for the plot registry. {@code agentcraft/plots.json} is the source of
 * truth (uuids, indexes, origins). Each plot's layout is {@code agentcraft/plots/<index>/anchors.json}.
 * An index becomes part of a path only after it is a non-negative int.
 */
public final class PlotStore {
    static final int MAX_BYTES = 1_048_576;
    /** How many rejected rows or off-grid plots a start refusal names before "and N more". */
    static final int MAX_NAMED = 8;
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private PlotStore() {}

    /** Why the whole registry file could not be used. A missing file is {@code NONE}: an empty registry. */
    public enum Problem { NONE, UNREADABLE, OVERSIZED, BAD_SHAPE }

    /** Why one row was rejected: the first check it failed, in this order. A fixed set, safe to log. */
    public enum Reason { INVALID, DUPLICATE_INDEX, DUPLICATE_OWNER, OVERLAP }

    /** A rejected row: its zero-based position in the {@code plots} array and why. Nothing from the row itself. */
    public record Rejected(int row, Reason reason) {}

    public record Loaded(List<Plot> plots, Problem problem, List<Rejected> rejected) {
        public Loaded {
            plots = List.copyOf(plots);
            rejected = List.copyOf(rejected);
        }

        /** How many rows were rejected. */
        public int skipped() {
            return rejected.size();
        }

        public boolean failed() {
            return problem != Problem.NONE;
        }
    }

    public static Path plotsFile(Path worldRoot) {
        return worldRoot.resolve("agentcraft").resolve("plots.json");
    }

    /** The plot directory, or empty when {@code index} must not be used as a path. */
    public static Optional<Path> plotDirectory(Path worldRoot, int index) {
        if (index < 0) return Optional.empty();
        Path base = worldRoot.resolve("agentcraft").resolve("plots").toAbsolutePath().normalize();
        Path dir = base.resolve(Integer.toString(index)).normalize();
        if (!dir.startsWith(base)) return Optional.empty();
        return Optional.of(dir);
    }

    public static boolean save(Path worldRoot, Collection<Plot> plots) {
        Path file = plotsFile(worldRoot);
        Path tmp = file.resolveSibling("plots.json.tmp");
        try {
            byte[] json = GSON.toJson(toJson(plots)).getBytes(StandardCharsets.UTF_8);
            // The loader refuses a larger file and the server then refuses to start: never write one.
            if (json.length > MAX_BYTES) return false;
            Files.createDirectories(file.getParent());
            Files.write(tmp, json);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            deleteQuietly(tmp);
            return false;
        }
    }

    public static Loaded load(Path worldRoot) {
        Path file = plotsFile(worldRoot);
        // Only a file that is known to be absent is an empty registry. One whose existence cannot be
        // determined falls through to the read below and fails there.
        if (Files.notExists(file)) return new Loaded(List.of(), Problem.NONE, List.of());
        try {
            if (Files.size(file) > MAX_BYTES) return new Loaded(List.of(), Problem.OVERSIZED, List.of());
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return new Loaded(List.of(), Problem.BAD_SHAPE, List.of());
            JsonElement plots = root.getAsJsonObject().get("plots");
            if (plots == null || !plots.isJsonArray()) return new Loaded(List.of(), Problem.BAD_SHAPE, List.of());
            List<Plot> loaded = new ArrayList<>();
            List<AABB> boxes = new ArrayList<>();
            Set<Integer> indexes = new HashSet<>();
            Set<UUID> owners = new HashSet<>();
            List<Rejected> rejected = new ArrayList<>();
            JsonArray rows = plots.getAsJsonArray();
            for (int row = 0; row < rows.size(); row++) {
                Plot plot = plotFrom(rows.get(row));
                AABB box = plot == null ? null : plot.box();
                // Every check runs before anything is recorded: a rejected row must not reserve its
                // index, owner or ground and reject the valid rows that follow it. Two stored origins
                // on the same ground would let two owners rebuild the same blocks, so the first row
                // wins there too, with the same box test an allocation uses.
                Reason reason = plot == null ? Reason.INVALID
                    : indexes.contains(plot.index()) ? Reason.DUPLICATE_INDEX
                    : owners.contains(plot.owner().owner()) ? Reason.DUPLICATE_OWNER
                    : overlaps(boxes, box) ? Reason.OVERLAP : null;
                if (reason != null) {
                    rejected.add(new Rejected(row, reason));
                    continue;
                }
                indexes.add(plot.index());
                owners.add(plot.owner().owner());
                boxes.add(box);
                loaded.add(plot);
            }
            return new Loaded(loaded, Problem.NONE, rejected);
        } catch (IOException | RuntimeException e) {
            return new Loaded(List.of(), Problem.UNREADABLE, List.of());
        }
    }

    /**
     * Why an enabled server must not start on {@code loaded}, or empty when it may. The next save
     * would drop a rejected row and hand its index to a new player on top of the old build, and a
     * client places a plot at {@code PlotGrid.originOf(index, stride)}, so one rejected row or one
     * origin off that grid refuses the whole file. The reason names rejected rows by their zero-based
     * position and a {@link Reason}, and off-grid plots by their index: ints and fixed words only, so
     * it carries no owner and no text from the file. At most {@link #MAX_NAMED} of either are named.
     */
    public static Optional<String> startRefusal(Loaded loaded, int stride) {
        String whole = switch (loaded.problem()) {
            case UNREADABLE -> "plots.json could not be read or is not JSON";
            case OVERSIZED -> "plots.json is larger than " + MAX_BYTES + " bytes";
            case BAD_SHAPE -> "plots.json is not an object with a \"plots\" array";
            case NONE -> null;
        };
        if (whole != null) return Optional.of(whole + "; no row was loaded");
        int rows = loaded.plots().size() + loaded.skipped();
        if (loaded.skipped() > 0) {
            List<String> names = new ArrayList<>();
            for (Rejected r : loaded.rejected()) {
                names.add("row " + r.row() + " (" + r.reason().name().toLowerCase(Locale.ROOT) + ")");
            }
            return Optional.of(loaded.skipped() + " of " + rows + " rows in plots.json were rejected: "
                + firstOf(names) + "; rows are counted from 0");
        }
        List<String> names = new ArrayList<>();
        for (Plot plot : loaded.plots()) {
            if (!onGrid(plot, stride)) names.add("plot " + plot.index());
        }
        if (names.isEmpty()) return Optional.empty();
        return Optional.of(names.size() + " of " + rows + " plots are not where plotStride " + stride + " puts their index ("
            + firstOf(names) + "): plots.json was written with another plotStride; restore the old value or move the plots");
    }

    /** The first {@link #MAX_NAMED} of {@code names}, then "and N more" for the rest. */
    private static String firstOf(List<String> names) {
        int shown = Math.min(names.size(), MAX_NAMED);
        String text = String.join(", ", names.subList(0, shown));
        return shown == names.size() ? text : text + " and " + (names.size() - shown) + " more";
    }

    private static boolean onGrid(Plot plot, int stride) {
        try {
            return plot.origin().equals(PlotGrid.originOf(plot.index(), stride));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public static boolean saveAnchors(Path worldRoot, int index, Anchors.Layout layout) {
        Optional<Path> dir = plotDirectory(worldRoot, index);
        if (dir.isEmpty()) return false;
        Path file = dir.get().resolve("anchors.json");
        Path tmp = dir.get().resolve("anchors.json.tmp");
        try {
            Files.createDirectories(dir.get());
            Files.writeString(tmp, GSON.toJson(Anchors.toJson(layout)), StandardCharsets.UTF_8);
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (IOException e) {
            deleteQuietly(tmp);
            return false;
        }
    }

    public static Optional<Anchors.Layout> loadAnchors(Path worldRoot, int index) {
        Optional<Path> dir = plotDirectory(worldRoot, index);
        if (dir.isEmpty()) return Optional.empty();
        Path file = dir.get().resolve("anchors.json");
        if (!Files.exists(file)) return Optional.empty();
        try {
            if (Files.size(file) > MAX_BYTES) return Optional.empty();
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) return Optional.empty();
            return Optional.of(Anchors.fromJson(root.getAsJsonObject()));
        } catch (IOException | RuntimeException e) {
            AgentCraft.LOGGER.warn("Could not read anchors for plot {}", index);
            return Optional.empty();
        }
    }

    /** Deletes {@code agentcraft/plots/<index>} only. The registry file and the blocks stay. */
    public static boolean deletePlotDirectory(Path worldRoot, int index) {
        Optional<Path> dir = plotDirectory(worldRoot, index);
        if (dir.isEmpty()) return false;
        if (!Files.exists(dir.get())) return true;
        try (var walk = Files.walk(dir.get())) {
            for (Path path : walk.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(path);
            }
            return true;
        } catch (IOException | UncheckedIOException e) {
            // Files.walk is lazy: an unreadable child directory surfaces here as an UncheckedIOException,
            // which must count as a deletion failure so the caller rolls back instead of throwing past it.
            return false;
        }
    }

    private static JsonObject toJson(Collection<Plot> plots) {
        JsonArray array = new JsonArray();
        for (Plot plot : plots) {
            JsonObject o = new JsonObject();
            o.addProperty("index", plot.index());
            o.addProperty("owner", plot.owner().owner().toString());
            o.addProperty("x", plot.origin().getX());
            o.addProperty("y", plot.origin().getY());
            o.addProperty("z", plot.origin().getZ());
            array.add(o);
        }
        JsonObject root = new JsonObject();
        root.add("plots", array);
        return root;
    }

    private static Plot plotFrom(JsonElement element) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject o = element.getAsJsonObject();
        OptionalInt index = integer(o.get("index"));
        OptionalInt x = integer(o.get("x"));
        OptionalInt y = integer(o.get("y"));
        OptionalInt z = integer(o.get("z"));
        if (index.isEmpty() || x.isEmpty() || y.isEmpty() || z.isEmpty()) return null;
        if (index.getAsInt() < 0 || y.getAsInt() != 0) return null;
        // Compare the signed values directly: Math.abs(Integer.MIN_VALUE) is negative, so it would
        // pass an absolute-value bound check and then pass the % 16 test.
        if (x.getAsInt() < -30_000_000 || x.getAsInt() > 30_000_000
            || z.getAsInt() < -30_000_000 || z.getAsInt() > 30_000_000) return null;
        if (x.getAsInt() % 16 != 0 || z.getAsInt() % 16 != 0) return null;
        JsonElement owner = o.get("owner");
        if (owner == null || !owner.isJsonPrimitive() || !owner.getAsJsonPrimitive().isString()) return null;
        UUID uuid;
        try {
            uuid = UUID.fromString(owner.getAsString());
        } catch (IllegalArgumentException e) {
            return null;
        }
        StudioId studio = StudioId.of(uuid);
        if (studio.equals(StudioId.LOCAL)) return null;
        return new Plot(index.getAsInt(), studio, new BlockPos(x.getAsInt(), y.getAsInt(), z.getAsInt()));
    }

    private static boolean overlaps(List<AABB> boxes, AABB box) {
        for (AABB old : boxes) {
            if (old.intersects(box)) return true;
        }
        return false;
    }

    private static OptionalInt integer(JsonElement element) {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) return OptionalInt.empty();
        try {
            BigDecimal value = element.getAsBigDecimal();
            return OptionalInt.of(value.intValueExact());
        } catch (ArithmeticException | NumberFormatException e) {
            return OptionalInt.empty();
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }
}
