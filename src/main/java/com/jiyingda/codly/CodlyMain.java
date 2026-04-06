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
import com.jiyingda.codly.llm.LlmProvider;
import com.jiyingda.codly.config.Config;
import com.jiyingda.codly.config.ConfigException;
import com.jiyingda.codly.constants.Banner;
import com.jiyingda.codly.memory.MemoryManager;
import com.jiyingda.codly.prompt.SystemPrompt;
import org.jline.reader.EndOfFileException;
import org.jline.reader.LineReader;
import org.jline.reader.LineReaderBuilder;
import org.jline.reader.Reference;
import org.jline.reader.UserInterruptException;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
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
    private static final String CLEAR_LINE = "\r\033[2K";
    private static final String[] PROGRESS_FRAMES = {"|", "/", "-", "\\"};
    private static final long PROGRESS_FRAME_DELAY_MS = 320L;

    public static void main(String[] args) {
        // 禁用 JLine3 JNA provider 弃用警告
        System.setProperty("org.jline.terminal.disableDeprecatedProviderWarning", "true");

        // 检查配置
        Config config = Config.getInstance();
        if (!config.isConfigLoaded()) {
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
        memory.add(Message.fromSystem(SystemPrompt.SOUL_PROMPT));

        // 初始化 MemoryManager
        MemoryManager memoryManager = MemoryManager.getInstance();

        Path startupPath = Paths.get("").toAbsolutePath().normalize();
        CommandDispatcher dispatcher = new CommandDispatcher();
        CommandContext ctx = new CommandContext(memory, llmClient, startupPath);
        boolean titleGenerated = false;

        printBanner();
        System.out.println("  输入对话内容开始编程，/help 查看可用命令");

        try (Terminal terminal = createTerminal()) {
            ctx.setTerminal(terminal);

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
                    memoryManager.shutdown();
                    System.exit(0);
                    break;
                } catch (EndOfFileException e) {
                    // 退出前等待异步任务完成
                    logger.info("检测到 EOF，等待异步任务完成...");
                    memoryManager.shutdown();
                    System.exit(0);
                    break;
                }
                if (line.trim().isEmpty()) {
                    continue;
                }
                DispatchResult result = dispatcher.dispatch(line, ctx);
                if (result == DispatchResult.QUIT) {
                    // 退出前等待异步任务完成
                    logger.info("用户退出，等待异步任务完成...");
                    memoryManager.shutdown();
                    System.exit(0);
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
                memoryManager.appendMessageAsync(userMessage);

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
                AtomicBoolean waiting = new AtomicBoolean(true);
                Thread progressThread = new Thread(() -> {
                    int frameIndex = 0;
                    while (waiting.get()) {
                        System.out.print("\r" + PROGRESS_FRAMES[frameIndex]);
                        System.out.flush();
                        frameIndex = (frameIndex + 1) % PROGRESS_FRAMES.length;
                        try {
                            Thread.sleep(PROGRESS_FRAME_DELAY_MS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }, "codly-progress");
                progressThread.setDaemon(true);
                progressThread.start();

                String res = llmClient.chat(ctx, token -> {
                    if (responseStarted.compareAndSet(false, true)) {
                        waiting.set(false);
                        progressThread.interrupt();
                        System.out.print(CLEAR_LINE + ">> ");
                    }
                    System.out.print(token);
                    System.out.flush();
                });

                waiting.set(false);
                progressThread.interrupt();

                if (!responseStarted.get()) {
                    System.out.print(CLEAR_LINE + ">> ");
                }

                // 保存助手消息到 memory 和 MemoryManager，并异步存储
                Message assistantMessage = Message.formAssistant(res);
                memory.add(assistantMessage);
                memoryManager.appendMessageAsync(assistantMessage);

                System.out.println();
            }
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    private static void printBanner() {
        System.out.print(Banner.text("v1.0.0"));
    }

    private static Terminal createTerminal() throws IOException {
        boolean loggedEnv = false;
        try {
            return TerminalBuilder.builder()
                    .system(true)
                    .jna(true)
                    .dumb(false)
                    .build();
        } catch (Exception e) {
            if (!loggedEnv) {
                System.err.println("[terminal] 创建终端失败，进入回退流程。"
                        + terminalDebugContext());
                loggedEnv = true;
            }
            logTerminalFailure("system=true,jna=true,dumb=false", e);
        }

        try {
            return TerminalBuilder.builder()
                    .system(true)
                    .jna(false)
                    .dumb(false)
                    .build();
        } catch (Exception e) {
            logTerminalFailure("system=true,jna=false,dumb=false", e);
        }

        try {
            return TerminalBuilder.builder()
                    .system(true)
                    .dumb(true)
                    .build();
        } catch (Exception e) {
            logTerminalFailure("system=true,dumb=true", e);
            throw new IOException("Unable to create a terminal", e);
        }
    }

    private static void logTerminalFailure(String stage, Exception e) {
        logger.error("[terminal] 阶段失败：{} | {}: {}", stage, e.getClass().getSimpleName(), e.getMessage(), e);
    }

    private static String terminalDebugContext() {
        return " TERM=" + System.getenv("TERM")
                + ", COLORTERM=" + System.getenv("COLORTERM")
                + ", console=" + (System.console() != null);
    }
}
