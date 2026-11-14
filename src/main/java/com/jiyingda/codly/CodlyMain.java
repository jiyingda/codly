/**
 * @(#)CodeCli.java, 3 月 31, 2026.
 * <p>
 * Copyright 2026 chapaof.com. All rights reserved.
 * chapaof.COM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.jiyingda.codly;

import com.jiyingda.codly.command.CommandCompleter;
import com.jiyingda.codly.command.CommandContext;
import com.jiyingda.codly.command.CommandDispatcher;
import com.jiyingda.codly.command.CommandDispatcher.DispatchResult;
import com.jiyingda.codly.config.Config;
import com.jiyingda.codly.config.ConfigException;
import com.jiyingda.codly.constants.Banner;
import com.jiyingda.codly.core.ChatSession;
import com.jiyingda.codly.desktop.DesktopMain;
import com.jiyingda.codly.llm.LlmClient;
import com.jiyingda.codly.llm.LlmProvider;
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

/**
 * Codly 命令行入口，负责初始化和用户交互主循环。
 *
 * @author jiyingda
 */
public class CodlyMain {

    private static final Logger logger = LoggerFactory.getLogger(CodlyMain.class);

    public static void main(String[] args) {
        if (args.length > 0 && "--desktop".equals(args[0])) {
            DesktopMain.main(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
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

        Path startupPath = Paths.get("").toAbsolutePath().normalize();
        CommandContext ctx = new CommandContext(new ArrayList<>(), llmClient, startupPath);
        ChatSession session = ChatSession.open(ctx, title -> {
            System.out.print("\033]0;Codly — " + title + "\007");
            System.out.flush();
        });
        ResponseRenderer renderer = new ResponseRenderer();
        CommandDispatcher dispatcher = new CommandDispatcher();

        printBanner();
        System.out.println("  输入对话内容开始编程，/help 查看可用命令");

        try (session; Terminal terminal = TerminalUtils.createTerminal()) {
            ctx.setTerminal(terminal);
            ctx.getSystemInfo().setTerminal(terminal);

            CommandCompleter completer = new CommandCompleter();
            LineReader reader = LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(completer)
                    .build();
            completer.install(reader);
            reader.getKeyMaps().get(LineReader.MAIN)
                    .bind(new Reference(LineReader.KILL_WHOLE_LINE), "^U");

            while (true) {
                String line;
                try {
                    line = reader.readLine("> ");
                } catch (UserInterruptException e) {
                    logger.info("用户中断，等待异步任务完成...");
                    return;
                } catch (EndOfFileException e) {
                    logger.info("检测到 EOF，等待异步任务完成...");
                    return;
                }
                if (line.trim().isEmpty()) {
                    continue;
                }

                DispatchResult result = dispatcher.dispatch(line, ctx);
                if (result == DispatchResult.QUIT) {
                    logger.info("用户退出，等待异步任务完成...");
                    return;
                } else if (result == DispatchResult.HANDLED) {
                    continue;
                } else if (result == DispatchResult.UNKNOWN) {
                    System.out.println("未知命令，输入 /help 查看可用命令");
                    continue;
                } else if (result != DispatchResult.NOT_COMMAND) {
                    continue;
                }

                try {
                    renderer.render(onToken -> session.send(line, onToken));
                } catch (RuntimeException e) {
                    System.err.println("请求失败：" + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("Error: " + e.getMessage());
        }
    }

    private static void printBanner() {
        System.out.print(Banner.text("v1.0.0"));
    }
}
