package com.jiyingda.codly.mcp;

import com.alibaba.fastjson.*;
import com.jiyingda.codly.config.SettingsStore;
import com.jiyingda.codly.core.*;
import com.jiyingda.codly.data.Parameters;
import com.jiyingda.codly.function.FunctionCallApi;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import static com.jiyingda.codly.mcp.McpHttpClient.object;

/** 每个模型入口独立管理 MCP；按需连接，失败隔离，保存设置后重新发现。 */
public final class McpRegistry implements AutoCloseable {
    private final JSONObject config;
    private final List<McpHttpClient> clients = new ArrayList<>();
    private final Map<String, FunctionCallApi> functions = new LinkedHashMap<>();
    private final List<String> status = new ArrayList<>();
    private boolean loaded;

    public McpRegistry(JSONObject config) {
        this.config = JSON.parseObject(JSON.toJSONString(config));
        SettingsStore.validateMcp(this.config);
    }

    public synchronized void load(CancellationToken token, Consumer<TraceEvent> trace) {
        if (loaded) return;
        clients.forEach(McpHttpClient::close); clients.clear(); functions.clear(); status.clear();
        loaded = true;
        for (String name : config.keySet()) {
            if (token.isCancelled()) { loaded = false; return; }
            JSONObject server = config.getJSONObject(name);
            if (server.getBooleanValue("disabled")) { status.add(name + "：已停用"); continue; }
            String id = TraceEvent.newId();
            emit(trace, TraceEvent.of(id, TraceEvent.Kind.LIFECYCLE, TraceEvent.Phase.START, "连接 MCP · " + name, "初始化并发现工具"));
            McpHttpClient client = null;
            try {
                String type = server.getString("type");
                if (server.getString("url") == null || "sse".equals(type)) throw new IllegalArgumentException("当前支持 http / streamable-http，暂不支持 stdio 和旧版 SSE 传输");
                client = new McpHttpClient(name, server); clients.add(client);
                Map<String, FunctionCallApi> discovered = new LinkedHashMap<>();
                String cursor = null;
                Set<String> cursors = new HashSet<>();
                do {
                    JSONObject params = new JSONObject(); if (cursor != null) params.put("cursor", cursor);
                    JSONObject result = client.request("tools/list", params, token);
                    JSONArray tools = result.getJSONArray("tools");
                    if (tools == null) throw new IllegalArgumentException("tools/list 未返回 tools 数组");
                    for (Object raw : tools) {
                        JSONObject tool = (JSONObject) raw;
                        String remoteName = tool.getString("name");
                        if (remoteName == null || remoteName.isBlank() || tool.getJSONObject("inputSchema") == null) throw new IllegalArgumentException("工具定义无效");
                        String localName = toolName(name, remoteName);
                        if (discovered.put(localName, new RemoteTool(localName, name, remoteName, tool, client)) != null) throw new IllegalArgumentException("服务器返回重复工具名");
                    }
                    cursor = result.getString("nextCursor");
                    if (cursor != null && !cursor.isBlank() && (!cursors.add(cursor) || cursors.size() > 100)) throw new IllegalArgumentException("工具分页游标循环或页数过多");
                } while (cursor != null && !cursor.isBlank());
                functions.putAll(discovered);
                status.add(name + "：已连接，" + discovered.size() + " 个工具");
                emit(trace, TraceEvent.of(id, TraceEvent.Kind.LIFECYCLE, TraceEvent.Phase.COMPLETE, "MCP 已连接 · " + name, "发现 " + discovered.size() + " 个工具"));
            } catch (RuntimeException error) {
                String message = client == null ? error.getMessage() : client.redact(error.getMessage());
                status.add(name + "：连接失败，" + message);
                emit(trace, TraceEvent.of(id, TraceEvent.Kind.LIFECYCLE, TraceEvent.Phase.FAILED, "MCP 连接失败 · " + name, message));
                if (client != null) { client.close(); clients.remove(client); }
                if (token.isCancelled()) { loaded = false; return; }
            }
        }
    }

    private static void emit(Consumer<TraceEvent> trace, TraceEvent event) { if (trace != null) trace.accept(event); }
    public synchronized Map<String, FunctionCallApi> functions() { return new LinkedHashMap<>(functions); }
    public synchronized String status() { return status.isEmpty() ? "未配置 MCP 服务器" : String.join("\n", status); }
    @Override public synchronized void close() { clients.forEach(McpHttpClient::close); clients.clear(); functions.clear(); }

    public static String toolName(String server, String tool) {
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((server + "\0" + tool).getBytes(StandardCharsets.UTF_8))).substring(0, 10);
            return "mcp_" + safe(server, 20) + "_" + safe(tool, 24) + "_" + hash;
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String safe(String text, int length) { text = text.replaceAll("[^A-Za-z0-9_]", "_"); return text.substring(0, Math.min(length, text.length())); }

    private record RemoteTool(String getName, String server, String remote, JSONObject definition, McpHttpClient client) implements FunctionCallApi {
        @Override public String getDescription() { return "MCP " + server + " / " + remote + "\n" + Objects.toString(definition.getString("description"), ""); }
        @Override public Parameters getParameters() { return Parameters.create(); }
        @Override public Object getParameterSchema() { return definition.getJSONObject("inputSchema"); }
        @Override public boolean requiresConfirmation() {
            JSONObject annotations = definition.getJSONObject("annotations");
            return annotations == null || !annotations.getBooleanValue("readOnlyHint");
        }
        @Override public String confirmationSummary(String args) { return "MCP 服务器：" + server + "\n工具：" + remote + "\n参数：" + args; }
        @Override public String execute(String args, ConversationContext context) {
            try {
                JSONObject arguments = JSON.parseObject(args);
                if (arguments == null) throw new IllegalArgumentException("工具参数必须为 JSON 对象");
                JSONObject result = client.request("tools/call", object("name", remote, "arguments", arguments), context.cancellation());
                // 保留 content、structuredContent 和 isError；图像/音频二进制只返回类型说明。
                JSONArray content = result.getJSONArray("content");
                if (content != null) for (Object raw : content) if (raw instanceof JSONObject item && ("image".equals(item.getString("type")) || "audio".equals(item.getString("type")))) {
                    item.remove("data"); item.put("note", "当前对话仅展示文本结果，二进制内容已省略");
                }
                return JSON.toJSONString(result);
            } catch (RuntimeException error) { return "MCP 工具执行失败：" + client.redact(error.getMessage()); }
        }
    }
}
