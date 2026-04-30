package com.jiyingda.codly.command;

import com.jiyingda.codly.data.Message;
import com.jiyingda.codly.llm.LlmProvider;
import com.jiyingda.codly.prompt.SystemPrompt;
import com.jiyingda.codly.skill.Skill;
import org.jline.terminal.Terminal;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 命令执行上下文，携带命令操作所需的共享状态。
 */
public class CommandContext {

    private Message systemPrompt;
    private final List<Message> memory;
    private final LlmProvider llmClient;
    private final Path startupPath;
    private Terminal terminal;
    private volatile boolean shouldQuit;
    /** 当前会话已激活的 skill,按激活顺序,去重 */
    private final Map<String, Skill> activeSkills = new LinkedHashMap<>();

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

    public Message getSystemPrompt() {
        return systemPrompt;
    }
    public void setSystemPrompt(Message systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public void resetMemory() {
        memory.clear();
        memory.add(Message.fromSystem(SystemPrompt.SOUL_PROMPT));
        activeSkills.clear();
    }

    public List<Skill> getActiveSkills() {
        return new ArrayList<>(activeSkills.values());
    }

    /** @return true 表示新激活,false 表示已激活过 */
    public boolean activateSkill(Skill skill) {
        return activeSkills.put(skill.getName(), skill) == null;
    }

    public boolean deactivateSkill(String name) {
        return activeSkills.remove(name) != null;
    }

    public boolean isSkillActive(String name) {
        return activeSkills.containsKey(name);
    }

    /**
     * 把所有激活 skill 的完整正文拼成 system prompt 片段。
     * 无激活 skill 时返回空串。
     */
    public String activeSkillsPromptSection() {
        if (activeSkills.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n## Active Skills\n");
        sb.append("以下 skill 已被用户激活,请在本轮对话中遵循其说明:\n\n");
        for (Skill s : activeSkills.values()) {
            sb.append(s.toFullPromptSection()).append("\n\n");
        }
        return sb.toString();
    }

    public boolean shouldQuit() {
        return shouldQuit;
    }

    public void setShouldQuit(boolean shouldQuit) {
        this.shouldQuit = shouldQuit;
    }
}
