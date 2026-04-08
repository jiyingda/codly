/**
 * @(#)SystemInfoFunctionCall.java, 4 月 8, 2026.
 * <p>
 * Copyright 2026 chapaof.com. All rights reserved.
 * chapaof.COM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.jiyingda.codly.function;

import com.jiyingda.codly.command.CommandContext;
import com.jiyingda.codly.data.Parameters;
import com.jiyingda.codly.data.Property;
import com.jiyingda.codly.systeminfo.SystemInfoManager;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 获取系统与环境信息 Function Call 实现
 */
@SuppressWarnings("unused")
public class SystemInfoFunctionCall implements FunctionCallApi {

    @Override
    public String getName() {
        return "get_system_info";
    }

    @Override
    public String getDescription() {
        return "获取当前系统与环境信息，包括操作系统、Java 版本、Git 状态、项目版本、Maven 信息、JVM 内存等。当用户询问系统状态、环境配置、项目信息时使用。";
    }

    @Override
    public Parameters getParameters() {
        Parameters params = new Parameters();
        params.setType("object");

        Map<String, Property> props = new HashMap<>();
        Property refreshProp = new Property();
        refreshProp.setType("boolean");
        refreshProp.setDescription("是否强制刷新缓存重新采集信息，默认 false");
        props.put("refresh", refreshProp);
        params.setProperties(props);
        params.setRequired(Collections.emptyList());
        return params;
    }

    @Override
    public String execute(String argsJson, CommandContext ctx) {
        SystemInfoManager manager = SystemInfoManager.getInstance();

        // 解析参数
        boolean refresh = false;
        if (argsJson != null && !argsJson.isBlank()) {
            try {
                com.alibaba.fastjson.JSONObject args = com.alibaba.fastjson.JSON.parseObject(argsJson);
                refresh = args.getBooleanValue("refresh");
            } catch (Exception ignored) {}
        }

        if (refresh) {
            manager.refresh();
        }
        return manager.formatAsText();
    }
}