package com.jiyingda.codly.util;

import org.junit.Test;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.Assert.*;

public class TokenStatsTest {
    @Test public void throughputUsesApiCompletionTokensRatherThanCallbackCount() {
        AtomicLong clock = new AtomicLong(0);
        TokenStats stats = new TokenStats(clock::get);
        stats.markStart(); clock.set(1000); stats.markFirstToken();
        stats.recordChunk(); stats.recordChunk();
        stats.setUsage(100, 20, 120);
        clock.set(2500); stats.markEnd();
        assertEquals(1000, stats.getTimeToFirstTokenMs());
        assertEquals(2500, stats.getTotalTimeMs());
        assertEquals(8.0, stats.getTokensPerSecond(), 0.001);
        assertTrue(stats.formatSummary().contains("100 prompt + 20 completion = 120 tokens"));
        clock.set(5000);
        assertEquals(2500, stats.getTotalTimeMs());
        stats.reset();
        assertFalse(stats.hasUsage()); assertEquals(0, stats.getChunkCount());
        assertEquals(0, stats.getTotalTimeMs());
    }

    @Test public void absentUsageDoesNotInventTokensOrSpeedAndZeroUsageIsValid() {
        AtomicLong clock = new AtomicLong(1000);
        TokenStats stats = new TokenStats(clock::get);
        stats.markStart();
        for (int i = 0; i < 100; i++) stats.recordChunk();
        clock.set(2000); stats.markEnd();
        assertEquals(0, stats.getTokensPerSecond(), 0);
        assertFalse(stats.formatSummary().contains("tok/s"));
        assertFalse(stats.formatSummary().contains("~100"));
        assertTrue(stats.formatSummary().contains("未返回 token 用量"));
        stats.setUsage(0, 0, 0);
        assertTrue(stats.hasUsage());
        assertTrue(stats.formatSummary().contains("0 tokens"));
        stats.setUsage(-1, -1, -1);
        assertEquals(0, stats.getTotalTokens());
    }
}
