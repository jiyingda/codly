/**
 * @(#)FunctionManager.java, 4 月 2, 2026.
 * <p>
 * Copyright 2026 chapaof.com. All rights reserved.
 * chapaof.COM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.jiyingda.codly.function;

import java.util.HashMap;
import java.util.Map;

/**
 * Function 管理器
 * 负责注册和执行所有 tool functions
 */
@SuppressWarnings("unused")
public class FunctionManager {

    private final Map<String, Function> functions = new HashMap<>();

    /**
     * 构造函数，初始化默认的 functions
     */
    public FunctionManager() {
        registerFunction(new ReadFileFunction());
        registerFunction(new SearchFileFunction());
        registerFunction(new ExecBashFunction());
    }

    /**
     * 注册一个 Function
     *
     * @param function Function 实现类
     */
    public void registerFunction(Function function) {
        functions.put(function.getName(), function);
    }

    /**
     * 获取一个 Function
     *
     * @param name 函数名称
     * @return Function 实现，如果不存在则返回 null
     */
    public Function getFunction(String name) {
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
        Function function = getFunction(functionName);
        if (function == null) {
            return "未知的函数：" + functionName;
        }
        return function.execute(argsJson);
    }

    /**
     * 获取所有注册的 Functions
     *
     * @return Functions 的 Map
     */
    public Map<String, Function> getAllFunctions() {
        return new HashMap<>(functions);
    }
}

