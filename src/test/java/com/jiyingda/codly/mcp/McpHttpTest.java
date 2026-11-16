package com.jiyingda.codly.mcp;

import com.alibaba.fastjson.*;
import com.jiyingda.codly.core.*;
import com.jiyingda.codly.function.FunctionManager;
import com.sun.net.httpserver.*;
import org.junit.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;
import static com.jiyingda.codly.mcp.McpHttpClient.object;

public class McpHttpTest {
    HttpServer server;
    ExecutorService pool;
    final List<String> methods = new CopyOnWriteArrayList<>();
    final List<String> failures = new CopyOnWriteArrayList<>();
    final AtomicInteger calls = new AtomicInteger();
    final AtomicInteger initializations = new AtomicInteger();
    final AtomicInteger deletes = new AtomicInteger();
    volatile String mode = "normal";
    final CountDownLatch blocked = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);

    @Before public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        pool = Executors.newCachedThreadPool(); server.setExecutor(pool);
        server.createContext("/mcp", exchange -> {
            try {
                if (exchange.getRequestMethod().equals("DELETE")) { deletes.incrementAndGet(); exchange.sendResponseHeaders(204,-1); return; }
                JSONObject request = JSON.parseObject(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String method = request.getString("method"); methods.add(method);
                if (!"Bearer fake-test-secret".equals(exchange.getRequestHeaders().getFirst("Authorization"))) failures.add("authorization header missing");
                if (!exchange.getRequestHeaders().getFirst("Accept").contains("text/event-stream")) failures.add("accept header missing");
                if (!"initialize".equals(method) && !"test-session".equals(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"))) failures.add("session header missing");
                if (!"initialize".equals(method) && !"2025-03-26".equals(exchange.getRequestHeaders().getFirst("MCP-Protocol-Version"))) failures.add("negotiated version missing");
                if ("notifications/initialized".equals(method)) { exchange.sendResponseHeaders(202,-1); return; }
                JSONObject result;
                if ("initialize".equals(method)) {
                    initializations.incrementAndGet(); exchange.getResponseHeaders().set("Mcp-Session-Id","test-session");
                    result = object("protocolVersion","2025-03-26","capabilities",object("tools",new JSONObject()),"serverInfo",object("name","fake","version","1"));
                } else if ("tools/list".equals(method)) {
                    if (mode.equals("block-list")) { blocked.countDown();release.await(5,TimeUnit.SECONDS); }
                    if (mode.equals("unauthorized")) { exchange.sendResponseHeaders(401,-1);return; }
                    if (mode.equals("rate-limit")) { exchange.getResponseHeaders().set("Retry-After","9"); exchange.sendResponseHeaders(429,-1);return; }
                    boolean second = request.getJSONObject("params").containsKey("cursor");
                    result=object("tools",List.of(tool(second?"modify":"query",!second)));
                    if (!second) result.put("nextCursor","page2");
                } else if ("tools/call".equals(method)) {
                    calls.incrementAndGet();
                    if (mode.equals("block")) { blocked.countDown();release.await(5,TimeUnit.SECONDS); }
                    if (mode.equals("expired")) { mode="normal";exchange.sendResponseHeaders(404,-1);return; }
                    if (mode.equals("drop")) return;
                    if (mode.equals("rpc-error")) { send(exchange,object("jsonrpc","2.0","id",request.get("id"),"error",object("code",-32000,"message","bad Bearer fake-test-secret")),false);return; }
                    result = object("content",List.of(object("type","text","text","ok fake-test-secret"),object("type","image","mimeType","image/png","data","fake-binary")),"structuredContent",request.getJSONObject("params").getJSONObject("arguments"),"isError",mode.equals("tool-error"));
                } else { exchange.sendResponseHeaders(400,-1);return; }
                Object id = mode.equals("wrong-id") && method.equals("tools/call") ? 9999 : request.get("id");
                send(exchange,object("jsonrpc","2.0","id",id,"result",result),"tools/list".equals(method));
            } catch (Exception ignored) { } finally { exchange.close(); }
        });server.start();
    }
    @After public void stop() { release.countDown();if (server != null) server.stop(0);if (pool != null) pool.shutdownNow(); }
    static JSONObject tool(String name, boolean readOnly) {
        return object("name",name,"description","测试工具", "annotations",object("readOnlyHint",readOnly),"inputSchema",
                JSON.parseObject("{\"type\":\"object\",\"properties\":{\"ids\":{\"type\":\"array\",\"items\":{\"type\":\"integer\"}},\"filter\":{\"oneOf\":[{\"type\":\"null\"},{\"type\":\"object\",\"properties\":{\"值\":{\"enum\":[\"a\",\"b\"]}}}]}},\"additionalProperties\":false}"));
    }
    static void send(HttpExchange exchange,JSONObject value,boolean sse)throws Exception {
        exchange.getResponseHeaders().set("Content-Type",sse?"text/event-stream":"application/json");
        String response=JSON.toJSONString(value);
        if(sse) response=": heartbeat\n\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\nevent: message\ndata: "+response+"\n\n";
        byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);
    }
    JSONObject config(){return object("url","http://127.0.0.1:"+server.getAddress().getPort()+"/mcp","type","http","headers",object("Authorization","Bearer fake-test-secret"));}
    ConversationContext context(){return new ConversationContext(new ArrayList<>(),null,Path.of(".").toAbsolutePath());}

    @Test public void discoversPaginatedSseAndPreservesSchemasThenCallsWithSessionAndAuth() {
        try(var registry=new McpRegistry(object("ares-mcp",config()))) {
            registry.load(new CancellationToken(),null);
            assertEquals(2,registry.functions().size());
            assertEquals(List.of("initialize","notifications/initialized","tools/list","tools/list"),methods);
            var query=registry.functions().get(McpRegistry.toolName("ares-mcp","query"));
            assertEquals(tool("query",true).getJSONObject("inputSchema"),query.getParameterSchema());
            assertFalse(query.requiresConfirmation());
            var result=JSON.parseObject(query.execute("{\"ids\":[1,2],\"filter\":{\"值\":\"a\"}}",context()));
            assertEquals(2,result.getJSONObject("structuredContent").getJSONArray("ids").size());
            assertFalse(result.getBooleanValue("isError"));
            assertFalse(result.toJSONString().contains("fake-test-secret"));
            assertFalse(result.toJSONString().contains("fake-binary"));
            assertTrue(failures.toString(),failures.isEmpty());
        }
        assertEquals(1,deletes.get());
    }

    @Test public void integratesToolRegistrationAndApprovalPolicies() {
        FunctionManager manager=new FunctionManager(object("server",config()));
        try {
            var context=context();manager.prepareMcp(context);
            String query=McpRegistry.toolName("server","query"),modify=McpRegistry.toolName("server","modify");
            assertTrue(manager.hasFunction(query));
            assertTrue(manager.getTools().stream().anyMatch(t->t.getFunction().getName().equals(query)));
            assertTrue(JSON.toJSONString(manager.getTools()).contains("oneOf"));
            AtomicInteger approvals=new AtomicInteger();context.setConfirmation(summary->{approvals.incrementAndGet();return false;});
            manager.execute(query,"{}",context);assertEquals(0,approvals.get());assertEquals(1,calls.get());
            assertEquals("用户拒绝执行此操作",manager.execute(modify,"{}",context));assertEquals(1,calls.get());
            context.setApprovalPolicy(ApprovalPolicy.FULL);manager.execute(modify,"{}",context);assertEquals(2,calls.get());
            context.setApprovalPolicy(ApprovalPolicy.ASSISTED);manager.execute(modify,"{}",context);assertEquals(2,calls.get());assertEquals(2,approvals.get());
        } finally {manager.close();}
    }

    @Test public void isolatesFailedUnsupportedAndDisabledServersAndNeverExposesHeaders() {
        mode="unauthorized";
        JSONObject disabled=config();disabled.put("disabled",true);
        try(var registry=new McpRegistry(object("bad",config(),"disabled",disabled,"stdio",object("command","do-not-start")))) {
            var trace=new ArrayList<TraceEvent>();registry.load(new CancellationToken(),trace::add);
            assertTrue(registry.functions().isEmpty());
            assertTrue(registry.status().contains("HTTP 401"));assertTrue(registry.status().contains("已停用"));
            assertFalse(JSON.toJSONString(trace).contains("fake-test-secret"));
            registry.load(new CancellationToken(),trace::add);assertEquals(1,initializations.get());
        }
    }

    @Test public void reconnectReplacesServerDefinitionsAndClearsStaleTools() {
        FunctionManager manager=new FunctionManager(object("old",config()));
        try {
            var ctx=context();var trace=new ArrayList<TraceEvent>();ctx.setTraceEvents(trace::add);
            assertTrue(manager.mcpStatus(ctx,null).contains("2 个工具"));
            assertTrue(manager.hasFunction(McpRegistry.toolName("old","query")));
            String status=manager.mcpStatus(ctx,object("new",config()));
            assertTrue(status.contains("new"));assertFalse(status.contains("old"));
            assertFalse(manager.hasFunction(McpRegistry.toolName("old","query")));
            assertTrue(manager.hasFunction(McpRegistry.toolName("new","query")));
            assertEquals(2,initializations.get());assertEquals(1,deletes.get());
            assertFalse(JSON.toJSONString(trace).contains("fake-test-secret"));
            assertEquals("未配置 MCP 服务器",manager.mcpStatus(ctx,new JSONObject()));
            assertFalse(manager.hasFunction(McpRegistry.toolName("new","query")));
        } finally {manager.close();}
    }

    @Test public void namesRemainUniqueAndModelCompatible() {
        String a=McpRegistry.toolName("one-two","get.weather"),b=McpRegistry.toolName("one_two","get.weather");
        assertNotEquals(a,b);assertTrue(a.matches("[A-Za-z0-9_]{1,64}"));
        assertTrue(McpRegistry.toolName("a".repeat(100),"b".repeat(100)).length()<=64);
    }

    @Test public void doesNotReplayToolCallsOnSessionExpiryAndCanStartFreshNextTime() {
        try(var client=new McpHttpClient("test",config())) {
            mode="expired";
            assertTrue(assertThrows(IllegalStateException.class,()->client.request("tools/call",object("name","modify","arguments",new JSONObject()),new CancellationToken())).getMessage().contains("404"));
            assertEquals(1,calls.get());assertEquals(1,initializations.get());
            client.request("tools/call",object("name","modify","arguments",new JSONObject()),new CancellationToken());
            assertEquals(2,calls.get());assertEquals(2,initializations.get());
        }
    }

    @Test public void surfacesToolAndRpcErrorsWithoutCredentialLeakOrAutomaticRetry() {
        try(var registry=new McpRegistry(object("server",config()))) {
            registry.load(new CancellationToken(),null);var tool=registry.functions().get(McpRegistry.toolName("server","modify"));
            mode="tool-error";assertTrue(JSON.parseObject(tool.execute("{}",context())).getBooleanValue("isError"));
            mode="rpc-error";String error=tool.execute("{}",context());assertTrue(error.contains("-32000"));assertFalse(error.contains("fake-test-secret"));
            mode="wrong-id";assertTrue(tool.execute("{}",context()).contains("响应标识不匹配"));
            mode="drop";assertTrue(tool.execute("{}",context()).contains("失败"));assertEquals(4,calls.get());
        }
    }

    @Test public void cancellationClosesBlockedHttpCallPromptly()throws Exception {
        try(var client=new McpHttpClient("test",config())) {
            mode="block";CancellationToken token=new CancellationToken();
            CompletableFuture<String> task=CompletableFuture.supplyAsync(()->{
                try{client.request("tools/call",object("name","query","arguments",new JSONObject()),token);return "unexpected";}
                catch(IllegalStateException error){return error.getMessage();}
            });
            assertTrue(blocked.await(3,TimeUnit.SECONDS));token.cancel();assertTrue(task.get(2,TimeUnit.SECONDS).contains("已取消"));assertEquals(1,calls.get());release.countDown();
        }
    }

    @Test public void rateLimitReportsRetryAfterWithoutRetryLoop() {
        mode="rate-limit";
        try(var registry=new McpRegistry(object("test",config()))) {
            registry.load(new CancellationToken(),null);assertTrue(registry.status().contains("Retry-After: 9"));
            assertEquals(1,methods.stream().filter("tools/list"::equals).count());
        }
    }

    @Test public void cancelledDiscoveryCanReconnectOnNextTurn() throws Exception {
        mode="block-list";
        try(var registry=new McpRegistry(object("test",config()))) {
            CancellationToken token=new CancellationToken();
            CompletableFuture<Void> task=CompletableFuture.runAsync(()->registry.load(token,null));
            assertTrue(blocked.await(3,TimeUnit.SECONDS));token.cancel();task.get(3,TimeUnit.SECONDS);
            mode="normal";release.countDown();
            registry.load(new CancellationToken(),null);
            assertEquals(2,registry.functions().size());
            assertEquals(2,initializations.get());
        }
    }
}
