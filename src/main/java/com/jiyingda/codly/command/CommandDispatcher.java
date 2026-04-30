package com.jiyingda.codly.command;

import com.jiyingda.codly.skill.Skill;
import com.jiyingda.codly.skill.SkillRegistry;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.Optional;

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
    SkillCommand.class,
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

        // 动态命令 /skill-<name>:激活对应 skill
        if (trimmed.startsWith("/skill-") && trimmed.length() > "/skill-".length()) {
            String skillName = trimmed.substring("/skill-".length()).split("\\s+")[0];
            return handleSkillActivate(skillName, ctx);
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
              /skill   交互式选择并激活 skill（↑↓ 选择，Enter 确认）
              /skill-<name>  直接激活指定 skill
              /quit (/exit)   退出程序
              /sysinfo 显示系统与环境信息""";
    }

    private static DispatchResult handleSkillActivate(String skillName, CommandContext ctx) {
        Optional<Skill> skill = SkillRegistry.getInstance().findByName(skillName);
        if (skill.isEmpty()) {
            System.out.println("未找到 skill: " + skillName + "，输入 /skill 查看所有可用 skill");
            return DispatchResult.HANDLED;
        }
        boolean isNew = ctx.activateSkill(skill.get());
        if (isNew) {
            System.out.println("已激活 skill: \u001B[36m" + skillName + "\u001B[0m");
        } else {
            System.out.println("skill 已处于激活状态: " + skillName);
        }
        return DispatchResult.HANDLED;
    }
}
