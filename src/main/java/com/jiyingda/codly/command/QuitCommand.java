package com.jiyingda.codly.command;

import picocli.CommandLine.Command;

@Command(name = "/quit", description = "退出程序")
public class QuitCommand implements Runnable {

    @Override
    public void run() {
        // 由 CommandDispatcher 捕获并返回 QUIT 结果
    }
}
