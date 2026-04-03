package com.jiyingda.codly.command;

import picocli.CommandLine.Command;

@Command(name = "/exit", description = "退出程序")
public class ExitCommand implements Runnable {

    @Override
    public void run() {
        // 由 CommandDispatcher 捕获并返回 QUIT 结果
    }
}
