package dev.agentcraft.mp.state;

import java.util.*;
import org.jspecify.annotations.Nullable;

public record Counts(int todo, int doing, int review, int done, int blocked, int openDecisions, int openMerges) {
}
