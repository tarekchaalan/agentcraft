package dev.agentcraft.mp.state;

import java.util.Locale;

public enum AgentStateWire {
    IDLE, THINKING, READING, EDITING, RUNNING, TESTING, WAITING_USER, BLOCKED, DONE, ERROR;
    public String wire() { return name().toLowerCase(Locale.ROOT); }
}
