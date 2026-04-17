/**
 * @(#)TerminalUtils.java, 4月 18, 2026.
 * <p>
 * Copyright 2026 chapaof.com. All rights reserved.
 * chapaof.COM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.jiyingda.codly.util;

import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/**
 * @author jiyingda
 */
public class TerminalUtils {

    private static final Logger logger = LoggerFactory.getLogger(TerminalUtils.class);

    public static Terminal createTerminal() throws IOException {
        // 依次尝试：完整 JNA → 无 JNA → dumb 终端
        boolean[][] candidates = {
                {true, true},   // system, jna
                {true, false},  // system, no-jna
                {true, false},  // dumb
        };

        for (int i = 0; i < candidates.length; i++) {
            boolean system = candidates[i][0];
            boolean jna = candidates[i][1];
            try {
                TerminalBuilder builder = TerminalBuilder.builder()
                        .system(system)
                        .jna(jna);
                if (i == 2) {
                    builder.dumb(true);
                }
                return builder.build();
            } catch (Exception e) {
                if (i == 0) {
                    System.err.println("[terminal] 创建终端失败，进入回退流程。" + terminalDebugContext());
                }
                String desc = i == 0 ? "system=true,jna=true,dumb=false"
                        : i == 1 ? "system=true,jna=false,dumb=false"
                        : "system=true,dumb=true";
                logTerminalFailure(desc, e);
            }
        }
        throw new IOException("Unable to create a terminal");
    }

    private static void logTerminalFailure(String stage, Exception e) {
        logger.error("[terminal] 阶段失败：{} | {}: {}", stage, e.getClass().getSimpleName(), e.getMessage(), e);
    }

    private static String terminalDebugContext() {
        return " TERM=" + System.getenv("TERM")
                + ", COLORTERM=" + System.getenv("COLORTERM")
                + ", console=" + (System.console() != null);
    }
}