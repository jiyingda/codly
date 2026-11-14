package com.jiyingda.codly.core;

import java.util.HashSet;
import java.util.Set;

/** 每轮请求独立的取消信号，可关闭正在阻塞的 HTTP 请求和子进程。 */
public final class CancellationToken {
    private boolean cancelled;
    private final Set<Runnable> actions = new HashSet<>();

    public synchronized boolean isCancelled() {
        return cancelled;
    }

    public synchronized void register(Runnable action) {
        if (cancelled) {
            action.run();
        } else {
            actions.add(action);
        }
    }

    public synchronized void unregister(Runnable action) {
        actions.remove(action);
    }

    public void cancel() {
        Set<Runnable> pending;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            pending = Set.copyOf(actions);
            actions.clear();
        }
        pending.forEach(Runnable::run);
    }
}
