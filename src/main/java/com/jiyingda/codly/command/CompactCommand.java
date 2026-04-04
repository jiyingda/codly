package com.jiyingda.codly.command;

import com.jiyingda.codly.data.Message;
import picocli.CommandLine.Command;

import java.util.List;
import java.util.stream.Collectors;

@Command(name = "/compact", description = "Compact conversation history")
public class CompactCommand implements CliCommand {

    private static final String DEFAULT_SUMMARIZE_PROMPT = """
        Please summarize the above conversation in 2-3 sentences. \
        Focus on:
        1. The main task or problem being worked on
        2. Key decisions or solutions discussed
        3. Current status or next steps

        Provide only the summary, no other commentary.
        """;

    @Override
    public boolean execute(CommandContext ctx) {
        List<Message> memory = ctx.getMemory();

        if (memory.size() <= 1) {
            System.out.println("对话历史为空，无需压缩");
            return false;
        }

        // 提取用户和助手的对话内容（排除系统提示）
        String conversation = memory.stream()
            .skip(1) // 跳过系统提示
            .map(Message::getContent)
            .collect(Collectors.joining("\n\n"));

        if (conversation.isBlank()) {
            System.out.println("对话历史为空，无需压缩");
            return false;
        }

        System.out.println("正在总结对话历史...");

        // 构建总结请求
        List<Message> summarizeMessages = List.of(
            Message.fromSystem("You are a helpful assistant that summarizes conversations."),
            Message.fromUser(conversation + "\n\n" + DEFAULT_SUMMARIZE_PROMPT)
        );

        // 同步调用 LLM 进行总结
        StringBuilder summary = new StringBuilder();
        ctx.getLlmClient().chat(ctx, summarizeMessages, summary::append);

        System.out.println("总结完成:");
        System.out.println("---");
        System.out.println(summary.toString());
        System.out.println("---");

        // 保留系统提示和总结，清空其他对话
        Message systemMessage = memory.get(0);
        memory.clear();
        memory.add(systemMessage);
        memory.add(Message.fromUser("[Previous conversation summarized]: " + summary.toString()));

        System.out.println("对话历史已压缩，保留了总结内容");
        return false;
    }
}
