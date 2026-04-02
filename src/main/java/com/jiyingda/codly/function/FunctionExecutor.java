/**
 * @(#)FunctionExecutor.java, 4 月 2, 2026.
 * <p>
 * Copyright 2026 chapaof.com. All rights reserved.
 * chapaof.COM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.jiyingda.codly.function;

import com.alibaba.fastjson.JSON;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;

/**
 * 函数执行器
 * 负责执行各种 tool functions
 */
@SuppressWarnings("unused")
public class FunctionExecutor {

    /**
     * 执行 read_file 函数
     *
     * @param argsJson 函数参数（JSON 格式）
     * @return 执行结果
     */
    @SuppressWarnings("unchecked")
    public static String executeReadFile(String argsJson) {
        try {
            Map<String, Object> args = JSON.parseObject(argsJson, Map.class);
            String filePath = (String) args.get("filePath");
            if (filePath != null) {
                java.nio.file.Path path = java.nio.file.Paths.get(filePath);
                if (java.nio.file.Files.exists(path)) {
                    return java.nio.file.Files.readString(path);
                } else {
                    return "文件不存在：" + filePath;
                }
            }
            return "未提供 filePath 参数";
        } catch (Exception e) {
            return "执行 readFile 失败：" + e.getMessage();
        }
    }

    /**
     * 执行 search_file 函数
     *
     * @param argsJson 函数参数（JSON 格式）
     * @return 执行结果
     */
    public static String searchFile(String argsJson) {
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

    /**
     * 执行 exec_bash 函数
     *
     * @param argsJson 函数参数（JSON 格式）
     * @return 执行结果
     */
    @SuppressWarnings("unchecked")
    public static String executeExecBash(String argsJson) {
        try {
            Map<String, Object> args = JSON.parseObject(argsJson, Map.class);
            String command = (String) args.get("command");
            if (command == null || command.trim().isEmpty()) {
                return "未提供 command 参数";
            }

            ProcessBuilder processBuilder = new ProcessBuilder("bash", "-c", command);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            StringBuilder output = new StringBuilder();
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
            int exitCode = process.exitValue();

            return "执行结果:\n" + output + "(退出码：" + exitCode + ")";
        } catch (Exception e) {
            return "执行 execBash 失败：" + e.getMessage();
        }
    }
}

