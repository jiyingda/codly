package com.jiyingda.codly;

import com.jiyingda.codly.core.ConversationContext;
import com.jiyingda.codly.core.SessionMemory;
import com.jiyingda.codly.core.ConversationTitle;
import com.jiyingda.codly.data.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 会话生命周期管理：memory 维护、标题生成、长期记忆触发、shutdown。
 */
public class SessionManager {

    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);

    private final List<Message> memory;
    private final SessionMemory memoryManager;
    private final ConversationContext ctx;
    private final Consumer<String> onTitle;
    private volatile boolean closed;
    private final java.util.concurrent.atomic.AtomicInteger generation = new java.util.concurrent.atomic.AtomicInteger();
    private final AtomicBoolean titleGenerated = new AtomicBoolean(false);

    public SessionManager(List<Message> memory, SessionMemory memoryManager,
                          ConversationContext ctx, Consumer<String> onTitle) {
        this.memory = memory;
        this.memoryManager = memoryManager;
        this.ctx = ctx;
        this.onTitle = onTitle;
    }

    /**
     * 处理用户输入：追加到 memory，首次触发异步标题生成。
     */
    public void handleUserMessage(String userInput) {
        Message userMessage = Message.fromUser(userInput);
        memory.add(userMessage);
        memoryManager.appendMessage(userMessage);

        // 即使标题请求失败或用户立即退出，也有可刷盘的会话文件。
        memoryManager.initializeWithSession(userInput.substring(0, Math.min(userInput.length(), 40)));
        if (titleGenerated.compareAndSet(false, true)) {
            int currentGeneration = generation.get();
            ctx.getLlmClient().generateTitleAsync(userInput, title -> {
                synchronized (SessionManager.this) {
                    String name = ConversationTitle.fromModel(title);
                    if (!closed && currentGeneration == generation.get() && name != null) onTitle.accept(name);
                }
            }, ctx.traceListener());
        }
    }

    /**
     * 处理助手响应：追加到 memory，触发长期记忆提取。
     */
    public void handleAssistantResponse(String res, String userInput) {
        Message assistantMessage = Message.formAssistant(res);
        memory.add(assistantMessage);
        boolean flushed = memoryManager.appendMessage(assistantMessage);

        memoryManager.appendLtmRound(userInput, res);
        if (flushed) {
            memoryManager.flushAndExtractLtmAsync(ctx);
        }
    }

    /**
     * 退出时刷盘、提取长期记忆、关闭异步执行器。
     */
    public synchronized void newConversation() {
        memoryManager.flushNow();
        memoryManager.clearSession();
        ctx.resetMemory();
        generation.incrementAndGet();
        titleGenerated.set(false);
    }

    public synchronized void restoreConversation(List<Message> messages, String reference) {
        memoryManager.flushNow();
        memoryManager.restoreSession(reference, messages);
        ctx.resetMemory();
        memory.addAll(messages);
        generation.incrementAndGet();
        titleGenerated.set(true);
    }

    public String getSessionReference() { return memoryManager.getCurrentSessionFile(); }

    public void shutdown() {
        closed = true;
        logger.info("SessionManager shutdown 开始...");
        memoryManager.flushNow();
        try {
            memoryManager.flushAndExtractLtmSync(ctx);
        } finally {
            memoryManager.shutdown();
        }
    }

    public ConversationContext getContext() {
        return ctx;
    }
}
