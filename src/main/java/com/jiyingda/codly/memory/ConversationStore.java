package com.jiyingda.codly.memory;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.jiyingda.codly.core.ConversationRecord;
import com.jiyingda.codly.core.ConversationTitle;
import com.jiyingda.codly.core.TraceEvent;
import com.jiyingda.codly.data.Message;
import com.jiyingda.codly.llm.ThinkingDepth;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** 原子保存独立会话文件；兼容旧版 JSON Lines 消息，不覆盖旧日志。 */
public final class ConversationStore {
    private final Path directory;
    private final Path legacyDirectory;

    public ConversationStore(Path directory, Path legacyDirectory) {
        this.directory = directory.toAbsolutePath().normalize();
        this.legacyDirectory = legacyDirectory.toAbsolutePath().normalize();
    }

    private Path file(String id) {
        if (id == null || !id.matches("[a-zA-Z0-9_-]{1,80}")) throw new IllegalArgumentException("对话标识无效");
        return directory.resolve(id + ".json");
    }

    public synchronized void save(ConversationRecord record) throws IOException {
        Path target = file(record.id());
        if (Files.isSymbolicLink(target)) throw new IOException("不能写入符号链接对话文件");
        if (Files.exists(target)) {
            ConversationRecord previous = read(target);
            if (previous.updatedAt().isAfter(record.updatedAt())) return;
        }
        JSONObject value = new JSONObject(true);
        value.put("version", 1);
        value.put("id", record.id()); value.put("title", record.title());
        value.put("workspace", record.workspace()); value.put("provider", record.provider()); value.put("model", record.model());
        value.put("workspaceVerified", record.workspace() != null && !record.workspace().isBlank());
        value.put("thinkingDepth", record.thinkingDepth().name());
        value.put("createdAt", record.createdAt().toString()); value.put("updatedAt", record.updatedAt().toString());
        value.put("messages", record.messages()); value.put("turns", record.turns());
        List<JSONObject> trace = new ArrayList<>();
        for (TraceEvent event : record.trace()) {
            JSONObject node = new JSONObject(true);
            node.put("id", event.id()); node.put("at", event.at().toString());
            node.put("kind", event.kind().name()); node.put("phase", event.phase().name());
            node.put("title", event.title()); node.put("detail", event.detail());
            trace.add(node);
        }
        value.put("trace", trace);
        value.put("sessionFile", record.sessionFile());
        Files.createDirectories(directory);
        Path temporary = Files.createTempFile(directory, ".conversation-", ".tmp");
        try {
            try { Files.setPosixFilePermissions(temporary, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)); }
            catch (UnsupportedOperationException ignored) { }
            Files.writeString(temporary, JSON.toJSONString(value), StandardCharsets.UTF_8);
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }

    public synchronized ConversationRecord load(String id) throws IOException {
        Path target = file(id);
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return read(target);
        for (Path legacy : legacyFiles()) if (legacyId(legacy).equals(id)) return readLegacy(legacy);
        throw new IOException("对话文件不存在");
    }

