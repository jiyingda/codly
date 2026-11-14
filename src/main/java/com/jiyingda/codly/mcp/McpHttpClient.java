package com.jiyingda.codly.mcp;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.jiyingda.codly.core.CancellationToken;
import okhttp3.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Streamable HTTP：JSON-RPC、JSON/SSE 响应、会话协商和可取消请求。不重放工具操作。 */
public final class McpHttpClient implements AutoCloseable {
    private static final String VERSION = "2025-06-18";
    private static final int MAX_RESPONSE = 8 * 1024 * 1024;
    private final String name;
    private final String url;
    private final JSONObject headers;
    private final OkHttpClient http = new OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS).callTimeout(60, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).build();
    private long sequence;
    private String session;
    private String version = VERSION;
    private boolean initialized;
    private boolean closed;

    public McpHttpClient(String name, JSONObject config) {
        this.name = name;
        this.url = config.getString("url");
        this.headers = config.getJSONObject("headers") == null ? new JSONObject() : config.getJSONObject("headers");
    }

    public synchronized JSONObject request(String method, JSONObject params, CancellationToken token) {
        try {
            if (closed) throw new IOException("连接已关闭");
            initialize(token);
            return rpc(method, params, token);
        } catch (IOException | RuntimeException e) {
            if (token.isCancelled()) throw new IllegalStateException("MCP " + name + " 请求已取消");
            throw new IllegalStateException("MCP " + name + "：" + redact(e.getMessage()));
        }
    }

    private void initialize(CancellationToken token) throws IOException {
        if (initialized) return;
        version = VERSION;
        JSONObject result = rpc("initialize", object("protocolVersion", VERSION, "capabilities", new JSONObject(),
                "clientInfo", object("name", "codly", "version", "1.0")), token);
        String negotiated = result.getString("protocolVersion");
        if (!Set.of("2025-11-25", VERSION, "2025-03-26", "2024-11-05").contains(Objects.toString(negotiated, ""))) {
            throw new IOException("不支持服务器协商的协议版本");
        }
        version = negotiated;
        post(object("jsonrpc", "2.0", "method", "notifications/initialized"), null, token);
        initialized = true;
    }

    private JSONObject rpc(String method, JSONObject params, CancellationToken token) throws IOException {
        long id = ++sequence;
        JSONObject response = post(object("jsonrpc", "2.0", "id", id, "method", method, "params", params), id, token);
        if (response.getJSONObject("error") != null) {
            JSONObject error = response.getJSONObject("error");
            throw new IOException("RPC " + error.getIntValue("code") + "：" + redact(error.getString("message")));
        }
        JSONObject result = response.getJSONObject("result");
        if (result == null) throw new IOException("服务器未返回有效 result");
        // 远端若在错误或结果中回显鉴权值，也不向模型、轨迹传递。
        scrub(result);
        return result;
    }

    private JSONObject post(JSONObject message, Long id, CancellationToken token) throws IOException {
        if (token.isCancelled()) throw new IOException("请求已取消");
        Request.Builder builder = new Request.Builder().url(url);
        for (String key : headers.keySet()) builder.header(key, headers.getString(key));
        builder.header("Accept", "application/json, text/event-stream");
        builder.header("MCP-Protocol-Version", version);
        if (session != null) builder.header("Mcp-Session-Id", session);
        builder.post(RequestBody.create(JSON.toJSONString(message), MediaType.get("application/json; charset=utf-8")));
        Call call = http.newCall(builder.build());
        Runnable cancel = call::cancel;
        token.register(cancel);
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                if (response.code() == 404) { session = null; initialized = false; }
                if (response.code() == 429) throw new IOException("调用频率受限，请稍后重试（Retry-After: " + response.header("Retry-After", "未提供") + "）");
                throw new IOException("HTTP " + response.code() + (response.code() == 401 || response.code() == 403 ? "，请检查鉴权配置" : ""));
            }
            if ("initialize".equals(message.getString("method"))) session = response.header("Mcp-Session-Id");
            if (id == null) return new JSONObject();
            ResponseBody body = response.body();
            if (body == null) throw new IOException("空响应");
            if (response.header("Content-Type", "").toLowerCase(Locale.ROOT).contains("text/event-stream")) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(body.byteStream(), StandardCharsets.UTF_8))) {
                    String line; StringBuilder data = new StringBuilder(); int total = 0;
                    while ((line = reader.readLine()) != null) {
                        total += line.length();
                        if (total > MAX_RESPONSE) throw new IOException("响应过大");
                        if (line.isEmpty()) {
                            JSONObject match = event(data, id, token); if (match != null) return match;
                        } else if (line.startsWith("data:")) {
                            if (!data.isEmpty()) data.append('\n');
                            data.append(line.substring(5).stripLeading());
                        }
                    }
                    JSONObject match = event(data, id, token); if (match != null) return match;
                }
                throw new IOException("SSE 流未返回匹配的响应");
            }
            byte[] bytes = body.byteStream().readNBytes(MAX_RESPONSE + 1);
            if (bytes.length > MAX_RESPONSE) throw new IOException("响应过大");
            JSONObject result = JSON.parseObject(new String(bytes, StandardCharsets.UTF_8));
            if (matches(result, id)) return result;
            throw new IOException("响应标识不匹配");
        } catch (com.alibaba.fastjson.JSONException e) {
            throw new IOException("服务器响应不是有效 JSON-RPC");
        } finally { token.unregister(cancel); }
    }

    private JSONObject event(StringBuilder data, long id, CancellationToken token) throws IOException {
        if (data.isEmpty()) return null;
        JSONObject value = JSON.parseObject(data.toString()); data.setLength(0);
        if (matches(value, id)) return value;
        if (value != null && value.containsKey("id") && value.containsKey("method")) {
            JSONObject reply = object("jsonrpc", "2.0", "id", value.get("id"));
            if ("ping".equals(value.getString("method"))) reply.put("result", new JSONObject());
            else reply.put("error", object("code", -32601, "message", "Client capability not supported"));
            post(reply, null, token);
        }
        return null;
    }

    private static boolean matches(JSONObject response, long id) {
        return response != null && "2.0".equals(response.getString("jsonrpc")) && response.containsKey("id")
                && Long.toString(id).equals(response.getString("id"));
    }

    public String redact(String text) {
        if (text == null) return "请求失败";
        for (String value : headers.values().stream().map(String::valueOf).toList()) {
            if (!value.isBlank()) text = text.replace(value, "[已隐藏]");
            if (value.regionMatches(true, 0, "Bearer ", 0, 7) && value.length() > 7) text = text.replace(value.substring(7), "[已隐藏]");
        }
        return text;
    }

    private void scrub(Object node) {
        if (node instanceof JSONObject object) {
            for (String key : new ArrayList<>(object.keySet())) {
                Object value = object.get(key);
                if (value instanceof String text) object.put(key, redact(text)); else scrub(value);
            }
        } else if (node instanceof com.alibaba.fastjson.JSONArray array) {
            for (int i = 0; i < array.size(); i++) {
                Object value = array.get(i);
                if (value instanceof String text) array.set(i, redact(text)); else scrub(value);
            }
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        if (session != null) {
            try {
                Request.Builder request = new Request.Builder().url(url);
                for (String key : headers.keySet()) request.header(key, headers.getString(key));
                request.header("Mcp-Session-Id", session).header("MCP-Protocol-Version", version).delete();
                try (Response ignored = http.newBuilder().callTimeout(2, TimeUnit.SECONDS).build().newCall(request.build()).execute()) { }
            } catch (Exception ignored) { }
        }
        http.dispatcher().cancelAll();
        http.connectionPool().evictAll();
        http.dispatcher().executorService().shutdown();
    }

    public static JSONObject object(Object... pairs) {
        JSONObject value = new JSONObject(true);
        for (int i = 0; i < pairs.length; i += 2) value.put((String) pairs[i], pairs[i + 1]);
        return value;
    }
}
