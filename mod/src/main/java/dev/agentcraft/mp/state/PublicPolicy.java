package dev.agentcraft.mp.state;

import java.util.*;
import org.jspecify.annotations.Nullable;

public record PublicPolicy(boolean activityText, boolean sayText, boolean taskTitles, boolean goalText) {
    public static final PublicPolicy DEFAULT = new PublicPolicy(false, false, false, false);
}
