package com.jiyingda.codly.cli;

import com.jiyingda.codly.command.*;
import com.jiyingda.codly.core.*;
import org.jline.reader.*;
import org.jline.terminal.*;
import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

/** 终端交互编排；模型执行与读行串行，运行期间 INT 直接取消当前 HTTP / 子进程。 */
public final class CliController implements CliActions {
    private final CommandContext context;
    private final ChatSession session;
    private final CliHistory history;
    private final Terminal terminal;
    private final LineReader reader;
    private final CliDisplay display;
    private final AtomicReference<CancellationToken> active = new AtomicReference<>();
    private boolean exitRequested;
    private CliInput input;
    public void setInput(CliInput input){this.input=input;}
    private String auxiliary(String prompt){if(input!=null)return input.auxiliary(prompt);return reader.readLine(prompt);}

    public CliController(CommandContext context, ChatSession session, CliHistory history, Terminal terminal, LineReader reader, CliDisplay display) {
        this.context = context; this.session = session; this.history = history;
        this.terminal = terminal; this.reader = reader; this.display = display;
        context.setSession(session); context.setCliActions(this); context.setApprovalHandler(this::approve);
    }

    public void run(CliOptions options) {
        // JLine 在读行时临时接管 INT；保存或切换历史的间隙也不能落回进程默认退出。
        Terminal.SignalHandler previous = terminal.handle(Terminal.Signal.INT, signal -> cancelActive());
        try { runLoop(options); }
        finally { terminal.handle(Terminal.Signal.INT, previous); }
    }

    private void runLoop(CliOptions options) {
        if (options.continueLast) resumeLast();
        else if (options.command != null) resume(options.conversation);
        options.apply(session);
        if(options.initialPrompt()!=null)try{String prepared=chooseFileRanges(options.initialPrompt());if(prepared!=null)turn(prepared);}catch(IOException error){display.line("保存失败："+error.getMessage());}
        CommandDispatcher commands = new CommandDispatcher();
        long exitArmed = 0;
        while (!exitRequested) {
            try{session.refreshContextBudget();}catch(IOException error){display.line("读取上下文预算失败："+error.getMessage());}
            display.budget(context.budget().describe(session.getModel()));
            display.context(session, context.getApprovalPolicy());
            if(input!=null)try{input.workspace(session.getWorkspace());}catch(IOException error){display.line("读取输入历史失败："+error.getMessage());}
            String line;
            try { line = input==null?reader.readLine("> "):input.read(); }
            catch(IOException error){display.line("读取输入失败："+error.getMessage());continue;}
            catch (UserInterruptException error) {
                long now = System.nanoTime();
                if (error.getPartialLine() != null && !error.getPartialLine().isBlank()) {
                    exitArmed = 0; display.line("草稿已清空。/draft 或 Ctrl+X Ctrl+R 可恢复。");
                } else if (exitArmed != 0 && now - exitArmed < Duration.ofSeconds(2).toNanos()) break;
                else { exitArmed = now; display.line("再次按 Ctrl+C（2 秒内）退出；输入内容继续对话。"); }
                continue;
            } catch (EndOfFileException error) { break; }
            exitArmed = 0;
            if (line.isBlank()) continue;
            try {
                boolean clear = line.strip().equals("/clear");
                if (clear) history.save();
                var result = commands.dispatch(line, context);
                if (result == CommandDispatcher.DispatchResult.QUIT) break;
                if (result == CommandDispatcher.DispatchResult.UNKNOWN) { display.line("未知命令，输入 /help 查看可用命令。"); continue; }
                if (result == CommandDispatcher.DispatchResult.HANDLED) {
                    if (clear) { history.reset(); display.phase("新的对话"); }
                    history.save(); continue;
                }
                String prepared=chooseFileRanges(line);if(prepared!=null)turn(prepared);
            } catch (RuntimeException | IOException error) { display.line("操作失败：" + error.getMessage()); }
        }
    }

    private String chooseFileRanges(String line)throws IOException {
        var project=context.project();if(project==null)return line;
        com.jiyingda.codly.context.ProjectContext.Reference reference;
        while((reference=project.needsRange(line))!=null){
            String range=auxiliary("文件 "+reference.path()+" 超过 64KB，请输入起止行（如 20-80，回车取消）> ").strip();
            if(range.isEmpty())return null;if(!range.matches("L?\\d+(?:-L?\\d+)?")){display.line("行范围格式无效。");continue;}
            line=line.replace(reference.token(),"@\""+reference.path()+"\"#"+range);
        }return line;
    }

