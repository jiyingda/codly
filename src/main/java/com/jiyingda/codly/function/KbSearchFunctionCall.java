package com.jiyingda.codly.function;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.TypeReference;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.jiyingda.codly.command.CommandContext;
import com.jiyingda.codly.data.Parameters;
import com.jiyingda.codly.knowledge.KnowledgeRepository;
import com.jiyingda.codly.knowledge.KnowledgeSearcher;

import java.util.List;
import java.util.Map;

/**
 * 跨所有知识包做关键词检索，命中结果按节级粒度返回（含 snippet）。
 */
@SuppressWarnings("unused")
public class KbSearchFunctionCall implements FunctionCallApi {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;

    private static final Parameters PARAMETERS = Parameters.create()
            .addProperty("query", "string", "要搜索的关键词，可包含中文与英文词")
            .addProperty("limit", "integer", "返回的最大命中数，默认 5、上限 20")
            .addRequired("query");

    @Override
    public String getName() {
        return "kb_search";
    }

    @Override
    public String getDescription() {
        return "在所有已加载的知识包中按关键词检索，命中粒度为节（positioning/concepts/relations/flows/diff/pending/sources），"
                + "返回 packName / section / score / snippet。需要看完整内容时再用 kb_section 或 kb_read。";
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
            String query = asString(args.get("query"));
            if (query == null || query.isBlank()) {
                return "未提供 query 参数";
            }
            int limit = asInt(args.get("limit"), DEFAULT_LIMIT);
            if (limit <= 0) limit = DEFAULT_LIMIT;
            if (limit > MAX_LIMIT) limit = MAX_LIMIT;

            List<KnowledgeSearcher.Hit> hits = KnowledgeRepository.getInstance().search(query, limit);
            if (hits.isEmpty()) {
                return "未命中任何知识包，可尝试更宽泛的关键词或先调用 kb_list 看可用主题。";
            }
            JSONArray arr = new JSONArray();
            for (KnowledgeSearcher.Hit hit : hits) {
                JSONObject obj = new JSONObject(true);
                obj.put("packName", hit.packName());
                obj.put("section", hit.sectionId().slug());
                obj.put("score", hit.score());
                obj.put("snippet", hit.snippet());
                arr.add(obj);
            }
            return JSON.toJSONString(arr, SerializerFeature.PrettyFormat);
        } catch (Exception e) {
            return "执行 kb_search 失败：" + e.getMessage();
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static int asInt(Object value, int defaultValue) {
        if (value == null) return defaultValue;
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }
}
