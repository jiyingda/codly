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
import com.jiyingda.codly.constants.Banner;
import com.jiyingda.codly.prompt.SystemPrompt;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Codly 命令行入口，负责用户交互主循环。
 *
 * @author jiyingda
 */
public class CodlyMain {

    private static final String PROMPT = "> ";
    private static final String TITLE_ESCAPE_PREFIX = "\033]0;Codly — ";
    private static final String TITLE_ESCAPE_SUFFIX = "\007";

    public static void main(String[] args) {
        // 禁用 JLine3 JNA provider 弃用警告
        System.setProperty("org.jline.terminal.disableDeprecatedProviderWarning", "true");

        List<Message> memory = new ArrayList<>();
        memory.add(Message.fromSystem(SystemPrompt.SOUL_PROMPT));

        Path startupPath = Paths.get("").toAbsolutePath().normalize();
        LlmClient llmClient = new LlmClient();
        CommandDispatcher dispatcher = new CommandDispatcher();
        CommandContext ctx = new CommandContext(memory, llmClient, startupPath);
        boolean titleGenerated = false;

        System.out.println(Banner.TEXT);
        System.out.println("  输入对话内容开始编程，/help 查看可用命令");

        try (Terminal terminal = TerminalBuilder.builder()
                .system(true)
                .jna(true)
                .dumb(true)
                .build()) {

            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .build();

            while (true) {
                String line;
                try {
                    line = reader.readLine(PROMPT);
                } catch (UserInterruptException e) {
                    // Ctrl+C: cancel current input and keep REPL alive.
                    System.out.println();
                    continue;
                } catch (EndOfFileException e) {
                    // Ctrl+D: exit REPL.
                    System.out.println();
                    break;
                }
                if (line.trim().isEmpty()) {
                    continue;
                }
                DispatchResult result = dispatcher.dispatch(line, ctx);
                if (result == DispatchResult.QUIT) {
                    break;
                } else if (result == DispatchResult.HANDLED) {
                    continue;
                } else if (result == DispatchResult.UNKNOWN) {
                    System.out.println("未知命令，输入 /help 查看可用命令");
                    continue;
                }
                memory.add(Message.fromUser(line));
                if (!titleGenerated) {
                    titleGenerated = true;
                    llmClient.generateTitleAsync(line, title -> {
                        String terminalTitle = TITLE_ESCAPE_PREFIX + title + TITLE_ESCAPE_SUFFIX;
                        System.out.print(terminalTitle);
                        System.out.flush();
                    });
                }
                System.out.print(">> ");
                String res = llmClient.chat(ctx, System.out::print);
                memory.add(Message.formAssistant(res));
                System.out.println();
            }
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
        }
    }
}
