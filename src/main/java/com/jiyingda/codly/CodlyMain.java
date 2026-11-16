/**
 * @(#)CodeCli.java, 3 月 31, 2026.
 * <p>
 * Copyright 2026 chapaof.com. All rights reserved.
 * chapaof.COM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.jiyingda.codly;

import com.jiyingda.codly.command.CommandCompleter;
import com.jiyingda.codly.command.CommandContext;
import com.jiyingda.codly.config.Config;
import com.jiyingda.codly.config.ConfigException;
import com.jiyingda.codly.constants.Banner;
import com.jiyingda.codly.core.ChatSession;
import com.jiyingda.codly.desktop.DesktopMain;
import com.jiyingda.codly.llm.LlmClient;
import com.jiyingda.codly.llm.LlmProvider;
import com.jiyingda.codly.util.TerminalUtils;
import org.jline.reader.LineReader;
import org.jline.reader.Reference;
import org.jline.terminal.Terminal;

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

    public static void main(String[] args) {
        if (args.length > 0 && "--desktop".equals(args[0])) {
            DesktopMain.main(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        int code = run(args);
        if(code!=0)System.exit(code);
    }

    public static int run(String[] args) {
        var options = new com.jiyingda.codly.cli.CliOptions();
        var arguments = new picocli.CommandLine(options);
        try {
            var parsed = arguments.parseArgs(args);
            if (parsed.isUsageHelpRequested()) { arguments.usage(System.out); return 0; }
            if (parsed.isVersionHelpRequested()) { arguments.printVersionHelp(System.out); return 0; }
            options.validate();
        } catch (RuntimeException error) {
            System.err.println("启动参数无效：" + error.getMessage());
            arguments.usage(System.err); return 2;
        }
        boolean tty=System.console()!=null;
        if(options.batch(tty))return com.jiyingda.codly.cli.BatchRunner.run(options,System.in,System.out,System.err,tty);
        System.setProperty("org.jline.terminal.disableDeprecatedProviderWarning", "true");
        Config config = Config.getInstance();
        if (config.isNotConfigLoaded()) { printBanner(); Config.printLoadErr(config, System.err::println); return 1; }
        LlmProvider llmClient;
        try { llmClient = LlmClient.create(); }
        catch (ConfigException error) { printBanner(); Config.printLlmConfigErr(error, System.err::println); return 1; }
        Path startupPath;
        try {startupPath=(options.cwd==null?Paths.get(""):options.cwd).toRealPath();if(!java.nio.file.Files.isDirectory(startupPath))throw new IOException("工作区不是目录");}
        catch(IOException error){System.err.println("工作区不可访问："+error.getMessage());llmClient.close();return 2;}
        CommandContext ctx = new CommandContext(new ArrayList<>(), llmClient, startupPath);
        var archiveRef = new java.util.concurrent.atomic.AtomicReference<com.jiyingda.codly.cli.CliHistory>();
        ChatSession session = ChatSession.open(ctx, title -> {
            var archive = archiveRef.get(); if (archive != null) archive.rename(title);
        });
        Path home = Path.of(System.getProperty("user.home"), ".codly");
        var store = new com.jiyingda.codly.memory.ConversationStore(home.resolve("conversations"), home.resolve("memory/session"));
        var archive = new com.jiyingda.codly.cli.CliHistory(store, session);
        archiveRef.set(archive);
        try (Terminal terminal = TerminalUtils.createTerminal(); var display = new com.jiyingda.codly.cli.CliDisplay(terminal)) {
            ctx.setTerminal(terminal); ctx.getSystemInfo().setTerminal(terminal);
            CommandCompleter completer = new CommandCompleter();
            var reader = new com.jiyingda.codly.command.CommandLineReader(terminal);
            reader.setParser(com.jiyingda.codly.cli.CliInput.parser());
            reader.setCompleter(com.jiyingda.codly.cli.CliInput.completer(ctx::project));
            completer.install(reader);
            reader.getKeyMaps().get(LineReader.MAIN).bind(new Reference(LineReader.KILL_WHOLE_LINE), "\025");
            printBanner();
            display.line("输入任务开始编程，/help 查看命令；运行中 Ctrl+C 停止，/resume 恢复历史，/trace 查看详情。");
            var controller = new com.jiyingda.codly.cli.CliController(ctx, session, archive, terminal, reader, display);
            try (var input=new com.jiyingda.codly.cli.CliInput(reader,terminal,home,ctx::project)) {controller.setInput(input);controller.run(options);}
            finally {
                display.phase("正在保存会话…");
                if(session.runningTasks()>0)display.line("正在停止本进程管理的后台任务，完整日志将保留。");
                try { session.close(); }
                finally { archive.save(); }
            }
        } catch (IOException | RuntimeException error) {
            System.err.println("CLI 错误：" + error.getMessage());return 1;
        } finally { session.close(); }
        return 0;
    }

    private static void printBanner() {
        System.out.print(Banner.text("v1.0.0"));
    }
}
