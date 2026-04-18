package com.jiyingda.codly.command;

import picocli.CommandLine.Command;

@Command(name = "/exit", description = "退出程序")
public class ExitCommand implements Runnable, CliCommand {

    @Override
    public void run() {}

    @Override
    public boolean execute(CommandContext ctx) {
        ctx.setShouldQuit(true);
        return true;
    }
}
