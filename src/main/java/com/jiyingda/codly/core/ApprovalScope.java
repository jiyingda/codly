package com.jiyingda.codly.core;

import com.alibaba.fastjson.JSON;
import java.nio.file.Path;
import java.util.List;

public record ApprovalScope(String key, String description) {
    static ApprovalScope forOperation(String tool, String arguments, String summary, Path workspace) {
        try {
            var args = JSON.parseObject(arguments);
            if (args != null && ("write_file".equals(tool) || "edit_file".equals(tool))) {
                Object value = args.get("filePath");
                if (value instanceof String path && ExecutionPermission.projectPath(path, workspace, false)) {
                    Path parent = workspace.resolve(path.trim()).toAbsolutePath().normalize().getParent();
                    return new ApprovalScope("files:" + parent, "文件修改：" + parent + "（仅该目录，不含子目录）");
                }
            }
            if (args != null && "Bash".equals(tool) && args.get("command") instanceof String command) {
                ExecutionPermission level = ExecutionPermission.classify(tool, arguments, workspace);
                List<String> words = ExecutionPermission.simpleWords(command);
                if (words != null && !words.isEmpty()) {
                    String program = ExecutionPermission.program(words.get(0));
                    if (level == ExecutionPermission.PROJECT) {
                        String family = switch (program) {
                            case "mvn", "./mvnw" -> "Maven 常规构建 / 测试 / 本地安装";
                            case "gradle", "./gradlew" -> "Gradle 构建 / 测试";
                            case "npm", "pnpm", "yarn" -> words.size() > 1 && List.of("install", "ci", "add").contains(words.get(1))
                                    ? "项目依赖安装" : "前端构建 / 测试";
                            default -> null;
                        };
                        if (family != null) return new ApprovalScope("build:" + program + ":" + family, family + "（当前项目内已识别的常规操作）");
                    }
                    if (List.of("python", "python3", "bash", "sh", "node").contains(program) && words.size() > 1 && !words.get(1).startsWith("-")) {
                        Path script = workspace.resolve(words.get(1)).toAbsolutePath().normalize();
                        if (java.nio.file.Files.isRegularFile(script)) {
                            script = script.toRealPath();
                            return new ApprovalScope("script:" + program + ":" + script,
                                    "运行脚本：" + program + " " + script + "（允许参数变化）");
                        }
                    }
                }
                // 复杂 shell 和敏感命令只记忆完整命令，避免同一程序的危险参数继承授权。
                return new ApprovalScope("command:" + command, "完全相同的命令（包含参数和路径）");
            }
        } catch (Exception ignored) { }
        return new ApprovalScope("exact:" + tool + ":" + arguments + ":" + summary, "完全相同的操作与参数");
    }
}
