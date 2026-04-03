package com.jiyingda.codly.command;

import picocli.CommandLine.Command;

@Command(name = "/help", description = "显示帮助信息")
public class HelpCommand implements Runnable {

    @Override
    public void run() {
        // 由 CommandDispatcher 捕获并返回 HELP 结果
    }
}
