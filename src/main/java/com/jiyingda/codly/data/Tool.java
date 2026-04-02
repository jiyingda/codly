/**
 * @(#)Tool.java, 4 月 2, 2026.
 * <p>
 * Copyright 2026 chapaof.com. All rights reserved.
 * chapaof.COM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.jiyingda.codly.data;

/**
 * Tool 定义
 */
@SuppressWarnings("unused")
public class Tool {
    private String type;
    private Function function;

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Function getFunction() {
        return function;
    }

    public void setFunction(Function function) {
        this.function = function;
    }

    public static Tool createFunction(String name, String description, Parameters parameters) {
        Tool tool = new Tool();
        tool.setType("function");
        Function function = new Function();
        function.setName(name);
        function.setDescription(description);
        function.setParameters(parameters);
        tool.setFunction(function);
        return tool;
    }
}

