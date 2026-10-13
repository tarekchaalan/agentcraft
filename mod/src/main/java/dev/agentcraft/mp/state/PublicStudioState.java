package dev.agentcraft.mp.state;

import java.util.*;
import org.jspecify.annotations.Nullable;

public record PublicStudioState(int rev, boolean foremanOnline, List<PublicAgent> agents, Counts counts, GoalSummary goal, List<CiSlot> ci, PublicPolicy policy, @Nullable List<PublicTask> tasks) {
    public PublicStudioState {
        MpText.cap("agents",agents,16); MpText.cap("ci",ci,8); if(tasks!=null) MpText.cap("tasks",tasks,32);
        agents = List.copyOf(agents); ci = List.copyOf(ci); tasks = tasks == null ? null : List.copyOf(tasks);
        if((!policy.activityText() && agents.stream().anyMatch(a->a.activity()!=null)) ||
            (!policy.goalText() && goal.text()!=null) || (!policy.taskTitles() && tasks!=null))
            throw new IllegalArgumentException("text without public policy");
    }
}
