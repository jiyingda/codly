package com.jiyingda.codly.skill;

import org.jline.terminal.Attributes;
import org.jline.terminal.Terminal;
import org.jline.utils.InfoCmp;

import java.io.IOException;
import java.util.List;

/**
 * /skill 命令的 TUI:上下键选择,Enter 确认,Esc/q/Ctrl+C 取消。
 * 参考 {@link com.jiyingda.codly.llm.ModelSelector} 的实现风格。
 */
public class SkillSelector {

    private static final String RESET = "\033[0m";
    private static final String BOLD = "\033[1m";
    private static final String GREEN = "\033[32m";
    private static final String CYAN = "\033[36m";
    private static final String DIM = "\033[2m";

    /**
     * 展示 skill 列表,返回用户选中的 skill;取消返回 null。
     */
    public static Skill select(List<Skill> skills, Terminal terminal) {
        if (skills.isEmpty()) {
            System.out.println("当前没有可用的 skill。请在 ~/.codly/skills/<name>/SKILL.md 下创建。");
            return null;
        }
        if (terminal == null) {
            // 无 terminal 时走文本菜单
            return selectViaTextMenu(skills);
        }

        try {
            return selectWithTerminal(skills, terminal);
        } catch (IOException e) {
            System.err.println("终端交互失败，切换到文本菜单模式：" + e.getMessage());
            return selectViaTextMenu(skills);
        }
    }

    private static Skill selectWithTerminal(List<Skill> skills, Terminal terminal) throws IOException {
        int selected = 0;
        Attributes prevAttr = terminal.enterRawMode();
        terminal.puts(InfoCmp.Capability.cursor_invisible);
        terminal.flush();
        int printedLines = 0;

        try {
            printedLines = printList(terminal, skills, selected);

            while (true) {
                int c = terminal.reader().read();

                if (c == 27) { // ESC 序列
                    int c2 = terminal.reader().read();
                    if (c2 == '[') {
                        int c3 = terminal.reader().read();
                        if (c3 == 'A') {
                            selected = (selected - 1 + skills.size()) % skills.size();
                        } else if (c3 == 'B') {
                            selected = (selected + 1) % skills.size();
                        }
                        clearLines(terminal, printedLines);
                        printedLines = printList(terminal, skills, selected);
                    } else if (c2 == 27 || c2 == -1) {
                        clearLines(terminal, printedLines);
                        terminal.writer().println("已取消");
                        terminal.writer().flush();
                        return null;
                    }
                } else if (c == '\r' || c == '\n') {
                    clearLines(terminal, printedLines);
                    Skill chosen = skills.get(selected);
                    terminal.writer().println("已激活 skill: " + CYAN + chosen.getName() + RESET);
                    terminal.writer().flush();
                    return chosen;
                } else if (c == 'q' || c == 3) {
                    clearLines(terminal, printedLines);
                    terminal.writer().println("已取消");
                    terminal.writer().flush();
                    return null;
                }
            }
        } finally {
            terminal.setAttributes(prevAttr);
            terminal.puts(InfoCmp.Capability.cursor_visible);
            terminal.flush();
        }
    }

    /** @return 实际打印的行数(方便精确清除) */
    private static int printList(Terminal terminal, List<Skill> skills, int selected) {
        int lines = 0;
        terminal.writer().println(BOLD + "选择要激活的 skill（↑↓ 移动，Enter 确认，q/Esc 取消）：" + RESET);
        lines++;
        for (int i = 0; i < skills.size(); i++) {
            Skill s = skills.get(i);
            String desc = truncate(s.getDescription(), 60);
            if (i == selected) {
                terminal.writer().println(GREEN + BOLD + "  ❯ " + s.getName() + RESET
                        + "  " + DIM + desc + RESET);
            } else {
                terminal.writer().println("    " + s.getName() + "  " + DIM + desc + RESET);
            }
            lines++;
        }
        terminal.writer().flush();
        return lines;
    }

    private static void clearLines(Terminal terminal, int count) {
        for (int i = 0; i < count; i++) {
            terminal.puts(InfoCmp.Capability.cursor_up);
            terminal.puts(InfoCmp.Capability.clr_eol);
        }
        terminal.flush();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 1) + "…";
    }

    private static Skill selectViaTextMenu(List<Skill> skills) {
        System.out.println("\n可用 skill:");
        for (int i = 0; i < skills.size(); i++) {
            Skill s = skills.get(i);
            System.out.println("  " + (i + 1) + ". " + s.getName() + " - " + truncate(s.getDescription(), 60));
        }
        System.out.print("请输入序号 [1-" + skills.size() + "]: ");
        try {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
            String line = r.readLine();
            if (line == null) return null;
            int idx = Integer.parseInt(line.trim());
            if (idx < 1 || idx > skills.size()) {
                System.out.println("无效的选择");
                return null;
            }
            Skill chosen = skills.get(idx - 1);
            System.out.println("已激活 skill: " + CYAN + chosen.getName() + RESET);
            return chosen;
        } catch (NumberFormatException e) {
            System.out.println("输入无效");
            return null;
        } catch (IOException e) {
            System.err.println("读取输入失败: " + e.getMessage());
            return null;
        }
    }
}
