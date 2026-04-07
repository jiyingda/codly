/**
 * @(#)GrepFunctionCall.java, 4 月 7, 2026.
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
 * 代码内容搜索函数实现，通过 grep 命令在文件内容中搜索文本
 */
@SuppressWarnings("unused")
public class GrepFunctionCall implements FunctionCallApi {

    private static final Parameters PARAMETERS = Parameters.create()
            .addProperty("pattern", "string", "搜索的文本或正则表达式")
            .addProperty("directory", "string", "搜索目录，默认为启动目录")
            .addProperty("fileGlob", "string", "文件名过滤 glob，如 *.java，默认匹配所有文件")
            .addProperty("isRegex", "boolean", "是否使用正则表达式，默认 false（字面量搜索）")
            .addRequired("pattern");

    private final ExecBashFunctionCall bash = new ExecBashFunctionCall();

    @Override
    public String getName() {
        return "grep";
    }

    @Override
    public String getDescription() {
        return "在代码库文件内容中搜索文本，返回匹配的文件路径、行号和行内容";
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
            String pattern = asString(args.get("pattern"));
            if (pattern == null || pattern.isBlank()) {
                return "未提供 pattern 参数";
            }
            String directory = asString(args.get("directory"));
            String fileGlob = asString(args.get("fileGlob"));
            boolean isRegex = asBoolean(args.get("isRegex"));

            // 构造 grep 命令：-r 递归，-n 显示行号，-F 字面量（非正则），--include 文件过滤
            StringBuilder cmd = new StringBuilder("grep -r -n");
            if (!isRegex) {
                cmd.append(" -F");
            }
            if (fileGlob != null && !fileGlob.isBlank()) {
                cmd.append(" --include=").append(shellQuote(fileGlob.trim()));
            }
            cmd.append(" ").append(shellQuote(pattern));

            String searchDir = (directory != null && !directory.isBlank()) ? directory.trim() : ".";
            cmd.append(" ").append(shellQuote(searchDir));

            String bashArgs = JSON.toJSONString(Map.of("command", cmd.toString()));
            return bash.execute(bashArgs, ctx);
        } catch (Exception e) {
            return "执行 grep 失败：" + e.getMessage();
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
