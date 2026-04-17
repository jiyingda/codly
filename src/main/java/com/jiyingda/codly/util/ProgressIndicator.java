/**
 * @(#)ProgressIndicator.java, 4月 18, 2026.
 * <p>
 * Copyright 2026 chapaof.com. All rights reserved.
 * chapaof.COM PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */
package com.jiyingda.codly.util;

/**
 * 简单的终端进度指示器，使用 wait/notify 替代 busy-wait + sleep。
 * @author jiyingda
 */
@SuppressWarnings("BusyWait")
public class ProgressIndicator {

    private static final String CLEAR_LINE = "\r\033[2K";
    private static final String[] FRAMES = {"|", "/", "-", "\\"};
    private static final long FRAME_DELAY_MS = 320L;

    private final Thread thread;
    private volatile boolean running = false;

    public ProgressIndicator() {
        this.thread = new Thread(this::runLoop, "codly-progress");
        this.thread.setDaemon(true);
    }

    private void runLoop() {
        int frameIndex = 0;
        while (true) {
            synchronized (this) {
                while (!running) {
                    try {
                        wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            System.out.print("\r" + FRAMES[frameIndex]);
            System.out.flush();
            frameIndex = (frameIndex + 1) % FRAMES.length;
            try {
                Thread.sleep(FRAME_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public void start() {
        running = true;
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        notifyAll();
    }

    public void clear() {
        System.out.print(CLEAR_LINE);
        System.out.flush();
    }
}