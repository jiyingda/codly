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
import com.jiyingda.codly.config.Config;
import com.jiyingda.codly.config.ConfigException;
import com.jiyingda.codly.constants.Banner;
import com.jiyingda.codly.data.Message;
import com.jiyingda.codly.llm.LlmClient;
import com.jiyingda.codly.llm.LlmProvider;
import com.jiyingda.codly.memory.MemoryManager;
import com.jiyingda.codly.prompt.SystemPrompt;
import com.jiyingda.codly.systeminfo.SystemInfoManager;
import com.jiyingda.codly.util.ProgressIndicator;
import com.jiyingda.codly.util.TerminalUtils;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Codly 命令行入口，负责用户交互主循环。
 *
 * @author jiyingda
 */
public class CodlyMain {

    private static final Logger logger = LoggerFactory.getLogger(CodlyMain.class);
    private static final String TITLE_ESCAPE_PREFIX = "\033]0;Codly — ";
    private static final String TITLE_ESCAPE_SUFFIX = "\007";

    public static void main(String[] args) {
        // 禁用 JLine3 JNA provider 弃用警告
        System.setProperty("org.jline.terminal.disableDeprecatedProviderWarning", "true");

        // 检查配置
        Config config = Config.getInstance();
        if (config.isNotConfigLoaded()) {
            printBanner();
            Config.printLoadErr(config, System.err::println);
            return;
        }

        LlmProvider llmClient;
        try {
            llmClient = LlmClient.create();
        } catch (ConfigException e) {
            printBanner();
            Config.printLlmConfigErr(e, System.err::println);
            return;
        }

        List<Message> memory = new ArrayList<>();

        // 初始化 MemoryManager
        MemoryManager memoryManager = MemoryManager.getInstance();

        Path startupPath = Paths.get("").toAbsolutePath().normalize();
        CommandDispatcher dispatcher = new CommandDispatcher();
        CommandContext ctx = new CommandContext(memory, llmClient, startupPath);
        boolean titleGenerated = false;

        printBanner();
        System.out.println("  输入对话内容开始编程，/help 查看可用命令");

        try (Terminal terminal = TerminalUtils.createTerminal()) {
            ctx.setTerminal(terminal);
            SystemInfoManager.getInstance().setTerminal(terminal);

            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .build();
            reader.getKeyMaps().get(LineReader.MAIN)
                    .bind(new Reference(LineReader.KILL_WHOLE_LINE), "^U");

            while (true) {
                String line;
                try {
                    line = reader.readLine("> ");
                } catch (UserInterruptException e) {
                    logger.info("用户中断，等待异步任务完成...");
                    shutdown(ctx, memoryManager);
                    return;
                } catch (EndOfFileException e) {
                    logger.info("检测到 EOF，等待异步任务完成...");
                    shutdown(ctx, memoryManager);
                    return;
                }
                if (line.trim().isEmpty()) {
                    continue;
                }
                DispatchResult result = dispatcher.dispatch(line, ctx);
                if (result == DispatchResult.QUIT) {
                    logger.info("用户退出，等待异步任务完成...");
                    shutdown(ctx, memoryManager);
                    return;
                } else if (result == DispatchResult.HANDLED) {
                    continue;
                } else if (result == DispatchResult.UNKNOWN) {
                    System.out.println("未知命令，输入 /help 查看可用命令");
                    continue;
                }

                // 保存用户消息到 memory 和 MemoryManager
                Message userMessage = Message.fromUser(line);
                memory.add(userMessage);
                memoryManager.appendMessage(userMessage);

                if (!titleGenerated) {
                    titleGenerated = true;
                    llmClient.generateTitleAsync(line, title -> {
                        String terminalTitle = TITLE_ESCAPE_PREFIX + title + TITLE_ESCAPE_SUFFIX;
                        System.out.print(terminalTitle);
                        System.out.flush();
                        // 使用标题重新初始化会话文件
                        memoryManager.initializeWithSession(title.trim());
                    });

                }
                AtomicBoolean responseStarted = new AtomicBoolean(false);
                ProgressIndicator indicator = new ProgressIndicator();
                indicator.start();

                String longTermMemory = memoryManager.toLongTermPromptSection();
                ctx.setSystemPrompt(Message.fromSystem(SystemPrompt.SOUL_PROMPT + longTermMemory + "\n\n" + SystemInfoManager.getInstance().currentTime()));
                String res = llmClient.chat(ctx, token -> {
                    if (responseStarted.compareAndSet(false, true)) {
                        indicator.stop();
                        indicator.clear();
                        System.out.print(">> ");
                    }
                    System.out.print(token);
                    System.out.flush();
                });

                indicator.stop();

                if (!responseStarted.get()) {
                    indicator.clear();
                    System.out.print(">> ");
                }

                // 保存助手消息到 memory 和 MemoryManager
                Message assistantMessage = Message.formAssistant(res);
                memory.add(assistantMessage);
                boolean flushed = memoryManager.appendMessage(assistantMessage);

                // 暂存本轮对话；MemoryManager flush 时同步触发长期记忆提取
                memoryManager.appendLtmRound(line, res);
                if (flushed) {
                    memoryManager.flushAndExtractLtmAsync(ctx);
                }

                System.out.println();
            }
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    private static void printBanner() {
        System.out.print(Banner.text("v1.0.0"));
    }

    private static void shutdown(CommandContext ctx, MemoryManager memoryManager) {
        memoryManager.flushNow();
        memoryManager.flushAndExtractLtmSync(ctx);
        memoryManager.shutdown();
        System.exit(0);
    }
}
