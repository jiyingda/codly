/**
 * @(#)FunctionManager.java, 4 月 2, 2026.
 * <p>
 * Copyright 2026 chapaof.com. All rights reserved.
 * chapaof.COM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.jiyingda.codly.function;

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
    private final List<Tool> tools = new ArrayList<>();

    /**
     * 构造函数，初始化默认的 functions
     */
    public FunctionManager() {
        registerFunction(new ReadFileFunctionCall());
        registerFunction(new SearchFileFunctionCall());
        registerFunction(new ExecBashFunctionCall());
        for (FunctionCallApi functionCall : functions.values()) {
            tools.add(Tool.createFunction(functionCall));
        }
    }

    public List<Tool> getTools() {
        return new ArrayList<>(tools);
    }

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
        return functions.get(name);
    }

    /**
     * 检查函数是否存在
     *
     * @param name 函数名称
     * @return 是否存在
     */
    public boolean hasFunction(String name) {
        return functions.containsKey(name);
    }

    /**
     * 执行一个函数
     *
     * @param functionName 函数名称
     * @param argsJson     函数参数（JSON 格式）
     * @return 执行结果
     */
    public String execute(String functionName, String argsJson) {
        FunctionCallApi functionCallApi = getFunction(functionName);
        if (functionCallApi == null) {
            return "未知的函数：" + functionName;
        }
        return functionCallApi.execute(argsJson);
    }

    /**
     * 获取所有注册的 Functions
     *
     * @return Functions 的 Map
     */
    public Map<String, FunctionCallApi> getAllFunctions() {
        return new HashMap<>(functions);
    }
}

