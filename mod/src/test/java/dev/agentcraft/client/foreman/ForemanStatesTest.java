package dev.agentcraft.client.foreman;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import dev.agentcraft.mp.MpLog;
import org.junit.jupiter.api.Test;

class ForemanStatesTest {
    @Test void showcase_fixtures_load_without_a_client_and_have_the_captured_counts() {
        try(var capture=MpLog.capture()) {
            ForemanState first=ForemanStates.showcase(), late=ForemanStates.showcaseLate();
            for(ForemanState state:new ForemanState[]{first,late}) {
                assertEquals(6,state.agents().size()); assertEquals(9,state.tasks().size());
                assertNotNull(state.goal()); assertTrue(state.hasData()); assertEquals(1,state.snapshotCount());
                assertTrue(state.link().synced());
            }
            assertEquals(2,first.openDecisions().size()); assertEquals(0,late.openDecisions().size());
            assertEquals(6,late.decisions().size());
            assertTrue(capture.lines().isEmpty());
        }
    }
    @Test void every_factory_call_is_fresh_and_custom_snapshots_are_applied() {
        ForemanState first=ForemanStates.showcase(), other=ForemanStates.showcase();
        first.receive("snapshot",new JsonObject());
        assertTrue(first.agents().isEmpty()); assertEquals(6,other.agents().size());
        assertNotSame(ForemanStates.showcaseLate(),ForemanStates.showcaseLate());
        JsonObject snapshot=new JsonObject();
        snapshot.add("agents",ForemanJson.GSON.toJsonTree(other.agents().values()));
        ForemanState custom=ForemanStates.fromSnapshot(snapshot);
        assertEquals(other.agents(),custom.agents()); assertTrue(custom.tasks().isEmpty());
        snapshot.getAsJsonArray("agents").remove(0); assertEquals(6,custom.agents().size());
        assertNotSame(custom,ForemanStates.fromSnapshot(snapshot));
    }
}
