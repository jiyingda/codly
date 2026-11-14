package com.jiyingda.codly.memory;

import com.jiyingda.codly.core.*;
import com.jiyingda.codly.data.Message;
import com.jiyingda.codly.llm.ThinkingDepth;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.*;

public class ConversationStoreTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private ConversationStore store() { return new ConversationStore(temporary.getRoot().toPath().resolve("conversations"), temporary.getRoot().toPath().resolve("legacy")); }
    private ConversationRecord record(String id, String title, Instant updated) {
        return new ConversationRecord(id, title, temporary.getRoot().toString(), "qwen", "qwen3-test", ThinkingDepth.HIGH,
                Instant.parse("2026-01-01T00:00:00Z"), updated,
                List.of(Message.fromUser("用户提问"), Message.formAssistant("中间说明和最终正文")),
                List.of(new ConversationRecord.Turn("用户提问", "最终正文", "已完成")),
                List.of(TraceEvent.of("request", TraceEvent.Kind.MODEL_REQUEST, TraceEvent.Phase.START, "模型请求", "系统提示词\n完整参数")), null);
    }

    @Test public void persistsTitleContextFinalAnswerAndTraceAcrossRepositoryInstances() throws Exception {
        ConversationRecord saved = record("chat-1", "项目检查", Instant.now());
        store().save(saved);
        ConversationRecord restored = store().load("chat-1");
        assertEquals(saved.title(), restored.title());
        assertEquals(saved.workspace(), restored.workspace());
        assertEquals(saved.thinkingDepth(), restored.thinkingDepth());
        assertEquals(saved.updatedAt(), restored.updatedAt());
        assertEquals("中间说明和最终正文", restored.messages().get(1).getContent());
        assertEquals("最终正文", restored.turns().get(0).assistant());
        assertEquals(saved.trace(), restored.trace());
        Path file = temporary.getRoot().toPath().resolve("conversations/chat-1.json");
        assertEquals(Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(file));
    }

    @Test public void independentConversationsSurviveAndStaleSnapshotsDoNotOverwriteRecentSaves() throws Exception {
        Instant time = Instant.now();
        ConversationStore store = store();
        store.save(record("first", "第一段", time));
        store.save(record("second", "第二段", time.plusSeconds(1)));
        store.save(record("first", "更新名称", time.plusSeconds(2)));
        store.save(record("first", "过期名称", time.minusSeconds(1)));
        assertEquals(2, store.list().size());
        assertEquals("first", store.list().get(0).id());
        assertEquals("更新名称", store.load("first").title());
        assertEquals("第二段", store.load("second").title());
    }

    @Test public void importsLegacyMessagesWithoutOverwritingOrDuplicatingOriginalLog() throws Exception {
        Path legacy = Files.createDirectories(temporary.getRoot().toPath().resolve("legacy")).resolve("202611141200_旧版对话_abc123.txt");
        String contents = "{\"role\":\"user\",\"content\":\"继续旧任务\"}\n{\"role\":\"assistant\",\"content\":\"旧版回复\"}\n";
        Files.writeString(legacy, contents);
        ConversationStore store = store();
        var summary = store.list().get(0);
        assertTrue(summary.legacy());
        ConversationRecord imported = store.load(summary.id());
        assertEquals("", imported.workspace());
        assertEquals("继续旧任务", imported.title());
        assertEquals("旧版回复", imported.turns().get(0).assistant());
        store.save(imported);
        assertEquals(contents, Files.readString(legacy));
        assertEquals(1, store().list().size());
        assertFalse(store().list().get(0).legacy());
    }

    @Test public void reportsCorruptFilesWithoutHidingValidHistoryOrEchoingSecretContents() throws Exception {
        ConversationStore store = store();
        store.save(record("good", "有效记录", Instant.now()));
        Files.writeString(temporary.getRoot().toPath().resolve("conversations/broken.json"), "{secret:fake-secret");
        assertEquals(2, store.list().size());
        java.io.IOException error = assertThrows(java.io.IOException.class, () -> store.load("broken"));
        assertFalse(error.getMessage().contains("fake-secret"));
        assertEquals("有效记录", store.load("good").title());
    }

    @Test public void legacyWorkspaceMustBeBoundOnceInsteadOfTrustingOldFallbackDirectory() throws Exception {
        Path legacy = Files.createDirectories(temporary.getRoot().toPath().resolve("legacy")).resolve("old.txt");
        Files.writeString(legacy, "{\"role\":\"user\",\"content\":\"继续旧任务\"}\n");
        ConversationStore store = store();
        ConversationRecord original = store.load(store.list().get(0).id());
        String project = temporary.newFolder("bound-project").toPath().toRealPath().toString();
        ConversationRecord bound = new ConversationRecord(original.id(), original.title(), project, original.provider(), original.model(),
                original.thinkingDepth(), original.createdAt(), original.updatedAt(), original.messages(), original.turns(), original.trace(), original.sessionFile());
        store.save(bound);
        assertEquals(project, store.load(bound.id()).workspace());
        Path archive = temporary.getRoot().toPath().resolve("conversations/" + bound.id() + ".json");
        var oldArchive = com.alibaba.fastjson.JSON.parseObject(Files.readString(archive));
        oldArchive.remove("workspaceVerified");
        Files.writeString(archive, oldArchive.toJSONString());
        assertEquals("", store.load(bound.id()).workspace());
        assertEquals("", store.list().get(0).workspace());
        store.save(bound);
        assertEquals(project, store().load(bound.id()).workspace());
    }

    @Test public void rejectsTraversalAndSymlinkOverwrite() throws Exception {
        ConversationStore store = store();
        assertThrows(IllegalArgumentException.class, () -> store.load("../outside"));
        Files.createDirectories(temporary.getRoot().toPath().resolve("conversations"));
        Path outside = temporary.newFile("outside.json").toPath();
        Files.writeString(outside, "original");
        Files.createSymbolicLink(temporary.getRoot().toPath().resolve("conversations/link.json"), outside);
        assertThrows(java.io.IOException.class, () -> store.save(record("link", "不允许覆盖", Instant.now())));
        assertEquals("original", Files.readString(outside));
    }
}
