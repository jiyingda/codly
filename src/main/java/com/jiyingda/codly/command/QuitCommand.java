package com.jiyingda.codly.command;

import picocli.CommandLine.Command;

@Command(name = "/quit", description = "退出程序")
public class QuitCommand implements Runnable, CliCommand {

    @Override
    public void run() {}

    @Override
    public boolean execute(CommandContext ctx) {
        return true;
    }
}
