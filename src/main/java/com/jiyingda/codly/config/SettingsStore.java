package com.jiyingda.codly.config;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** 设置文件读写与校验。保留未知字段，并拒绝覆盖打开设置后发生的外部修改。 */
public final class SettingsStore {
    private final Path path;

    public record Document(JSONObject values, String original) {}

    public SettingsStore(Path path) {
        this.path = path.toAbsolutePath().normalize();
    }

    public Document read() throws IOException {
        String original = Files.exists(path) ? Files.readString(path) : null;
        if (original == null) return new Document(new JSONObject(true), null);
        try {
            JSONObject values = JSON.parseObject(original);
            if (values == null) throw new IllegalArgumentException();
            return new Document(values, original);
        } catch (RuntimeException e) {
            throw new IOException("配置文件不是有效的 JSON 对象，请先修复 " + path);
        }
    }

    public void save(Document document) throws IOException {
        validate(document.values());
        String current = Files.exists(path) ? Files.readString(path) : null;
        if (!Objects.equals(current, document.original())) {
            throw new IOException("配置文件已被其他程序修改，请关闭设置后重新打开。");
        }
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".settings-", ".json");
        try {
            try {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) { }
            Files.writeString(temporary, JSON.toJSONString(document.values(), true), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static void validate(JSONObject settings) {
        String provider = text(settings, "provider");
        if (provider.isBlank()) provider = "qwen";
        if (!Set.of("qwen", "deepseek").contains(provider)) {
            throw new IllegalArgumentException("模型服务商必须为 qwen 或 deepseek。");
        }
        if ("qwen".equals(provider)) {
            required(settings, "apiKey", "请填写通义千问 API Key。");
            required(settings, "defaultModel", "请填写通义千问默认模型。");
        }
        url(text(settings, "apiUrl"), "通义千问 API URL");
        strings(settings.get("availableModels"), "通义千问可选模型");
        Object rawDeepseek = settings.get("deepseek");
        if (rawDeepseek != null && !(rawDeepseek instanceof JSONObject)) {
            throw new IllegalArgumentException("deepseek 必须为 JSON 对象。");
        }
        JSONObject deepseek = settings.getJSONObject("deepseek");
        if ("deepseek".equals(provider) && (deepseek == null || text(deepseek, "apiKey").isBlank())) {
            throw new IllegalArgumentException("请填写 DeepSeek API Key。");
        }
        if (deepseek != null) {
            url(text(deepseek, "apiUrl"), "DeepSeek API URL");
            strings(deepseek.get("availableModels"), "DeepSeek 可选模型");
            String effort = text(deepseek, "reasoningEffort");
            if (!effort.isBlank() && !Set.of("none", "low", "high", "max").contains(effort)) {
                throw new IllegalArgumentException("DeepSeek 思考强度必须为 none、low、high 或 max。");
            }
        }
        if (settings.get("enableThinking") != null && !(settings.get("enableThinking") instanceof Boolean)) {
            throw new IllegalArgumentException("enableThinking 必须为布尔值。");
        }
        Object rawSkills = settings.get("skills");
        if (rawSkills != null && !(rawSkills instanceof JSONObject)) {
            throw new IllegalArgumentException("skills 必须为 JSON 对象。");
        }
        JSONObject skills = settings.getJSONObject("skills");
        if (skills != null) {
            strings(skills.get("directories"), "Skills 额外目录");
            strings(skills.get("enabled"), "默认激活 Skills");
            JSONArray directories = skills.getJSONArray("directories");
            if (directories != null) {
                for (String directory : directories.toJavaList(String.class)) {
                    try {
                        if (!directory.startsWith("~/") && !Path.of(directory).isAbsolute()) {
                            throw new IllegalArgumentException();
                        }
                        Path.of(directory);
                    } catch (RuntimeException e) {
                        throw new IllegalArgumentException("Skills 额外目录必须为有效的绝对路径或 ~/ 开头的路径。");
                    }
                }
            }
            JSONArray enabled = skills.getJSONArray("enabled");
            if (enabled != null && enabled.toJavaList(String.class).stream()
                    .anyMatch(name -> !name.matches("[a-z0-9][a-z0-9-]*"))) {
                throw new IllegalArgumentException("Skill 名称仅允许小写字母、数字和横线。");
            }
        }
        validateMcp(settings.get("mcpServers"));
        validateRuntime(settings);
    }

    public static void validateRuntime(JSONObject settings) {
        for(String key : List.of("context","execution")) if(settings.get(key)!=null&&!(settings.get(key) instanceof JSONObject))throw new IllegalArgumentException(key+" 必须是 JSON 对象。");
        JSONObject context=settings.getJSONObject("context"),execution=settings.getJSONObject("execution");
        if(context!=null) {
            integer(context,"windowTokens",1024,2_000_000);integer(context,"reserveTokens",1,1_000_000);integer(context,"keepRecentMessages",0,100);
            if(context.get("autoCompact")!=null&&!(context.get("autoCompact") instanceof Boolean))throw new IllegalArgumentException("autoCompact 必须为布尔值。");
            if(context.get("models")!=null&&!(context.get("models") instanceof JSONObject))throw new IllegalArgumentException("context.models 必须为对象。");
            JSONObject models=context.getJSONObject("models");if(models!=null)for(String model:models.keySet())integer(models,model,1024,2_000_000);
        }
        if(execution!=null){integer(execution,"timeoutSeconds",1,86400);integer(execution,"backgroundTimeoutSeconds",1,604800);integer(execution,"outputLimit",100,100000);}
    }
    private static void integer(JSONObject object,String key,int minimum,int maximum) {
        Object value=object.get(key);if(value==null)return;
        if(!(value instanceof Number number)||number.doubleValue()!=number.intValue()||number.intValue()<minimum||number.intValue()>maximum)throw new IllegalArgumentException(key+" 必须为 "+minimum+".."+maximum+" 的整数。");
    }

    public static void validateMcp(Object value) {
        if (value == null) return;
        if (!(value instanceof JSONObject servers)) {
            throw new IllegalArgumentException("mcpServers 必须为 JSON 对象。");
        }
        for (String name : servers.keySet()) {
            if (!name.matches("[a-zA-Z0-9._-]+") || !(servers.get(name) instanceof JSONObject server)) {
                throw new IllegalArgumentException("MCP 名称仅支持字母、数字、点、横线、下划线，每个服务器必须为对象。");
            }
            String command = text(server, "command");
            String endpoint = text(server, "url");
            if (command.isBlank() == endpoint.isBlank()) {
                throw new IllegalArgumentException("MCP " + name + " 必须填写 command 或 url，且只能填写一个。");
            }
            String type = text(server, "type");
            if (!type.isBlank() && !(command.isBlank()
                    ? Set.of("http", "sse", "streamable-http").contains(type) : "stdio".equals(type))) {
                throw new IllegalArgumentException("MCP " + name + " 的 type 与 command / url 不匹配。");
            }
            url(endpoint, "MCP " + name + " URL");
            strings(server.get("args"), "MCP " + name + " 参数");
            stringMap(server.get("env"), "MCP 环境变量");
            stringMap(server.get("headers"), "MCP 请求头");
            JSONObject headers = server.getJSONObject("headers");
            if (headers != null) for (String key : headers.keySet()) {
                try { new okhttp3.Headers.Builder().add(key, headers.getString(key)).build(); }
                catch (IllegalArgumentException error) { throw new IllegalArgumentException("MCP 请求头包含无效名称或字符。"); }
            }
            if (server.get("disabled") != null && !(server.get("disabled") instanceof Boolean)) {
                throw new IllegalArgumentException("MCP disabled 必须为布尔值。");
            }
        }
    }

    public static JSONObject parseMcp(String text) {
        try {
            JSONObject servers = JSON.parseObject(text);
            if (servers == null) throw new IllegalArgumentException("MCP 配置必须为 JSON 对象。");
            validateMcp(servers);
            return servers;
        } catch (com.alibaba.fastjson.JSONException e) {
            throw new IllegalArgumentException("MCP JSON 格式不正确，请检查引号、逗号和括号。");
        }
    }

    private static void required(JSONObject object, String key, String message) {
        if (text(object, key).isBlank()) throw new IllegalArgumentException(message);
    }

    private static String text(JSONObject object, String key) {
        Object value = object.get(key);
        if (value == null) return "";
        if (!(value instanceof String)) throw new IllegalArgumentException(key + " 必须为字符串。");
        return ((String) value).trim();
    }

    private static void url(String value, String field) {
        if (value.isBlank()) return;
        try {
            URI uri = URI.create(value);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) throw new IllegalArgumentException();
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(field + " 必须为有效的 HTTP/HTTPS 地址，不支持在地址中嵌入用户名密码。");
        }
    }

    private static void strings(Object value, String field) {
        if (value == null) return;
        if (!(value instanceof JSONArray array)
                || array.stream().anyMatch(item -> !(item instanceof String) || ((String) item).isBlank())) {
            throw new IllegalArgumentException(field + " 必须为非空字符串组成的数组。");
        }
    }

    private static void stringMap(Object value, String field) {
        if (value == null) return;
        if (!(value instanceof JSONObject map) || map.values().stream().anyMatch(item -> !(item instanceof String))) {
            throw new IllegalArgumentException(field + " 必须为字符串键值对象。");
        }
    }
}
