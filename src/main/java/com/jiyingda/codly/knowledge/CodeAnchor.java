package com.jiyingda.codly.knowledge;

/**
 * KNOWLEDGE.md frontmatter 中的 code_anchors[] 列表项，对齐 skill-kit/templates.md 第 4.1 节。
 *
 * @param path 项目内代码路径（如 backend/api/focus）
 * @param note 该路径的简短说明（可空）
 */
public record CodeAnchor(String path, String note) {

    public static CodeAnchor of(String path, String note) {
        return new CodeAnchor(path == null ? "" : path.trim(),
                note == null ? "" : note.trim());
    }
}
