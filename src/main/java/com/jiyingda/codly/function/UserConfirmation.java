package com.jiyingda.codly.function;

import org.jline.terminal.Terminal;

import java.io.IOException;

/**
 * 危险操作的用户二次确认工具。
 * 通过终端直接读取单字符输入（y/n），不干扰 JLine LineReader 的行缓冲。
 */
public final class UserConfirmation {

    private static final String ANSI_YELLOW = "\u001B[33m";
    private static final String ANSI_RED = "\u001B[31m";
    private static final String ANSI_RESET = "\u001B[0m";

    private UserConfirmation() {}

    /**
     * 向用户展示操作摘要并等待确认。
     *
     * @param terminal JLine 终端实例
     * @param summary  操作摘要（人类可读）
     * @return true 表示用户允许，false 表示用户拒绝
     */
    public static boolean confirm(Terminal terminal, String summary) {
        if (terminal == null) {
            return true;
        }

        try {
            terminal.writer().println();
            terminal.writer().println(ANSI_YELLOW + "⚠ 即将执行以下操作：" + ANSI_RESET);
            terminal.writer().println("  " + summary);
            terminal.writer().print(ANSI_YELLOW + "是否允许？(y/N): " + ANSI_RESET);
            terminal.writer().flush();

            int ch = terminal.reader().read();

            // 消费掉可能的回车/换行
            if (ch == 'y' || ch == 'Y') {
                drainNewline(terminal);
                terminal.writer().println(ANSI_YELLOW + "✓ 已确认，正在执行..." + ANSI_RESET);
                terminal.writer().flush();
                return true;
            } else {
                drainNewline(terminal);
                terminal.writer().println(ANSI_RED + "✗ 已拒绝执行" + ANSI_RESET);
                terminal.writer().flush();
                return false;
            }
        } catch (IOException e) {
            terminal.writer().println(ANSI_RED + "读取确认输入失败，默认拒绝执行" + ANSI_RESET);
            terminal.writer().flush();
            return false;
        }
    }

    private static void drainNewline(Terminal terminal) throws IOException {
        if (terminal.reader().ready()) {
            terminal.reader().read();
        }
    }
}
