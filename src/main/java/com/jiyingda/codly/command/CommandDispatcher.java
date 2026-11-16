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
    SysInfoCommand.class,
    KbCommand.class,
    ResumeCommand.class,
    PermissionsCommand.class,
    TraceCommand.class,
    ContextCommand.class,
    McpCommand.class,
    TasksCommand.class,
    EditorCommand.class,
    DraftCommand.class,
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

        if (!trimmed.startsWith("/") || trimmed.contains("\n")) {
            return DispatchResult.NOT_COMMAND;
        }

        // 动态命令 /skill-<name>:激活对应 skill
        if (trimmed.startsWith("/skill-") && trimmed.length() > "/skill-".length()) {
            try {
                var words=com.jiyingda.codly.util.CommandWords.parse(trimmed);
                if(words.size()!=1)throw new IllegalArgumentException("/skill-<name> 不接受额外参数");
                return handleSkillActivate(words.get(0).substring("/skill-".length()), ctx);
            } catch(IllegalArgumentException error){System.err.println("命令参数无效："+error.getMessage());return DispatchResult.HANDLED;}
        }

        CommandLine cmd = new CommandLine(this);
        cmd.setUnmatchedArgumentsAllowed(false);

        try {
            CommandLine.ParseResult parseResult = cmd.parseArgs(com.jiyingda.codly.util.CommandWords.parse(trimmed).toArray(String[]::new));

            if (!parseResult.hasSubcommand()) {
                return DispatchResult.UNKNOWN;
            }

            Object subcommandObj = parseResult.subcommand().commandSpec().userObject();
            if (subcommandObj instanceof CliCommand command) {
                boolean shouldQuit = command.execute(ctx);
                return shouldQuit ? DispatchResult.QUIT : DispatchResult.HANDLED;
            }

        } catch (CommandLine.ParameterException | IllegalArgumentException e) {
            System.err.println("命令参数无效："+e.getMessage());
            return DispatchResult.HANDLED;
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
              /resume [ID]  选择或恢复历史对话
              /permissions [ask|assisted|full]  切换批准策略
              /trace [序号|all]  查看执行轨迹和详情
              /context 查看上下文预算及项目规则
              /mcp [status|reconnect] 查看 MCP 或重新连接
              /tasks [status ID|output ID [offset]|stop ID] 管理后台任务
              /editor 使用外部编辑器编辑草稿（Ctrl+X Ctrl+E）
              /draft 恢复最近草稿（Ctrl+X Ctrl+R）
              /model   列出并切换模型
              /memory  查看长期记忆（/memory clear 清空，/memory delete <key> 删除）
              /gen-skill 根据最近的对话自动生成一个 skill
              /skill   交互式选择并激活 skill（↑↓ 选择，Enter 确认）
              /skill-<name>  直接激活指定 skill
              /kb      查看与管理知识包（show / section / search / scaffold / status / delete / reload）
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
