package com.jiyingda.codly.command;

import picocli.CommandLine;
import picocli.CommandLine.Command;

/**
 * 基于 picocli 的命令调度器，解析 "/" 开头的 CLI 命令并直接执行。
 * 非命令输入返回 {@link DispatchResult#NOT_COMMAND}。
 */
@Command(name = "", mixinStandardHelpOptions = false, subcommands = {
    QuitCommand.class,
    ExitCommand.class,
    ClearCommand.class,
    CompactCommand.class,
    HelpCommand.class,
    ModelCommand.class,
    MemoryCommand.class,
    GenSkillCommand.class,
})
public class CommandDispatcher implements Runnable {

    public enum DispatchResult {
        /** 命令已执行，需要退出主循环 */
        QUIT,
        /** 命令已执行，继续主循环 */
        HANDLED,
        /** 输入以 / 开头但不匹配任何命令 */
        UNKNOWN,
        /** 普通输入，非命令 */
        NOT_COMMAND
    }

    @Override
    public void run() {}

    /**
     * 解析并执行命令，返回调度结果。
     *
     * @param input 用户输入的一行文本
     * @param ctx   命令执行上下文
     * @return {@link DispatchResult}
     */
    public DispatchResult dispatch(String input, CommandContext ctx) {
        String trimmed = input.trim();

        if (!trimmed.startsWith("/")) {
            return DispatchResult.NOT_COMMAND;
        }

        CommandLine cmd = new CommandLine(this);
        cmd.setUnmatchedArgumentsAllowed(true);

        try {
            CommandLine.ParseResult parseResult = cmd.parseArgs(trimmed.split("\\s+"));

            if (!parseResult.hasSubcommand()) {
                return DispatchResult.UNKNOWN;
            }

            Object subcommandObj = parseResult.subcommand().commandSpec().userObject();
            if (subcommandObj instanceof CliCommand command) {
                boolean shouldQuit = command.execute(ctx);
                return shouldQuit ? DispatchResult.QUIT : DispatchResult.HANDLED;
            }

        } catch (CommandLine.UnmatchedArgumentException | CommandLine.MissingParameterException e) {
            // 忽略
        }

        return DispatchResult.UNKNOWN;
    }

    /** 返回 /help 时使用的帮助文本 */
    public static String helpText() {
        return """
            可用命令：
              /help    显示帮助信息
              /clear   清空对话历史
              /compact 压缩对话历史，保留总结
              /model   列出并切换模型
              /memory  查看长期记忆（/memory clear 清空，/memory delete <key> 删除）
              /gen-skill 根据最近的对话自动生成一个 skill
              /quit (/exit)   退出程序
              /sysinfo 显示系统与环境信息""";
    }
}
