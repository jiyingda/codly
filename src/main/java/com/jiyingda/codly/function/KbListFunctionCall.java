package com.jiyingda.codly.function;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.jiyingda.codly.command.CommandContext;
import com.jiyingda.codly.data.Parameters;
import com.jiyingda.codly.knowledge.KnowledgePack;
import com.jiyingda.codly.knowledge.KnowledgeRepository;

import java.util.List;

/**
 * 列出当前已加载的全部知识包（仅元数据，不含正文）。
 */
@SuppressWarnings("unused")
public class KbListFunctionCall implements FunctionCallApi {

    private static final Parameters PARAMETERS = Parameters.create();

    @Override
    public String getName() {
        return "kb_list";
    }

    @Override
    public String getDescription() {
        return "列出 ~/.codly/knowledge/ 下所有已加载的系统知识包，返回 name/system/description/status/sourcePageCount，"
                + "用于在不读正文的前提下筛选相关包，再用 kb_search 或 kb_section 进一步检索。";
    }

    @Override
    public Parameters getParameters() {
        return PARAMETERS;
    }

    @Override
    public String execute(String argsJson, CommandContext ctx) {
        List<KnowledgePack> packs = KnowledgeRepository.getInstance().all();
        if (packs.isEmpty()) {
            return "（暂无已加载的知识包，可让用户用 /kb scaffold <name> \"<system>\" 生成）";
        }
        JSONArray arr = new JSONArray();
        for (KnowledgePack pack : packs) {
            JSONObject obj = new JSONObject(true);
            obj.put("name", pack.getName());
            obj.put("system", pack.getSystem());
            obj.put("description", pack.getDescription());
            obj.put("status", pack.getStatus());
            obj.put("generatedAt", pack.getGeneratedAt());
            obj.put("sourcePageCount", pack.getSources().size());
            arr.add(obj);
        }
        return JSON.toJSONString(arr, SerializerFeature.PrettyFormat);
    }
}
