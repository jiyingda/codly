package com.jiyingda.codly.core;

import com.jiyingda.codly.data.Message;
import com.jiyingda.codly.function.ExecBashFunctionCall;
import com.jiyingda.codly.function.FunctionManager;
import com.jiyingda.codly.function.UserConfirmation;
import com.jiyingda.codly.llm.AbstractLlmClient;
import com.jiyingda.codly.llm.LlmProvider;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.Assert.*;

public class ChatSessionTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test public void sharedSessionPersistsBodyRatherThanRenderedStreamAndRefreshesPrompt() {
        FakeProvider provider = new FakeProvider();
        FakeMemory store = new FakeMemory();
        ConversationContext ctx = context(provider);
        List<String> titles = new ArrayList<>();
        AtomicInteger prompts = new AtomicInteger();
        ChatSession session = new ChatSession(ctx, store, () -> "prompt-" + prompts.incrementAndGet(), titles::add);
        List<String> stream = new ArrayList<>();
        assertEquals("正文", session.send("第一轮", stream::add));
        assertEquals("正文", store.messages.get(1).getContent());
        assertTrue(stream.contains(AbstractLlmClient.REASONING_START));
        assertEquals(List.of("标题"), titles);
        session.send("第二轮", null);
        assertEquals("prompt-2" + ApprovalPolicy.ASK.promptSection(), ctx.getSystemPrompt().getContent());
        assertEquals(4, ctx.getMemory().size());
        assertEquals(1, provider.titleRequests);
        session.setModel("other");
        assertEquals("other", session.getModel());
        session.close();
        session.close();
        assertEquals(1, store.shutdowns);
        assertTrue(store.flushed);
        assertThrows(IllegalStateException.class, () -> session.send("关闭后", null));
    }

    @Test public void restoringHistoryResumesMessagesWorkspaceAndModelWithoutRetitlingOrReusingApprovals() throws Exception {
        FakeProvider provider = new FakeProvider() {
            @Override public List<String> getAvailableModels() { return List.of("fake", "saved-model"); }
        };
        ConversationContext ctx = context(provider);
        FakeMemory memory = new FakeMemory();
        ChatSession session = new ChatSession(ctx, memory, () -> "workspace " + ctx.getStartupPath(), title -> {});
        ctx.setApprovalHandler(request -> ApprovalDecision.ALLOW_SESSION);
        assertTrue(ctx.confirm("Bash", "{\"command\":\"unknown\"}", "unknown"));
        Path project = temporary.newFolder("saved-workspace").toPath();
        var saved = new ConversationRecord("saved", "历史标题", project.toString(), "", "saved-model", com.jiyingda.codly.llm.ThinkingDepth.OFF,
                java.time.Instant.now(), java.time.Instant.now(), List.of(Message.fromUser("旧问题"), Message.formAssistant("旧答案")),
                List.of(new ConversationRecord.Turn("旧问题", "旧答案", "已完成")), List.of(), null);
        session.restoreConversation(saved, project);
        assertEquals(project.toRealPath(), session.getWorkspace());
        assertEquals("saved-model", session.getModel());
        assertEquals(2, ctx.getMemory().size());
        assertEquals("旧问题", memory.messages.get(0).getContent());
        ctx.setApprovalHandler(request -> ApprovalDecision.DENY);
        assertFalse(ctx.confirm("Bash", "{\"command\":\"unknown\"}", "unknown"));
        session.send("继续旧问题", null);
        assertEquals(4, ctx.getMemory().size());
        assertEquals(0, provider.titleRequests);
        var snapshot = session.snapshotMessages();
        snapshot.get(0).setContent("外部修改");
        assertEquals("旧问题", ctx.getMemory().get(0).getContent());
        session.close();
    }

    @Test public void thinkingChoicePreservesHistoryAndSurvivesNewConversation() {
        FakeProvider provider = new FakeProvider() {
            com.jiyingda.codly.llm.ThinkingDepth depth = com.jiyingda.codly.llm.ThinkingDepth.OFF;
            @Override public boolean supportsThinkingDepth() { return true; }
            @Override public com.jiyingda.codly.llm.ThinkingDepth getThinkingDepth() { return depth; }
            @Override public void setThinkingDepth(com.jiyingda.codly.llm.ThinkingDepth value) { depth = value; }
        };
        ConversationContext ctx = context(provider);
        ChatSession session = new ChatSession(ctx, new FakeMemory(), () -> "prompt", title -> {});
        session.send("第一轮", null);
        session.setThinkingDepth(com.jiyingda.codly.llm.ThinkingDepth.MAX);
        session.setModel("other");
        assertEquals(2, ctx.getMemory().size());
        assertEquals(com.jiyingda.codly.llm.ThinkingDepth.MAX, session.getThinkingDepth());
        session.newConversation();
        assertEquals(com.jiyingda.codly.llm.ThinkingDepth.MAX, session.getThinkingDepth());
        java.util.List<TraceEvent> trace = new java.util.ArrayList<>();
        session.send("新任务", null, new CancellationToken(), null, trace::add);
        assertTrue(trace.stream().anyMatch(event -> event.detail().contains("思考深度：极深思考")));
        session.close();
        assertThrows(IllegalStateException.class, () -> session.setThinkingDepth(com.jiyingda.codly.llm.ThinkingDepth.LOW));
    }

    @Test public void traceIncludesTurnContextAndInputAndIsDetachedAfterRequest() {
        ConversationContext ctx = context(new FakeProvider());
        ChatSession session = new ChatSession(ctx, new FakeMemory(), () -> "prompt", title -> {});
        List<TraceEvent> trace = new ArrayList<>();
        session.send("检查轨迹", null, new CancellationToken(), null, trace::add);
        assertTrue(trace.stream().anyMatch(event -> event.kind() == TraceEvent.Kind.INPUT && event.detail().equals("检查轨迹")));
        assertTrue(trace.stream().anyMatch(event -> event.kind() == TraceEvent.Kind.LIFECYCLE && event.phase() == TraceEvent.Phase.START && event.detail().contains(ctx.getStartupPath().toString())));
        assertTrue(trace.stream().anyMatch(event -> event.kind() == TraceEvent.Kind.LIFECYCLE && event.phase() == TraceEvent.Phase.COMPLETE));
        assertNull(ctx.traceListener());
        session.close();
    }

    @Test public void delayedTitleFromPreviousConversationDoesNotRenameTheNewConversation() {
        List<Consumer<String>> callbacks = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        FakeProvider provider = new FakeProvider() {
            @Override public void generateTitleAsync(String input, Consumer<String> callback) { callbacks.add(callback); }
        };
        ChatSession session = new ChatSession(context(provider), new FakeMemory(), () -> "prompt", titles::add);
        session.send("old", null);
        session.newConversation();
        session.send("new", null);
        callbacks.get(0).accept("old title");
        assertTrue(titles.isEmpty());
        callbacks.get(1).accept("new title");
        assertEquals(List.of("new title"), titles);
        session.close();
    }

    @Test public void titleUsesFirstMessageOnceAndInvalidOrClosedCallbacksDoNotRenameConversation() {
        List<String> inputs = new ArrayList<>();
        List<Consumer<String>> callbacks = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        FakeProvider provider = new FakeProvider() {
            @Override public void generateTitleAsync(String input, Consumer<String> callback) { inputs.add(input); callbacks.add(callback); }
        };
        ChatSession session = new ChatSession(context(provider), new FakeMemory(), () -> "prompt", titles::add);
        session.send("请优化首次对话命名", null);
        session.send("再检查一下", null);
        assertEquals(List.of("请优化首次对话命名"), inputs);
        callbacks.get(0).accept("[]");
        assertTrue(titles.isEmpty());
        callbacks.get(0).accept("标题：“首次对话自动命名”");
        assertEquals(List.of("首次对话自动命名"), titles);
        session.close();
        callbacks.get(0).accept("关闭后的标题");
        assertEquals(1, titles.size());
    }

    @Test public void newConversationFlushesOldMessagesAndStartsWithAFreshContext() {
        FakeProvider provider = new FakeProvider();
        FakeMemory store = new FakeMemory();
        ConversationContext context = context(provider);
        ChatSession session = new ChatSession(context, store, () -> "prompt", t -> {});
        session.send("old task", null);
        session.newConversation();
        assertTrue(store.flushed);
        assertTrue(context.getMemory().isEmpty());
        session.send("new task", null);
        assertEquals(2, context.getMemory().size());
        assertEquals("new task", context.getMemory().get(0).getContent());
        assertEquals(2, provider.titleRequests);
        session.close();
    }

    @Test public void cancellationBeforeWorkerStartsSkipsRequestAndNextTurnStillWorks() {
        FakeProvider provider = new FakeProvider();
        ChatSession session = new ChatSession(context(provider), new FakeMemory(), () -> "prompt", t -> {});
        CancellationToken turn = new CancellationToken();
        turn.cancel();
        assertEquals("", session.send("取消", null, turn));
        assertEquals(0, provider.requests);
        assertEquals("正文", session.send("继续", null));
        assertEquals(1, provider.requests);
        session.close();
    }

    @Test public void toolConfirmationDefaultsToDenyAndCanBeSuppliedByEitherEntry() throws Exception {
        ConversationContext ctx = context(new FakeProvider());
        FunctionManager functions = new FunctionManager();
        Path target = temporary.getRoot().toPath().resolve("written.txt");
        String arguments = "{\"filePath\":\"written.txt\",\"content\":\"hello\"}";
        assertEquals("用户拒绝执行此操作", functions.execute("write_file", arguments, ctx));
        assertFalse(Files.exists(target));
        AtomicInteger confirmations = new AtomicInteger();
        ctx.setConfirmation(summary -> { confirmations.incrementAndGet(); return true; });
        assertTrue(functions.execute("write_file", arguments, ctx).startsWith("写入成功"));
        assertEquals("hello", Files.readString(target));
        assertEquals(1, confirmations.get());
        assertFalse(UserConfirmation.confirm(null, "missing terminal"));
    }

    @Test public void selectedPolicyReachesModelOnNextTurnAndSurvivesNewConversation() {
        ConversationContext ctx = context(new FakeProvider());
        ChatSession session = new ChatSession(ctx, new FakeMemory(), () -> "base", title -> {});
        session.setApprovalPolicy(ApprovalPolicy.ASSISTED);
        session.send("第一轮", null);
        assertEquals("base" + ApprovalPolicy.ASSISTED.promptSection(), ctx.getSystemPrompt().getContent());
        session.setApprovalPolicy(ApprovalPolicy.FULL);
        session.newConversation();
        session.send("第二轮", null);
        assertEquals("base" + ApprovalPolicy.FULL.promptSection(), ctx.getSystemPrompt().getContent());
        session.close();
    }

    @Test public void bashUsesChosenProjectInsteadOfProcessDirectory() throws Exception {
        ConversationContext ctx = context(new FakeProvider());
        String output = new ExecBashFunctionCall().execute("{\"command\":\"pwd\"}", ctx);
        assertTrue(output, output.contains(temporary.getRoot().toPath().toRealPath().toString()));
        assertEquals(temporary.getRoot().toPath().toAbsolutePath().normalize(), ctx.getStartupPath());
    }

    @Test public void switchingWorkspaceSavesOldConversationResetsGrantsAndMovesToolsAndPrompt() throws Exception {
        ConversationContext ctx = context(new FakeProvider());
        FakeMemory store = new FakeMemory();
        ChatSession session = new ChatSession(ctx, store, () -> "Workspace: " + ctx.getStartupPath(), title -> {});
        ctx.setApprovalPolicy(ApprovalPolicy.ASK);
        ctx.setApprovalHandler(request -> ApprovalDecision.ALLOW_SESSION);
        assertTrue(ctx.confirm("Bash", "{\"command\":\"unknown command\"}", "command"));
        session.send("旧项目", null);
        var oldSystemInfo = ctx.getSystemInfo();
        Path target = temporary.newFolder("another-project").toPath().toRealPath();
        session.newConversation(target);
        assertTrue(store.flushed);
        assertTrue(ctx.getMemory().isEmpty());
        assertEquals(target, session.getWorkspace());
        assertNotSame(oldSystemInfo, ctx.getSystemInfo());
        assertNull(ctx.getSystemPrompt());
        ctx.setApprovalHandler(request -> ApprovalDecision.DENY);
        assertFalse(ctx.confirm("Bash", "{\"command\":\"unknown command\"}", "command"));
        assertTrue(new FunctionManager().execute("Bash", "{\"command\":\"pwd\"}", ctx).contains(target.toString()));
        session.send("新项目", null);
        assertTrue(ctx.getSystemPrompt().getContent().contains(target.toString()));
        assertEquals(2, ctx.getMemory().size());
        assertThrows(IllegalArgumentException.class, () -> session.newConversation(target.resolve("missing")));
        assertEquals(target, session.getWorkspace());
        assertEquals(2, ctx.getMemory().size());
        session.close();
    }

    @Test(timeout = 10000) public void stoppingTerminatesRunningBash() throws Exception {
        ConversationContext ctx = context(new FakeProvider());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> execution = executor.submit(() -> new ExecBashFunctionCall().execute(
                    "{\"command\":\"touch started; sleep 30\"}", ctx));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!Files.exists(temporary.getRoot().toPath().resolve("started"))) {
                if (System.nanoTime() > deadline) fail("Bash 未启动");
                Thread.sleep(20);
            }
            ctx.cancellation().cancel();
            assertEquals("命令已取消", execution.get(2, TimeUnit.SECONDS));
        } finally {
            ctx.cancellation().cancel();
            executor.shutdownNow();
        }
    }

    @Test public void cancellationRunsRegisteredAndLateActionsOnlyOnce() {
        CancellationToken token = new CancellationToken();
        AtomicInteger calls = new AtomicInteger();
        Runnable removed = () -> calls.addAndGet(100);
        token.register(removed);
        token.unregister(removed);
        token.register(calls::incrementAndGet);
        token.cancel();
        token.cancel();
        token.register(calls::incrementAndGet);
        assertEquals(2, calls.get());
    }

    private ConversationContext context(FakeProvider provider) {
        return new ConversationContext(new ArrayList<>(), provider, temporary.getRoot().toPath());
    }

    static class FakeProvider implements LlmProvider {
        String model = "fake";
        int requests;
        int titleRequests;
        public String getModel() { return model; }
        public void setModel(String model) { this.model = model; }
        public String chat(ConversationContext ctx, Consumer<String> stream) {
            requests++;
            if (stream != null) {
                stream.accept(AbstractLlmClient.REASONING_START);
                stream.accept("思考内容");
                stream.accept(AbstractLlmClient.REASONING_END);
                stream.accept("正文");
                stream.accept("[工具展示]");
            }
            return "正文";
        }
        public String chat(ConversationContext ctx, List<Message> messages, Consumer<String> stream) {
            return chat(ctx, stream);
        }
        public String complete(List<Message> messages) { return "[]"; }
        public void generateTitleAsync(String input, Consumer<String> onTitle) {
            titleRequests++;
            onTitle.accept("标题");
        }
        public List<String> getAvailableModels() { return List.of(model); }
    }

    static class FakeMemory implements SessionMemory {
        final List<Message> messages = new ArrayList<>();
        boolean flushed;
        int shutdowns;
        public void initializeWithSession(String title) {}
        public boolean appendMessage(Message message) { messages.add(message); return false; }
        public void appendLtmRound(String user, String assistant) {}
        public String toLongTermPromptSection() { return ""; }
        public void flushAndExtractLtmAsync(ConversationContext ctx) {}
        public void flushAndExtractLtmSync(ConversationContext ctx) {}
        public void flushNow() { flushed = true; }
        public void clearSession() { messages.clear(); }
        public void shutdown() { shutdowns++; }
    }
}
