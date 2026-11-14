package com.jiyingda.codly.core;

import com.alibaba.fastjson.JSONObject;
import com.jiyingda.codly.function.FunctionManager;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class ApprovalPolicyTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private String args(String path) {
        JSONObject args = new JSONObject();
        args.put("filePath", path);
        args.put("content", "hello");
        return args.toJSONString();
    }

    @Test public void defaultAsksAndAssistedApprovesOnlyWorkspaceFileTools() throws Exception {
        Path root = temporary.newFolder().toPath();
        ConversationContext ctx = new ConversationContext(new ArrayList<>(), null, root);
        AtomicInteger questions = new AtomicInteger();
        ctx.setConfirmation(summary -> { questions.incrementAndGet(); return false; });
        FunctionManager tools = new FunctionManager();
        assertEquals(ApprovalPolicy.ASK, ctx.getApprovalPolicy());
        tools.execute("write_file", args("hello.txt"), ctx);
        assertFalse(Files.exists(root.resolve("hello.txt")));
        assertEquals(1, questions.get());
        ctx.setApprovalPolicy(ApprovalPolicy.ASSISTED);
        tools.execute("write_file", args("nested/hello.txt"), ctx);
        assertEquals("hello", Files.readString(root.resolve("nested/hello.txt")));
        assertTrue(ctx.confirm("edit_file", args("nested/hello.txt"), "edit"));
        assertEquals(1, questions.get());
        assertTrue(ctx.confirm("Bash", "{\"command\":\"pwd\"}", "command"));
        assertFalse(ctx.confirm("删除知识包"));
        assertFalse(ctx.confirm("custom_tool", args("file"), "custom"));
        assertEquals(3, questions.get());
    }

    @Test public void assistedPromptsForEscapesSymlinksAndAutomationConfiguration() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path outside = temporary.newFolder().toPath();
        Files.createSymbolicLink(root.resolve("link"), outside);
        assertFalse(ApprovalPolicy.ASSISTED.approves("write_file", args("../escape"), root));
        assertFalse(ApprovalPolicy.ASSISTED.approves("write_file", args(outside.resolve("file").toString()), root));
        assertFalse(ApprovalPolicy.ASSISTED.approves("write_file", args("link/new/sub/file"), root));
        assertFalse(Files.exists(outside.resolve("new")));
        assertFalse(ApprovalPolicy.ASSISTED.approves("edit_file", args(".git/config"), root));
        assertFalse(ApprovalPolicy.ASSISTED.approves("write_file", args(".agents/rules"), root));
        assertFalse(ApprovalPolicy.ASSISTED.approves("write_file", "invalid json", root));
    }

    @Test public void fullSkipsQuestionsButPreservesFileBoundariesAndCancellation() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path outside = temporary.newFolder().toPath().resolve("denied.txt");
        ConversationContext ctx = new ConversationContext(new ArrayList<>(), null, root);
        ctx.setConfirmation(summary -> { fail("full authorization must not ask"); return false; });
        ctx.setApprovalPolicy(ApprovalPolicy.FULL);
        assertTrue(ctx.confirm("Bash", "{}", "command"));
        assertTrue(ctx.confirm("删除知识包"));
        new FunctionManager().execute("write_file", args(outside.toString()), ctx);
        assertFalse(Files.exists(outside));
        ctx.cancellation().cancel();
        assertFalse(ctx.confirm("anything"));
        new FunctionManager().execute("write_file", args("cancelled.txt"), ctx);
        assertFalse(Files.exists(root.resolve("cancelled.txt")));
    }

    @Test public void cancellationWhileAskingStillDeniesOperation() throws Exception {
        ConversationContext ctx = new ConversationContext(new ArrayList<>(), null, temporary.newFolder().toPath());
        ctx.setConfirmation(summary -> { ctx.cancellation().cancel(); return true; });
        assertFalse(ctx.confirm("write_file", args("file"), "write"));
    }
}
