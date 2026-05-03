package com.jiyingda.codly.knowledge;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * KNOWLEDGE.md 正文的 7 个标准节，与 skill-kit/extract-system-knowledge 的 templates.md 第 4.2 节对齐。
 * 每个枚举携带：模型/CLI 使用的标识符、人类可读的中文名、以及匹配 H2 标题的宽松正则。
 */
public enum SectionId {
    POSITIONING("positioning", "系统定位", 1),
    CONCEPTS("concepts", "概念表", 2),
    RELATIONS("relations", "系统关系", 3),
    FLOWS("flows", "核心流程", 4),
    DIFF("diff", "文档 vs 代码差异", 5),
    PENDING("pending", "未覆盖与后续问题", 6),
    SOURCES("sources", "出处索引", 7);

    private final String slug;
    private final String displayName;
    private final int order;
    private final Pattern headingPattern;

    SectionId(String slug, String displayName, int order) {
        this.slug = slug;
        this.displayName = displayName;
        this.order = order;
        // 宽松匹配："## 1. 系统定位" / "## 1.系统定位" / "## 系统定位"，
        // 也容忍中英文混排或空白差异。文档 vs 代码 节里的 vs 还要兼容 vs/VS/对比。
        String namePart = switch (slug) {
            case "diff" -> "(?:文档\\s*[vV][sS]\\s*代码差异|文档与代码差异|文档对比代码差异)";
            case "pending" -> "(?:未覆盖与后续问题|未覆盖|未覆盖问题)";
            default -> Pattern.quote(displayName);
        };
        this.headingPattern = Pattern.compile(
                "^##\\s*(?:" + order + "\\s*\\.?\\s*)?" + namePart + "\\s*$",
                Pattern.MULTILINE);
    }

    public String slug() {
        return slug;
    }

    public String displayName() {
        return displayName;
    }

    public int order() {
        return order;
    }

    public Pattern headingPattern() {
        return headingPattern;
    }

    /** 生成空骨架时使用的标准 H2 标题。 */
    public String standardHeading() {
        return "## " + order + ". " + displayName;
    }

    public static Optional<SectionId> fromSlug(String slug) {
        if (slug == null) {
            return Optional.empty();
        }
        String normalized = slug.trim().toLowerCase();
        for (SectionId s : values()) {
            if (s.slug.equals(normalized)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }
}
