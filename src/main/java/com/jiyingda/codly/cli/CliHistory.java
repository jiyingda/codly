package com.jiyingda.codly.cli;

import com.jiyingda.codly.core.*;
import com.jiyingda.codly.memory.ConversationStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/** CLI 的归档适配器；与 Swing 共用 ConversationStore、原始上下文和最终消息格式。 */
public final class CliHistory {
    private final ConversationStore store;
    private final ChatSession session;
    private String id, title;
    private Instant created;
    private long generation;
    private final List<ConversationRecord.Turn> turns = new ArrayList<>();
    private final List<TraceEvent> trace = new ArrayList<>();

    public CliHistory(ConversationStore store, ChatSession session) {
        this.store = store; this.session = session; reset();
    }
    public synchronized void reset() {
        id = UUID.randomUUID().toString(); title = "新的对话"; created = Instant.now();
        generation++; turns.clear(); trace.clear();
    }
    public synchronized void rename(String title) { this.title = title; }
    public synchronized void addTurn(String input, String answer, String state) {
        if (turns.isEmpty() && title.equals("新的对话")) title = ConversationTitle.fallback(input);
        turns.add(new ConversationRecord.Turn(input, answer, state));
    }
    public synchronized Consumer<TraceEvent> traceSink() {
        long expected = generation;
        return event -> { synchronized (CliHistory.this) { if (generation == expected) trace.add(event); } };
    }
    public synchronized List<TraceEvent> trace() { return List.copyOf(trace); }
    public synchronized String id() { return id; }
    public List<ConversationRecord.Summary> list() throws IOException { return store.list(); }
    public ConversationRecord load(String id) throws IOException { return store.load(id); }
    public void restore(ConversationRecord record, Path workspace) throws IOException {
        save();
        session.restoreConversation(record, workspace);
        synchronized (this) {
            generation++; id = record.id(); title = record.title(); created = record.createdAt();
            turns.clear(); turns.addAll(record.turns()); trace.clear(); trace.addAll(record.trace());
        }
        save();
    }
    public void save() throws IOException {
        // 不持有元数据锁等待 ChatSession，避免与异步标题回调产生锁顺序反转。
        var messages = session.snapshotMessages();
        String provider = session.getProviderId(), model = session.getModel(), reference = session.getSessionReference();
        var depth = session.getThinkingDepth(); Path workspace = session.getWorkspace();
        synchronized (this) {
            if (turns.isEmpty()) return;
            store.save(new ConversationRecord(id, title, workspace.toString(), provider, model, depth,
                    created, Instant.now(), messages, List.copyOf(turns), List.copyOf(trace), reference));
        }
    }
}
