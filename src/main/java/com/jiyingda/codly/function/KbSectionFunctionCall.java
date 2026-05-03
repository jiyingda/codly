package com.jiyingda.codly.function;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.jiyingda.codly.command.CommandContext;
import com.jiyingda.codly.data.Parameters;
import com.jiyingda.codly.knowledge.KnowledgePack;
import com.jiyingda.codly.knowledge.KnowledgeRepository;
import com.jiyingda.codly.knowledge.SectionId;

import java.util.Map;
import java.util.Optional;

/**
 * 读取指定知识包的某节正文。
 */
@SuppressWarnings("unused")
public class KbSectionFunctionCall implements FunctionCallApi {

    private static final Parameters PARAMETERS = Parameters.create()
            .addProperty("name", "string", "知识包名（kebab-case）")
            .addProperty("section", "string",
                    "节标识，取值：positioning（系统定位）/ concepts（概念表）/ relations（系统关系）"
                            + "/ flows（核心流程）/ diff（文档 vs 代码差异）/ pending（未覆盖）/ sources（出处索引）")
            .addRequired("name")
            .addRequired("section");

    @Override
    public String getName() {
        return "kb_section";
    }

    @Override
    public String getDescription() {
        return "读取指定知识包的指定节正文，节级粒度精读，比 kb_read 更省 context。"
                + "section 取值见参数说明。";
    }

    @Override
    public Parameters getParameters() {
        return PARAMETERS;
    }

    @Override
    public String execute(String argsJson, CommandContext ctx) {
        try {
            Map<String, Object> args = JSON.parseObject(argsJson, new TypeReference<>() {});
            if (args == null) {
                return "参数解析失败";
            }
            String name = args.get("name") == null ? null : String.valueOf(args.get("name")).trim();
            String slug = args.get("section") == null ? null : String.valueOf(args.get("section")).trim();
            if (name == null || name.isBlank()) {
                return "未提供 name 参数";
            }
            if (slug == null || slug.isBlank()) {
                return "未提供 section 参数";
            }

            Optional<SectionId> sid = SectionId.fromSlug(slug);
            if (sid.isEmpty()) {
                return "section 取值不合法：" + slug
                        + "（可用：positioning / concepts / relations / flows / diff / pending / sources）";
            }
            Optional<KnowledgePack> pack = KnowledgeRepository.getInstance().find(name);
            if (pack.isEmpty()) {
                return "未找到知识包：" + name;
            }
            String body = pack.get().getSection(sid.get());
            if (body == null || body.isBlank()) {
                return "该节为空或缺失：" + name + " / " + slug;
            }
            return sid.get().standardHeading() + "\n\n" + body;
        } catch (Exception e) {
            return "执行 kb_section 失败：" + e.getMessage();
        }
    }
}
