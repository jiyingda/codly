package com.jiyingda.codly.knowledge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 倒排索引：token 到 (packName, sectionId) → 加权 TF。
 *
 * <p>分词策略：
 * <ul>
 *   <li>英文/数字按 \W+ 切分，统一小写，长度 &gt;= 2 才入索引</li>
 *   <li>中文按 1-2 gram 字符滑窗（单字 + bigram 都入索引）</li>
 * </ul>
 *
 * <p>权重：frontmatter (name/system/description) ×3、节标题 ×2、概念表术语首列 ×3、正文 ×1。
 */
public class KnowledgeIndex {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeIndex.class);

    private static final Pattern CONCEPT_TERM = Pattern.compile("^\\|\\s*([^|`]+?)\\s*\\|", Pattern.MULTILINE);
    private static final Pattern STOP_HEADING = Pattern.compile("^#{1,6}\\s+", Pattern.MULTILINE);
    private static final Pattern CJK_CHAR = Pattern.compile("[\\u4e00-\\u9fa5]");

    /** PackSection = packName + sectionId（节级粒度）。 */
    public record PackSection(String packName, SectionId sectionId) {
    }

    /** token -> (PackSection -> 加权 TF) */
    private final Map<String, Map<PackSection, Integer>> inverted = new HashMap<>();
    /** packName -> PackSection 列表（用于增量删除） */
    private final Map<String, List<PackSection>> byPack = new HashMap<>();
    /** packName -> SectionId -> 原始正文（用于 snippet 截取） */
    private final Map<String, Map<SectionId, String>> sectionContent = new HashMap<>();
    private int tokenCount = 0;

    public synchronized void clear() {
        inverted.clear();
        byPack.clear();
        sectionContent.clear();
        tokenCount = 0;
    }

    public synchronized int tokenCount() {
        return inverted.size();
    }

    public synchronized int packCount() {
        return byPack.size();
    }

    /** 整库重建：清空后逐个 pack add。 */
    public synchronized void rebuild(List<KnowledgePack> packs) {
        clear();
        for (KnowledgePack pack : packs) {
            addPack(pack);
        }
        logger.info("索引构建完成：{} 个知识包，{} 个 token", packCount(), tokenCount());
    }

    /** 删除某 pack 的所有索引项（包括 token、byPack、sectionContent）。 */
    public synchronized void removePack(String packName) {
        List<PackSection> sections = byPack.remove(packName);
        if (sections == null) {
            return;
        }
        for (Map.Entry<String, Map<PackSection, Integer>> entry : inverted.entrySet()) {
            for (PackSection s : sections) {
                entry.getValue().remove(s);
            }
        }
        inverted.entrySet().removeIf(e -> e.getValue().isEmpty());
        sectionContent.remove(packName);
    }

    /** 增量加入一个 pack。如果已存在同名 pack，先 removePack。 */
    public synchronized void addPack(KnowledgePack pack) {
        if (pack == null) {
            return;
        }
        if (byPack.containsKey(pack.getName())) {
            removePack(pack.getName());
        }
        List<PackSection> sections = new ArrayList<>();

        // frontmatter 层（无 section 归到 POSITIONING 节用于召回）
        PackSection metaKey = new PackSection(pack.getName(), SectionId.POSITIONING);
        addText(metaKey, pack.getName(), 3);
        addText(metaKey, pack.getSystem(), 3);
        addText(metaKey, pack.getDescription(), 3);

        for (Map.Entry<SectionId, String> entry : pack.getSectionMap().entrySet()) {
            SectionId sid = entry.getKey();
            String content = entry.getValue();
            if (content == null || content.isBlank()) {
                continue;
            }
            PackSection ps = new PackSection(pack.getName(), sid);
            sections.add(ps);

            // 节标题
            addText(ps, sid.displayName(), 2);

            // 概念表的术语首列额外加权（只在 CONCEPTS 节）
            if (sid == SectionId.CONCEPTS) {
                Matcher m = CONCEPT_TERM.matcher(content);
                while (m.find()) {
                    String term = m.group(1).trim();
                    // 跳过表头分隔行（"--- | ---"）
                    if (term.matches("[-:\\s]+")) {
                        continue;
                    }
                    addText(ps, term, 3);
                }
            }

            // 正文（去掉行内代码反引号、链接括号干扰，再分词）
            addText(ps, normalizeForIndex(content), 1);
        }

        if (!sections.isEmpty() || hasMeta(metaKey)) {
            byPack.put(pack.getName(), sections.isEmpty() ? List.of(metaKey) : sections);
        }

        // 缓存原文用于 snippet
        Map<SectionId, String> mp = new LinkedHashMap<>();
        for (Map.Entry<SectionId, String> entry : pack.getSectionMap().entrySet()) {
            mp.put(entry.getKey(), entry.getValue());
        }
        sectionContent.put(pack.getName(), mp);

        tokenCount = inverted.size();
    }

    private boolean hasMeta(PackSection key) {
        for (Map<PackSection, Integer> tfs : inverted.values()) {
            if (tfs.containsKey(key)) {
                return true;
            }
        }
        return false;
    }

    private void addText(PackSection ps, String text, int weight) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (String token : tokenize(text)) {
            inverted.computeIfAbsent(token, k -> new HashMap<>())
                    .merge(ps, weight, Integer::sum);
        }
    }

    private static String normalizeForIndex(String content) {
        String out = content.replaceAll("`+", " ");
        out = out.replaceAll("\\[([^\\]]+)\\]\\([^)]+\\)", "$1");
        return out;
    }

    /**
     * 分词：英文/数字按 \W+，中文按 1-2 gram。
     * 公开静态方法，让 KnowledgeSearcher 复用同一规则。
     */
    static List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return tokens;
        }
        // 跳过 markdown H 标题前缀符号但不影响分词
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (CJK_CHAR.matcher(String.valueOf(c)).matches()) {
                if (!buf.isEmpty()) {
                    flushAscii(buf, tokens);
                }
                tokens.add(String.valueOf(c));
                if (i + 1 < text.length()) {
                    char next = text.charAt(i + 1);
                    if (CJK_CHAR.matcher(String.valueOf(next)).matches()) {
                        tokens.add("" + c + next);
                    }
                }
            } else if (Character.isLetterOrDigit(c)) {
                buf.append(Character.toLowerCase(c));
            } else {
                flushAscii(buf, tokens);
            }
        }
        flushAscii(buf, tokens);
        return tokens;
    }

    private static void flushAscii(StringBuilder buf, List<String> tokens) {
        if (buf.length() >= 2) {
            tokens.add(buf.toString());
        }
        buf.setLength(0);
    }

    /** 用于 KnowledgeSearcher：返回某 token 倒排表（不可变快照）。 */
    public synchronized Map<PackSection, Integer> postings(String token) {
        Map<PackSection, Integer> m = inverted.get(token);
        return m == null ? Map.of() : new HashMap<>(m);
    }

    /** 用于 snippet 截取：取某节原始正文。 */
    public synchronized String getSectionContent(String packName, SectionId sid) {
        Map<SectionId, String> m = sectionContent.get(packName);
        return m == null ? "" : m.getOrDefault(sid, "");
    }
}
