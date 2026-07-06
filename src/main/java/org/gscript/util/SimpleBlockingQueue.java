package org.gscript.util;

import java.util.LinkedList;

/**
 * 简单阻塞队列（替代 java.util.concurrent.LinkedBlockingQueue）。
 *
 * <p>Java 1.4 兼容：LinkedList + synchronized + wait/notify。仅实现项目用到的 API：
 * offer / poll() / poll(timeoutMs) / isEmpty。
 *
 * <p>线程安全：所有方法 synchronized(this)。poll(timeoutMs) 在队列为空时等待，
 * 被 offer 的 notifyAll 唤醒后重新检查。
 */
public class SimpleBlockingQueue {

    private final LinkedList queue = new LinkedList();

    public synchronized void offer(Object e) {
        queue.addLast(e);
        notifyAll();
    }

    /** 非阻塞取头部元素，队列空返回 null。 */
    public synchronized Object poll() {
        if (queue.isEmpty()) {
            return null;
        }
        return queue.removeFirst();
    }

    /**
     * 阻塞取头部元素，最多等待 timeoutMs 毫秒。
     *
     * @param timeoutMs 最大等待毫秒，<=0 等同于非阻塞 poll()
     * @return 头部元素，或超时返回 null
     */
    public synchronized Object poll(long timeoutMs) throws InterruptedException {
        if (timeoutMs <= 0) {
            return poll();
        }
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (queue.isEmpty()) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return null;
            }
            wait(remaining);
        }
        return queue.removeFirst();
    }

    public synchronized boolean isEmpty() {
        return queue.isEmpty();
    }

    public synchronized int size() {
        return queue.size();
    }

    /**
     * 无限阻塞取头部元素，直到有元素或被中断。
     *
     * <p>worker 主循环专用：无定时器任务时阻塞等待外部提交的 EvalTask。
     * 与 {@link #poll(long)} 区别：无超时，仅被 offer 唤醒或 interrupt 中断。
     *
     * @return 头部元素
     * @throws InterruptedException 被中断时抛出
     */
    public synchronized Object take() throws InterruptedException {
        while (queue.isEmpty()) {
            wait();
        }
        return queue.removeFirst();
    }

    /**
     * 清空队列（shutdown 排空剩余任务用）。
     */
    public synchronized void clear() {
        queue.clear();
    }
}
