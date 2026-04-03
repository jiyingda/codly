package com.jiyingda.codly.llm;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.InfoCmp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

/**
 * 终端交互式模型选择器，支持上下键高亮选择。
 */
public class ModelSelector {

    private static final String RESET  = "\033[0m";
    private static final String BOLD   = "\033[1m";
    private static final String GREEN  = "\033[32m";
    private static final String CYAN   = "\033[36m";

    /**
     * 展示模型列表，用户用上下键选择后按 Enter 确认。
     *
     * @param models      可用模型列表
     * @param currentModel 当前使用的模型（高亮显示）
     * @return 用户选择的模型，取消返回 null
     */
    public static String select(List<String> models, String currentModel) {
        int selected = models.indexOf(currentModel);
        if (selected < 0) selected = 0;

        try (Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .jna(true)
                .dumb(true)  // 如果系统终端不可用，回退到哑终端
                .build()) {

            terminal.enterRawMode();
            terminal.puts(InfoCmp.Capability.cursor_invisible);
            terminal.flush();

            try {
                printList(terminal, models, selected, currentModel);

                while (true) {
                    int c = terminal.reader().read();

                    if (c == 27) { // ESC 序列
                        int c2 = terminal.reader().read();
                        if (c2 == '[') {
                            int c3 = terminal.reader().read();
                            if (c3 == 'A') { // 上键
                                selected = (selected - 1 + models.size()) % models.size();
                            } else if (c3 == 'B') { // 下键
                                selected = (selected + 1) % models.size();
                            }
                            clearList(terminal, models.size());
                            printList(terminal, models, selected, currentModel);
                        } else if (c2 == 27 || c2 == -1) {
                            // 单独 ESC — 取消
                            clearList(terminal, models.size());
                            terminal.writer().println("已取消");
                            terminal.writer().flush();
                            return null;
                        }
                    } else if (c == '\r' || c == '\n') { // Enter
                        clearList(terminal, models.size());
                        String chosen = models.get(selected);
                        terminal.writer().println("已切换到模型：" + CYAN + chosen + RESET);
                        terminal.writer().flush();
                        return chosen;
                    } else if (c == 'q' || c == 3) { // q 或 Ctrl+C — 取消
                        clearList(terminal, models.size());
                        terminal.writer().println("已取消");
                        terminal.writer().flush();
                        return null;
                    }
                }
            } finally {
                terminal.puts(InfoCmp.Capability.cursor_visible);
                terminal.flush();
            }

        } catch (IOException e) {
            System.err.println("终端交互失败，切换到文本菜单模式：" + e.getMessage());
            return selectViaTextMenu(models, currentModel);
        }
    }

    private static void printList(Terminal terminal, List<String> models, int selected, String current) {
        terminal.writer().println(BOLD + "选择模型（↑↓ 移动，Enter 确认，q 取消）：" + RESET);
        for (int i = 0; i < models.size(); i++) {
            String model = models.get(i);
            boolean isCurrent = model.equals(current);
            if (i == selected) {
                terminal.writer().println(GREEN + BOLD + "  ❯ " + model + (isCurrent ? "  (当前)" : "") + RESET);
            } else {
                terminal.writer().println("    " + model + (isCurrent ? "  (当前)" : ""));
            }
        }
        terminal.writer().flush();
    }

    private static void clearList(Terminal terminal, int count) {
        // 清除列表行数 = 标题行 + 每个模型一行
        for (int i = 0; i < count + 1; i++) {
            terminal.puts(InfoCmp.Capability.cursor_up);
            terminal.puts(InfoCmp.Capability.clr_eol);
        }
        terminal.flush();
    }

    /**
     * 文本菜单选择（当系统终端不可用时的备用方案）
     */
    private static String selectViaTextMenu(List<String> models, String currentModel) {
        System.out.println("\n选择模型（输入序号 + Enter）：");
        for (int i = 0; i < models.size(); i++) {
            String model = models.get(i);
            String marker = model.equals(currentModel) ? " (当前) " : "";
            System.out.println((i + 1) + ". " + model + marker);
        }
        System.out.print("请输入序号 [1-" + models.size() + "]: ");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String input = reader.readLine();
            int choice = Integer.parseInt(input.trim());
            if (choice >= 1 && choice <= models.size()) {
                String chosen = models.get(choice - 1);
                System.out.println("已切换到模型：" + CYAN + chosen + RESET);
                return chosen;
            } else {
                System.out.println("无效的选择");
                return null;
            }
        } catch (NumberFormatException e) {
            System.out.println("输入无效，请输入数字");
            return null;
        } catch (IOException e) {
            System.err.println("读取输入失败：" + e.getMessage());
            return null;
        }
    }
}
