package com.jiyingda.codly.core;

import com.jiyingda.codly.SessionManager;
import com.jiyingda.codly.config.Config;
import com.jiyingda.codly.data.Message;
import com.jiyingda.codly.knowledge.KnowledgeRepository;
import com.jiyingda.codly.llm.LlmClient;
import com.jiyingda.codly.memory.MemoryManager;
import com.jiyingda.codly.prompt.SystemPrompt;
import com.jiyingda.codly.skill.SkillRegistry;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * CLI 与桌面共享的单会话服务。调用方负责线程调度和流式呈现；
 * send/close 串行，cancel 可由 UI 线程调用。首版每个进程只打开一个会话。
 */
public final class ChatSession implements AutoCloseable {
    private final ConversationContext context;
    private final SessionManager lifecycle;
    private final Supplier<String> prompt;
    private boolean closed;

    public ChatSession(ConversationContext context, SessionMemory memory,
                       Supplier<String> prompt, Consumer<String> onTitle) {
        this.context = Objects.requireNonNull(context);
        this.prompt = Objects.requireNonNull(prompt);
        lifecycle = new SessionManager(context.getMemory(), memory,
                context, Objects.requireNonNull(onTitle));
    }

    public static ChatSession open(Path workspace, Predicate<String> confirmation, Consumer<String> onTitle) {
        ConversationContext context = new ConversationContext(new ArrayList<>(), LlmClient.create(), workspace);
        context.setConfirmation(confirmation);
        return open(context, onTitle);
    }

    public static ChatSession openWithApprovals(Path workspace,
            java.util.function.Function<ApprovalRequest, ApprovalDecision> handler, Consumer<String> onTitle) {
        ConversationContext context = new ConversationContext(new ArrayList<>(), LlmClient.create(), workspace);
        context.setApprovalHandler(handler);
        return open(context, onTitle);
    }

    public static ChatSession open(ConversationContext context, Consumer<String> onTitle) {
        if (!Files.isDirectory(context.getStartupPath())) {
            throw new IllegalArgumentException("项目目录不存在：" + context.getStartupPath());
        }
        SkillRegistry.getInstance().load();
        applySkills(context);
        context.configureRuntime(Path.of(System.getProperty("user.home"),".codly"),Config.getContextOptions(),Config.getExecutionOptions());
        KnowledgeRepository.getInstance().load();
        MemoryManager memory = MemoryManager.getInstance();
        return new ChatSession(context, memory, () -> SystemPrompt.SOUL_PROMPT
                + SkillRegistry.getInstance().toCatalogSection()
                + context.activeSkillsPromptSection()
                + memory.toLongTermPromptSection()
                + KnowledgeRepository.getInstance().toCatalogSection()
                + "\n\n## Workspace\n" + context.getStartupPath()
                + "\n\n" + context.getSystemInfo().currentTime(), onTitle);
    }

    /** 流式回调保留现有 provider 协议（含 reasoning/usage 标记），返回值为助手正文。 */
    public String send(String input, Consumer<String> onToken) {
        return send(input, onToken, new CancellationToken());
    }

    public String send(String input, Consumer<String> onToken, CancellationToken cancellation) {
        return send(input, onToken, cancellation, null);
    }

    public synchronized String send(String input, Consumer<String> onToken, CancellationToken cancellation,
                                    Consumer<ToolEvent> tools) {
        return send(input, onToken, cancellation, tools, null);
    }

    public synchronized String send(String input, Consumer<String> onToken, CancellationToken cancellation,
                                    Consumer<ToolEvent> tools, Consumer<TraceEvent> trace) {
        if (closed) throw new IllegalStateException("会话已关闭");
        if (input == null || input.isBlank()) throw new IllegalArgumentException("消息不能为空");
        context.beginTurn(cancellation);
        if (cancellation.isCancelled()) { context.endTurn(cancellation); return ""; }
        context.setToolEvents(tools);
        context.setTraceEvents(trace);
        String turnId = TraceEvent.newId();
        context.emitTrace(TraceEvent.of(TraceEvent.newId(), TraceEvent.Kind.INPUT, TraceEvent.Phase.COMPLETE, "用户消息", input));
        context.emitTrace(TraceEvent.of(turnId, TraceEvent.Kind.LIFECYCLE, TraceEvent.Phase.START, "执行本轮对话", "工作目录：" + context.getStartupPath() + "\n模型：" + getModel() + "\n思考深度：" + getThinkingDepth() + "\n批准策略：" + context.getApprovalPolicy()));
        try {
            var project=context.project();
            String prepared=input;
            if(project!=null){
                var attached=project.prepare(input);prepared=attached.message();
                for(String file:attached.attachments())context.emitTrace(TraceEvent.of(TraceEvent.newId(),TraceEvent.Kind.LIFECYCLE,TraceEvent.Phase.COMPLETE,"附加文件",file));
            }
            context.setSystemPrompt(Message.fromSystem(prompt.get() + context.getApprovalPolicy().promptSection()+(project==null?"":project.ruleSection())));
            fitContext(prepared);
            if(cancellation.isCancelled())return "";
            lifecycle.handleUserMessage(prepared,input);
            String reply = context.getLlmClient().chat(context, onToken);
            lifecycle.handleAssistantResponse(reply, input);
            try{var measured=new ArrayList<Message>();measured.add(context.getSystemPrompt());measured.addAll(context.getMemory());context.budget().used(context.getLlmClient().estimateRequestTokens(context,measured));}catch(RuntimeException ignored){}
            context.emitTrace(TraceEvent.of(turnId, TraceEvent.Kind.LIFECYCLE,
                    cancellation.isCancelled() ? TraceEvent.Phase.CANCELLED : TraceEvent.Phase.COMPLETE,
                    cancellation.isCancelled() ? "本轮已停止" : "本轮已完成", ""));
            return reply;
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("读取文件或项目规则失败："+e.getMessage(),e);
        } catch (RuntimeException e) {
            context.emitTrace(TraceEvent.of(turnId, TraceEvent.Kind.LIFECYCLE, TraceEvent.Phase.FAILED, "本轮请求失败", e.getMessage()));
            throw e;
        } finally {
            context.setToolEvents(null);
            context.setTraceEvents(null);
            context.endTurn(cancellation);
        }
    }

