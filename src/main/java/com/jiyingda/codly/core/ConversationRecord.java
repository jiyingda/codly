package com.jiyingda.codly.core;

import com.jiyingda.codly.data.Message;
import com.jiyingda.codly.llm.ThinkingDepth;
import java.time.Instant;
import java.util.List;

/** 可恢复的对话快照：模型上下文与页面最终答复分别保留。 */
public record ConversationRecord(String id, String title, String workspace, String provider, String model,
                                 ThinkingDepth thinkingDepth, Instant createdAt, Instant updatedAt,
                                 List<Message> messages, List<Turn> turns, List<TraceEvent> trace, String sessionFile) {
    public ConversationRecord {
        messages = List.copyOf(messages);
        turns = List.copyOf(turns);
        trace = List.copyOf(trace);
    }
    public record Turn(String user, String assistant, String state) {}
    public record Summary(String id, String title, String workspace, Instant updatedAt, boolean legacy) {}
}
