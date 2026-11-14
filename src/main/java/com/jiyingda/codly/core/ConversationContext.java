package com.jiyingda.codly.core;

import com.jiyingda.codly.data.Message;
import com.jiyingda.codly.llm.LlmProvider;
import com.jiyingda.codly.skill.Skill;
import com.jiyingda.codly.systeminfo.SystemInfoManager;
import java.util.Objects;
import java.util.function.Predicate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 与用户界面无关的会话上下文：消息、工作区、Skills、确认策略和取消信号。
 */
public class ConversationContext {

    private Message systemPrompt;
    private final List<Message> memory;
    private volatile LlmProvider llmClient;
    private volatile Path startupPath;
    private volatile SystemInfoManager systemInfo;
    private java.util.function.Function<ApprovalRequest, ApprovalDecision> confirmation = request -> ApprovalDecision.DENY;
    private final java.util.Set<String> sessionApprovals = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile ApprovalPolicy approvalPolicy = ApprovalPolicy.ASK;
    private volatile CancellationToken cancellation = new CancellationToken();
    private volatile boolean shouldQuit;
    private java.util.function.Consumer<ToolEvent> toolEvents;
    private java.util.function.Consumer<TraceEvent> traceEvents;
    private final Map<String, String> toolTraceIds = new java.util.HashMap<>();
    /** 当前会话已激活的 skill,按激活顺序,去重 */
    private final Map<String, Skill> activeSkills = new LinkedHashMap<>();

    public ConversationContext(List<Message> memory, LlmProvider llmClient, Path startupPath) {
        this.memory = memory;
        this.llmClient = llmClient;
        this.startupPath = startupPath.toAbsolutePath().normalize();
        this.systemInfo = SystemInfoManager.forWorkspace(this.startupPath);
    }

    public List<Message> getMemory() {
        return memory;
    }

    public LlmProvider getLlmClient() {
        return llmClient;
    }

    public void setLlmClient(LlmProvider provider) {
        this.llmClient = Objects.requireNonNull(provider);
    }

    public void replaceActiveSkills(List<Skill> skills) {
        activeSkills.clear();
        skills.forEach(this::activateSkill);
    }

    public SystemInfoManager getSystemInfo() {
        return systemInfo;
    }

    public Path getStartupPath() {
        return startupPath;
    }

    /** 仅由 ChatSession 在保存并清空上一段对话后调用。 */
    void switchWorkspace(Path workspace) {
        startupPath = workspace;
        systemInfo = SystemInfoManager.forWorkspace(workspace);
        systemPrompt = null;
        cancellation = new CancellationToken();
    }

    public void setConfirmation(Predicate<String> confirmation) {
        Objects.requireNonNull(confirmation);
        setApprovalHandler(request -> confirmation.test(request.summary()) ? ApprovalDecision.ALLOW_ONCE : ApprovalDecision.DENY);
    }

    public void setApprovalHandler(java.util.function.Function<ApprovalRequest, ApprovalDecision> handler) {
        confirmation = Objects.requireNonNull(handler);
    }

    public boolean confirm(String summary) {
        return confirm(null, null, summary);
    }

    public void setApprovalPolicy(ApprovalPolicy policy) {
        approvalPolicy = Objects.requireNonNull(policy);
    }

    public ApprovalPolicy getApprovalPolicy() { return approvalPolicy; }

