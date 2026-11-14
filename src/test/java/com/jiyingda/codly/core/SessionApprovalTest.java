package com.jiyingda.codly.core;

import com.alibaba.fastjson.JSON;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class SessionApprovalTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private ConversationContext context() { return new ConversationContext(new ArrayList<>(), null, temporary.getRoot().toPath()); }
    private String file(String path) { return JSON.toJSONString(Map.of("filePath", path, "content", "x")); }
    private String bash(String command) { return JSON.toJSONString(Map.of("command", command)); }

    @Test public void traceRecordsExplicitDenialAndAutomaticPolicyDecision() {
        ConversationContext ctx = context();
        java.util.List<TraceEvent> trace = new ArrayList<>();
        ctx.setTraceEvents(trace::add);
        ctx.setApprovalHandler(request -> ApprovalDecision.DENY);
        assertFalse(ctx.confirm("write_file", file("a.txt"), "write a.txt"));
        assertEquals(TraceEvent.Phase.START, trace.get(0).phase());
        assertEquals(TraceEvent.Phase.FAILED, trace.get(1).phase());
        assertEquals(trace.get(0).id(), trace.get(1).id());
        ctx.setApprovalPolicy(ApprovalPolicy.ASSISTED);
        assertTrue(ctx.confirm("write_file", file("a.txt"), "write a.txt"));
        assertEquals("自动批准", trace.get(2).title());
    }

    @Test public void directoryGrantCoversSiblingEditsButNotSubdirectoriesOrEscapes() throws Exception {
        ConversationContext ctx = context();
        AtomicInteger prompts = new AtomicInteger();
        ctx.setApprovalHandler(request -> { prompts.incrementAndGet(); assertTrue(request.scope().description().contains("仅该目录")); return ApprovalDecision.ALLOW_SESSION; });
        assertTrue(ctx.confirm("write_file", file("src/a.java"), "write"));
        assertTrue(ctx.confirm("edit_file", file("src/b.java"), "edit"));
        assertEquals(1, prompts.get());
        ctx.setApprovalHandler(request -> { prompts.incrementAndGet(); return ApprovalDecision.DENY; });
        assertFalse(ctx.confirm("write_file", file("src/nested/a.java"), "nested"));
        Path outside = temporary.newFolder().toPath();
        Files.createDirectories(temporary.getRoot().toPath().resolve("src"));
        Files.createSymbolicLink(temporary.getRoot().toPath().resolve("src/link"), outside);
        assertFalse(ctx.confirm("write_file", file("src/link/file"), "link"));
        assertEquals(3, prompts.get());
    }

    @Test public void rememberedBuildFamilyDoesNotAuthorizeDeployOrShellSuffixes() {
        ConversationContext ctx = context();
        AtomicInteger prompts = new AtomicInteger();
        ctx.setApprovalHandler(request -> { prompts.incrementAndGet(); return ApprovalDecision.ALLOW_SESSION; });
        assertTrue(ctx.confirm("Bash", bash("mvn test"), "test"));
        assertTrue(ctx.confirm("Bash", bash("mvn -DskipTests package"), "package"));
        assertEquals(1, prompts.get());
        ctx.setApprovalHandler(request -> ApprovalDecision.DENY);
        assertFalse(ctx.confirm("Bash", bash("mvn deploy"), "deploy"));
        assertFalse(ctx.confirm("Bash", bash("mvn test; rm -rf src"), "shell"));
        assertFalse(ctx.confirm("Bash", bash("npm install"), "deps"));
    }

    @Test public void dangerousCommandsAreRememberedExactlyAndDenialNeverStoresGrant() {
        ConversationContext ctx = context();
        AtomicInteger prompts = new AtomicInteger();
        ctx.setApprovalHandler(request -> { prompts.incrementAndGet(); return ApprovalDecision.ALLOW_SESSION; });
        assertTrue(ctx.confirm("Bash", bash("rm old.txt"), "remove"));
        assertTrue(ctx.confirm("Bash", bash("rm old.txt"), "remove"));
        assertEquals(1, prompts.get());
        ctx.setApprovalHandler(request -> { prompts.incrementAndGet(); return ApprovalDecision.DENY; });
        assertFalse(ctx.confirm("Bash", bash("rm new.txt"), "remove"));
        assertFalse(ctx.confirm("Bash", bash("rm new.txt"), "remove"));
        assertEquals(3, prompts.get());
    }

    @Test public void scriptGrantAllowsArgumentsToVaryButNotADifferentScriptOrShellChain() throws Exception {
        ConversationContext ctx = context();
        Files.writeString(temporary.getRoot().toPath().resolve("report.py"), "print('ok')");
        ctx.setApprovalHandler(request -> ApprovalDecision.ALLOW_SESSION);
        assertTrue(ctx.confirm("Bash", bash("python3 report.py student-a"), "script"));
        ctx.setApprovalHandler(request -> ApprovalDecision.DENY);
        assertTrue(ctx.confirm("Bash", bash("python3 report.py student-b"), "script"));
        assertFalse(ctx.confirm("Bash", bash("python3 other.py student-b"), "other"));
        assertFalse(ctx.confirm("Bash", bash("python3 report.py student-b; rm data"), "chain"));
        assertFalse(ctx.confirm("Bash", bash("python3 -c 'print(1)'"), "inline"));
    }

    @Test public void allowOnceAndCancellationNeverCreateSessionGrant() {
        ConversationContext ctx = context();
        ctx.setApprovalHandler(request -> ApprovalDecision.ALLOW_ONCE);
        assertTrue(ctx.confirm("Bash", bash("rm a"), "remove"));
        ctx.setApprovalHandler(request -> ApprovalDecision.DENY);
        assertFalse(ctx.confirm("Bash", bash("rm a"), "remove"));
        ctx.setApprovalHandler(request -> { ctx.cancellation().cancel(); return ApprovalDecision.ALLOW_SESSION; });
        assertFalse(ctx.confirm("Bash", bash("rm b"), "remove"));
        ctx.beginTurn(new CancellationToken());
        ctx.setApprovalHandler(request -> ApprovalDecision.DENY);
        assertFalse(ctx.confirm("Bash", bash("rm b"), "remove"));
    }

    @Test public void newConversationClearsRememberedGrantsAndNewContextDoesNotInheritThem() {
        ConversationContext ctx = context();
        ChatSession session = new ChatSession(ctx, new ChatSessionTest.FakeMemory(), () -> "prompt", title -> {});
        ctx.setApprovalHandler(request -> ApprovalDecision.ALLOW_SESSION);
        assertTrue(ctx.confirm("write_file", file("first"), "write"));
        ctx.setApprovalHandler(request -> ApprovalDecision.DENY);
        assertTrue(ctx.confirm("write_file", file("second"), "write"));
        assertFalse(context().confirm("write_file", file("second"), "write"));
        session.newConversation();
        assertFalse(ctx.confirm("write_file", file("second"), "write"));
        session.close();
    }
}
