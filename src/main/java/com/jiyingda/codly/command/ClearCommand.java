package com.jiyingda.codly.command;

import com.jiyingda.codly.memory.MemoryManager;
import picocli.CommandLine.Command;

@Command(name = "/clear", description = "清空对话历史")
public class ClearCommand implements Runnable, CliCommand {

    @Override
    public void run() {}

    @Override
    public boolean execute(CommandContext ctx) {
        ctx.resetMemory();
        MemoryManager.getInstance().clearSession();
        System.out.println("对话内容已清空");
        return false;
    }
}
