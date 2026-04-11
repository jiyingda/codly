/**
 * @(#)WriteFileFunctionCall.java, 4 月 4, 2026.
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
import java.util.Objects;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Map;

/**
 * 写入文件函数实现
 */
@SuppressWarnings("unused")
public class WriteFileFunctionCall implements FunctionCallApi {

    private static final int MAX_WRITE_BYTES = 256 * 1024;
    private static final Parameters PARAMETERS = Parameters.create()
            .addProperty("filePath", "string", "启动目录内文件路径，支持相对路径或绝对路径")
            .addProperty("content", "string", "要写入的文本内容")
            .addProperty("append", "boolean", "是否追加写入，默认 false")
            .addRequired("filePath")
            .addRequired("content");

    @Override
    public String getName() {
        return "write_file";
    }

    @Override
    public String getDescription() {
        return "用来写入指定的文件内容";
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
            Map<String, Object> args = JSON.parseObject(argsJson, new TypeReference<>() {});
            String filePath = args == null ? null : asString(args.get("filePath"));
            boolean append = args != null && asBoolean(args.get("append"));
            String action = append ? "追加写入" : "写入";
            return action + "文件: " + Objects.requireNonNullElse(filePath, argsJson);
        } catch (Exception e) {
            return "写入文件: " + argsJson;
        }
    }

    @Override
    public String execute(String argsJson, CommandContext ctx) {
        try {
            Map<String, Object> args = JSON.parseObject(argsJson, new TypeReference<>() {});
            String filePath = args == null ? null : asString(args.get("filePath"));
            String content = args == null ? null : asString(args.get("content"));
            boolean append = args != null && asBoolean(args.get("append"));
            return writeFile(filePath, content, append, ctx.getStartupPath());
        } catch (Exception e) {
            return "执行 write_file 失败：" + e.getMessage();
        }
    }

    static String writeFile(String filePath, String content, boolean append, Path workspaceRoot) {
        if (filePath == null || filePath.isBlank()) {
            return "未提供 filePath 参数";
        }
        if (content == null) {
            return "未提供 content 参数";
        }

        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_WRITE_BYTES) {
            return "写入内容过大，拒绝写入（" + bytes.length + " bytes，限制 "
                    + MAX_WRITE_BYTES + " bytes）";
        }

        try {
            Path root = normalizeWorkspaceRoot(workspaceRoot);
            Path targetPath = resolveTargetPath(filePath, root);

            if (!targetPath.startsWith(root)) {
                return "不允许写入启动目录之外的文件：" + targetPath;
            }

            Path parent = targetPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
                validatePathWithoutSymlink(root, parent);
            }

            if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(targetPath)) {
                return "不允许写入符号链接：" + targetPath;
            }
            if (Files.exists(targetPath, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(targetPath, LinkOption.NOFOLLOW_LINKS)) {
                return "只能写入普通文件：" + targetPath;
            }

            if (append) {
                Files.write(targetPath, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                return "追加写入成功：" + targetPath;
            }

            Files.write(targetPath, bytes,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            return "写入成功：" + targetPath;
        } catch (InvalidPathException e) {
            return "文件路径非法：" + e.getInput();
        } catch (NoSuchFileException e) {
            return "文件不存在：" + e.getFile();
        } catch (AccessDeniedException e) {
            return "没有权限写入文件：" + e.getFile();
        } catch (IOException e) {
            return "写入文件失败：" + e.getMessage();
        }
    }

    private static Path resolveTargetPath(String filePath, Path root) {
        Path requestedPath = Paths.get(filePath.trim());
        return requestedPath.isAbsolute()
                ? requestedPath.normalize()
                : root.resolve(requestedPath).normalize();
    }

    private static Path normalizeWorkspaceRoot(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("workspaceRoot 不能为空");
        }
        return root.toAbsolutePath().normalize();
    }

    private static void validatePathWithoutSymlink(Path root, Path targetParent) throws IOException {
        Path current = root;
        Path relative = root.relativize(targetParent);
        for (Path name : relative) {
            current = current.resolve(name);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("路径包含符号链接，不允许写入：" + current);
            }
        }
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
