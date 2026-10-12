package dev.agentcraft.mp.state;

import java.util.*;
import org.jspecify.annotations.Nullable;

public record GoalSummary(GoalStatusWire status, float progress, @Nullable String text) {
    public GoalSummary { MpText.capOptional("goal text",text,120); }
}
