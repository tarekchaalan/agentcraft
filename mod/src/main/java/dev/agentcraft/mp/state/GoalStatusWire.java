package dev.agentcraft.mp.state;

import java.util.Locale;

public enum GoalStatusWire {
    NONE, PLANNING, ACTIVE, DONE, FAILED, CANCELLED;
    public String wire() { return name().toLowerCase(Locale.ROOT); }
}
