package dev.agentcraft.mp.state;

import java.util.*;
import org.jspecify.annotations.Nullable;

public record PublicTask(String id, String title, TaskStatusWire status, @Nullable String assignee) {
    public PublicTask { MpText.cap("task id",id,48); MpText.cap("task title",title,80); MpText.capOptional("task assignee",assignee,16); }
}
