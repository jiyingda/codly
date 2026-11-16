package com.jiyingda.codly.command;
import picocli.CommandLine;
@CommandLine.Command(name="/tasks",description="任务列表、status <ID>、output <ID> [offset]、stop <ID>")
public final class TasksCommand implements CliCommand {
 @CommandLine.Parameters(arity="0..*")private String[] args=new String[0];
 public boolean execute(CommandContext context){context.getCliActions().tasks(java.util.List.of(args));return false;}
}
