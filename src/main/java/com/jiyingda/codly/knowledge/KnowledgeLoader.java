package com.jiyingda.codly.knowledge;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 扫描 ~/.codly/knowledge/ 下的 &lt;name&gt;-knowledge/ 目录，加载每个知识包：
 * <ul>
 *   <li>解析 KNOWLEDGE.md frontmatter（白名单字段）</li>
 *   <li>按 H2 标题切分 7 节正文</li>
 *   <li>解析 sources.json（schema_version=1）</li>
 * </ul>
 * 与 skill-kit/extract-system-knowledge 的目录约定 1:1 对齐。
 */
public final class KnowledgeLoader {

    private static final Logger logger = LoggerFactory.getLogger(KnowledgeLoader.class);

    private static final String DEFAULT_ROOT =
            System.getProperty("user.home") + "/.codly/knowledge";

    private static final String KNOWLEDGE_FILE = "KNOWLEDGE.md";
    private static final String SOURCES_FILE = "sources.json";
    private static final String DIR_SUFFIX = "-knowledge";

    /** 合法 name：小写字母/数字/连字符开头。 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[a-z0-9][a-z0-9-]*$");

    /** 任何 H2 标题的位置匹配（用于切分相邻节）。 */
    private static final Pattern ANY_H2 = Pattern.compile("^##\\s+.+$", Pattern.MULTILINE);

    private KnowledgeLoader() {
    }

    public static Path defaultRoot() {
        return Paths.get(DEFAULT_ROOT);
    }

    /** 默认根目录下扫描。 */
    public static List<KnowledgePack> loadAll() {
        return loadAll(defaultRoot());
    }

    /** 指定根目录下扫描，根目录不存在时返回空列表。 */
    public static List<KnowledgePack> loadAll(Path root) {
        List<KnowledgePack> packs = new ArrayList<>();
        if (root == null || !Files.isDirectory(root)) {
            logger.info("knowledges 根目录不存在，跳过：{}", root);
            return packs;
        }
        try (Stream<Path> stream = Files.list(root)) {
            stream.filter(Files::isDirectory).forEach(dir -> {
                KnowledgePack pack = loadOne(dir);
                if (pack != null) {
                    packs.add(pack);
                }
            });
        } catch (IOException e) {
            logger.warn("扫描 knowledges 目录失败：{}", e.getMessage());
        }
        packs.sort((a, b) -> a.getName().compareTo(b.getName()));
        return packs;
    }

    /** 加载单个 &lt;name&gt;-knowledge/ 目录，失败返回 null。 */
    public static KnowledgePack loadOne(Path dir) {
        String dirName = dir.getFileName().toString();
        if (!dirName.endsWith(DIR_SUFFIX)) {
            logger.debug("目录不以 -knowledge 结尾，跳过：{}", dir);
            return null;
        }
        String name = dirName.substring(0, dirName.length() - DIR_SUFFIX.length());
        if (!NAME_PATTERN.matcher(name).matches()) {
            logger.warn("知识包目录名不合法（仅允许小写字母/数字/连字符）：{}", dirName);
            return null;
        }

        Path knowledgeMd = dir.resolve(KNOWLEDGE_FILE);
        if (!Files.isRegularFile(knowledgeMd)) {
            logger.warn("知识包目录缺少 KNOWLEDGE.md，跳过：{}", dir);
            return null;
        }

        String mdContent;
        try {
            mdContent = Files.readString(knowledgeMd);
        } catch (IOException e) {
            logger.warn("读取 KNOWLEDGE.md 失败：{} - {}", knowledgeMd, e.getMessage());
            return null;
        }

        KnowledgeFrontmatterParser.Parsed parsed = KnowledgeFrontmatterParser.parse(mdContent);
        Map<String, String> fm = parsed.scalars();

        String fmName = fm.get("name");
        if (fmName == null || fmName.isBlank()) {
            logger.warn("KNOWLEDGE.md frontmatter 缺少 name 字段，跳过：{}", knowledgeMd);
            return null;
        }
        if (!fmName.equals(name)) {
            logger.warn("frontmatter name({}) 与目录名前缀({}) 不一致，跳过：{}", fmName, name, knowledgeMd);
            return null;
        }
        if (!NAME_PATTERN.matcher(fmName).matches()) {
            logger.warn("frontmatter name 不合法：{} @ {}", fmName, knowledgeMd);
            return null;
        }

        String system = fm.getOrDefault("system", "");
        String description = fm.getOrDefault("description", "");
        String generatedAt = fm.getOrDefault("generated_at", "");
        String generator = fm.getOrDefault("generator", "");
        String status = fm.getOrDefault("status", "draft");

        SectionSplit split = splitSections(parsed.body());

        Path sourcesJson = dir.resolve(SOURCES_FILE);
        List<SourceEntry> sources = loadSources(sourcesJson);

        return new KnowledgePack(
                fmName, system, description, generatedAt, generator, status,
                parsed.codeAnchors(),
                dir, knowledgeMd, sourcesJson,
                parsed.body(), split.preamble, split.sectionMap, sources);
    }

