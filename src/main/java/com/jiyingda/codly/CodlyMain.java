/**
 * @(#)CodeCli.java, 3 月 31, 2026.
 * <p>
 * Copyright 2026 chapaof.com. All rights reserved.
 * chapaof.COM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.jiyingda.codly;

import com.jiyingda.codly.command.CommandDispatcher;
import com.jiyingda.codly.command.CommandDispatcher.CommandResult;
import com.jiyingda.codly.data.Message;
import com.jiyingda.codly.llm.LlmClient;
import com.jiyingda.codly.llm.ModelSelector;
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

        System.out.println("欢迎使用 Codly，输入对话内容开始与模型对话（输入 /help 查看命令）...");
        System.out.print("> ");

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) {
                    continue;
                }
                CommandResult result = dispatcher.dispatch(line);
                if (result == CommandResult.QUIT) {
                    break;
                } else if (result == CommandResult.CLEAR) {
                    memory.clear();
                    memory.add(Message.fromSystem(SystemPrompt.SOUL_PROMPT));
                    System.out.println("对话内容已清空");
                    System.out.print("> ");
                    continue;
                } else if (result == CommandResult.HELP) {
                    System.out.println(CommandDispatcher.helpText());
                    System.out.print("> ");
                    continue;
                } else if (result == CommandResult.MODEL) {
                    String chosen = ModelSelector.select(LlmClient.AVAILABLE_MODELS, llmClient.getModel());
                    if (chosen != null) {
                        llmClient.setModel(chosen);
                    }
                    System.out.print("> ");
                    continue;
                } else if (result == CommandResult.UNKNOWN) {
                    System.out.println("未知命令，输入 /help 查看可用命令");
                    System.out.print("> ");
                    continue;
                }
                memory.add(Message.fromUser(line));
                System.out.print(">>: ");
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