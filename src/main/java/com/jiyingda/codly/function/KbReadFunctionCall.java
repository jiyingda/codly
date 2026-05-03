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
 * 读取指定知识包的 KNOWLEDGE.md 全文（含 frontmatter）。
 * 文件超过 32KB 时报错，提示改用 kb_section。
 */
@SuppressWarnings("unused")
public class KbReadFunctionCall implements FunctionCallApi {

    private static final long MAX_BYTES = 32 * 1024L;

    private static final Parameters PARAMETERS = Parameters.create()
            .addProperty("name", "string", "知识包名（kebab-case，目录前缀，不含 -knowledge 后缀）")
            .addRequired("name");

    @Override
    public String getName() {
        return "kb_read";
    }

    @Override
    public String getDescription() {
        return "读取指定知识包的 KNOWLEDGE.md 全文（含 frontmatter + 7 节正文）。"
                + "文件 > 32KB 时会拒绝，请改用 kb_section 按节读取（section 取值：positioning/concepts/relations/flows/diff/pending/sources）。";
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
                return "未找到知识包：" + name + "（可先用 kb_list 列出全部）";
            }
            long size = Files.size(pack.get().getKnowledgeMdPath());
            if (size > MAX_BYTES) {
                return "KNOWLEDGE.md 过大（" + size + " bytes，限 " + MAX_BYTES
                        + " bytes），请改用 kb_section 按节读取。";
            }
            return Files.readString(pack.get().getKnowledgeMdPath());
        } catch (IOException e) {
            return "读取 KNOWLEDGE.md 失败：" + e.getMessage();
        } catch (Exception e) {
            return "执行 kb_read 失败：" + e.getMessage();
        }
    }
}
