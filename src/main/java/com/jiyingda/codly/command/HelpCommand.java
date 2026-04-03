package com.jiyingda.codly.command;

import picocli.CommandLine.Command;

@Command(name = "/help", description = "显示帮助信息")
public class HelpCommand implements Runnable, CliCommand {

    @Override
    public void run() {}

    @Override
    public boolean execute(CommandContext ctx) {
        System.out.println(CommandDispatcher.helpText());
        return false;
    }
}
