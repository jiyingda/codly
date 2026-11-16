package com.jiyingda.codly.command;
import picocli.CommandLine;
@CommandLine.Command(name="/mcp",description="查看 MCP 连接和工具数量；reconnect 重新读取配置并连接")
public final class McpCommand implements CliCommand {
 @CommandLine.Parameters(index="0",arity="0..1",defaultValue="status")private String action;
 public boolean execute(CommandContext context){context.getCliActions().mcp(action);return false;}
}
