/**
 * @(#)EditFileFunctionCall.java, 4 月 7, 2026.
 * <p>
 * Copyright 2026 chapaof.com. All rights reserved.
 * chapaof.COM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.jiyingda.codly.function;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.jiyingda.codly.command.CommandContext;
import com.jiyingda.codly.data.Parameters;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/**
 * 局部编辑文件函数实现
 * 通过 oldText/newText 替换方式对文件进行局部修改，避免重写整个文件
 */
@SuppressWarnings("unused")
public class EditFileFunctionCall implements FunctionCallApi {

    private static final Parameters PARAMETERS = Parameters.create()
            .addProperty("filePath", "string", "启动目录内文件路径，支持相对路径或绝对路径")
            .addProperty("oldText", "string", "要替换的原始文本（必须在文件中唯一存在）")
            .addProperty("newText", "string", "替换后的新文本")
            .addRequired("filePath")
            .addRequired("oldText")
            .addRequired("newText");

    @Override
    public String getName() {
        return "edit_file";
    }

    @Override
    public String getDescription() {
        return "对文件进行局部编辑，将文件中唯一出现的 oldText 替换为 newText，避免重写整个文件";
    }

    @Override
    public Parameters getParameters() {
        return PARAMETERS;
    }

    @Override
    public boolean requiresConfirmation() {
        return true;
    }

    @Override
    public String confirmationSummary(String argsJson) {
        try {
            Map<String, String> args = JSON.parseObject(argsJson, new TypeReference<>() {});
            String filePath = args == null ? null : args.get("filePath");
            return "编辑文件: " + Objects.requireNonNullElse(filePath, argsJson);
        } catch (Exception e) {
            return "编辑文件: " + argsJson;
        }
    }

    @Override
    public String execute(String argsJson, CommandContext ctx) {
        try {
            Map<String, String> args = JSON.parseObject(argsJson, new TypeReference<>() {});
            if (args == null) {
                return "参数解析失败";
            }
            String filePath = args.get("filePath");
            String oldText = args.get("oldText");
            String newText = args.get("newText");
            return editFile(filePath, oldText, newText, ctx.getStartupPath());
        } catch (Exception e) {
            return "执行 edit_file 失败：" + e.getMessage();
        }
    }

    static String editFile(String filePath, String oldText, String newText, Path workspaceRoot) {
        if (filePath == null || filePath.isBlank()) {
            return "未提供 filePath 参数";
        }
        if (oldText == null || oldText.isEmpty()) {
            return "未提供 oldText 参数";
        }
        if (newText == null) {
            return "未提供 newText 参数";
        }

        String content = ReadFileFunctionCall.readFile(filePath, workspaceRoot);

        // readFile 在失败时返回以特定前缀开头的错误信息，检测是否出错
        if (isReadError(content)) {
            return content;
        }

        int firstIndex = content.indexOf(oldText);
        if (firstIndex == -1) {
            return "未找到要替换的文本，请检查 oldText 是否正确";
        }

        int secondIndex = content.indexOf(oldText, firstIndex + 1);
        if (secondIndex != -1) {
            return "oldText 在文件中出现了多次，请提供更多上下文以确保唯一匹配";
        }

        String newContent = content.substring(0, firstIndex) + newText + content.substring(firstIndex + oldText.length());

        String result = WriteFileFunctionCall.writeFile(filePath, newContent, false, workspaceRoot);
        if (result != null && result.startsWith("写入成功")) {
            return "编辑成功：" + filePath;
        }
        return result;
    }

    private static boolean isReadError(String content) {
        if (content == null) {
            return true;
        }
        return content.startsWith("未提供")
                || content.startsWith("不允许")
                || content.startsWith("文件不存在")
                || content.startsWith("文件过大")
                || content.startsWith("疑似二进制")
                || content.startsWith("只能读取")
                || content.startsWith("没有权限")
                || content.startsWith("读取文件失败")
                || content.startsWith("文件路径非法");
    }
}
