/**
 * @(#)SearchFileFunction.java, 4 月 2, 2026.
 * <p>
 * Copyright 2026 chapaof.com. All rights reserved.
 * chapaof.COM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.jiyingda.codly.function;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.jiyingda.codly.command.CommandContext;
import com.jiyingda.codly.data.Parameters;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.FileVisitResult;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 搜索文件函数实现
 */
@SuppressWarnings("unused")
public class SearchFileFunctionCall implements FunctionCallApi {

    /** 搜索最大递归深度，100 已足够覆盖所有正常文件系统 */
    private static final int MAX_DEPTH = 100;
    private static final int MAX_RESULTS = 200;
    private static final Parameters PARAMETERS = Parameters.create()
            .addProperty("pattern", "string", "glob 文件名匹配模式，如 *.java 或 **/test/**，不支持正则表达式")
            .addProperty("directory", "string", "搜索的起始目录，默认为启动目录")
            .addRequired("pattern");

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
            return searchFiles(pattern, directory, ctx.getStartupPath());
        } catch (Exception e) {
            return "执行 search_file 失败：" + e.getMessage();
        }
    }

    private static String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** 搜索时跳过的无关目录名称 */
    private static final String[] SKIP_DIRECTORIES = {
            ".git", "node_modules", ".idea", "target", "build",
            ".gradle", ".mvn", ".claude", ".vscode", "out",
            "__pycache__", ".svn", "dist"
    };

    static String searchFiles(String pattern, String directory, Path workspaceRoot) {
        if (pattern == null || pattern.isBlank()) {
            return "未提供 pattern 参数";
        }

        try {
            Path root = normalizeWorkspaceRoot(workspaceRoot);
            Path searchRoot = resolveSearchRoot(directory, root);
            PathMatcher pathMatcher = buildPathMatcher(pattern.trim());
            List<String> results = new ArrayList<>();

            Files.walkFileTree(searchRoot, java.util.EnumSet.noneOf(FileVisitOption.class), MAX_DEPTH,
                    new SimpleFileVisitor<>() {
                        @Override
                        public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                            if (!isInsideRoot(dir, root)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            if (!dir.equals(searchRoot) && shouldSkipDirectory(dir)) {
                                return FileVisitResult.SKIP_SUBTREE;
                            }
                            return FileVisitResult.CONTINUE;
                        }

                        @Override
                        public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                            if (!attrs.isRegularFile() || !isInsideRoot(file, root)) {
                                return FileVisitResult.CONTINUE;
                            }
                            if (pathMatcher.matches(file.getFileName()) || pathMatcher.matches(file)) {
                                results.add(root.relativize(file).toString());
                                if (results.size() >= MAX_RESULTS) {
                                    return FileVisitResult.TERMINATE;
                                }
                            }
                            return FileVisitResult.CONTINUE;
                        }
                    });

            StringBuilder output = new StringBuilder();
            output.append("启动目录：").append(root).append("\n");
            output.append("搜索目录：").append(searchRoot).append("\n");
            output.append("搜索模式：").append(pattern.trim()).append("\n");
            output.append("结果上限：").append(MAX_RESULTS).append("\n\n");
            output.append("搜索结果:\n");

            if (results.isEmpty()) {
                output.append("(无匹配结果)\n");
            } else {
                for (String result : results) {
                    output.append(result).append("\n");
                }
            }
            if (results.size() >= MAX_RESULTS) {
                output.append("\n结果过多，已截断到 ").append(MAX_RESULTS).append(" 条\n");
            }
            return output.toString();
        } catch (InvalidPathException e) {
            return "搜索路径非法：" + e.getInput();
        } catch (IOException e) {
            return "执行 search_file 失败：" + e.getMessage();
        }
    }

    private static Path resolveSearchRoot(String directory, Path root) throws IOException {
        if (directory == null || directory.isBlank()) {
            return root;
        }
        Path requestedPath = Paths.get(directory.trim());
        Path normalizedPath = requestedPath.isAbsolute()
                ? requestedPath.normalize()
                : root.resolve(requestedPath).normalize();

        if (!normalizedPath.startsWith(root)) {
            throw new IOException("不允许搜索启动目录之外的路径：" + normalizedPath);
        }
        if (!Files.exists(normalizedPath)) {
            throw new IOException("搜索目录不存在：" + normalizedPath);
        }
        if (!Files.isDirectory(normalizedPath)) {
            throw new IOException("搜索目录不是文件夹：" + normalizedPath);
        }
        return normalizedPath;
    }

    private static Path normalizeWorkspaceRoot(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("workspaceRoot 不能为空");
        }
        return root.toAbsolutePath().normalize();
    }

    private static PathMatcher buildPathMatcher(String pattern) {
        return FileSystems.getDefault().getPathMatcher("glob:" + pattern);
    }

    private static boolean shouldSkipDirectory(Path dir) {
        String name = dir.getFileName().toString();
        for (String skip : SKIP_DIRECTORIES) {
            if (name.equals(skip)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isInsideRoot(Path path, Path root) {
        return path.normalize().startsWith(root);
    }
}
