package com.jiyingda.codly.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * KNOWLEDGE.md frontmatter 解析器。
 * 严格按 skill-kit/templates.md 第 4.1 节的 7 字段白名单：
 * name / system / description / generated_at / generator / code_anchors[] / status
 *
 * <p>不引入 snakeyaml，仅支持以下两种形式：
 * <ul>
 *   <li>平面 key: value（与 SkillLoader 一致，可去除前后引号）</li>
 *   <li>code_anchors: [] 或 code_anchors: 后跟 "- path: ...\n  note: ..." 列表</li>
 * </ul>
 * 多余字段一律 warn 跳过。
 */
public final class KnowledgeFrontmatterParser {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeFrontmatterParser.class);

    private static final Set<String> ALLOWED_KEYS = Set.of(
            "name", "system", "description", "generated_at", "generator", "code_anchors", "status");

    private KnowledgeFrontmatterParser() {
    }

    /**
     * 解析整篇 KNOWLEDGE.md 文本，分离 frontmatter 与正文。
     */
    public static Parsed parse(String content) {
        if (content == null || !content.startsWith("---")) {
            return new Parsed(new LinkedHashMap<>(), List.of(), content == null ? "" : content);
        }
        int firstNl = content.indexOf('\n');
        if (firstNl < 0) {
            return new Parsed(new LinkedHashMap<>(), List.of(), "");
        }
        int end = content.indexOf("\n---", firstNl);
        if (end < 0) {
            return new Parsed(new LinkedHashMap<>(), List.of(), content);
        }
        String block = content.substring(firstNl + 1, end);
        int afterEnd = content.indexOf('\n', end + 1);
        String body = afterEnd < 0 ? "" : content.substring(afterEnd + 1);

        Map<String, String> scalars = new LinkedHashMap<>();
        List<CodeAnchor> anchors = new ArrayList<>();
        parseBlock(block, scalars, anchors);
        return new Parsed(scalars, anchors, body);
    }

    private static void parseBlock(String block, Map<String, String> scalars, List<CodeAnchor> anchors) {
        String[] lines = block.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String raw = lines[i];
            String trimmed = raw.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                i++;
                continue;
            }

            int colon = trimmed.indexOf(':');
            if (colon <= 0) {
                logger.warn("frontmatter 行无法解析，跳过：{}", trimmed);
                i++;
                continue;
            }
            String key = trimmed.substring(0, colon).trim();
            String value = trimmed.substring(colon + 1).trim();

            if (!ALLOWED_KEYS.contains(key)) {
                logger.warn("frontmatter 出现非白名单字段，已忽略：{}", key);
                i++;
                continue;
            }

            if ("code_anchors".equals(key)) {
                if ("[]".equals(value)) {
                    i++;
                    continue;
                }
                if (!value.isEmpty()) {
                    logger.warn("code_anchors 行尾出现非空标量值（仅支持 [] 或换行后列表），忽略：{}", value);
                }
                i = parseCodeAnchors(lines, i + 1, anchors);
                continue;
            }

            scalars.put(key, stripQuotes(value));
            i++;
        }
    }

    /**
     * 从 startIdx 起解析 code_anchors 列表，遇到下一个顶层 key 时停止。
     * 支持的列表项形式：
     * <pre>
     *   - path: backend/api
     *     note: focus 列表页接口
     *   - path: backend/cms
     * </pre>
     *
     * @return 第一个非列表项行的索引
     */
    private static int parseCodeAnchors(String[] lines, int startIdx, List<CodeAnchor> anchors) {
        String currentPath = null;
        String currentNote = null;
        int i = startIdx;
        while (i < lines.length) {
            String raw = lines[i];
            if (raw.isBlank()) {
                i++;
                continue;
            }
            // 遇到顶层 key（无前导空格 + 含 ':'），停止
            if (!Character.isWhitespace(raw.charAt(0)) && raw.contains(":") && !raw.trim().startsWith("-")) {
                break;
            }
            String trimmed = raw.trim();
            if (trimmed.startsWith("-")) {
                if (currentPath != null) {
                    anchors.add(CodeAnchor.of(currentPath, currentNote));
                    currentNote = null;
                }
                String afterDash = trimmed.substring(1).trim();
                if (afterDash.isEmpty()) {
                    currentPath = "";
                } else {
                    int colon = afterDash.indexOf(':');
                    if (colon > 0) {
                        String k = afterDash.substring(0, colon).trim();
                        String v = stripQuotes(afterDash.substring(colon + 1).trim());
                        if ("path".equals(k)) {
                            currentPath = v;
                        } else if ("note".equals(k)) {
                            currentNote = v;
                        } else {
                            logger.warn("code_anchors 项中出现未知字段：{}", k);
                        }
                    }
                }
                i++;
                continue;
            }
            int colon = trimmed.indexOf(':');
            if (colon > 0) {
                String k = trimmed.substring(0, colon).trim();
                String v = stripQuotes(trimmed.substring(colon + 1).trim());
                if ("path".equals(k)) {
                    currentPath = v;
                } else if ("note".equals(k)) {
                    currentNote = v;
                } else {
                    logger.warn("code_anchors 项中出现未知字段：{}", k);
                }
            }
            i++;
        }
        if (currentPath != null) {
            anchors.add(CodeAnchor.of(currentPath, currentNote));
        }
        return i;
    }

    private static String stripQuotes(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() >= 2
                && ((value.startsWith("\"") && value.endsWith("\""))
                || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** 解析结果：scalar 字段、code_anchors 列表、frontmatter 之后的正文。 */
    public record Parsed(Map<String, String> scalars, List<CodeAnchor> codeAnchors, String body) {
    }
}
