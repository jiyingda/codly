package com.jiyingda.codly.function;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.jiyingda.codly.command.CommandContext;
import com.jiyingda.codly.data.Parameters;
import com.jiyingda.codly.knowledge.KnowledgePack;
import com.jiyingda.codly.knowledge.KnowledgeRepository;

import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;
import java.util.Optional;

/**
 * 读取指定知识包的 sources.json 原文，用于追溯原始 Confluence pageId。
 */
@SuppressWarnings("unused")
public class KbSourcesFunctionCall implements FunctionCallApi {

    private static final Parameters PARAMETERS = Parameters.create()
            .addProperty("name", "string", "知识包名（kebab-case）")
            .addRequired("name");

    @Override
    public String getName() {
        return "kb_sources";
    }

    @Override
    public String getDescription() {
        return "读取指定知识包的 sources.json 原文，用于追溯原始 Confluence pageId / URL / 父子层级。"
                + "格式见 skill-kit/extract-system-knowledge templates.md 第 5 节（schema_version=1）。";
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
            if (name == null || name.isBlank()) {
                return "未提供 name 参数";
            }
            Optional<KnowledgePack> pack = KnowledgeRepository.getInstance().find(name);
            if (pack.isEmpty()) {
                return "未找到知识包：" + name;
            }
            if (!Files.isRegularFile(pack.get().getSourcesJsonPath())) {
                return "sources.json 不存在：" + pack.get().getSourcesJsonPath();
            }
            return Files.readString(pack.get().getSourcesJsonPath());
        } catch (IOException e) {
            return "读取 sources.json 失败：" + e.getMessage();
        } catch (Exception e) {
            return "执行 kb_sources 失败：" + e.getMessage();
        }
    }
}
