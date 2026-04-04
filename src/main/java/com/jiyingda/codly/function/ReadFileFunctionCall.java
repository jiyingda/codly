/**
 * @(#)ReadFileFunction.java, 4 月 2, 2026.
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
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * 读取文件函数实现
 */
@SuppressWarnings("unused")
public class ReadFileFunctionCall implements FunctionCallApi {

    private static final int MAX_READ_BYTES = 64 * 1024;
    private static final Parameters PARAMETERS = Parameters.create()
            .addProperty("filePath", "string", "启动目录内文件路径，支持相对路径或绝对路径")
            .addRequired("filePath");

    @Override
    public String getName() {
        return "read_file";
    }

    @Override
    public String getDescription() {
        return "用来读取指定的文件内容";
    }

    @Override
    public Parameters getParameters() {
        return PARAMETERS;
    }

    @Override
    public String execute(String argsJson, CommandContext ctx) {
        try {
            Map<String, String> args = JSON.parseObject(argsJson, new TypeReference<>() {});
            String filePath = args == null ? null : args.get("filePath");
            return readFile(filePath, ctx.getStartupPath());
        } catch (Exception e) {
            return "执行 read_file 失败：" + e.getMessage();
        }
    }

    static String readFile(String filePath, Path workspaceRoot) {
        if (filePath == null || filePath.isBlank()) {
            return "未提供 filePath 参数";
        }

        try {
            Path root = normalizeWorkspaceRoot(workspaceRoot);
            Path requestedPath = Paths.get(filePath.trim());
            Path normalizedPath = requestedPath.isAbsolute()
                    ? requestedPath.normalize()
                    : root.resolve(requestedPath).normalize();

            if (!normalizedPath.startsWith(root)) {
                return "不允许读取工作目录之外的文件：" + normalizedPath;
            }
            if (!Files.exists(normalizedPath, LinkOption.NOFOLLOW_LINKS)) {
                return "文件不存在：" + normalizedPath;
            }
            if (Files.isSymbolicLink(normalizedPath)) {
                return "不允许读取符号链接：" + normalizedPath;
            }
            if (!Files.isRegularFile(normalizedPath, LinkOption.NOFOLLOW_LINKS)) {
                return "只能读取普通文件：" + normalizedPath;
            }

            long fileSize = Files.size(normalizedPath);
            if (fileSize > MAX_READ_BYTES) {
                return "文件过大，拒绝读取（" + fileSize + " bytes，限制 " + MAX_READ_BYTES + " bytes）："
                        + normalizedPath;
            }

            byte[] content = Files.readAllBytes(normalizedPath);
            if (containsNullByte(content)) {
                return "疑似二进制文件，拒绝读取：" + normalizedPath;
            }
            return new String(content, StandardCharsets.UTF_8);
        } catch (InvalidPathException e) {
            return "文件路径非法：" + e.getInput();
        } catch (NoSuchFileException e) {
            return "文件不存在：" + e.getFile();
        } catch (AccessDeniedException e) {
            return "没有权限读取文件：" + e.getFile();
        } catch (IOException e) {
            return "读取文件失败：" + e.getMessage();
        }
    }

    private static Path normalizeWorkspaceRoot(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("workspaceRoot 不能为空");
        }
        return root.toAbsolutePath().normalize();
    }

    private static boolean containsNullByte(byte[] content) {
        for (byte b : content) {
            if (b == 0) {
                return true;
            }
        }
        return false;
    }
}
