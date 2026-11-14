/**
 * @(#)ExecBashFunction.java, 4 月 2, 2026.
 * <p>
 * Copyright 2026 chapaof.com. All rights reserved.
 * chapaof.COM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.jiyingda.codly.function;

import com.alibaba.fastjson.JSON;
import com.jiyingda.codly.core.ConversationContext;
import com.jiyingda.codly.data.Parameters;
import com.jiyingda.codly.data.Property;

import java.io.InputStreamReader;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 执行 Bash 命令函数实现
 */
@SuppressWarnings("unused")
public class ExecBashFunctionCall implements FunctionCallApi {

    @Override
    public String getName() {
        return "Bash";
    }

    @Override
    public String getDescription() {
        return """
        在当前项目目录内启动独立 bash 进程执行命令。

        执行前请遵循以下步骤：

        1. 目录验证：
           - 如果命令将创建新目录或文件，先用 ls 确认父目录存在
           - 例如，执行 "mkdir foo/bar" 前先用 ls 检查 "foo" 是否存在

        2. 命令执行：
           - 含空格的路径必须用双引号包裹（如：cd "带空格的路径/文件.txt"）
           - 正确示例：cd "/Users/name/My Documents"
           - 错误示例：cd /Users/name/My Documents
           - 确保引号正确后执行命令，并捕获输出

        使用须知：
          - command 参数为必填
          - 固定超时 30 秒
          - 请用 5-10 个字写一段清晰的命令描述
          - 输出超过 30000 字符会被截断
          - 多条命令用 `;` 或 `&&` 分隔，不要用换行（引号内换行除外）
          - 尽量使用绝对路径，避免使用 `cd`，除非用户明确要求
        """;
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
    public boolean requiresConfirmation() {
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public String confirmationSummary(String argsJson) {
        try {
            Map<String, Object> args = JSON.parseObject(argsJson, Map.class);
            String command = args == null ? null : (String) args.get("command");
            return "执行 bash 命令: " + Objects.requireNonNullElse(command, argsJson);
        } catch (Exception e) {
            return "执行 bash 命令: " + argsJson;
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(String argsJson, ConversationContext ctx) {
        try {
            Map<String, Object> args = JSON.parseObject(argsJson, Map.class);
            String command = (String) args.get("command");
            if (command == null || command.trim().isEmpty()) {
                return "未提供 command 参数";
            }

            if (ctx.cancellation().isCancelled()) return "命令已取消";
            ProcessBuilder processBuilder = new ProcessBuilder("bash", "-c", command);
            processBuilder.directory(ctx.getStartupPath().toFile());
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();
            Runnable terminate = () -> {
                try {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                } catch (RuntimeException ignored) {
                    // 受限运行环境可能禁止枚举进程，仍需终止直接子进程。
                } finally {
                    process.destroyForcibly();
                }
            };
            var cancellation = ctx.cancellation();
            cancellation.register(terminate);
            java.util.concurrent.FutureTask<String> output = new java.util.concurrent.FutureTask<>(() -> {
                StringBuilder text = new StringBuilder();
                try (var reader = new InputStreamReader(process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8)) {
                    char[] buffer = new char[2048];
                    int count;
                    while ((count = reader.read(buffer)) != -1) {
                        int remaining = 30000 - text.length();
                        if (remaining > 0) text.append(buffer, 0, Math.min(count, remaining));
                    }
                }
                return text.toString();
            });
            Thread reader = new Thread(output, "Codly-Bash-Output");
            reader.setDaemon(true);
            reader.start();
            try {
                if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
                    terminate.run();
                    return "命令执行超时（超过 30 秒）";
                }
                if (cancellation.isCancelled()) return "命令已取消";
                String text = output.get(2, java.util.concurrent.TimeUnit.SECONDS);
                int exitCode = process.exitValue();
                return exitCode == 0 ? "执行成功:\n" + text
                        : "执行失败，退出码 " + exitCode + ":\n" + text;
            } finally {
                cancellation.unregister(terminate);
                if (process.isAlive() || !output.isDone()) terminate.run();
                process.getInputStream().close();
            }
        } catch (Exception e) {
            return "执行 execBash 失败：" + e.getMessage();
        }
    }
}
