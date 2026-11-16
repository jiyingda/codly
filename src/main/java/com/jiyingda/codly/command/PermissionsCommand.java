package com.jiyingda.codly.command;

import picocli.CommandLine;

@CommandLine.Command(name = "/permissions", description = "选择批准策略：ask / assisted / full")
public final class PermissionsCommand implements CliCommand {
    @CommandLine.Parameters(index = "0", arity = "0..1") private String mode;
    @Override public boolean execute(CommandContext context) { context.getCliActions().permissions(mode); return false; }
}
