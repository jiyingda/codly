package com.jiyingda.codly;

import com.jiyingda.codly.llm.AbstractLlmClient;
import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class ResponseRendererTest {
    @Test public void retainsLastUsageAndReplacesCumulativeSnapshotsRatherThanAddingThem() {
        PrintStream previous = System.out;
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (PrintStream capture = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            System.setOut(capture);
            ResponseRenderer renderer = new ResponseRenderer();
            assertEquals("正文", renderer.render(stream -> {
                stream.accept(AbstractLlmClient.REASONING_START); stream.accept("思考");
                stream.accept(AbstractLlmClient.REASONING_END);
                stream.accept(AbstractLlmClient.USAGE_PREFIX + "10:4:14");
                stream.accept("正文"); stream.accept(AbstractLlmClient.USAGE_PREFIX + "20:8:28");
            }));
            assertEquals(28, renderer.getLastStats().getTotalTokens());
            assertEquals(2, renderer.getLastStats().getChunkCount());
            assertTrue(renderer.getLastStats().hasUsage());
            assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("20 prompt + 8 completion = 28 tokens"));
            bytes.reset(); renderer.render(stream -> stream.accept("无 usage 的回答"));
            assertFalse(renderer.getLastStats().hasUsage());
            assertFalse(bytes.toString(StandardCharsets.UTF_8).contains("tok/s"));
            bytes.reset();
            assertThrows(IllegalStateException.class, () -> renderer.render(stream -> {
                stream.accept(AbstractLlmClient.REASONING_START); stream.accept("思考中断");
                throw new IllegalStateException("失败");
            }));
            assertTrue(bytes.toString(StandardCharsets.UTF_8).endsWith("\033[0m" + System.lineSeparator()));
        } finally { System.setOut(previous); }
    }
}
