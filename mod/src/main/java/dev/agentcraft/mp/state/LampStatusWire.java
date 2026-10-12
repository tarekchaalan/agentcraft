package dev.agentcraft.mp.state;

import java.util.Locale;

public enum LampStatusWire {
    OFF, IDLE, THINKING, WORKING, WAITING, ERROR, DONE;
    public String wire() { return name().toLowerCase(Locale.ROOT); }
}
