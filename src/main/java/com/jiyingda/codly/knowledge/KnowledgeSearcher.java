package com.jiyingda.codly.knowledge;

import com.jiyingda.codly.knowledge.KnowledgeIndex.PackSection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 在 KnowledgeIndex 上做关键字检索 + TF 加权打分 + snippet 截取。
 */
public class KnowledgeSearcher {

    private static final int SNIPPET_RADIUS = 80;

    private final KnowledgeIndex index;

    public KnowledgeSearcher(KnowledgeIndex index) {
        this.index = index;
    }

    /** 检索结果。 */
    public record Hit(String packName, SectionId sectionId, int score, String snippet) {
    }

    /**
     * 按 query 命中的 (pack, section) 维度返回 top-N 结果。
     */
    public List<Hit> search(String query, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        List<String> tokens = KnowledgeIndex.tokenize(query);
        if (tokens.isEmpty()) {
            return List.of();
        }

        Map<PackSection, Integer> scores = new HashMap<>();
        for (String token : tokens) {
            Map<PackSection, Integer> postings = index.postings(token);
            for (Map.Entry<PackSection, Integer> entry : postings.entrySet()) {
                scores.merge(entry.getKey(), entry.getValue(), Integer::sum);
            }
        }
        if (scores.isEmpty()) {
            return List.of();
        }

        List<Hit> hits = new ArrayList<>(scores.size());
        for (Map.Entry<PackSection, Integer> entry : scores.entrySet()) {
            PackSection ps = entry.getKey();
            String snippet = makeSnippet(ps.packName(), ps.sectionId(), tokens);
            hits.add(new Hit(ps.packName(), ps.sectionId(), entry.getValue(), snippet));
        }
        hits.sort(Comparator.comparingInt(Hit::score).reversed()
                .thenComparing(h -> h.packName())
                .thenComparing(h -> h.sectionId().slug()));
        if (limit > 0 && hits.size() > limit) {
            return hits.subList(0, limit);
        }
        return hits;
    }

    /**
     * 在节正文中找到任一 query token 第一次命中的位置，截取前后 SNIPPET_RADIUS 字符的片段。
     */
    private String makeSnippet(String packName, SectionId sid, List<String> tokens) {
        String content = index.getSectionContent(packName, sid);
        if (content == null || content.isEmpty()) {
            return "";
        }
        String lower = content.toLowerCase();
        int hit = -1;
        for (String token : tokens) {
            int idx = lower.indexOf(token.toLowerCase());
            if (idx >= 0 && (hit < 0 || idx < hit)) {
                hit = idx;
            }
        }
        if (hit < 0) {
            return content.length() <= SNIPPET_RADIUS * 2
                    ? oneLine(content)
                    : oneLine(content.substring(0, SNIPPET_RADIUS * 2)) + "...";
        }
        int start = Math.max(0, hit - SNIPPET_RADIUS);
        int end = Math.min(content.length(), hit + SNIPPET_RADIUS);
        String prefix = start == 0 ? "" : "...";
        String suffix = end == content.length() ? "" : "...";
        return prefix + oneLine(content.substring(start, end)) + suffix;
    }

    private static String oneLine(String text) {
        return text.replaceAll("\\s+", " ").trim();
    }
}
