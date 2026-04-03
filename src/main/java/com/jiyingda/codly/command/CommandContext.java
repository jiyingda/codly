package com.jiyingda.codly.command;

import com.jiyingda.codly.data.Message;
import com.jiyingda.codly.llm.LlmClient;
import com.jiyingda.codly.prompt.SystemPrompt;

import java.util.List;

/**
 * 命令执行上下文，携带命令操作所需的共享状态。
 */
public class CommandContext {

    private final List<Message> memory;
    private final LlmClient llmClient;

    public CommandContext(List<Message> memory, LlmClient llmClient) {
        this.memory = memory;
        this.llmClient = llmClient;
    }

    public List<Message> getMemory() {
        return memory;
    }

    public LlmClient getLlmClient() {
        return llmClient;
    }

    public void resetMemory() {
        memory.clear();
        memory.add(Message.fromSystem(SystemPrompt.SOUL_PROMPT));
    }
}
