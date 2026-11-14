package com.jiyingda.codly.command;

import com.jiyingda.codly.core.ConversationContext;
import com.jiyingda.codly.data.Message;
import com.jiyingda.codly.function.UserConfirmation;
import com.jiyingda.codly.llm.LlmProvider;
import org.jline.terminal.Terminal;

import java.nio.file.Path;
import java.util.List;

/** CLI 专用上下文。核心会话和工具不依赖终端。 */
public class CommandContext extends ConversationContext {
    private Terminal terminal;

    public CommandContext(List<Message> memory, LlmProvider llmClient, Path startupPath) {
        super(memory, llmClient, startupPath);
    }

    public Terminal getTerminal() {
        return terminal;
    }

    public void setTerminal(Terminal terminal) {
        this.terminal = terminal;
        setConfirmation(summary -> UserConfirmation.confirm(terminal, summary));
    }
}
