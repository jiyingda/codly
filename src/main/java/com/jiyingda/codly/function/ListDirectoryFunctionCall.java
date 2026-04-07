/**
 * @(#)ListDirectoryFunctionCall.java, 4 月 7, 2026.
 * <p>
 * Copyright 2026 chapaof.com. All rights reserved.
 * chapaof.COM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.jiyingda.codly.function;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.jiyingda.codly.command.CommandContext;
import com.jiyingda.codly.data.Parameters;

import java.util.Map;

/**
 * 列出目录内容函数实现，通过 ls 命令列出目录结构
 */
@SuppressWarnings("unused")
public class ListDirectoryFunctionCall implements FunctionCallApi {

    private static final Parameters PARAMETERS = Parameters.create()
            .addProperty("path", "string", "要列出的目录路径，默认为启动目录（.）")
            .addProperty("recursive", "boolean", "是否递归列出子目录，默认 false");

    private final ExecBashFunctionCall bash = new ExecBashFunctionCall();

    @Override
    public String getName() {
        return "list_directory";
    }

    @Override
    public String getDescription() {
        return "列出目录内容，显示文件和子目录，支持递归模式，帮助导航项目结构";
    }

    @Override
    public Parameters getParameters() {
        return PARAMETERS;
    }

    @Override
    public String execute(String argsJson, CommandContext ctx) {
        try {
            Map<String, Object> args = JSON.parseObject(argsJson, new TypeReference<>() {});
            String dirPath = args == null ? null : asString(args.get("path"));
            boolean recursive = args != null && asBoolean(args.get("recursive"));

            String targetDir = (dirPath != null && !dirPath.isBlank()) ? dirPath.trim() : ".";

            // ls -lah 显示详细信息（含隐藏文件和人类可读大小），-R 递归
            String flags = recursive ? "-lahR" : "-lah";
            String cmd = "ls " + flags + " " + shellQuote(targetDir);

            String bashArgs = JSON.toJSONString(Map.of("command", cmd));
            return bash.execute(bashArgs, ctx);
        } catch (Exception e) {
            return "执行 list_directory 失败：" + e.getMessage();
        }
    }

    private static String shellQuote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value));
    }
}
