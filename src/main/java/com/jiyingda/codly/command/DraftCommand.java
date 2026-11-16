package com.jiyingda.codly.command;
import picocli.CommandLine;
@CommandLine.Command(name="/draft",description="恢复当前项目最近清空或编辑的草稿")
public final class DraftCommand implements CliCommand {public boolean execute(CommandContext context){context.getCliActions().draft();return false;}}
