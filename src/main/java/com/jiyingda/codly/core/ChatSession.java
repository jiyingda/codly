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
        if (cancellation.isCancelled()) return "";
        context.setToolEvents(tools);
        context.setTraceEvents(trace);
        String turnId = TraceEvent.newId();
        context.emitTrace(TraceEvent.of(TraceEvent.newId(), TraceEvent.Kind.INPUT, TraceEvent.Phase.COMPLETE, "用户消息", input));
        context.emitTrace(TraceEvent.of(turnId, TraceEvent.Kind.LIFECYCLE, TraceEvent.Phase.START, "执行本轮对话", "工作目录：" + context.getStartupPath() + "\n模型：" + getModel() + "\n思考深度：" + getThinkingDepth() + "\n批准策略：" + context.getApprovalPolicy()));
        try {
            context.setSystemPrompt(Message.fromSystem(prompt.get() + context.getApprovalPolicy().promptSection()));
            lifecycle.handleUserMessage(input);
            String reply = context.getLlmClient().chat(context, onToken);
            lifecycle.handleAssistantResponse(reply, input);
            context.emitTrace(TraceEvent.of(turnId, TraceEvent.Kind.LIFECYCLE,
                    cancellation.isCancelled() ? TraceEvent.Phase.CANCELLED : TraceEvent.Phase.COMPLETE,
                    cancellation.isCancelled() ? "本轮已停止" : "本轮已完成", ""));
            return reply;
        } catch (RuntimeException e) {
            context.emitTrace(TraceEvent.of(turnId, TraceEvent.Kind.LIFECYCLE, TraceEvent.Phase.FAILED, "本轮请求失败", e.getMessage()));
            throw e;
        } finally {
            context.setToolEvents(null);
            context.setTraceEvents(null);
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
        if (closed) return;
        closed = true;
        context.setShouldQuit(true);
        try { lifecycle.shutdown(); }
        finally { if (context.getLlmClient() != null) context.getLlmClient().close(); }
    }
}
