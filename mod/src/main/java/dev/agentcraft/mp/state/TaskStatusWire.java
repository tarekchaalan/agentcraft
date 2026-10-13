package dev.agentcraft.mp.state;

import java.util.Locale;

public enum TaskStatusWire {
    TODO, DOING, REVIEW, DONE, BLOCKED, CANCELLED;
    public String wire() { return name().toLowerCase(Locale.ROOT); }
}
