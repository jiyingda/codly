package com.jiyingda.codly.skill;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 全局 skill 注册表,启动时加载一次。
 */
public class SkillRegistry {

    private static final Logger logger = LoggerFactory.getLogger(SkillRegistry.class);

    private static SkillRegistry instance;

    private final Map<String, Skill> byName = new LinkedHashMap<>();
    private boolean loaded = false;

    private SkillRegistry() {}

    public static synchronized SkillRegistry getInstance() {
        if (instance == null) {
            instance = new SkillRegistry();
        }
        return instance;
    }

    public synchronized void load() {
        byName.clear();
        List<Skill> list = SkillLoader.loadAll();
        for (Skill s : list) {
            byName.put(s.getName(), s);
        }
        loaded = true;
        logger.info("已加载 {} 个 skill", byName.size());
    }

    public synchronized List<Skill> all() {
        return List.copyOf(byName.values());
    }

    public synchronized Optional<Skill> findByName(String name) {
        if (name == null) return Optional.empty();
        return Optional.ofNullable(byName.get(name));
    }

    public synchronized boolean isEmpty() {
        return byName.isEmpty();
    }

    public synchronized int size() {
        return byName.size();
    }

    public boolean isLoaded() {
        return loaded;
    }

    /**
     * 常驻系统提示词段:列出所有 skill 的 name/description,帮助模型知道可激活的能力。
     * 没有 skill 时返回空串。
     */
    public synchronized String toCatalogSection() {
        if (byName.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("\n\n## Available Skills\n");
        sb.append("以下 skill 当前已装载,用户可通过 `/skill-<name>` 激活对应能力:\n");
        for (Skill s : byName.values()) {
            sb.append(s.toCatalogLine()).append("\n");
        }
        return sb.toString();
    }

    /** 仅用于测试 */
    static synchronized void resetForTest() {
        instance = null;
    }

    /** 仅用于测试 */
    synchronized void registerForTest(Skill s) {
        byName.put(s.getName(), s);
        loaded = true;
    }

    public synchronized Map<String, Skill> snapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(byName));
    }
}