    public synchronized void newConversation() {
        newConversation(context.getStartupPath());
    }

    public synchronized void newConversation(Path workspace) {
        if (closed) throw new IllegalStateException("会话已关闭");
        Path target;
        try {
            target = Objects.requireNonNull(workspace).toRealPath();
            if (!Files.isDirectory(target)) throw new IllegalArgumentException("工作目录不是目录：" + target);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("无法访问工作目录：" + workspace, e);
        }
        var activeSkills = context.getActiveSkills();
        lifecycle.newConversation();
        context.switchWorkspace(target);
        context.replaceActiveSkills(activeSkills);
    }

    public synchronized Path getWorkspace() { return context.getStartupPath(); }

    /** 仅压缩模型上下文；原始会话日志保留，失败时不修改现有消息或会话状态。 */
    public synchronized String compactConversation() {
        return compactConversation(0);
    }

    private String compactConversation(int keepRecent) {
        if (closed) throw new IllegalStateException("会话已关闭");
        var messages = snapshotMessages().stream().filter(message -> !"system".equals(message.getRole())).toList();
        if (messages.isEmpty()) return null;
        int split=Math.max(0,messages.size()-keepRecent);
        while(split>0&&split<messages.size()&&!"user".equals(messages.get(split).getRole()))split--;
        if(split==0&&keepRecent>0)return null;
        var prefix=messages.subList(0,split);
        String summary = context.getLlmClient().complete(java.util.List.of(
                Message.fromSystem(SystemPrompt.GEN_SUMMARY_PROMPT),
                Message.fromUser(com.alibaba.fastjson.JSON.toJSONString(prefix) + "\n\n" + SystemPrompt.DEFAULT_SUMMARIZE_PROMPT)),
                context.traceListener(), "压缩会话",context.cancellation());
        if (summary == null || summary.isBlank()||java.util.Set.of("[]","{}","null").contains(summary.strip())) throw new IllegalStateException("模型未返回有效总结，已保留原对话");
        context.getMemory().clear();
        context.getMemory().add(Message.fromUser("[Previous conversation summarized]: " + summary.strip()));
        context.getMemory().addAll(messages.subList(split,messages.size()));
        return summary.strip();
    }

    private int estimate(String input){var messages=new ArrayList<Message>();messages.add(context.getSystemPrompt());messages.addAll(context.getMemory());messages.add(Message.fromUser(input));return context.getLlmClient().estimateRequestTokens(context,messages);}
    private void fitContext(String input){
        var budget=context.budget();int used=estimate(input);budget.used(used);
        if(used>=budget.threshold(getModel())&&budget.autoCompact()&&!context.getMemory().isEmpty()){
            try {String summary=compactConversation(budget.keepRecent());if(summary!=null)context.emitTrace(TraceEvent.of(TraceEvent.newId(),TraceEvent.Kind.LIFECYCLE,TraceEvent.Phase.COMPLETE,"自动压缩上下文","保留最近消息及原始日志"));}
            catch(RuntimeException error){context.emitTrace(TraceEvent.of(TraceEvent.newId(),TraceEvent.Kind.LIFECYCLE,TraceEvent.Phase.FAILED,"自动压缩失败，原对话保留",error.getMessage()));}
            used=estimate(input);budget.used(used);
        }
        if(used>budget.limit(getModel()))throw new ContextLimitException(budget.describe(getModel())+"；请缩小文件范围、压缩历史或配置更大的模型容量");
    }

    /** 本地预算刷新不连接 MCP；工具首次发现后会在实际请求前重新校验。 */
    public synchronized void refreshContextBudget() throws java.io.IOException {
        var project=context.project();
        context.setSystemPrompt(Message.fromSystem(prompt.get()+context.getApprovalPolicy().promptSection()+(project==null?"":project.ruleSection())));
        var messages=new ArrayList<Message>();messages.add(context.getSystemPrompt());messages.addAll(context.getMemory());
        context.budget().used(context.getLlmClient().estimateRequestTokens(context,messages));
    }

