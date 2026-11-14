package com.jiyingda.codly.llm;

import com.alibaba.fastjson.JSON;
import com.jiyingda.codly.data.Message;
import org.junit.Test;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class ThinkingDepthTest {
    private final List<Message> messages = List.of(Message.fromUser("检查代码"));

    @Test public void qwenDepthChangesThinkingSwitchAndBudgetInRequest() {
        QwenLlmClient client = new QwenLlmClient("http://localhost/chat", "fake", "qwen-test", ThinkingDepth.OFF);
        Map<ThinkingDepth, Integer> budgets = Map.of(ThinkingDepth.LOW, 1024, ThinkingDepth.HIGH, 4000, ThinkingDepth.MAX, 16384);
        for (ThinkingDepth depth : ThinkingDepth.values()) {
            client.setThinkingDepth(depth);
            var body = JSON.parseObject(client.buildChatRequestBody(messages));
            assertEquals(depth != ThinkingDepth.OFF, body.getBooleanValue("enable_thinking"));
            if (depth == ThinkingDepth.OFF) assertFalse(body.containsKey("thinking_budget"));
            else assertEquals(budgets.get(depth), body.getInteger("thinking_budget"));
            assertFalse(body.containsKey("reasoning_effort"));
            assertTrue(body.getBooleanValue("stream"));
        }
        client.setModel("qwen-other");
        assertEquals(ThinkingDepth.MAX, client.getThinkingDepth());
        var simple = JSON.parseObject(client.buildSimpleRequestBody(messages));
        assertFalse(simple.getBooleanValue("enable_thinking"));
        assertFalse(simple.containsKey("thinking_budget"));
        assertNull(simple.get("tools"));
    }

    @Test public void deepseekDepthUsesNativeEffortAndNeverSendsQwenParameters() {
        DeepSeekLlmClient client = new DeepSeekLlmClient("http://localhost/chat", "fake", "deepseek-test", ThinkingDepth.OFF);
        Map<ThinkingDepth, String> efforts = Map.of(ThinkingDepth.LOW, "low", ThinkingDepth.HIGH, "high", ThinkingDepth.MAX, "max");
        for (ThinkingDepth depth : ThinkingDepth.values()) {
            client.setThinkingDepth(depth);
            var body = JSON.parseObject(client.buildChatRequestBody(messages));
            assertEquals(depth == ThinkingDepth.OFF ? "disabled" : "enabled", body.getJSONObject("thinking").getString("type"));
            if (depth == ThinkingDepth.OFF) assertFalse(body.containsKey("reasoning_effort"));
            else assertEquals(efforts.get(depth), body.getString("reasoning_effort"));
            assertFalse(body.containsKey("enable_thinking"));
            assertFalse(body.containsKey("thinking_budget"));
        }
        var simple = JSON.parseObject(client.buildSimpleRequestBody(messages));
        assertEquals("disabled", simple.getJSONObject("thinking").getString("type"));
        assertFalse(simple.containsKey("reasoning_effort"));
        assertNull(simple.get("tools"));
    }

    @Test public void configuredEffortMapsToDepthWithoutInventingUnsupportedLevels() {
        assertEquals(ThinkingDepth.OFF, ThinkingDepth.fromEffort("none"));
        assertEquals(ThinkingDepth.LOW, ThinkingDepth.fromEffort("low"));
        assertEquals(ThinkingDepth.HIGH, ThinkingDepth.fromEffort("high"));
        assertEquals(ThinkingDepth.MAX, ThinkingDepth.fromEffort("max"));
        assertEquals(ThinkingDepth.HIGH, ThinkingDepth.fromEffort(null));
        assertThrows(IllegalArgumentException.class, () -> ThinkingDepth.fromEffort("invalid"));
    }
}
