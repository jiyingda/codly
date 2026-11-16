package com.jiyingda.codly.command;

import picocli.CommandLine;

@CommandLine.Command(name = "/resume", description = "选择或恢复历史对话（可指定 ID）")
public final class ResumeCommand implements CliCommand {
    @CommandLine.Parameters(index = "0", arity = "0..1") private String selector;
    @Override public boolean execute(CommandContext context) { context.getCliActions().resume(selector); return false; }
}
