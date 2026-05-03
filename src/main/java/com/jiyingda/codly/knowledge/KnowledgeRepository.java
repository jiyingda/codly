package com.jiyingda.codly.knowledge;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 知识库门面（单例）。负责：加载、检索、骨架生成、状态修改、删除、INDEX.md 同步、目录段拼装。
 *
 * <p>所有写操作（scaffold / delete / setStatus）会同步：
 * 1) 改磁盘文件
 * 2) 重新加载该 pack（或在 delete 时移除）
 * 3) 重写顶层 INDEX.md
 */
public class KnowledgeRepository {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeRepository.class);

    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]*$");
    private static final String DIR_SUFFIX = "-knowledge";
    private static final String GENERATOR_TAG = "codly-kb-scaffold@v1";

    private static volatile KnowledgeRepository instance;

    private final Path root;
    private final Map<String, KnowledgePack> byName = new LinkedHashMap<>();
    private final KnowledgeIndex index = new KnowledgeIndex();
    private final KnowledgeSearcher searcher = new KnowledgeSearcher(index);
    private boolean loaded = false;

    KnowledgeRepository(Path root) {
        this.root = root;
    }

    public static KnowledgeRepository getInstance() {
        if (instance == null) {
            synchronized (KnowledgeRepository.class) {
                if (instance == null) {
                    instance = new KnowledgeRepository(KnowledgeLoader.defaultRoot());
                }
            }
        }
        return instance;
    }

    /** 仅用于测试：替换实例（指定根目录）。 */
    static synchronized void setInstanceForTest(KnowledgeRepository repo) {
        instance = repo;
    }

    public Path getRoot() {
        return root;
    }

    public synchronized boolean isLoaded() {
        return loaded;
    }

    public synchronized int size() {
        return byName.size();
    }

    /** 启动加载：扫描磁盘 → 构建索引 → 重写 INDEX.md（reconcile 残留行）。 */
    public synchronized void load() {
        byName.clear();
        List<KnowledgePack> packs = KnowledgeLoader.loadAll(root);
        for (KnowledgePack pack : packs) {
            byName.put(pack.getName(), pack);
        }
        index.rebuild(new ArrayList<>(byName.values()));
        loaded = true;
        if (Files.isDirectory(root)) {
            IndexMdWriter.rewrite(root, new ArrayList<>(byName.values()));
        }
        logger.info("已加载 {} 个知识包，索引 token 数 {}", byName.size(), index.tokenCount());
    }

    /** 重新加载（不动磁盘正文，仅重建内存与 INDEX.md）。 */
    public synchronized void reload() {
        load();
    }

    public synchronized List<KnowledgePack> all() {
        return List.copyOf(byName.values());
    }

    public synchronized Optional<KnowledgePack> find(String name) {
        if (name == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byName.get(name.trim()));
    }

    public synchronized List<KnowledgeSearcher.Hit> search(String query, int limit) {
        return searcher.search(query, limit);
    }

    /** 系统提示词目录段。无知识包时返回空串。 */
    public synchronized String toCatalogSection() {
        if (byName.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n## Knowledge Packs（可检索的系统知识包）\n");
        sb.append("以下知识包已加载，需要时通过 kb_search / kb_read / kb_section 检索：\n");
        for (KnowledgePack pack : byName.values()) {
            sb.append(pack.toCatalogLine()).append('\n');
        }
        return sb.toString();
    }

    // ============================================================
    // 写操作
    // ============================================================

    /** 操作结果。 */
    public record OpResult(boolean ok, String message) {
        public static OpResult ok(String msg) {
            return new OpResult(true, msg);
        }
        public static OpResult fail(String msg) {
            return new OpResult(false, msg);
        }
    }

    /**
     * 生成空骨架知识包：&lt;name&gt;-knowledge/{KNOWLEDGE.md, sources.json}。
     * 已存在则失败。
     */
    public synchronized OpResult scaffold(String name, String system, String description) {
        if (name == null || !NAME_PATTERN.matcher(name).matches()) {
            return OpResult.fail("name 不合法（仅允许小写字母/数字/连字符开头）：" + name);
        }
        if (byName.containsKey(name)) {
            return OpResult.fail("知识包已存在：" + name);
        }
        Path packDir = root.resolve(name + DIR_SUFFIX);
        if (Files.exists(packDir)) {
            return OpResult.fail("目录已存在：" + packDir);
        }
        try {
            Files.createDirectories(packDir);
            Files.writeString(packDir.resolve("KNOWLEDGE.md"), buildScaffoldMd(name, system, description));
            Files.writeString(packDir.resolve("sources.json"), buildScaffoldSourcesJson(system));
        } catch (IOException e) {
            return OpResult.fail("创建知识包失败：" + e.getMessage());
        }

        KnowledgePack pack = KnowledgeLoader.loadOne(packDir);
        if (pack == null) {
            return OpResult.fail("骨架生成后加载失败，请检查 KNOWLEDGE.md：" + packDir);
        }
        byName.put(pack.getName(), pack);
        rebuildOrderAndIndex();
        IndexMdWriter.rewrite(root, new ArrayList<>(byName.values()));
        return OpResult.ok("已生成空骨架：" + packDir);
    }

    /** 删除整个知识包目录。 */
    public synchronized OpResult delete(String name) {
        KnowledgePack pack = byName.get(name);
        if (pack == null) {
            return OpResult.fail("未找到知识包：" + name);
        }
        try {
            deleteRecursively(pack.getDir());
        } catch (IOException e) {
            return OpResult.fail("删除目录失败：" + e.getMessage());
        }
        byName.remove(name);
        index.removePack(name);
        IndexMdWriter.rewrite(root, new ArrayList<>(byName.values()));
        return OpResult.ok("已删除：" + name);
    }

    /** 修改 frontmatter status 字段（draft / reviewed / stale）。 */
    public synchronized OpResult setStatus(String name, String newStatus) {
        if (newStatus == null || newStatus.isBlank()) {
            return OpResult.fail("新 status 不能为空");
        }
        if (!List.of("draft", "reviewed", "stale").contains(newStatus)) {
            return OpResult.fail("status 取值仅允许 draft / reviewed / stale，收到：" + newStatus);
        }
        KnowledgePack pack = byName.get(name);
        if (pack == null) {
            return OpResult.fail("未找到知识包：" + name);
        }
        try {
            String content = Files.readString(pack.getKnowledgeMdPath());
            String updated = replaceFrontmatterStatus(content, newStatus);
            if (updated.equals(content)) {
                return OpResult.fail("未在 frontmatter 中找到 status 字段，已跳过");
            }
            Files.writeString(pack.getKnowledgeMdPath(), updated);
        } catch (IOException e) {
            return OpResult.fail("写入 KNOWLEDGE.md 失败：" + e.getMessage());
        }
        // 重新加载该 pack
        KnowledgePack reloaded = KnowledgeLoader.loadOne(pack.getDir());
        if (reloaded == null) {
            return OpResult.fail("写入后重新加载失败，请检查 KNOWLEDGE.md");
        }
        byName.put(name, reloaded);
        index.addPack(reloaded);
        IndexMdWriter.rewrite(root, new ArrayList<>(byName.values()));
        return OpResult.ok("已更新 status 为 " + newStatus + "：" + name);
    }

    private void rebuildOrderAndIndex() {
        List<KnowledgePack> sorted = byName.values().stream()
                .sorted((a, b) -> a.getName().compareTo(b.getName()))
                .collect(Collectors.toList());
        byName.clear();
        for (KnowledgePack p : sorted) {
            byName.put(p.getName(), p);
        }
        index.rebuild(sorted);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Collections.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (RuntimeException e) {
            if (e.getCause() instanceof IOException io) {
                throw io;
            }
            throw e;
        }
    }

    static String replaceFrontmatterStatus(String content, String newStatus) {
        if (content == null || !content.startsWith("---")) {
            return content;
        }
        int firstNl = content.indexOf('\n');
        if (firstNl < 0) {
            return content;
        }
        int end = content.indexOf("\n---", firstNl);
        if (end < 0) {
            return content;
        }
        String fmBlock = content.substring(firstNl + 1, end);
        String[] lines = fmBlock.split("\n", -1);
        StringBuilder rebuilt = new StringBuilder();
        boolean replaced = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().startsWith("status:")) {
                rebuilt.append("status: ").append(newStatus);
                replaced = true;
            } else {
                rebuilt.append(line);
            }
            if (i < lines.length - 1) {
                rebuilt.append('\n');
            }
        }
        if (!replaced) {
            return content;
        }
        return content.substring(0, firstNl + 1) + rebuilt + content.substring(end);
    }

    static String buildScaffoldMd(String name, String system, String description) {
        String today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);
        String safeSystem = system == null || system.isBlank() ? name : system;
        String safeDesc = description == null ? "" : description;
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("name: ").append(name).append('\n');
        sb.append("system: ").append(safeSystem).append('\n');
        sb.append("description: ").append(safeDesc).append('\n');
        sb.append("generated_at: ").append(today).append('\n');
        sb.append("generator: ").append(GENERATOR_TAG).append('\n');
        sb.append("code_anchors: []\n");
        sb.append("status: draft\n");
        sb.append("---\n\n");
        sb.append("# ").append(safeSystem).append(" 知识包（").append(today).append("）\n\n");
        for (SectionId sid : SectionId.values()) {
            sb.append(sid.standardHeading()).append("\n\n");
            sb.append(scaffoldSectionPlaceholder(sid)).append("\n\n");
        }
        return sb.toString();
    }

    private static String scaffoldSectionPlaceholder(SectionId sid) {
        return switch (sid) {
            case POSITIONING -> "<2–4 句：这个系统解决什么问题、对谁负责、在大图里处于什么位置>";
            case CONCEPTS -> """
                    | 术语 | 定义（≤ 30 字） | 别名 / 缩写 | 出处 |
                    | --- | --- | --- | --- |
                    | `<术语>` | `<定义>` | `<别名>` | `Confluence:pageId` 或 `path/to/file:L12` |""";
            case RELATIONS -> """
                    ```mermaid
                    graph LR
                      A --> B
                    ```

                    <模块/角色图与实体图各 1 张，每张图后写 1–2 句解读>""";
            case FLOWS -> """
                    ### 4.1 <流程名>
                    - 触发：<谁、在什么场景触发>
                    - 前置：<已登录态、配置项、权限>
                    - 步骤：
                      1. <动作>（<出处>）
                    - 后置：<状态变化、副作用、异步任务>
                    - 失败模式：<已知的容易出错点>""";
            case DIFF -> """
                    | 主题 | 文档说法 | 代码实现 | 建议 |
                    | --- | --- | --- | --- |""";
            case PENDING -> "- <还没读完的子文档 / 还没验证的代码模块 / 需要找谁确认的开放问题>";
            case SOURCES -> """
                    - Confluence: pageId=... <title>
                    - Code: <file:line>""";
        };
    }

    static String buildScaffoldSourcesJson(String system) {
        JSONObject root = new JSONObject(true);
        root.put("schema_version", 1);
        root.put("system", system == null ? "" : system);
        root.put("captured_at", LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
        root.put("captured_via", new JSONArray());
        root.put("pages", new JSONArray());
        return JSON.toJSONString(root,
                SerializerFeature.PrettyFormat,
                SerializerFeature.MapSortField);
    }
}