    public void turn(String input) throws IOException {
        CancellationToken token = new CancellationToken(); active.set(token);
        Attributes previousAttributes = terminal.getAttributes();
        Terminal.SignalHandler previousHandler = terminal.handle(Terminal.Signal.INT, signal -> cancelActive());
        Attributes running = new Attributes(previousAttributes);
        running.setLocalFlag(Attributes.LocalFlag.ISIG, true);
        running.setControlChar(Attributes.ControlChar.VINTR, 3);
        Throwable failure = null;
        display.begin();
        try {
            terminal.setAttributes(running);
            var archiveTrace=history.traceSink();
            session.send(input, display::token, token, display::tool, event->{archiveTrace.accept(event);if(event.title().equals("附加文件"))display.line("已附加："+event.detail());if(event.title().contains("自动压缩"))display.line(event.title()+"："+event.detail());});
        } catch (RuntimeException error) { failure = error; }
        finally {
            // 先恢复终端输入模式，再把信号所有权交还给 JLine。
            terminal.setAttributes(previousAttributes);
            terminal.handle(Terminal.Signal.INT, previousHandler); active.set(null);
            String answer = display.finish(token.isCancelled(), failure);
            history.addTurn(input, answer, token.isCancelled() ? "已停止" : failure != null ? "请求失败" : "已完成");
            history.save();
        }
    }

    public void cancelActive() {
        CancellationToken token = active.get();
        if (token != null) { display.phase("正在停止当前操作…"); token.cancel(); }
    }

    private ApprovalDecision approve(ApprovalRequest request) {
        display.phase("等待批准");
        display.line(request.summary());
        display.line("可记忆范围：" + request.scope().description());
        while (true) {
            try {
                String answer = auxiliary("[y] 仅本次允许  [a] 本会话同类操作允许  [n/回车] 拒绝 > ").strip().toLowerCase(Locale.ROOT);
                switch (answer) {
                    case "y", "yes" -> { display.phase("正在执行"); return ApprovalDecision.ALLOW_ONCE; }
                    case "a", "always" -> { display.phase("正在执行（会话授权）"); return ApprovalDecision.ALLOW_SESSION; }
                    case "", "n", "no" -> { display.phase("已拒绝操作"); return ApprovalDecision.DENY; }
                    default -> display.line("请输入 y、a 或 n。");
                }
            } catch (UserInterruptException error) { cancelActive(); return ApprovalDecision.DENY; }
            catch (EndOfFileException error) { exitRequested = true; cancelActive(); return ApprovalDecision.DENY; }
        }
    }

    @Override public void permissions(String mode) {
        try {
            if (mode == null || mode.isBlank()) {
                for (ApprovalPolicy policy : ApprovalPolicy.values()) display.line((policy.ordinal() + 1) + ". " + policy + " · " + policy.description());
                mode = auxiliary("批准策略 [1 请求批准 / 2 帮我批准 / 3 完全授权，回车保留] > ").strip();
                if (mode.isEmpty()) return;
            }
            ApprovalPolicy policy = switch (mode.toLowerCase(Locale.ROOT)) {
                case "1", "ask", "请求批准" -> ApprovalPolicy.ASK;
                case "2", "assisted", "帮我批准" -> ApprovalPolicy.ASSISTED;
                case "3", "full", "完全授权" -> ApprovalPolicy.FULL;
                default -> throw new IllegalArgumentException("可选值：ask / assisted / full");
            };
            session.setApprovalPolicy(policy);
            display.line("批准策略：" + policy + " · " + policy.description());
            display.context(session, policy);
        } catch (UserInterruptException | EndOfFileException ignored) { display.line("保留当前批准策略。"); }
    }

    private void resumeLast() {
        try {
            Path workspace = session.getWorkspace().toRealPath();
            var latest = history.list().stream().filter(record -> sameWorkspace(record.workspace(), workspace)).findFirst();
            if (latest.isPresent()) resume(latest.get().id());
            else display.line("当前工作区没有历史记录，可用 codly resume 浏览全部历史。");
        } catch (IOException error) { display.line("读取历史失败：" + error.getMessage()); }
    }
    private static boolean sameWorkspace(String value, Path workspace) {
        try { return value != null && !value.isBlank() && Path.of(value).toRealPath().equals(workspace); }
        catch (IOException | RuntimeException ignored) { return false; }
    }
    @Override public void resume(String selector) {
        try {
            // 在枚举前保存当前对话，避免切换丢失刚刚完成的消息和设置。
            history.save();
            var records = history.list();
            if (records.isEmpty()) { display.line("暂无历史对话。"); return; }
            String id = selector == null || selector.isBlank() ? selectHistory(records) : resolveId(records, selector);
            if (id == null) return;
            ConversationRecord record = history.load(id);
            Path workspace = chooseWorkspace(record);
            if (workspace == null) { display.line("未切换，保留当前对话。"); return; }
            history.restore(record, workspace);
            display.line("已恢复：" + record.title() + " · " + workspace + " · " + record.turns().size() + " 轮");
            if (!Objects.equals(record.provider(), session.getProviderId()) || !Objects.equals(record.model(), session.getModel())) display.line("原模型不可用于当前服务，模型沿用当前设置。");
            display.phase("历史已恢复，可继续对话");
        } catch (UserInterruptException | EndOfFileException ignored) { display.line("未切换，保留当前对话。"); }
        catch (IOException | RuntimeException error) { display.line("恢复失败：" + error.getMessage()); }
    }

