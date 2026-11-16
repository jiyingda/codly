package com.jiyingda.codly.cli;

import com.jiyingda.codly.core.*;
import com.jiyingda.codly.llm.AbstractLlmClient;
import com.jiyingda.codly.util.*;
import org.jline.terminal.Terminal;
import org.jline.utils.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** JLine 状态区及每轮执行摘要；完整思考和工具内容只写入轨迹。 */
public final class CliDisplay implements AutoCloseable {
    private final Terminal terminal;
    private final Status footer;
    private String context = "", settings = "", phase = "就绪";
    private String budget="";
    public synchronized void budget(String value){budget=value;update();}
    private final StringBuilder answer = new StringBuilder();
    private final Map<String, Long> starts = new HashMap<>();
    private boolean reasoning;
    private TokenStats stats = new TokenStats();

    public CliDisplay(Terminal terminal) {
        this.terminal = terminal;
        footer = terminal.getType().startsWith("dumb") ? null : Status.getStatus(terminal);
        if (footer != null) footer.setBorder(true);
    }
    public synchronized void context(ChatSession session, ApprovalPolicy policy) {
        String workspace = session.getWorkspace().toString();
        if (workspace.length() > 35) workspace = "…" + workspace.substring(workspace.length() - 34);
        context = workspace + " · " + session.getModel();
        settings = session.getThinkingDepth() + " · " + policy;
        update();
        if (footer == null) line(context + " · " + settings);
    }
    public synchronized void phase(String text) { if (!phase.equals(text)) { phase = text; update(); } }
    private void update() {
        if (footer != null) {
            int width = Math.max(10, terminal.getWidth());
            var lines=new ArrayList<AttributedString>();lines.add(new AttributedString(safe(context)).columnSubSequence(0,width));lines.add(new AttributedString(safe(settings+" · "+phase)).columnSubSequence(0,width));if(!budget.isBlank())lines.add(new AttributedString(safe(budget)).columnSubSequence(0,width));footer.update(lines);
        }
    }
    public synchronized void begin() {
        answer.setLength(0); starts.clear(); reasoning = false;
        stats = new TokenStats(); stats.markStart(); phase("正在思考");
    }
    public synchronized void token(String token) {
        if (token.startsWith(AbstractLlmClient.USAGE_PREFIX)) {
            String[] parts = token.substring(AbstractLlmClient.USAGE_PREFIX.length()).split(":");
            if (parts.length == 3) try { stats.setUsage(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2])); }
            catch (NumberFormatException ignored) { }
            return;
        }
        if (token.equals(AbstractLlmClient.REASONING_START)) { reasoning = true; phase("正在思考"); return; }
        if (token.equals(AbstractLlmClient.REASONING_END)) { reasoning = false; return; }
        stats.markFirstToken(); stats.recordChunk();
        if (!reasoning) { answer.append(token); phase("正在生成回答"); }
    }
    public synchronized void tool(ToolEvent event) {
        if(event.state()==ToolEvent.State.OUTPUT){String text=safe(Objects.toString(event.result(),"")).replaceAll("\\s+"," ").strip();if(!text.isEmpty())phase("正在调用 "+event.name()+" · "+text.substring(Math.max(0,text.length()-60)));return;}
        if (event.state() == ToolEvent.State.REQUESTED) {
            answer.setLength(0); starts.put(event.id(), System.nanoTime()); phase("正在调用 " + event.name());
        } else {
            Long start = starts.remove(event.id());
            double seconds = start == null ? 0 : (System.nanoTime() - start) / 1_000_000_000.0;
            boolean failed = event.failed();
            String summary = safe(Objects.toString(event.result(), "")).replaceAll("\\s+", " ").strip();
            if (summary.length() > 100) summary = summary.substring(0, 100) + "…（/trace 查看完整结果）";
            line((failed ? "! " : "✓ ") + event.name() + " · " + String.format(Locale.ROOT, "%.1fs", seconds) + " · " + summary);
            phase("正在思考");
        }
    }
    public synchronized String finish(boolean cancelled, Throwable error) {
        stats.markEnd();
        String body = answer.toString();
        if (!body.isBlank()) {
            MarkdownRenderer markdown = new MarkdownRenderer();
            terminal.writer().println(markdown.process(safe(body)) + markdown.flush()); terminal.flush();
        }
        if (cancelled) line("已停止，可继续输入。");
        else if (error != null) line("请求失败：" + Objects.toString(error.getMessage(), "未知错误"));
        if (stats.hasUsage() || stats.getChunkCount() > 0) line(AttributedString.fromAnsi(stats.formatSummary()).toString());
        phase(cancelled ? "已停止" : error != null ? "请求失败" : "就绪");
        return body;
    }
    public synchronized void line(String text) { terminal.writer().println(safe(text)); terminal.flush(); }
    public synchronized void trace(List<TraceEvent> events, String selection) {
        record Node(TraceEvent event, StringBuilder detail) { }
        Map<String, Node> nodes = new LinkedHashMap<>();
        for (TraceEvent event : events) {
            Node previous = nodes.get(event.id());
            StringBuilder detail = previous == null ? new StringBuilder() : previous.detail();
            detail.append(event.detail()); nodes.put(event.id(), new Node(event, detail));
        }
        if (nodes.isEmpty()) { line("当前对话暂无轨迹。"); return; }
        List<Node> list = new ArrayList<>(nodes.values());
        boolean all = "all".equalsIgnoreCase(selection);
        int selected = 0;
        if (selection != null && !selection.isBlank() && !all) {
            try { selected = Integer.parseInt(selection); }
            catch (NumberFormatException error) { line("用法：/trace [序号|all]"); return; }
            if (selected < 1 || selected > list.size()) { line("轨迹序号无效。"); return; }
        }
        for (int i = 0; i < list.size(); i++) {
            Node node = list.get(i); TraceEvent event = node.event();
            if (selected != 0 && selected != i + 1) continue;
            String time = DateTimeFormatter.ofPattern("HH:mm:ss").format(event.at().atZone(ZoneId.systemDefault()));
            String color = event.phase() == TraceEvent.Phase.FAILED ? "31" : switch (event.kind()) {
                case MODEL_REQUEST -> "34"; case THINKING -> "35"; case TOOL, APPROVAL -> "33";
                case MODEL_RESPONSE -> "32"; default -> "36";
            };
            terminal.writer().println("\033[" + color + "m" + (i + 1) + ". " + time + " [" + event.phase() + "] " + safe(event.title()) + "\033[0m");
            if (all || selected > 0) line(node.detail().toString());
        }
        if (!all && selected == 0) line("/trace <序号> 查看完整提示词、命令和结果；/trace all 查看全部。");
        terminal.flush();
    }
    public static String safe(String text) { return text.replace("\r", "").replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", ""); }
    @Override public synchronized void close() { if (footer != null) footer.close(); }
}
