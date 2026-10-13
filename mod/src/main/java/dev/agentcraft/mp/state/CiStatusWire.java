package dev.agentcraft.mp.state;

import java.util.Locale;

public enum CiStatusWire {
    UNKNOWN, RUNNING, PASS, FAIL;
    public String wire() { return name().toLowerCase(Locale.ROOT); }
}
