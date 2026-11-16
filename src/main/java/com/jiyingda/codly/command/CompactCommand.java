package com.jiyingda.codly.command;

import picocli.CommandLine.Command;

@Command(name = "/compact", description = "压缩对话历史，保留总结")
public class CompactCommand implements CliCommand {
    @Override
    public boolean execute(CommandContext ctx) {
        try {
            String summary = ctx.getSession().compactConversation();
            System.out.println(summary == null ? "对话历史为空，无需压缩" : "[压缩完成] " + summary);
        } catch (RuntimeException error) {
            System.err.println("压缩失败，原对话已保留：" + error.getMessage());
        }
        return false;
    }
}
