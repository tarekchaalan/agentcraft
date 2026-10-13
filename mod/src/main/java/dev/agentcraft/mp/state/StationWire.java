package dev.agentcraft.mp.state;

import java.util.Locale;

public enum StationWire {
    DESK, LIBRARY, TERMINAL, TESTBENCH, MERGESTATION, MEETING, LOUNGE, USER;
    public String wire() { return name().toLowerCase(Locale.ROOT); }
}
