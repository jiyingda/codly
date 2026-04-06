package com.jiyingda.codly.command;

import com.jiyingda.codly.data.Message;
import com.jiyingda.codly.prompt.SystemPrompt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;

import java.util.List;
import java.util.stream.Collectors;

@Command(name = "/compact", description = "Compact conversation history")
public class CompactCommand implements CliCommand {

    private static final Logger logger = LoggerFactory.getLogger(CompactCommand.class);

    @Override
    public boolean execute(CommandContext ctx) {
        List<Message> memory = ctx.getMemory();

        if (memory.size() <= 1) {
            logger.info("对话历史为空，无需压缩");
            return false;
        }

        // 提取用户和助手的对话内容（排除系统提示）
        String conversation = memory.stream()
            .skip(1) // 跳过系统提示
            .map(Message::getContent)
            .collect(Collectors.joining("\n\n"));

        if (conversation.isBlank()) {
            logger.info("对话历史为空，无需压缩");
            return false;
        }

        logger.info("正在总结对话历史...");

        // 构建总结请求
        List<Message> summarizeMessages = List.of(
            Message.fromSystem(SystemPrompt.GEN_SUMMARY_PROMPT),
            Message.fromUser(conversation + "\n\n" + SystemPrompt.DEFAULT_SUMMARIZE_PROMPT)
        );

        // 同步调用 LLM 进行总结
        StringBuilder summary = new StringBuilder();
        ctx.getLlmClient().chat(ctx, summarizeMessages, summary::append);

        logger.info("总结完成：{}", summary.toString());

        // 保留系统提示和总结，清空其他对话
        Message systemMessage = memory.get(0);
        memory.clear();
        memory.add(systemMessage);
        memory.add(Message.fromUser("[Previous conversation summarized]: " + summary.toString()));

        logger.info("对话历史已压缩，保留了总结内容");
        System.out.println("[压缩完成] " + summary.toString());
        return false;
    }
}
