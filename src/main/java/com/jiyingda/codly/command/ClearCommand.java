package com.jiyingda.codly.command;

import picocli.CommandLine.Command;

@Command(name = "/clear", description = "清空对话历史")
public class ClearCommand implements Runnable {

    @Override
    public void run() {
        // 由 CommandDispatcher 捕获并返回 CLEAR 结果
    }
}
