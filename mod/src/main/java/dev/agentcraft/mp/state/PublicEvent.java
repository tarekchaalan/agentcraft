package dev.agentcraft.mp.state;

import org.jspecify.annotations.Nullable;

public sealed interface PublicEvent permits PublicEvent.Say, PublicEvent.TaskDone {
    record Say(String agentId, @Nullable String to, @Nullable String text, int length) implements PublicEvent {
        public Say { MpText.cap("say agentId",agentId,16); MpText.capOptional("say to",to,16); MpText.capOptional("say text",text,120); }
    }
    record TaskDone(String agentId) implements PublicEvent {
        public TaskDone { MpText.cap("task_done agentId",agentId,16); }
    }
}
