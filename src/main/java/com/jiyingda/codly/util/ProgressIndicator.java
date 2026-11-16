package com.jiyingda.codly.util;

/** 每次响应独立的终端进度动画；stop 幂等，返回后不再输出并终止线程。 */
public class ProgressIndicator {
    private static final String CLEAR_LINE = "\r\033[2K";
    private static final String[] FRAMES = {"|", "/", "-", "\\"};
    private final Thread thread;
    private boolean started;
    private boolean stopped;

    public ProgressIndicator() {
        thread = new Thread(this::runLoop, "codly-progress");
        thread.setDaemon(true);
    }

    private void runLoop() {
        int frame = 0;
        while (true) {
            synchronized (this) {
                if (stopped) return;
                System.out.print("\r" + FRAMES[frame++ % FRAMES.length]);
                System.out.flush();
            }
            try { Thread.sleep(320); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); return; }
        }
    }

    public synchronized void start() {
        if (started || stopped) return;
        started = true;
        thread.start();
    }

    public synchronized void stop() {
        stopped = true;
        thread.interrupt();
    }

    public void clear() {
        System.out.print(CLEAR_LINE);
        System.out.flush();
    }
}
