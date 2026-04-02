/**
 * @(#)ExecBashFunction.java, 4 月 2, 2026.
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
 * 执行 Bash 命令函数实现
 */
@SuppressWarnings("unused")
public class ExecBashFunctionCall implements FunctionCallApi {

    @Override
    public String getName() {
        return "exec_bash";
    }

    @Override
    public String getDescription() {
        return "用来执行 bash 命令";
    }

    @Override
    public Parameters getParameters() {
        Parameters execBashParams = new Parameters();
        execBashParams.setType("object");
        Map<String, Property> execBashProps = new HashMap<>();
        Property execBashProp = new Property();
        execBashProp.setType("string");
        execBashProp.setDescription("要执行的 bash 命令");
        execBashProps.put("command", execBashProp);
        execBashParams.setProperties(execBashProps);
        execBashParams.setRequired(Collections.singletonList("command"));
        return execBashParams;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(String argsJson) {
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

