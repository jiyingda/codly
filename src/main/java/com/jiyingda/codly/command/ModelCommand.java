package com.jiyingda.codly.command;

import picocli.CommandLine.Command;

@Command(name = "/model", description = "列出并切换当前使用的模型")
public class ModelCommand implements Runnable {

    @Override
    public void run() {
        // 由 CommandDispatcher 捕获并返回 MODEL 结果
    }
}