    public synchronized String contextInfo() throws java.io.IOException {
        refreshContextBudget();
        StringBuilder text=new StringBuilder(context.budget().describe(getModel()));var project=context.project();
        if(project!=null)for(var rule:project.rules())text.append("\n").append(rule.file()).append(" · ").append(rule.scope());return text.toString();
    }

    public synchronized String mcpStatus(boolean reconnect,Consumer<TraceEvent> trace) throws java.io.IOException {
        return mcpStatus(reconnect,new CancellationToken(),trace);
    }
    public synchronized String mcpStatus(boolean reconnect,CancellationToken cancellation,Consumer<TraceEvent> trace) throws java.io.IOException {
        if(closed)throw new IllegalStateException("会话已关闭");
        com.alibaba.fastjson.JSONObject servers=null;
        if(reconnect){var settings=new com.jiyingda.codly.config.SettingsStore(Path.of(Config.getConfigPath())).read().values();com.jiyingda.codly.config.SettingsStore.validateMcp(settings.get("mcpServers"));servers=settings.getJSONObject("mcpServers");if(servers==null)servers=new com.alibaba.fastjson.JSONObject();}
        var previous=context.traceListener();context.setTraceEvents(trace);context.beginTurn(cancellation);
        try{return context.getLlmClient().mcpStatus(context,servers);}finally{context.setTraceEvents(previous);context.endTurn(cancellation);}
    }

    public synchronized java.util.List<Message> snapshotMessages() {
        return com.alibaba.fastjson.JSON.parseArray(com.alibaba.fastjson.JSON.toJSONString(context.getMemory()), Message.class);
    }
    public synchronized String getProviderId() { return context.getLlmClient().providerId(); }
    public synchronized String getSessionReference() { return lifecycle.getSessionReference(); }

    public synchronized void restoreConversation(ConversationRecord record, Path workspace) {
        if (closed) throw new IllegalStateException("会话已关闭");
        Path target;
        try {
            target = workspace.toRealPath();
            if (!Files.isDirectory(target)) throw new IllegalArgumentException("工作目录不是目录");
        } catch (java.io.IOException e) { throw new IllegalArgumentException("无法访问工作目录：" + workspace, e); }
        var skills = context.getActiveSkills();
        var messages = com.alibaba.fastjson.JSON.parseArray(com.alibaba.fastjson.JSON.toJSONString(record.messages()), Message.class);
        lifecycle.restoreConversation(messages, record.sessionFile());
        context.switchWorkspace(target);
        context.replaceActiveSkills(skills);
        if (Objects.equals(record.provider(), getProviderId()) && record.model() != null && !record.model().isBlank()
                && (record.model().equals(getModel()) || context.getLlmClient().getAvailableModels().contains(record.model()))) {
            setModel(record.model());
            if (supportsThinkingDepth()) setThinkingDepth(record.thinkingDepth());
        }
    }

    public synchronized int getActiveSkillCount() { return context.getActiveSkills().size(); }

    public synchronized void setApprovalPolicy(ApprovalPolicy policy) { context.setApprovalPolicy(policy); }

    private static void applySkills(ConversationContext context) {
        context.replaceActiveSkills(Config.getEnabledSkills().stream()
                .map(name -> SkillRegistry.getInstance().findByName(name))
                .flatMap(java.util.Optional::stream).toList());
    }

    /** 调用方确保没有进行中的请求；保留当前会话消息。 */
    public synchronized void reloadConfiguration() {
        if (closed) throw new IllegalStateException("会话已关闭");
        Config.getInstance().load();
        var provider = LlmClient.create();
        SkillRegistry.getInstance().load();
        var previous = context.getLlmClient();
        context.setLlmClient(provider);
        if (previous != null) previous.close();
        applySkills(context);
        context.configureRuntime(Path.of(System.getProperty("user.home"),".codly"),Config.getContextOptions(),Config.getExecutionOptions());
    }

    public void cancel() {
        context.cancellation().cancel();
    }

    public synchronized String getModel() {
        return context.getLlmClient().getModel();
    }

    public synchronized void setModel(String model) {
        if (closed) throw new IllegalStateException("会话已关闭");
        context.getLlmClient().setModel(model);
    }

    public synchronized com.jiyingda.codly.llm.ThinkingDepth getThinkingDepth() { return context.getLlmClient().getThinkingDepth(); }
    public synchronized boolean supportsThinkingDepth() { return context.getLlmClient().supportsThinkingDepth(); }
    public synchronized void setThinkingDepth(com.jiyingda.codly.llm.ThinkingDepth depth) {
        if (closed) throw new IllegalStateException("会话已关闭");
        context.getLlmClient().setThinkingDepth(depth);
    }

    @Override
    public synchronized void close() {
        close(true);
    }

    public synchronized void close(boolean extractLongTerm) {
        if (closed) return;
        closed = true;
        context.setShouldQuit(true);
        try { context.closeTasks();lifecycle.shutdown(extractLongTerm); }
        finally { if (context.getLlmClient() != null) context.getLlmClient().close(); }
    }
    public synchronized int runningTasks(){return context.runningTasks();}
}
