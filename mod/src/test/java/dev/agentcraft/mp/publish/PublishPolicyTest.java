package dev.agentcraft.mp.publish;

import static org.junit.jupiter.api.Assertions.*;

import dev.agentcraft.client.mp.publish.PolicyStore;
import dev.agentcraft.mp.state.PublicPolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublishPolicyTest {
    @Test void missing_and_malformed_files_stay_default(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("agentcraft-public.json");
        assertEquals(PublicPolicy.DEFAULT, PolicyStore.load(file).current());
        assertEquals(0, PolicyStore.load(file).bits());
        Files.writeString(file, "{{{");
        assertEquals(PublicPolicy.DEFAULT, PolicyStore.load(file).current());
        Files.writeString(file, "{\"activityText\":\"yes\",\"sayText\":true,\"secretText\":\"SENTINEL_POLICY_SECRET\"}");
        PublicPolicy loaded = PolicyStore.load(file).current();
        assertFalse(loaded.activityText());
        assertTrue(loaded.sayText());
        assertFalse(loaded.taskTitles());
        assertFalse(loaded.goalText());
        assertEquals(2, PolicyStore.bits(loaded));
    }

    @Test void set_round_trips_and_does_not_write_an_unchanged_value(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("nested").resolve("agentcraft-public.json");
        PolicyStore store = PolicyStore.load(file);
        assertEquals(PublicPolicy.DEFAULT, store.set("activityText", false));
        assertFalse(Files.exists(file));
        store.set("goalText", true);
        assertEquals(8, store.bits());
        assertEquals(new PublicPolicy(false, false, false, true), PolicyStore.load(file).current());
        store.set("activityText", true);
        store.set("sayText", true);
        store.set("taskTitles", true);
        assertEquals(15, store.bits());
        assertEquals(store.current(), PolicyStore.load(file).current());
        String text = Files.readString(file);
        assertFalse(text.contains(dir.toString()));
        assertTrue(text.contains("\"goalText\":true"));
    }

    @Test void each_flag_is_its_own_bit() {
        assertEquals(1, PolicyStore.bits(new PublicPolicy(true, false, false, false)));
        assertEquals(2, PolicyStore.bits(new PublicPolicy(false, true, false, false)));
        assertEquals(4, PolicyStore.bits(new PublicPolicy(false, false, true, false)));
        assertEquals(8, PolicyStore.bits(new PublicPolicy(false, false, false, true)));
        assertTrue(PolicyStore.flag(new PublicPolicy(false, true, false, false), "sayText"));
        assertFalse(PolicyStore.flag(PublicPolicy.DEFAULT, "sayText"));
    }
}
