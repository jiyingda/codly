/**
 * @(#)ReadFileFunction.java, 4 月 2, 2026.
 * <p>
 * Copyright 2026 chapaof.com. All rights reserved.
 * chapaof.COM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.jiyingda.codly.function;

import com.alibaba.fastjson.JSON;

import java.util.Map;

/**
 * 读取文件函数实现
 */
@SuppressWarnings("unused")
public class ReadFileFunction implements Function {

    @Override
    public String getName() {
        return "read_file";
    }

    @Override
    public String getDescription() {
        return "用来读取指定的文件内容";
    }

    @Override
    @SuppressWarnings("unchecked")
    public String execute(String argsJson) {
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
}

