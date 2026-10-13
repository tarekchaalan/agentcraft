package dev.agentcraft.mp.state;

import java.util.*;
import org.jspecify.annotations.Nullable;

public record PublicAgent(String id, String name, String skin, AgentStateWire state, StationWire station, boolean active, boolean paused, boolean awaitingUser, @Nullable String activity) {
    /** A viewer's client builds resource identifiers from the id and the skin ({@code entity/agent/<skin>}), and the game throws on any other character. */
    public PublicAgent {
        MpText.path("agent id",id,16); MpText.cap("agent name",name,16); MpText.path("agent skin",skin,16); MpText.capOptional("agent activity",activity,48);
    }
}
