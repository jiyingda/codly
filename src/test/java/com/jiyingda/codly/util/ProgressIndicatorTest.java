package com.jiyingda.codly.util;

import org.junit.Test;
import java.io.*;
import java.util.*;
import static org.junit.Assert.*;

public class ProgressIndicatorTest {
    @Test public void repeatedResponsesReleaseEveryProgressThreadAndCannotPrintAfterStop() throws Exception {
        PrintStream previous = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        List<ProgressIndicator> indicators = new ArrayList<>();
        Set<Thread> before = Thread.getAllStackTraces().keySet();
        try (PrintStream capture = new PrintStream(output)) {
            System.setOut(capture);
            for (int i = 0; i < 30; i++) {
                ProgressIndicator indicator = new ProgressIndicator(); indicators.add(indicator);
                indicator.start(); indicator.start(); indicator.stop(); indicator.stop();
            }
            for (Thread thread : Thread.getAllStackTraces().keySet()) {
                if (!before.contains(thread) && "codly-progress".equals(thread.getName())) {
                    thread.join(2000); assertFalse("progress thread leaked", thread.isAlive());
                }
            }
            int size = output.size();
            for (ProgressIndicator indicator : indicators) indicator.start();
            assertEquals(size, output.size());
        } finally { indicators.forEach(ProgressIndicator::stop); System.setOut(previous); }
    }
}
