/**
 * @(#)FunctionManager.java, 4 月 2, 2026.
 * <p>
 * Copyright 2026 chapaof.com. All rights reserved.
 * chapaof.COM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.jiyingda.codly.function;

import com.jiyingda.codly.core.ConversationContext;
import com.jiyingda.codly.data.Tool;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Function 管理器
 * 负责注册和执行所有 tool functions
 */
@SuppressWarnings("unused")
public class FunctionManager {

    private final Map<String, FunctionCallApi> functions = new HashMap<>();
    private final com.jiyingda.codly.mcp.McpRegistry mcp;

    /**
     * 构造函数，初始化默认的 functions
     */
    public FunctionManager() {
        this(com.jiyingda.codly.config.Config.getMcpServers());
    }

    public FunctionManager(com.alibaba.fastjson.JSONObject servers) {
        mcp = new com.jiyingda.codly.mcp.McpRegistry(servers);
        registerFunction(new ReadFileFunctionCall());
        registerFunction(new WriteFileFunctionCall());
        registerFunction(new EditFileFunctionCall());
        registerFunction(new SearchFileFunctionCall());
        registerFunction(new GrepFunctionCall());
        registerFunction(new ListDirectoryFunctionCall());
        registerFunction(new ExecBashFunctionCall());
        registerFunction(new SystemInfoFunctionCall());
        registerFunction(new WebSearchFunctionCall());
        registerFunction(new KbListFunctionCall());
        registerFunction(new KbSearchFunctionCall());
        registerFunction(new KbReadFunctionCall());
        registerFunction(new KbSectionFunctionCall());
        registerFunction(new KbSourcesFunctionCall());
    }

    public List<Tool> getTools() {
        List<Tool> tools = new ArrayList<>();
        functions.values().forEach(function -> tools.add(Tool.createFunction(function)));
        mcp.functions().values().forEach(function -> tools.add(Tool.createFunction(function)));
        return tools;
    }

    public void prepareMcp(ConversationContext context) { mcp.load(context.cancellation(), context::emitTrace); }
    public void close() { mcp.close(); }

    /**
     * 注册一个 Function
     *
     * @param functionCallApi Function 实现类
     */
    public void registerFunction(FunctionCallApi functionCallApi) {
        functions.put(functionCallApi.getName(), functionCallApi);
    }

    /**
     * 获取一个 Function
     *
     * @param name 函数名称
     * @return Function 实现，如果不存在则返回 null
     */
    public FunctionCallApi getFunction(String name) {
        return functions.containsKey(name) ? functions.get(name) : mcp.functions().get(name);
    }

    /**
     * 检查函数是否存在
     *
     * @param name 函数名称
     * @return 是否存在
     */
    public boolean hasFunction(String name) {
        return functions.containsKey(name) || mcp.functions().containsKey(name);
    }

    /**
     * 执行一个函数。敏感操作通过会话的批准策略和界面确认适配器授权。
     *
     * @param functionName 函数名称
     * @param argsJson     函数参数（JSON 格式）
     * @return 执行结果；若用户拒绝则返回拒绝提示
     */
    public String execute(String functionName, String argsJson, ConversationContext ctx) {
        FunctionCallApi functionCallApi = getFunction(functionName);
        if (functionCallApi == null) {
            return "未知的函数：" + functionName;
        }

        if (functionCallApi.requiresConfirmation()) {
            String summary = functionCallApi.confirmationSummary(argsJson);
            boolean allowed = ctx.confirm(functionName, argsJson, summary);
            if (!allowed) {
                return "用户拒绝执行此操作";
            }
        }

        return functionCallApi.execute(argsJson, ctx);
    }

    /**
     * 获取所有注册的 Functions
     *
     * @return Functions 的 Map
     */
    public Map<String, FunctionCallApi> getAllFunctions() {
        Map<String, FunctionCallApi> all = new HashMap<>(functions);
        all.putAll(mcp.functions());
        return all;
    }
}
