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
    private com.jiyingda.codly.core.ChatSession session;
    private com.jiyingda.codly.cli.CliActions cliActions;

    public void setCliActions(com.jiyingda.codly.cli.CliActions actions) { cliActions = java.util.Objects.requireNonNull(actions); }
    public com.jiyingda.codly.cli.CliActions getCliActions() {
        if (cliActions == null) throw new IllegalStateException("CLI 交互尚未初始化");
        return cliActions;
    }

    public void setSession(com.jiyingda.codly.core.ChatSession session) { this.session = java.util.Objects.requireNonNull(session); }
    public com.jiyingda.codly.core.ChatSession getSession() {
        if (session == null) throw new IllegalStateException("CLI 会话尚未初始化");
        return session;
    }

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
