/**
 * @(#)CodeCli.java, 3 月 31, 2026.
 * <p>
 * Copyright 2026 chapaof.com. All rights reserved.
 * chapaof.COM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.jiyingda.codly;

import com.jiyingda.codly.command.CommandContext;
import com.jiyingda.codly.command.CommandDispatcher;
import com.jiyingda.codly.command.CommandDispatcher.DispatchResult;
import com.jiyingda.codly.data.Message;
import com.jiyingda.codly.llm.LlmClient;
import com.jiyingda.codly.prompt.Banner;
import com.jiyingda.codly.prompt.SystemPrompt;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Codly 命令行入口，负责用户交互主循环。
 *
 * @author jiyingda
 */
public class CodlyMain {

    public static void main(String[] args) {
        List<Message> memory = new ArrayList<>();
        memory.add(Message.fromSystem(SystemPrompt.SOUL_PROMPT));

        LlmClient llmClient = new LlmClient();
        CommandDispatcher dispatcher = new CommandDispatcher();
        CommandContext ctx = new CommandContext(memory, llmClient);
        boolean titleGenerated = false;

        System.out.println(Banner.TEXT);
        System.out.println("  输入对话内容开始编程，/help 查看可用命令");
        System.out.print("> ");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                DispatchResult result = dispatcher.dispatch(line, ctx);
                if (result == DispatchResult.QUIT) {
                    break;
                } else if (result == DispatchResult.HANDLED) {
                    System.out.print("> ");
                    continue;
                } else if (result == DispatchResult.UNKNOWN) {
                    System.out.println("未知命令，输入 /help 查看可用命令");
                    System.out.print("> ");
                    continue;
                }
                memory.add(Message.fromUser(line));
                if (!titleGenerated) {
                    titleGenerated = true;
                    final String firstMessage = line;
                    llmClient.generateTitleAsync(firstMessage, title -> System.out.print("\033]0;Codly — " + title + "\007"));
                }
                System.out.print(">> ");
                String res = llmClient.chat(memory, System.out::print);
                memory.add(Message.formAssistant(res));
                System.out.println();
                System.out.print("> ");
            }
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
        }
    }
}
