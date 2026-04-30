package com.jiyingda.codly.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 扫描 ~/.codly/skills/,解析每个子目录下的 SKILL.md。
 */
public class SkillLoader {

    private static final Logger logger = LoggerFactory.getLogger(SkillLoader.class);

    private static final String SKILLS_DIR =
            System.getProperty("user.home") + "/.codly/skills";
    private static final String SKILL_FILE_NAME = "SKILL.md";

    /** 合法 skill 名:小写字母、数字、连字符 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]*$");

    public static Path skillsRoot() {
        return Paths.get(SKILLS_DIR);
    }

    public static List<Skill> loadAll() {
        Path root = skillsRoot();
        List<Skill> skills = new ArrayList<>();

        if (!Files.isDirectory(root)) {
            logger.info("skills 目录不存在,跳过加载: {}", root);
            return skills;
        }

        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                Skill s = loadOne(dir);
                if (s != null) {
                    skills.add(s);
                }
            });
        } catch (IOException e) {
            logger.warn("扫描 skills 目录失败: {}", e.getMessage());
        }

        skills.sort((a, b) -> a.getName().compareTo(b.getName()));
        return skills;
    }

    private static Skill loadOne(Path dir) {
        Path skillFile = dir.resolve(SKILL_FILE_NAME);
        if (!Files.isRegularFile(skillFile)) {
            logger.warn("skill 目录缺少 SKILL.md,跳过: {}", dir);
            return null;
        }

        String content;
        try {
            content = Files.readString(skillFile);
        } catch (IOException e) {
            logger.warn("读取 {} 失败: {}", skillFile, e.getMessage());
            return null;
        }

        Parsed parsed = parseFrontmatter(content);
        if (parsed.frontmatter.isEmpty()) {
            logger.warn("skill 缺少 frontmatter,跳过: {}", skillFile);
            return null;
        }

        String name = parsed.frontmatter.get("name");
        String description = parsed.frontmatter.get("description");

        if (name == null || name.isBlank()) {
            logger.warn("skill 缺少 name 字段,跳过: {}", skillFile);
            return null;
        }
        if (description == null || description.isBlank()) {
            logger.warn("skill 缺少 description 字段,跳过: {}", skillFile);
            return null;
        }
        if (!NAME_PATTERN.matcher(name).matches()) {
            logger.warn("skill name 不合法(仅允许小写字母/数字/连字符): {} @ {}", name, skillFile);
            return null;
        }

        String dirName = dir.getFileName().toString();
        if (!name.equals(dirName)) {
            logger.warn("skill name({}) 与目录名({}) 不一致,跳过: {}", name, dirName, skillFile);
            return null;
        }

        return new Skill(name, description.trim(), parsed.body, dir, parsed.frontmatter);
    }

    /**
     * 最小化的 YAML frontmatter 解析:只支持 `key: value` 单行键值对。
     * 不依赖 snakeyaml,足以处理 name/description/allowed-tools 等平面字段。
     */
    static Parsed parseFrontmatter(String content) {
        Map<String, String> fm = new LinkedHashMap<>();
        String body = content;

        if (!content.startsWith("---")) {
            return new Parsed(fm, body);
        }

        int firstNl = content.indexOf('\n');
        if (firstNl < 0) {
            return new Parsed(fm, body);
        }

        int end = content.indexOf("\n---", firstNl);
        if (end < 0) {
            return new Parsed(fm, body);
        }

        String block = content.substring(firstNl + 1, end);
        int afterEnd = content.indexOf('\n', end + 1);
        body = afterEnd < 0 ? "" : content.substring(afterEnd + 1);

        for (String raw : block.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            // 去掉可能的引号
            if ((value.startsWith("\"") && value.endsWith("\"") && value.length() >= 2)
                    || (value.startsWith("'") && value.endsWith("'") && value.length() >= 2)) {
                value = value.substring(1, value.length() - 1);
            }
            fm.put(key, value);
        }

        return new Parsed(fm, body);
    }

    static class Parsed {
        final Map<String, String> frontmatter;
        final String body;

        Parsed(Map<String, String> frontmatter, String body) {
            this.frontmatter = frontmatter;
            this.body = body;
        }
    }
}