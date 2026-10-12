package dev.agentcraft.client.mp.publish;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.agentcraft.mp.state.PublicPolicy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Owner opt-in flags. Missing or malformed files stay at {@link PublicPolicy#DEFAULT}. */
public final class PolicyStore {
    private final Path file;
    private PublicPolicy policy;

    private PolicyStore(Path file, PublicPolicy policy) {
        this.file = file;
        this.policy = policy;
    }

    public static PolicyStore load(Path file) {
        PublicPolicy policy = PublicPolicy.DEFAULT;
        if (Files.isRegularFile(file)) {
            try {
                JsonElement parsed = JsonParser.parseString(Files.readString(file));
                if (parsed.isJsonObject()) policy = read(parsed.getAsJsonObject());
            } catch (RuntimeException | IOException ignored) {
                policy = PublicPolicy.DEFAULT;
            }
        }
        return new PolicyStore(file, policy);
    }

    public PublicPolicy current() { return policy; }

    public static int bits(PublicPolicy policy) {
        return (policy.activityText() ? 1 : 0) | (policy.sayText() ? 2 : 0) | (policy.taskTitles() ? 4 : 0) | (policy.goalText() ? 8 : 0);
    }

    public int bits() { return bits(policy); }

    public static boolean flag(PublicPolicy policy, String name) {
        return switch (name) {
            case "activityText" -> policy.activityText();
            case "sayText" -> policy.sayText();
            case "taskTitles" -> policy.taskTitles();
            case "goalText" -> policy.goalText();
            default -> throw new IllegalArgumentException("unknown policy flag");
        };
    }

    /** Returns the previous policy. An unchanged value is not written. */
    public PublicPolicy set(String name, boolean value) {
        PublicPolicy before = policy;
        PublicPolicy next = switch (name) {
            case "activityText" -> new PublicPolicy(value, policy.sayText(), policy.taskTitles(), policy.goalText());
            case "sayText" -> new PublicPolicy(policy.activityText(), value, policy.taskTitles(), policy.goalText());
            case "taskTitles" -> new PublicPolicy(policy.activityText(), policy.sayText(), value, policy.goalText());
            case "goalText" -> new PublicPolicy(policy.activityText(), policy.sayText(), policy.taskTitles(), value);
            default -> throw new IllegalArgumentException("unknown policy flag");
        };
        if (!next.equals(before)) {
            write(next);
            policy = next;
        }
        return before;
    }

    private static PublicPolicy read(JsonObject root) {
        return new PublicPolicy(bool(root, "activityText"), bool(root, "sayText"), bool(root, "taskTitles"), bool(root, "goalText"));
    }

    private static boolean bool(JsonObject root, String key) {
        if (!root.has(key)) return false;
        JsonElement value = root.get(key);
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean() && value.getAsBoolean();
    }

    private void write(PublicPolicy next) {
        JsonObject root = new JsonObject();
        root.addProperty("activityText", next.activityText());
        root.addProperty("sayText", next.sayText());
        root.addProperty("taskTitles", next.taskTitles());
        root.addProperty("goalText", next.goalText());
        Path parent = file.getParent();
        try {
            if (parent != null) Files.createDirectories(parent);
            Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
            Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new IllegalStateException("policy file could not be saved");
        }
    }
}
