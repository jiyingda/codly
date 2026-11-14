package com.jiyingda.codly.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class ConversationTitleTest {
    @Test public void cleansModelFormattingAndRejectsEmptyOrStructuredResponses() {
        assertEquals("优化桌面命名", ConversationTitle.fromModel("<think>分析任务</think>\n标题：“优化桌面命名”\n额外说明"));
        for (String invalid : new String[] {null, "", "   ", "[]", "{}", "null", "```json\n{}\n```", "<think>unfinished"}) {
            assertNull(ConversationTitle.fromModel(invalid));
        }
    }

    @Test public void titleLengthUsesUnicodeCodePoints() {
        String name = "😀".repeat(25);
        assertEquals("😀".repeat(20), ConversationTitle.fromModel(name));
        assertEquals("😀".repeat(20), ConversationTitle.fallback(name));
    }

    @Test public void fallbackUsesFirstMessageAndNormalizesWhitespace() {
        assertEquals("帮我 优化桌面入口", ConversationTitle.fallback("  帮我\n优化桌面入口  "));
        assertEquals("新的对话", ConversationTitle.fallback("  "));
        assertEquals(20, ConversationTitle.fallback("a".repeat(100)).length());
    }
}
