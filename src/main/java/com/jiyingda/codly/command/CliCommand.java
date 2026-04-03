package com.jiyingda.codly.command;

/**
 * 所有 CLI 命令的执行接口。
 *
 * @return true 表示需要退出主循环，false 表示继续
 */
public interface CliCommand {
    boolean execute(CommandContext ctx);
}