    /**
     * 按 H2 标题把正文切成 7 节；标题前的内容作为 preamble（通常是 H1 标题）。
     */
    static SectionSplit splitSections(String body) {
        Map<SectionId, String> sectionMap = new EnumMap<>(SectionId.class);
        if (body == null || body.isEmpty()) {
            return new SectionSplit("", sectionMap);
        }

        // 先把每个 H2 标题的位置和它对应的 SectionId 找出来
        Matcher headingMatcher = ANY_H2.matcher(body);
        List<int[]> h2Ranges = new ArrayList<>();
        List<String> h2Lines = new ArrayList<>();
        while (headingMatcher.find()) {
            h2Ranges.add(new int[]{headingMatcher.start(), headingMatcher.end()});
            h2Lines.add(body.substring(headingMatcher.start(), headingMatcher.end()));
        }

        if (h2Ranges.isEmpty()) {
            return new SectionSplit(body, sectionMap);
        }

        String preamble = body.substring(0, h2Ranges.get(0)[0]).trim();

        for (int i = 0; i < h2Ranges.size(); i++) {
            String headingLine = h2Lines.get(i);
            SectionId matched = matchSection(headingLine);
            if (matched == null) {
                continue;
            }
            int contentStart = h2Ranges.get(i)[1];
            int contentEnd = (i + 1 < h2Ranges.size()) ? h2Ranges.get(i + 1)[0] : body.length();
            String sectionBody = body.substring(contentStart, contentEnd).trim();
            sectionMap.put(matched, sectionBody);
        }
        return new SectionSplit(preamble, sectionMap);
    }

    private static SectionId matchSection(String headingLine) {
        for (SectionId id : SectionId.values()) {
            if (id.headingPattern().matcher(headingLine).find()) {
                return id;
            }
        }
        return null;
    }

    private static List<SourceEntry> loadSources(Path sourcesJson) {
        if (!Files.isRegularFile(sourcesJson)) {
            return List.of();
        }
        try {
            String content = Files.readString(sourcesJson);
            JSONObject root = JSON.parseObject(content);
            if (root == null) {
                return List.of();
            }
            JSONArray arr = root.getJSONArray("pages");
            if (arr == null || arr.isEmpty()) {
                return List.of();
            }
            List<SourceEntry> list = new ArrayList<>(arr.size());
            for (int i = 0; i < arr.size(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                if (obj == null) {
                    continue;
                }
                list.add(new SourceEntry(
                        obj.getString("pageId"),
                        obj.getString("title"),
                        obj.getString("url"),
                        obj.getString("confluence_updated"),
                        obj.getIntValue("depth"),
                        obj.getString("parent_pageId"),
                        obj.getString("note")));
            }
            return list;
        } catch (Exception e) {
            logger.warn("解析 sources.json 失败：{} - {}", sourcesJson, e.getMessage());
            return List.of();
        }
    }

    /** 节切分中间产物。 */
    record SectionSplit(String preamble, Map<SectionId, String> sectionMap) {
    }

    /** 仅供单元测试或 IndexMdWriter 复用：所有标量字段以原始 key 形式返回（线性顺序）。 */
    public static Map<String, String> readScalarsForTest(Path knowledgeMd) throws IOException {
        String content = Files.readString(knowledgeMd);
        return new LinkedHashMap<>(KnowledgeFrontmatterParser.parse(content).scalars());
    }
}
