package com.jiyingda.codly.llm;

import com.alibaba.fastjson.JSON;
import com.jiyingda.codly.core.ConversationContext;
import com.jiyingda.codly.core.ToolEvent;
import com.jiyingda.codly.core.TraceEvent;
import com.jiyingda.codly.data.Message;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** 仅使用本地假服务，验证共享调用链，不访问真实模型。 */
public class AbstractLlmClientTest {
    @Test(timeout = 10000) public void generatesTitleThroughIndependentModelRequestUsingFirstMessage() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> requests = new CopyOnWriteArrayList<>();
        server.createContext("/chat", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"桌面自动命名\"}}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            TestClient client = new TestClient("http://127.0.0.1:" + server.getAddress().getPort() + "/chat");
            CompletableFuture<String> title = new CompletableFuture<>();
            List<TraceEvent> trace = new CopyOnWriteArrayList<>();
            client.generateTitleAsync("请自动为首次对话命名", title::complete, trace::add);
            assertEquals("桌面自动命名", title.get(3, TimeUnit.SECONDS));
            assertEquals(1, requests.size());
            var messages = JSON.parseObject(requests.get(0)).getJSONArray("messages");
            assertEquals(2, messages.size());
            assertEquals("system", messages.getJSONObject(0).getString("role"));
            assertEquals("请自动为首次对话命名", messages.getJSONObject(1).getString("content"));
            assertTrue(trace.stream().anyMatch(event -> event.kind() == TraceEvent.Kind.MODEL_REQUEST && event.title().contains("自动命名")));
            assertTrue(trace.stream().anyMatch(event -> event.kind() == TraceEvent.Kind.MODEL_RESPONSE && event.detail().contains("桌面自动命名")));
        } finally { server.stop(0); }
    }

    @Test(timeout = 10000) public void keepsBodyBeforeAndAfterToolCallOutOfDisplayDiagnostics() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger calls = new AtomicInteger();
        List<String> requests = new CopyOnWriteArrayList<>();
        server.createContext("/chat", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String response = calls.getAndIncrement() == 0
                    ? "data: {\"choices\":[{\"delta\":{\"content\":\"before\",\"tool_calls\":[{\"index\":0,\"id\":\"t1\",\"type\":\"function\",\"function\":{\"name\":\"kb_list\",\"arguments\":\"{}\"}}]}}]}\n\ndata: [DONE]\n\n"
                    : "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"after\"}}]}\n\ndata: [DONE]\n\n";
            response = response.replace("data: [DONE]", "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":4,\"total_tokens\":14}}\n\ndata: [DONE]");
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            TestClient client = new TestClient("http://127.0.0.1:" + server.getAddress().getPort() + "/chat");
            ConversationContext ctx = context(client);
            List<TraceEvent> trace = new CopyOnWriteArrayList<>();
            ctx.setTraceEvents(trace::add);
            List<String> stream = new ArrayList<>();
            assertEquals("beforeafter", client.chat(ctx, stream::add));
            assertEquals(2, calls.get());
            assertTrue(stream.contains(AbstractLlmClient.USAGE_PREFIX + "10:4:14"));
            assertTrue(stream.contains(AbstractLlmClient.USAGE_PREFIX + "20:8:28"));
            assertTrue(stream.contains(AbstractLlmClient.REASONING_START));
            assertTrue(stream.stream().anyMatch(token -> token.contains("kb_list")));
            var messages = JSON.parseObject(requests.get(1)).getJSONArray("messages");
            assertEquals("before", messages.getJSONObject(2).getString("content"));
            assertEquals("t1", messages.getJSONObject(3).getString("tool_call_id"));
            var modelRequests = trace.stream().filter(event -> event.kind() == TraceEvent.Kind.MODEL_REQUEST && event.phase() == TraceEvent.Phase.START).toList();
            assertEquals(2, modelRequests.size());
            assertTrue(modelRequests.get(0).detail().contains("提示词（system）\ntest"));
            assertTrue(modelRequests.get(1).detail().contains("tool_call_id"));
            assertTrue(trace.stream().anyMatch(event -> event.kind() == TraceEvent.Kind.THINKING && event.detail().equals("thinking")));
            assertTrue(trace.stream().anyMatch(event -> event.kind() == TraceEvent.Kind.TOOL && event.phase() == TraceEvent.Phase.START && event.detail().contains("参数")));
            assertTrue(trace.stream().anyMatch(event -> event.kind() == TraceEvent.Kind.TOOL && event.phase() == TraceEvent.Phase.COMPLETE && event.detail().contains("返回结果")));
            assertEquals(2, trace.stream().filter(event -> event.kind() == TraceEvent.Kind.MODEL_RESPONSE).count());
            assertTrue(trace.stream().noneMatch(event -> event.detail().contains("fake-key")));
            calls.set(0);
            requests.clear();
            ConversationContext desktop = context(client);
            List<ToolEvent> events = new ArrayList<>();
            List<String> desktopStream = new ArrayList<>();
            desktop.setToolEvents(events::add);
            assertEquals("beforeafter", client.chat(desktop, desktopStream::add));
            assertEquals(2, events.size());
            assertEquals(ToolEvent.State.REQUESTED, events.get(0).state());
            assertEquals(ToolEvent.State.RETURNED, events.get(1).state());
            assertFalse(desktopStream.stream().anyMatch(token -> token.contains("kb_list")));

        } finally { server.stop(0); }
    }

    @Test(timeout = 10000) public void cancelUnblocksPendingNetworkRead() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch streaming = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/chat", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write("data: {\"choices\":[]}\n\n".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                streaming.countDown();
                release.await(8, TimeUnit.SECONDS);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            TestClient client = new TestClient("http://127.0.0.1:" + server.getAddress().getPort() + "/chat");
            ConversationContext ctx = context(client);
            List<TraceEvent> trace = new CopyOnWriteArrayList<>();
            ctx.setTraceEvents(trace::add);
            Future<String> reply = worker.submit(() -> client.chat(ctx, token -> {}));
            assertTrue(streaming.await(3, TimeUnit.SECONDS));
            ctx.cancellation().cancel();
            assertEquals("", reply.get(2, TimeUnit.SECONDS));
            assertTrue(trace.stream().anyMatch(event -> event.phase() == TraceEvent.Phase.CANCELLED));
        } finally {
            release.countDown();
            server.stop(0);
            worker.shutdownNow();
        }
    }

    @Test(timeout = 10000) public void reportsHttpErrorsToTheEntryInsteadOfReturningAnEmptySuccess() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.start();
        try {
            TestClient client = new TestClient("http://127.0.0.1:" + server.getAddress().getPort() + "/chat");
            List<TraceEvent> trace = new CopyOnWriteArrayList<>();
            ConversationContext ctx = context(client);
            ctx.setTraceEvents(trace::add);
            assertTrue(assertThrows(IllegalStateException.class,
                    () -> client.chat(ctx, token -> {})).getMessage().contains("401"));
            assertTrue(trace.stream().anyMatch(event -> event.phase() == TraceEvent.Phase.FAILED && event.detail().contains("401")));
        } finally { server.stop(0); }
    }

    @Test(timeout=10000) public void toolLoopLimitIsAnExplicitFailure()throws Exception {
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        AtomicInteger calls=new AtomicInteger();
        server.createContext("/chat",exchange->{
            exchange.getRequestBody().readAllBytes();int i=calls.incrementAndGet();
            String body="data: "+JSON.toJSONString(java.util.Map.of("choices",List.of(java.util.Map.of("delta",java.util.Map.of("tool_calls",List.of(java.util.Map.of("index",0,"id","t"+i,"type","function","function",java.util.Map.of("name","kb_list","arguments","{}"))))))))+"\n\ndata: [DONE]\n\n";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        TestClient client=new TestClient("http://127.0.0.1:"+server.getAddress().getPort()+"/chat");
        try {
            assertThrows(com.jiyingda.codly.core.TurnLimitException.class,()->client.chat(context(client),token->{}));assertEquals(10,calls.get());
        }finally{client.close();server.stop(0);}
    }

    private ConversationContext context(TestClient client) {
        ConversationContext ctx = new ConversationContext(new ArrayList<>(List.of(Message.fromUser("hello"))),
                client, Path.of("."));
        ctx.setSystemPrompt(Message.fromSystem("test"));
        return ctx;
    }

    private static class TestClient extends AbstractLlmClient {
        TestClient(String url) { super(url, "fake-key", "fake-model", false); }
        protected String buildChatRequestBody(List<Message> messages) {
            return JSON.toJSONString(java.util.Map.of("messages", messages));
        }
        protected String buildSimpleRequestBody(List<Message> messages) { return buildChatRequestBody(messages); }
        public List<String> getAvailableModels() { return List.of("fake-model"); }
    }
}
