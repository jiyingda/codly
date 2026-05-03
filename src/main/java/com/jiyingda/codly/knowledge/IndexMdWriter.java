package com.jiyingda.codly.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 顶层 INDEX.md 维护器。
 * 按 skill-kit/templates.md 第 6 节模板写入，按 name 唯一 upsert。
 *
 * 表格列：知识包 / 系统 / 生成时间 / 来源页数 / 状态
 */
public final class IndexMdWriter {

    private static final Logger logger = LoggerFactory.getLogger(IndexMdWriter.class);

    private static final String FILE_NAME = "INDEX.md";
    private static final String HEADER = """
            # Knowledges Index

            | 知识包 | 系统 | 生成时间 | 来源页数 | 状态 |
            | --- | --- | --- | --- | --- |
            """;

    private IndexMdWriter() {
    }

    /**
     * 用当前内存中所有知识包重写 INDEX.md（reconcile）。
     * 这种全量重写比"加载已有 + 单条 upsert"更稳，避免行残留。
     */
    public static void rewrite(Path root, List<KnowledgePack> packs) {
        if (root == null) {
            return;
        }
        try {
            if (!Files.exists(root)) {
                Files.createDirectories(root);
            }
            Path indexFile = root.resolve(FILE_NAME);
            StringBuilder sb = new StringBuilder(HEADER);
            for (KnowledgePack pack : packs) {
                sb.append(rowOf(pack)).append('\n');
            }
            Files.writeString(indexFile, sb.toString());
            logger.debug("已重写 INDEX.md：{}", indexFile);
        } catch (IOException e) {
            logger.warn("写入 INDEX.md 失败：{}", e.getMessage());
        }
    }

    /** 读取 INDEX.md 当前内容（不存在时返回空串），主要用于 /kb 命令直接打印。 */
    public static String read(Path root) {
        if (root == null) {
            return "";
        }
        Path indexFile = root.resolve(FILE_NAME);
        if (!Files.isRegularFile(indexFile)) {
            return "";
        }
        try {
            return Files.readString(indexFile);
        } catch (IOException e) {
            logger.warn("读取 INDEX.md 失败：{}", e.getMessage());
            return "";
        }
    }

    private static String rowOf(KnowledgePack pack) {
        String name = nullToDash(pack.getName());
        String linkPath = pack.getName() + "-knowledge/KNOWLEDGE.md";
        String system = nullToDash(pack.getSystem());
        String generatedAt = nullToDash(pack.getGeneratedAt());
        int pageCount = pack.getSources().size();
        String status = nullToDash(pack.getStatus());
        return String.format("| [%s](%s) | %s | %s | %d | %s |",
                name, linkPath, system, generatedAt, pageCount, status);
    }

    private static String nullToDash(String s) {
        return (s == null || s.isBlank()) ? "-" : s;
    }

    /**
     * 仅供测试：解析现有 INDEX.md 行成 Map（key=知识包名）。
     */
    static Map<String, String> parseRowsForTest(String content) {
        Map<String, String> rows = new LinkedHashMap<>();
        if (content == null || content.isEmpty()) {
            return rows;
        }
        List<String> lines = new ArrayList<>(List.of(content.split("\n")));
        for (String line : lines) {
            if (line.startsWith("| [") && line.contains("](") && line.contains("-knowledge/KNOWLEDGE.md)")) {
                int nameStart = line.indexOf("[") + 1;
                int nameEnd = line.indexOf("]", nameStart);
                if (nameStart > 0 && nameEnd > nameStart) {
                    rows.put(line.substring(nameStart, nameEnd), line);
                }
            }
        }
        return rows;
    }
}
