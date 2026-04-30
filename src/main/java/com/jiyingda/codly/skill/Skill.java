package com.jiyingda.codly.skill;

import java.nio.file.Path;
import java.util.Map;

/**
 * 一个 skill 对应 ~/.codly/skills/&lt;name&gt;/SKILL.md 文件。
 */
public class Skill {

    private final String name;
    private final String description;
    private final String body;
    private final Path dir;
    private final Map<String, String> frontmatter;

    public Skill(String name, String description, String body, Path dir, Map<String, String> frontmatter) {
        this.name = name;
        this.description = description;
        this.body = body;
        this.dir = dir;
        this.frontmatter = frontmatter;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getBody() {
        return body;
    }

    public Path getDir() {
        return dir;
    }

    public Map<String, String> getFrontmatter() {
        return frontmatter;
    }

    /**
     * 用作系统提示词目录项:简短一行。
     */
    public String toCatalogLine() {
        return "- " + name + ": " + description;
    }

    /**
     * 用作系统提示词完整正文段:激活时注入。
     */
    public String toFullPromptSection() {
        return "## Skill: " + name + "\n" + body;
    }
}