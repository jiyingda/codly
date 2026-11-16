package com.jiyingda.codly.command;
import picocli.CommandLine;
@CommandLine.Command(name="/context",description="查看上下文预算及生效的 AGENTS.md 规则")
public final class ContextCommand implements CliCommand {public boolean execute(CommandContext context){context.getCliActions().context();return false;}}
