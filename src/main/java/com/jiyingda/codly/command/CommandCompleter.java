package com.jiyingda.codly.command;

import com.jiyingda.codly.skill.Skill;
import com.jiyingda.codly.skill.SkillRegistry;
import org.jline.keymap.KeyMap;
import org.jline.reader.Binding;
import org.jline.reader.Buffer;
import org.jline.reader.Candidate;
import org.jline.reader.Completer;
import org.jline.reader.LineReader;
import org.jline.reader.ParsedLine;
import org.jline.reader.Parser;
import org.jline.reader.Reference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 斜杠命令补全器：输入 / 时给出匹配的内置命令与已装载 skill。
 * <p>
 * 交互方式：
 * <ul>
 *   <li>逐字符输入 —— 光标处于命令位置时自动列出候选（带描述，按「命令 / Skills」分组），并随输入收窄</li>
 *   <li>Tab —— 立即进入 JLine 原生高亮菜单：↑↓ 移动选择，Tab 跳到下一个，Enter 接受整行；
 *       若当前前缀只匹配一项，则直接补全并补一个空格</li>
 * </ul>
 * 候选只在「当前 token 以 / 开头」时给出，因此普通对话输入不会被打扰。
 */
public class CommandCompleter implements Completer {

    private static final Logger logger = LoggerFactory.getLogger(CommandCompleter.class);

    private static final String GROUP_COMMAND = "命令";
    private static final String GROUP_SKILL = "Skills";
    private static final String SKILL_PREFIX = "/skill-";

    /** 逐字符输入时刷新候选列表的 widget 名 */
    private static final String AUTO_LIST_WIDGET = "codly-auto-list";

    private static final char FIRST_PRINTABLE = 33;   // '!'
    private static final char LAST_PRINTABLE = 126;   // '~'

    @Override
    public void complete(LineReader reader, ParsedLine line, List<Candidate> candidates) {
        if (line == null) {
            return;
        }
        String word = line.word();
        if (word == null || !word.startsWith("/")) {
            return;
        }
        candidates.addAll(candidatesFor(word));
    }

    /**
     * 在 LineReader 上启用补全：逐字符输入自动列出候选，Tab 进入可上下选择的高亮菜单。
     * 需在 reader 构建完成、且已通过 builder 设置本 completer 之后调用。
     */
    public void install(LineReader reader) {
        reader.option(LineReader.Option.AUTO_GROUP, true);

        reader.getWidgets().put(AUTO_LIST_WIDGET, () -> {
            reader.callWidget(LineReader.SELF_INSERT);
            refreshCandidates(reader);
            return true;
        });

        KeyMap<Binding> main = reader.getKeyMaps().get(LineReader.MAIN);
        if (main == null) {
            logger.warn("未找到 MAIN keymap，跳过斜杠命令自动补全");
            return;
        }

        bindTabToMenuComplete(main);

        // 可见字符默认绑定为 self-insert，接管它们等价于在原有行为上追加一次候选刷新；
        // Tab（现在被改绑为 menu-complete）等有实际含义的绑定必须保留。
        int bound = 0;
        for (char c = FIRST_PRINTABLE; c <= LAST_PRINTABLE; c++) {
            String key = String.valueOf(c);
            Binding existing = main.getBound(key);
            if (existing instanceof Reference ref && !LineReader.SELF_INSERT.equals(ref.name())) {
                continue;
            }
            try {
                main.bind(new Reference(AUTO_LIST_WIDGET), key);
                bound++;
            } catch (Exception e) {
                logger.debug("按键 {} 绑定自动补全失败：{}", key, e.getMessage());
            }
        }
        logger.info("斜杠命令自动补全已启用（接管 {} 个按键）", bound);
    }

    /**
     * 把 Tab 从 expand-or-complete 改绑为 menu-complete。
     * <p>
     * expand-or-complete 在候选多于一个时只把候选「列」出来，不进菜单，此时按 ↑↓ 毫无反应；
     * menu-complete 一次按下就进入带高亮的原生菜单，↑↓ 可移动选择，Tab 继续下一个，
     * Enter 接受整行。唯一匹配时它同样直接补全。
     */
    private static void bindTabToMenuComplete(KeyMap<Binding> main) {
        List<String> tabKeys = new ArrayList<>();
        for (Map.Entry<String, Binding> entry : main.getBoundKeys().entrySet()) {
            Binding binding = entry.getValue();
            if (binding instanceof Reference ref && LineReader.EXPAND_OR_COMPLETE.equals(ref.name())) {
                tabKeys.add(entry.getKey());
            }
        }
        if (tabKeys.isEmpty()) {
            tabKeys.add("\t");
        }
        for (String key : tabKeys) {
            try {
                main.bind(new Reference(LineReader.MENU_COMPLETE), key);
            } catch (Exception e) {
                logger.debug("Tab 改绑 menu-complete 失败：{}", e.getMessage());
            }
        }
    }

    /**
     * 按前缀列出内置命令与 skill 候选。
     */
    static List<Candidate> candidatesFor(String prefix) {
        String lower = prefix.toLowerCase();
        List<Candidate> result = new ArrayList<>();

        for (CommandEntry entry : builtinCommands()) {
            if (entry.name().toLowerCase().startsWith(lower)) {
                result.add(candidate(entry.name(), GROUP_COMMAND, entry.description()));
            }
        }
        for (Skill skill : SkillRegistry.getInstance().all()) {
            String name = SKILL_PREFIX + skill.getName();
            if (name.toLowerCase().startsWith(lower)) {
                result.add(candidate(name, GROUP_SKILL, skill.getDescription()));
            }
        }
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

    /**
     * 逐字符输入后刷新候选列表。仅在命令位置、且存在未完全匹配的候选时才打印，
     * 避免普通输入和历史回滚时产生噪音。
     */
    private void refreshCandidates(LineReader reader) {
        try {
            Buffer buffer = reader.getBuffer();
            String before = buffer.upToCursor();
            if (!isCommandToken(before)) {
                return;
            }
            ParsedLine parsed = reader.getParser().parse(before, before.length(), Parser.ParseContext.COMPLETE);
            List<Candidate> candidates = new ArrayList<>();
            complete(reader, parsed, candidates);
            if (candidates.isEmpty()) {
                return;
            }
            if (candidates.size() == 1 && candidates.get(0).value().equals(parsed.word())) {
                return;
            }
            reader.callWidget(LineReader.LIST_CHOICES);
        } catch (Exception e) {
            logger.debug("刷新补全候选失败：{}", e.getMessage());
        }
    }

    /**
     * 判断光标前的内容是否是一个「正在输入的斜杠命令」：以 / 开头且尚未出现空白。
     */
    private static boolean isCommandToken(String text) {
        if (text == null || !text.startsWith("/")) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (Character.isWhitespace(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private record CommandEntry(String name, String description) {}
}
