package com.jiyingda.codly.command;

import picocli.CommandLine;

@CommandLine.Command(name = "/trace", description = "查看轨迹；序号查看详情，all 查看全部")
public final class TraceCommand implements CliCommand {
    @CommandLine.Parameters(index = "0", arity = "0..1") private String selection;
    @Override public boolean execute(CommandContext context) { context.getCliActions().trace(selection); return false; }
}
