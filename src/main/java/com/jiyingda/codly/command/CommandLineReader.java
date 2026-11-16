package com.jiyingda.codly.command;

import org.jline.keymap.KeyMap;
import org.jline.reader.*;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.utils.*;

import java.util.*;
import java.util.function.Supplier;

/** 非模态命令面板：使用 JLine 自身的重绘区域，保持输入和编辑按键的所有权。 */
public final class CommandLineReader extends LineReaderImpl {
    private final Supplier<AttributedString> commandPost = this::renderCommands;
    private List<Candidate> commands = List.of();
    private String prefix, dismissed;
    private int selected = -1;
    private boolean menuInstalled;
    private boolean menuSuppressed;

    public CommandLineReader(Terminal terminal) {
        super(terminal, "Codly", new HashMap<>());
    }

    void installCommandMenu() {
        if (menuInstalled) return;
        menuInstalled = true;
        Widget previousInit = getWidgets().get(CALLBACK_INIT);
        getWidgets().put(CALLBACK_INIT, () -> {
            menuSuppressed = false;
            prefix = dismissed = null;
            selected = -1;
            return previousInit == null || previousInit.apply();
        });
        KeyMap<Binding> keys = getKeyMaps().get(MAIN);
        for (String name : List.of(UP_LINE_OR_HISTORY, UP_LINE_OR_SEARCH,
                DOWN_LINE_OR_HISTORY, DOWN_LINE_OR_SEARCH)) {
            int step = name.startsWith("up-") ? -1 : 1;
            wrap(keys, name, "codly-" + name, () -> {
                if (!commandMenuVisible()) return getBuiltinWidgets().get(name).apply();
                selected = selected < 0 ? 0 : Math.floorMod(selected + step, commands.size());
                return true;
            });
        }
        wrap(keys, ACCEPT_LINE, "codly-accept-command", () -> {
            if (commandMenuVisible()) return chooseCommand();
            return getBuiltinWidgets().get(ACCEPT_LINE).apply();
        });
        getWidgets().put("codly-complete-command", () -> {
            if (commandMenuVisible()) return chooseCommand();
            return getBuiltinWidgets().get(MENU_COMPLETE).apply();
        });
        keys.bind(new Reference("codly-complete-command"), "\t");
        getWidgets().put("codly-dismiss-commands", () -> {
            refreshCommands();
            dismissed = prefix;
            commands = List.of();
            if (post == commandPost) post = null;
            return true;
        });
        keys.bind(new Reference("codly-dismiss-commands"), "\033");
        keys.setAmbiguousTimeout(100);
    }

    private void wrap(KeyMap<Binding> keys, String original, String name, Widget widget) {
        getWidgets().put(name, widget);
        for (var entry : new ArrayList<>(keys.getBoundKeys().entrySet())) {
            if (entry.getValue() instanceof Reference ref && original.equals(ref.name()))
                keys.bind(new Reference(name), entry.getKey());
        }
    }

    /** 可用于验收；每次重绘均根据真实输入重新计算，删除、清行、粘贴都能退出面板。 */
    public boolean commandMenuVisible() {
        refreshCommands();
        return !commands.isEmpty();
    }

    private void refreshCommands() {
        String text = getBuffer().toString();
        if (menuSuppressed || getBuffer().cursor() != getBuffer().length() || !CommandCompleter.isCommandToken(text)
                || Boolean.TRUE.equals(getVariable(DISABLE_HISTORY))) {
            prefix = dismissed = null;
            selected = -1;
            commands = List.of();
            return;
        }
        if (!text.equals(prefix)) {
            prefix = text;
            dismissed = null;
            selected = -1;
        }
        if (text.equals(dismissed)) { commands = List.of(); return; }
        commands = CommandCompleter.candidatesFor(text);
        // 已输入完整命令时，Enter 直接执行，继续输入空格即可填参数。
        if (commands.stream().anyMatch(candidate -> candidate.value().equals(text))) commands = List.of();
        if (selected >= commands.size()) selected = -1;
    }

    private boolean chooseCommand() {
        Candidate candidate = commands.get(Math.max(0, selected));
        getBuffer().clear();
        getBuffer().write(candidate.value() + " ");
        refreshCommands();
        if (post == commandPost) post = null;
        return true;
    }

    @Override protected void redisplay(boolean flush) {
        if (menuInstalled && (post == null || post == commandPost)) {
            refreshCommands();
            post = commands.isEmpty() ? null : commandPost;
        }
        super.redisplay(flush);
    }

    @Override protected void cleanup() {
        menuSuppressed = true;
        commands = List.of();
        if (post == commandPost) post = null;
        super.cleanup();
    }

    private AttributedString renderCommands() {
        int width = Math.max(1, getTerminal().getWidth() - 1);
        int rows = Math.max(1, Math.min(6, getTerminal().getHeight() - 9));
        int choice = Math.max(0, selected);
        int first = Math.max(0, Math.min(choice - rows + 1, commands.size() - rows));
        int end = Math.min(first + rows, commands.size());
        int nameWidth = Math.min(25, Math.max(10, width / 3));
        AttributedStringBuilder result = new AttributedStringBuilder();
        appendRow(result, "  命令  " + (first + 1) + "–" + end + "/" + commands.size(), AttributedStyle.DEFAULT.faint(), width);
        for (int i = first; i < end; i++) {
            Candidate candidate = commands.get(i);
            boolean active = i == choice;
            String label = new AttributedString(candidate.value()).columnSubSequence(0, nameWidth).toString();
            int padding = Math.max(1, nameWidth - new AttributedString(label).columnLength() + 2);
            String description = Objects.toString(candidate.descr(), "").replaceAll("[\\p{Cntrl}\\s]+", " ");
            if ("Skills".equals(candidate.group())) description = "Skill · " + description;
            appendRow(result, (active ? "› " : "  ") + label + " ".repeat(padding) + description,
                    active ? AttributedStyle.DEFAULT.foreground(AttributedStyle.CYAN).bold() : AttributedStyle.DEFAULT.faint(), width);
        }
        String hint = width < 55 ? "  ↑↓ 选择 · Tab/Enter 补全 · Esc 关闭" : "  ↑↓ 选择   Tab/Enter 补全   Esc 关闭   直接输入可筛选";
        result.style(AttributedStyle.DEFAULT.faint()).append(new AttributedString(hint).columnSubSequence(0, width));
        return result.toAttributedString();
    }

    private static void appendRow(AttributedStringBuilder result, String text, AttributedStyle style, int width) {
        result.style(style).append(new AttributedString(text).columnSubSequence(0, width)).append('\n');
    }
}