    private String selectHistory(List<ConversationRecord.Summary> records) {
        var shown = records;
        while (true) {
            display.line("历史对话：");
            for (int i = 0; i < shown.size(); i++) {
                var record = shown.get(i);
                String date = DateTimeFormatter.ofPattern("MM-dd HH:mm").format(record.updatedAt().atZone(ZoneId.systemDefault()));
                display.line((i + 1) + ". " + record.title() + " · " + date + " · " + (record.workspace() == null || record.workspace().isBlank() ? "待绑定工作区" : record.workspace()) + " · " + record.id());
            }
            String selected = auxiliary("序号 / ID / 搜索关键词（回车取消）> ").strip();
            if (selected.isEmpty()) return null;
            if (selected.matches("\\d+")) {
                try { int index = Integer.parseInt(selected); if (index > 0 && index <= shown.size()) return shown.get(index - 1).id(); }
                catch (NumberFormatException ignored) { }
                display.line("序号无效。"); continue;
            }
            try { return resolveId(records, selected); }
            catch (IllegalArgumentException ignored) {
                String query = selected.toLowerCase(Locale.ROOT);
                shown = records.stream().filter(record -> (record.title() + " " + record.workspace()).toLowerCase(Locale.ROOT).contains(query)).toList();
                if (shown.isEmpty()) { display.line("没有匹配的历史。"); shown = records; }
            }
        }
    }
    private static String resolveId(List<ConversationRecord.Summary> records, String selector) {
        var matches = records.stream().filter(record -> record.id().equals(selector) || record.id().startsWith(selector)).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("对话 ID 不存在或前缀不唯一");
        return matches.get(0).id();
    }
    private Path chooseWorkspace(ConversationRecord record) throws IOException {
        String original = record.workspace();
        if (original != null && !original.isBlank() && Files.isDirectory(Path.of(original))) return Path.of(original).toRealPath();
        display.line(original == null || original.isBlank() ? "此历史未保存工作区，请先绑定目录。" : "原工作区不可访问：" + original);
        while (true) {
            String value = auxiliary("该对话的工作目录（回车取消）> ").strip();
            if (value.isEmpty()) return null;
            try {
                Path target = value.startsWith("~/") ? Path.of(System.getProperty("user.home")).resolve(value.substring(2)) : session.getWorkspace().resolve(value);
                target = target.toRealPath();
                if (!Files.isDirectory(target)) throw new IOException("工作区必须是目录");
                return target;
            } catch (IOException | RuntimeException error) { display.line("目录不可用，请重新输入。"); }
        }
    }
    @Override public void trace(String selection) { display.trace(history.trace(), selection); }
    @Override public void context(){try{display.line(session.contextInfo());}catch(IOException error){display.line("读取规则失败："+error.getMessage());}}
    @Override public void mcp(String action){if(!List.of("status","reconnect").contains(action))throw new IllegalArgumentException("用法：/mcp [status|reconnect]");CancellationToken token=new CancellationToken();active.set(token);display.phase("正在连接 MCP");try{String result=session.mcpStatus(action.equals("reconnect"),token,history.traceSink());display.line(token.isCancelled()?"MCP 连接已取消":result);}catch(IOException error){display.line("读取 MCP 配置失败："+error.getMessage());}finally{active.set(null);display.phase("就绪");}}
    @Override public void tasks(List<String> args){
        try{var tasks=context.tasks();if(args.isEmpty()||args.equals(List.of("list"))){display.line(com.alibaba.fastjson.JSON.toJSONString(tasks.list(),true));return;}
            if(args.size()<2)throw new IllegalArgumentException("用法：/tasks [status ID|output ID [offset]|stop ID]");
            Object result=switch(args.get(0)){case "status"->{if(args.size()!=2)throw new IllegalArgumentException("status 仅接受任务 ID");yield tasks.status(args.get(1));}case "stop"->{if(args.size()!=2)throw new IllegalArgumentException("stop 仅接受任务 ID");yield tasks.stop(args.get(1));}case "output"->{if(args.size()>3)throw new IllegalArgumentException("output 接受 ID 和可选 offset");yield tasks.output(args.get(1),args.size()==3?Long.parseLong(args.get(2)):0,16384);}default->throw new IllegalArgumentException("未知任务操作");};display.line(com.alibaba.fastjson.JSON.toJSONString(result,true));
        }catch(IOException error){display.line("读取任务失败："+error.getMessage());}
    }
    @Override public void editor(){if(input==null)throw new IllegalStateException("编辑器输入尚未初始化");try{input.editor();}catch(IOException error){display.line(error.getMessage());}}
    @Override public void draft(){if(input==null)throw new IllegalStateException("草稿输入尚未初始化");try{input.draft();}catch(IOException error){display.line(error.getMessage());}}
}
