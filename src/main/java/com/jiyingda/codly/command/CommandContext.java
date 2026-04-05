package com.jiyingda.codly.command;

import com.jiyingda.codly.data.Message;
import com.jiyingda.codly.llm.LlmProvider;
import com.jiyingda.codly.prompt.SystemPrompt;
import org.jline.terminal.Terminal;

import java.nio.file.Path;
import java.util.List;

/**
 * 命令执行上下文，携带命令操作所需的共享状态。
 */
public class CommandContext {

    private final List<Message> memory;
    private final LlmProvider llmClient;
    private final Path startupPath;
    private Terminal terminal;

    public CommandContext(List<Message> memory, LlmProvider llmClient, Path startupPath) {
        this.memory = memory;
        this.llmClient = llmClient;
        this.startupPath = startupPath;
    }

    public List<Message> getMemory() {
        return memory;
    }

    public LlmProvider getLlmClient() {
        return llmClient;
    }

    public Path getStartupPath() {
        return startupPath;
    }

    public Terminal getTerminal() {
        return terminal;
    }

    public void setTerminal(Terminal terminal) {
        this.terminal = terminal;
    }

    public void resetMemory() {
        memory.clear();
        memory.add(Message.fromSystem(SystemPrompt.SOUL_PROMPT));
    }
}
