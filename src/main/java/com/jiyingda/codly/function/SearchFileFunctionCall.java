/**
 * @(#)SearchFileFunction.java, 4 月 2, 2026.
 * <p>
 * Copyright 2026 chapaof.com. All rights reserved.
 * chapaof.COM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.jiyingda.codly.function;

import com.alibaba.fastjson.JSON;
import com.jiyingda.codly.data.Parameters;
import com.jiyingda.codly.data.Property;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 搜索文件函数实现
 */
@SuppressWarnings("unused")
public class SearchFileFunctionCall implements FunctionCallApi {

    @Override
    public String getName() {
        return "search_file";
    }

    @Override
    public String getDescription() {
        return "搜索文件的技能，用于在项目目录中查找指定名称或模式的文件";
    }

    @Override
    public Parameters getParameters() {
        Parameters searchFileParams = new Parameters();
        searchFileParams.setType("object");
        Map<String, Property> searchFileProps = new HashMap<>();
        Property patternProp = new Property();
        patternProp.setType("string");
        patternProp.setDescription("搜索模式，如 *.java 或文件名");
        searchFileProps.put("pattern", patternProp);
        Property directoryProp = new Property();
        directoryProp.setType("string");
        directoryProp.setDescription("搜索目录，默认为当前目录");
        searchFileProps.put("directory", directoryProp);
        searchFileParams.setProperties(searchFileProps);
        searchFileParams.setRequired(Collections.singletonList("pattern"));
        return searchFileParams;
    }

    @Override
    public String execute(String argsJson) {
        try {
            Map<String, Object> args = JSON.parseObject(argsJson, new com.alibaba.fastjson.TypeReference<>() {});
            String pattern = (String) args.get("pattern");
            String directory = (String) args.get("directory");

            if (pattern == null || pattern.trim().isEmpty()) {
                return "未提供 pattern 参数";
            }

            // 默认当前目录
            if (directory == null || directory.trim().isEmpty()) {
                directory = System.getProperty("user.dir");
            }

            // 1. 先执行 pwd 确认当前目录
            ProcessBuilder pwdBuilder = new ProcessBuilder("bash", "-c", "pwd");
            Process pwdProcess = pwdBuilder.start();
            String pwdOutput;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(pwdProcess.getInputStream()))) {
                pwdOutput = reader.readLine();
            }
            pwdProcess.waitFor();

            // 2. 执行 find 命令搜索文件
            String findCommand = "find \"" + directory + "\" -name \"" + pattern + "\" 2>/dev/null";
            ProcessBuilder processBuilder = new ProcessBuilder("bash", "-c", findCommand);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            StringBuilder output = new StringBuilder();
            output.append("当前目录：").append(pwdOutput).append("\n");
            output.append("搜索目录：").append(directory).append("\n");
            output.append("搜索模式：").append(pattern).append("\n\n");
            output.append("搜索结果:\n");

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroy();
                return "命令执行超时（超过 30 秒）";
            }

            return output.toString();
        } catch (Exception e) {
            return "执行 SearchFileSkill 失败：" + e.getMessage();
        }
    }
}

