package com.jiyingda.codly.command;

import com.jiyingda.codly.skill.Skill;
import com.jiyingda.codly.skill.SkillRegistry;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import picocli.CommandLine.Command;

import java.util.ArrayList;
import java.util.List;

/** 行首斜杠命令与 Skill 的候选来源；显示及按键由 CommandLineReader 管理。 */
public class CommandCompleter implements Completer {

    private static final String GROUP_COMMAND = "命令";
    private static final String GROUP_SKILL = "Skills";
    private static final String SKILL_PREFIX = "/skill-";

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        if (line == null) {
            return;
        }
        String word = line.word();
        if (word == null || !word.startsWith("/") || !isCommandToken(line.line())) {
            return;
        }
        candidates.addAll(candidatesFor(word));
    }

    /** 安装命令面板；面板与输入共用主 keymap，不进入 JLine 阻塞式菜单。 */
    public void install(LineReader reader) {
        if (reader instanceof CommandLineReader commandReader) commandReader.installCommandMenu();
    }

    /**
     * 按前缀列出内置命令与 skill 候选。
     */
    static List<Candidate> candidatesFor(String prefix) {
        String lower = prefix.toLowerCase(java.util.Locale.ROOT);
        List<Candidate> result = new ArrayList<>();

        for (CommandEntry entry : builtinCommands()) {
            if (entry.name().toLowerCase(java.util.Locale.ROOT).startsWith(lower)) {
                result.add(candidate(entry.name(), GROUP_COMMAND, entry.description()));
            }
        }
        for (Skill skill : SkillRegistry.getInstance().all()) {
            String name = SKILL_PREFIX + skill.getName();
            if (name.toLowerCase(java.util.Locale.ROOT).startsWith(lower)) {
                result.add(candidate(name, GROUP_SKILL, skill.getDescription()));
            }
        }
        List<String> priority = List.of("/help", "/model", "/permissions", "/resume", "/clear", "/compact", "/context", "/mcp", "/tasks", "/trace");
        result.sort(java.util.Comparator.comparingInt((Candidate candidate) -> {
            int index = priority.indexOf(candidate.value());
            return index < 0 ? priority.size() : index;
        }).thenComparing(Candidate::value));
        return result;
    }

    private static Candidate candidate(String name, String group, String description) {
        // suffix 必须留空：JLine 在 suffix 非 null 时只补 suffix 而不再补全词本身。
        // complete=true 表示该候选是完整取值，补全后会补一个空格，方便继续输入子命令参数。
        return new Candidate(name, name, group, description, null, null, true);
    }

    /**
     * 从 {@link CommandDispatcher} 的 picocli 注解读取内置命令，
     * 新增或删除子命令时无需再改这里。
     */
    private static List<CommandEntry> builtinCommands() {
        List<CommandEntry> entries = new ArrayList<>();
        Command root = CommandDispatcher.class.getAnnotation(Command.class);
        if (root == null) {
            return entries;
        }
        for (Class<?> subcommand : root.subcommands()) {
            Command annotation = subcommand.getAnnotation(Command.class);
            if (annotation == null || annotation.name().isBlank()) {
                continue;
            }
            String[] description = annotation.description();
            entries.add(new CommandEntry(annotation.name(), description.length == 0 ? "" : description[0]));
        }
        return entries;
    }

    static boolean isCommandToken(String text) {
        return text != null && text.startsWith("/") && text.chars().noneMatch(Character::isWhitespace);
    }

    private record CommandEntry(String name, String description) {}
}
