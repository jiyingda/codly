package com.jiyingda.codly.knowledge;

import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 一个知识包 = ~/.codly/knowledge/&lt;name&gt;-knowledge/ 目录。
 * 对齐 skill-kit/templates.md 第 4 节：frontmatter 7 字段白名单 + 正文 7 节固定结构。
 */
public class KnowledgePack {

    private final String name;
    private final String system;
    private final String description;
    private final String generatedAt;
    private final String generator;
    private final String status;
    private final List<CodeAnchor> codeAnchors;

    private final Path dir;
    private final Path knowledgeMdPath;
    private final Path sourcesJsonPath;

    private final String rawBody;
    private final String preamble;
    private final Map<SectionId, String> sectionMap;
    private final List<SourceEntry> sources;

    public KnowledgePack(
            String name,
            String system,
            String description,
            String generatedAt,
            String generator,
            String status,
            List<CodeAnchor> codeAnchors,
            Path dir,
            Path knowledgeMdPath,
            Path sourcesJsonPath,
            String rawBody,
            String preamble,
            Map<SectionId, String> sectionMap,
            List<SourceEntry> sources) {
        this.name = name;
        this.system = system;
        this.description = description;
        this.generatedAt = generatedAt;
        this.generator = generator;
        this.status = status;
        this.codeAnchors = codeAnchors == null ? List.of() : List.copyOf(codeAnchors);
        this.dir = dir;
        this.knowledgeMdPath = knowledgeMdPath;
        this.sourcesJsonPath = sourcesJsonPath;
        this.rawBody = rawBody == null ? "" : rawBody;
        this.preamble = preamble == null ? "" : preamble;
        this.sectionMap = sectionMap == null ? new EnumMap<>(SectionId.class) : new EnumMap<>(sectionMap);
        this.sources = sources == null ? List.of() : List.copyOf(sources);
    }

    public String getName() {
        return name;
    }

    public String getSystem() {
        return system;
    }

    public String getDescription() {
        return description;
    }

    public String getGeneratedAt() {
        return generatedAt;
    }

    public String getGenerator() {
        return generator;
    }

    public String getStatus() {
        return status;
    }

    public List<CodeAnchor> getCodeAnchors() {
        return codeAnchors;
    }

    public Path getDir() {
        return dir;
    }

    public Path getKnowledgeMdPath() {
        return knowledgeMdPath;
    }

    public Path getSourcesJsonPath() {
        return sourcesJsonPath;
    }

    public String getRawBody() {
        return rawBody;
    }

    public String getPreamble() {
        return preamble;
    }

    public Map<SectionId, String> getSectionMap() {
        return Collections.unmodifiableMap(sectionMap);
    }

    public List<SourceEntry> getSources() {
        return sources;
    }

    /** 返回某节正文，若该节缺失则空串。 */
    public String getSection(SectionId id) {
        return sectionMap.getOrDefault(id, "");
    }

    /** 系统提示词目录段使用的简短一行。 */
    public String toCatalogLine() {
        StringBuilder sb = new StringBuilder("- ");
        sb.append(name);
        if (status != null && !status.isBlank()) {
            sb.append(" [").append(status).append("]");
        }
        if (system != null && !system.isBlank()) {
            sb.append(" · ").append(system);
        }
        if (description != null && !description.isBlank()) {
            sb.append(": ").append(description);
        }
        return sb.toString();
    }
}