    public boolean confirm(String tool, String arguments, String summary) {
        var token = cancellation;
        if (token.isCancelled()) return false;
        ApprovalScope scope = ApprovalScope.forOperation(tool, arguments, summary, startupPath);
        boolean remembered = sessionApprovals.contains(scope.key());
        if (approvalPolicy.approves(tool, arguments, startupPath) || remembered) {
            emitTrace(TraceEvent.of(TraceEvent.newId(), TraceEvent.Kind.APPROVAL, TraceEvent.Phase.COMPLETE,
                    remembered ? "会话授权" : "自动批准", "授权来源：" + (remembered ? scope.description() : approvalPolicy.description()) + "\n" + summary));
            return !token.isCancelled();
        }
        ExecutionPermission level = ExecutionPermission.classify(tool, arguments, startupPath);
        String approvalId = TraceEvent.newId();
        emitTrace(TraceEvent.of(approvalId, TraceEvent.Kind.APPROVAL, TraceEvent.Phase.START, "等待用户批准", level.label() + "\n" + summary + "\n可记忆范围：" + scope.description()));
        ApprovalDecision decision = confirmation.apply(new ApprovalRequest("权限级别：" + level.label() + "\n" + summary, level, scope));
        boolean denied = token.isCancelled() || decision == null || decision == ApprovalDecision.DENY;
        emitTrace(TraceEvent.of(approvalId, TraceEvent.Kind.APPROVAL,
                token.isCancelled() ? TraceEvent.Phase.CANCELLED : denied ? TraceEvent.Phase.FAILED : TraceEvent.Phase.COMPLETE,
                denied ? "未批准执行" : "已批准执行", "\n批准结果：" + (denied ? "拒绝或取消" : decision == ApprovalDecision.ALLOW_SESSION ? "本会话类似操作不再申请" : "仅本次允许")));
        if (denied) return false;
        if (decision == ApprovalDecision.ALLOW_SESSION) sessionApprovals.add(scope.key());
        return true;
    }

    public void setToolEvents(java.util.function.Consumer<ToolEvent> listener) {
        toolEvents = listener;
    }

    public boolean hasToolEvents() { return toolEvents != null; }

    public void emitTool(ToolEvent event) {
        if (toolEvents != null) toolEvents.accept(event);
        if (traceEvents == null) return;
        if (event.state() == ToolEvent.State.REQUESTED) {
            String id = TraceEvent.newId();
            toolTraceIds.put(event.id(), id);
            emitTrace(TraceEvent.of(id, TraceEvent.Kind.TOOL, TraceEvent.Phase.START, "工具调用 · " + event.name(), "参数\n" + event.arguments()));
        } else {
            String id = toolTraceIds.remove(event.id());
            if (id == null) id = TraceEvent.newId();
            boolean failed = event.state() == ToolEvent.State.FAILED || event.result() != null && (event.result().startsWith("用户拒绝") || event.result().startsWith("执行失败") || event.result().startsWith("执行 execBash 失败"));
            emitTrace(TraceEvent.of(id, TraceEvent.Kind.TOOL, event.state() == ToolEvent.State.CANCELLED ? TraceEvent.Phase.CANCELLED
                    : failed ? TraceEvent.Phase.FAILED : TraceEvent.Phase.COMPLETE, "工具调用 · " + event.name(), "\n\n返回结果\n" + event.result()));
        }
    }

    public void setTraceEvents(java.util.function.Consumer<TraceEvent> listener) { traceEvents = listener; toolTraceIds.clear(); }
    public java.util.function.Consumer<TraceEvent> traceListener() { return traceEvents; }
    public void emitTrace(TraceEvent event) { if (traceEvents != null) traceEvents.accept(event); }

    public CancellationToken cancellation() {
        return cancellation;
    }

    void beginTurn(CancellationToken token) {
        cancellation = Objects.requireNonNull(token);
    }

    public Message getSystemPrompt() {
        return systemPrompt;
    }
    public void setSystemPrompt(Message systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public void resetMemory() {
        sessionApprovals.clear();
        memory.clear();
        activeSkills.clear();
    }

    public List<Skill> getActiveSkills() {
        return new ArrayList<>(activeSkills.values());
    }

    /** @return true 表示新激活,false 表示已激活过 */
    public boolean activateSkill(Skill skill) {
        return activeSkills.put(skill.getName(), skill) == null;
    }

    public boolean deactivateSkill(String name) {
        return activeSkills.remove(name) != null;
    }

    public boolean isSkillActive(String name) {
        return activeSkills.containsKey(name);
    }

    /**
     * 把所有激活 skill 的完整正文拼成 system prompt 片段。
     * 无激活 skill 时返回空串。
     */
    public String activeSkillsPromptSection() {
        if (activeSkills.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n## Active Skills\n");
        sb.append("以下 skill 已被用户激活,请在本轮对话中遵循其说明:\n\n");
        for (Skill s : activeSkills.values()) {
            sb.append(s.toFullPromptSection()).append("\n\n");
        }
        return sb.toString();
    }

    public boolean shouldQuit() {
        return shouldQuit;
    }

    public void setShouldQuit(boolean shouldQuit) {
        this.shouldQuit = shouldQuit;
    }
}
