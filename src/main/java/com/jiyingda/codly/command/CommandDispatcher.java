package com.jiyingda.codly.command;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * 基于 picocli 的命令调度器，解析 "/" 开头的 CLI 命令。
 * 非命令输入返回 {@link CommandResult#NOT_COMMAND}，由主循环继续处理。
 */
@Command(name = "", mixinStandardHelpOptions = false, subcommands = {
    QuitCommand.class,
    ExitCommand.class,
    ClearCommand.class,
    HelpCommand.class,
    ModelCommand.class
})
public class CommandDispatcher implements Runnable {

    public enum CommandResult {
        /** /quit 或 /exit */
        QUIT,
        /** /clear */
        CLEAR,
        /** /help */
        HELP,
        /** /model */
        MODEL,
        /** 输入以 / 开头但不匹配任何命令 */
        UNKNOWN,
        /** 普通输入，非命令 */
        NOT_COMMAND
    }

    private CommandResult result = CommandResult.NOT_COMMAND;

    @Override
    public void run() {
        // 根命令本身不处理任何事，交给子命令
    }

    /**
     * 解析并执行命令，返回解析结果。
     *
     * @param input 用户输入的一行文本
     * @return {@link CommandResult}
     */
    public CommandResult dispatch(String input) {
        String trimmed = input.trim();

        if (!trimmed.startsWith("/")) {
            return CommandResult.NOT_COMMAND;
        }

        result = CommandResult.UNKNOWN;

        CommandLine cmd = new CommandLine(this);
        // 禁止 picocli 自动打印用法和错误，由我们自己控制
        cmd.setUnmatchedArgumentsAllowed(true);

        try {
            CommandLine.ParseResult parseResult = cmd.parseArgs(trimmed.split("\\s+"));
            String matchedSubcommand = parseResult.hasSubcommand()
                ? parseResult.subcommand().commandSpec().name()
                : null;

            if ("/quit".equals(matchedSubcommand)) {
                result = CommandResult.QUIT;
            } else if ("/exit".equals(matchedSubcommand)) {
                result = CommandResult.QUIT;
            } else if ("/clear".equals(matchedSubcommand)) {
                result = CommandResult.CLEAR;
            } else if ("/help".equals(matchedSubcommand)) {
                result = CommandResult.HELP;
            } else if ("/model".equals(matchedSubcommand)) {
                result = CommandResult.MODEL;
            }
        } catch (CommandLine.UnmatchedArgumentException e) {
            result = CommandResult.UNKNOWN;
        } catch (Exception e) {
            result = CommandResult.UNKNOWN;
        }

        return result;
    }

    /** 返回 /help 时使用的帮助文本 */
    public static String helpText() {
        return """
            可用命令：
              /help   显示帮助信息
              /clear  清空对话历史
              /model  列出并切换模型
              /quit   退出程序
              /exit   退出程序""";
    }
}
