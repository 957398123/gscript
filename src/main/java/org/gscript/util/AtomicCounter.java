package org.gscript.util;

/**
 * 原子计数器（替代 java.util.concurrent.atomic.AtomicInteger）。
 *
 * <p>Java 1.4 兼容：用 synchronized 实现原子性。仅实现项目用到的 API：
 * getAndIncrement / get / set。
 */
public class AtomicCounter {

    private int count;

    public AtomicCounter(int initialValue) {
        this.count = initialValue;
    }

    public synchronized int getAndIncrement() {
        return count++;
    }

    public synchronized int incrementAndGet() {
        return ++count;
    }

    public synchronized int get() {
        return count;
    }

    public synchronized void set(int value) {
        this.count = value;
    }
}
