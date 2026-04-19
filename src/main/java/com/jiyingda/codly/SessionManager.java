package com.jiyingda.codly;

import com.jiyingda.codly.command.CommandContext;
import com.jiyingda.codly.data.Message;
import com.jiyingda.codly.llm.LlmProvider;
import com.jiyingda.codly.memory.MemoryManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 会话生命周期管理：memory 维护、标题生成、长期记忆触发、shutdown。
 */
public class SessionManager {

    private static final Logger logger = LoggerFactory.getLogger(SessionManager.class);

    private static final String TITLE_ESCAPE_PREFIX = "\033]0;Codly — ";
    private static final String TITLE_ESCAPE_SUFFIX = "\007";

    private final List<Message> memory;
    private final LlmProvider llmClient;
    private final MemoryManager memoryManager;
    private final CommandContext ctx;
    private final AtomicBoolean titleGenerated = new AtomicBoolean(false);

    public SessionManager(List<Message> memory, LlmProvider llmClient,
                          MemoryManager memoryManager, CommandContext ctx) {
        this.memory = memory;
        this.llmClient = llmClient;
        this.memoryManager = memoryManager;
        this.ctx = ctx;
    }

    /**
     * 处理用户输入：追加到 memory，首次触发异步标题生成。
     */
    public void handleUserMessage(String userInput) {
        Message userMessage = Message.fromUser(userInput);
        memory.add(userMessage);
        memoryManager.appendMessage(userMessage);

        if (!titleGenerated.get()) {
            llmClient.generateTitleAsync(userInput, title -> {
                titleGenerated.set(true);
                String terminalTitle = TITLE_ESCAPE_PREFIX + title + TITLE_ESCAPE_SUFFIX;
                System.out.print(terminalTitle);
                System.out.flush();
                memoryManager.initializeWithSession(title.trim());
            });
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
    public void shutdown() {
        logger.info("SessionManager shutdown 开始...");
        memoryManager.flushNow();
        memoryManager.flushAndExtractLtmSync(ctx);
        memoryManager.shutdown();
    }

    public CommandContext getCommandContext() {
        return ctx;
    }
}
