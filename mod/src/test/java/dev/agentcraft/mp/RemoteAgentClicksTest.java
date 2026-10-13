package dev.agentcraft.mp;

import static org.junit.jupiter.api.Assertions.*;
import dev.agentcraft.client.mp.RemoteAgentClicks;
import dev.agentcraft.client.mp.StudioView;
import dev.agentcraft.layout.Anchors;
import java.util.*;
import org.junit.jupiter.api.Test;

class RemoteAgentClicksTest {
    @Test void a_click_does_nothing_until_a_handler_is_registered_and_then_reaches_only_it() {
        StudioView remote=new StudioView(StudioId.of(UUID.randomUUID()),false,"Bob",true,Anchors.Layout.EMPTY,null,1);
        assertDoesNotThrow(()->RemoteAgentClicks.fire(remote,"kit"));
        List<String> seen=new ArrayList<>();
        try {
            RemoteAgentClicks.set((studio,agentId)->seen.add(studio.ownerName()+":"+agentId));
            RemoteAgentClicks.fire(remote,"kit");
            assertEquals(List.of("Bob:kit"),seen);
            RemoteAgentClicks.set((studio,agentId)->seen.add("second"));
            RemoteAgentClicks.fire(remote,"wren");
            assertEquals(List.of("Bob:kit","second"),seen);
            assertThrows(NullPointerException.class,()->RemoteAgentClicks.set(null));
            assertThrows(NullPointerException.class,()->RemoteAgentClicks.fire(null,"kit"));
        } finally { RemoteAgentClicks.set((studio,agentId)->{}); }
    }
}