    public synchronized List<ConversationRecord.Summary> list() throws IOException {
        List<ConversationRecord.Summary> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<String> referenced = new HashSet<>();
        if (Files.isDirectory(directory)) {
            try (var files = Files.list(directory)) {
                for (Path path : files.filter(p -> p.getFileName().toString().endsWith(".json") && Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)).toList()) {
                    String id = path.getFileName().toString().replaceFirst("\\.json$", "");
                    try {
                        ConversationRecord record = read(path);
                        result.add(new ConversationRecord.Summary(record.id(), record.title(), record.workspace(), record.updatedAt(), false));
                        ids.add(record.id());
                        if (record.sessionFile() != null) referenced.add(Path.of(record.sessionFile()).toAbsolutePath().normalize().toString());
                    } catch (IOException | RuntimeException e) {
                        result.add(new ConversationRecord.Summary(id, "无法读取的对话", "", Files.getLastModifiedTime(path).toInstant(), false));
                        ids.add(id);
                    }
                }
            }
        }
        for (Path path : legacyFiles()) {
            String id = legacyId(path);
            if (ids.contains(id) || referenced.contains(path.toAbsolutePath().normalize().toString()) || Files.size(path) == 0) continue;
            String title = path.getFileName().toString().replaceFirst("^\\d{12,14}_", "").replaceFirst("_[a-z0-9]{6}\\.(txt|json)$", "").replace('_', ' ');
            result.add(new ConversationRecord.Summary(id, title, "", Files.getLastModifiedTime(path).toInstant(), true));
        }
        result.sort(Comparator.comparing(ConversationRecord.Summary::updatedAt).reversed());
        return result;
    }

    private List<Path> legacyFiles() throws IOException {
        if (!Files.isDirectory(legacyDirectory)) return List.of();
        try (var files = Files.list(legacyDirectory)) {
            return files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.getFileName().toString().matches(".*\\.(txt|json)")).toList();
        }
    }

    private ConversationRecord read(Path path) throws IOException {
        try {
            JSONObject value = JSON.parseObject(Files.readString(path));
            if (value == null || value.getIntValue("version") != 1) throw new IllegalArgumentException();
            String id = value.getString("id");
            if (!file(id).equals(path)) throw new IllegalArgumentException();
            if (value.getString("title") == null || value.getString("title").isBlank()) throw new IllegalArgumentException();
            if (value.getString("workspace") != null && !value.getString("workspace").isBlank()) Path.of(value.getString("workspace"));
            List<TraceEvent> trace = new ArrayList<>();
            for (Object item : value.getJSONArray("trace")) {
                JSONObject node = (JSONObject) item;
                Instant at = node.get("at") instanceof Number number ? Instant.ofEpochMilli(number.longValue()) : Instant.parse(node.getString("at"));
                trace.add(new TraceEvent(node.getString("id"), at, TraceEvent.Kind.valueOf(node.getString("kind")),
                        TraceEvent.Phase.valueOf(node.getString("phase")), node.getString("title"), node.getString("detail")));
            }
            // 早期导入旧日志时会把当前目录误记为工作区；没有明确绑定标记的旧版归档需重新确认一次。
            String workspace = id.startsWith("legacy-") && !value.getBooleanValue("workspaceVerified") && trace.isEmpty()
                    ? "" : value.getString("workspace");
            return new ConversationRecord(id, value.getString("title"), workspace, value.getString("provider"), value.getString("model"),
                    ThinkingDepth.valueOf(value.getString("thinkingDepth")), Instant.parse(value.getString("createdAt")), Instant.parse(value.getString("updatedAt")),
                    value.getJSONArray("messages").toJavaList(Message.class), value.getJSONArray("turns").toJavaList(ConversationRecord.Turn.class),
                    trace, value.getString("sessionFile"));
        } catch (RuntimeException e) { throw new IOException("对话文件格式无效：" + path.getFileName()); }
    }

    private ConversationRecord readLegacy(Path path) throws IOException {
        try {
            String content = Files.readString(path).strip();
            List<Message> messages = new ArrayList<>();
            if (content.startsWith("[")) messages.addAll(JSON.parseArray(content, Message.class));
            else for (String line : content.lines().filter(s -> !s.isBlank()).toList()) messages.add(JSON.parseObject(line, Message.class));
            for (Message message : messages) if (message == null || message.getRole() == null || message.getContent() == null) throw new IllegalArgumentException();
            List<ConversationRecord.Turn> turns = new ArrayList<>();
            String user = null;
            for (Message message : messages) {
                if ("user".equals(message.getRole())) {
                    if (user != null) turns.add(new ConversationRecord.Turn(user, "", "未完成"));
                    user = message.getContent();
                } else if ("assistant".equals(message.getRole())) {
                    turns.add(new ConversationRecord.Turn(user == null ? "" : user, message.getContent(), "已完成")); user = null;
                }
            }
            if (user != null) turns.add(new ConversationRecord.Turn(user, "", "未完成"));
            if (turns.isEmpty()) throw new IllegalArgumentException();
            Instant date = Files.getLastModifiedTime(path).toInstant();
            return new ConversationRecord(legacyId(path), ConversationTitle.fallback(turns.get(0).user()), "", "", "", ThinkingDepth.OFF, date, date,
                    messages, turns, List.of(), path.toAbsolutePath().normalize().toString());
        } catch (RuntimeException e) { throw new IOException("旧版对话文件格式无效：" + path.getFileName()); }
    }

    private static String legacyId(Path path) {
        try { return "legacy-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(path.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
