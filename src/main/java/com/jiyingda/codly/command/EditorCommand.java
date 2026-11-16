package com.jiyingda.codly.command;
import picocli.CommandLine;
@CommandLine.Command(name="/editor",description="使用 VISUAL/EDITOR 编辑草稿，回到输入区后按 Enter 发送")
public final class EditorCommand implements CliCommand {public boolean execute(CommandContext context){context.getCliActions().editor();return false;}}
