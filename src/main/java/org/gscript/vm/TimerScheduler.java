package org.gscript.vm;

import org.gscript.vm.value.GSFunction;
import org.gscript.vm.value.GSValue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 定时器调度器（方案 B：守护线程计时 + 主线程串行执行回调）。
 *
 * <p>架构：
 * <ul>
 *   <li>守护线程 {@code gscript-timer}：负责计时。peek {@link #timerQueue} 头部任务，
 *       {@code wait} 到到期时间，到期后把任务从 timerQueue 移到 {@link #readyQueue}。
 *       若是 setInterval（period>0），重新计算下次到期时间塞回 timerQueue。
 *       <b>该线程不跑 gscript 字节码</b>，与解释器解耦。</li>
 *   <li>主解释器线程：通过 {@link #pollReady(long)} 从 {@link #readyQueue} 取到期任务，
 *       同步调用 {@code GSInterpreter.callFunction(cb, args)} 执行回调。
 *       单线程串行，与现有调试器（THREAD_ID=1）兼容。</li>
 * </ul>
 *
 * <p>线程安全：{@link #timerQueue} 和 {@link #taskMap} 通过 {@link #lock} 保护；
 * {@link #readyQueue} 用 {@link LinkedBlockingQueue} 自带的并发安全。
 *
 * <p>注意：所有 gscript 回调都在主线程执行（callFunction 走 eval→push frame→suspendCheck），
 * 调试器断点/单步在回调里天然工作。守护线程不接触 GSInterpreter 任何字段。
 */
public class TimerScheduler {

    /**
     * 定时器任务。
     * period==0 表示 setTimeout（一次性）；period>0 表示 setInterval（周期性）。
     */
    static class TimerTask {
        final int id;
        final GSFunction callback;
        final ArrayList<GSValue> args;  // 已按 OP_INVOKE 约定构造：args[0]=this(GSNull), args[1..]=实际参数
        long nextRunTime;  // System.currentTimeMillis() 到期时间戳
        final long period;  // 周期（ms），0=setTimeout
        volatile boolean cancelled;

        TimerTask(int id, GSFunction callback, ArrayList<GSValue> args, long delay, long period) {
            this.id = id;
            this.callback = callback;
            this.args = args;
            this.nextRunTime = System.currentTimeMillis() + Math.max(delay, 0);
            this.period = period;
            this.cancelled = false;
        }
    }

    /** 保护 timerQueue 和 taskMap 的监视器锁。 */
    private final Object lock = new Object();
    /** 按到期时间排序的待触发队列（守护线程 peek/wait/poll）。 */
    private final PriorityQueue<TimerTask> timerQueue = new PriorityQueue<>(
            (a, b) -> Long.compare(a.nextRunTime, b.nextRunTime));
    /** 已到期、待主线程取走的任务队列（守护线程 offer，主线程 poll）。 */
    private final LinkedBlockingQueue<TimerTask> readyQueue = new LinkedBlockingQueue<>();
    /** id → task 映射，供 cancel 查找。 */
    private final Map<Integer, TimerTask> taskMap = new HashMap<>();
    /** 下一个任务 id（从 1 开始，0 保留给"无任务"语义）。 */
    private final AtomicInteger nextId = new AtomicInteger(1);

    private volatile boolean stopped = false;
    private Thread timerThread;

    public TimerScheduler() {
        startTimerThread();
    }

    private void startTimerThread() {
        timerThread = new Thread(() -> {
            while (!stopped) {
                TimerTask task;
                synchronized (lock) {
                    // 队列空：等待新任务被 schedule 唤醒
                    while (!stopped && timerQueue.isEmpty()) {
                        try {
                            lock.wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    if (stopped) return;
                    task = timerQueue.peek();
                    long now = System.currentTimeMillis();
                    long waitTime = task.nextRunTime - now;
                    if (waitTime > 0) {
                        // 未到期：等待剩余时间（或被新任务/cancel 唤醒后重新 peek）
                        try {
                            lock.wait(waitTime);
                        } catch (InterruptedException e) {
                            return;
                        }
                        continue;
                    }
                    // 到期：移出 timerQueue
                    timerQueue.poll();
                    taskMap.remove(task.id);
                    // setInterval：必须在释放锁前重新入队，否则存在窗口期
                    // （task 已从 timerQueue 移出、尚未塞回，readyQueue 也未 offer），
                    // 主线程 hasPending 在此窗口看到双空 → 误判无任务 → event loop 提前退出。
                    if (task.period > 0 && !task.cancelled && !stopped) {
                        task.nextRunTime = System.currentTimeMillis() + task.period;
                        timerQueue.offer(task);
                        taskMap.put(task.id, task);
                    }
                }
                // 投递到 readyQueue 供主线程取（锁外操作，readyQueue 自带并发安全）
                // 此时 setInterval 任务已重新入 timerQueue，hasPending 不会看到双空。
                if (!task.cancelled) {
                    readyQueue.offer(task);
                }
            }
        }, "gscript-timer");
        timerThread.setDaemon(true);
        timerThread.start();
    }

    /**
     * 调度一次性定时器（setTimeout）。
     *
     * @param callback 回调 gscript 函数
     * @param delayMs  延迟毫秒（负数当 0 处理）
     * @param args     回调参数列表（args[0]=this, args[1..]=实际参数）
     * @return 任务 id（可用于 clearTimeout）
     */
    public int schedule(GSFunction callback, long delayMs, ArrayList<GSValue> args) {
        TimerTask task = new TimerTask(nextId.getAndIncrement(), callback, args, delayMs, 0);
        synchronized (lock) {
            taskMap.put(task.id, task);
            timerQueue.offer(task);
            lock.notifyAll();  // 唤醒守护线程重新 peek（新任务可能更早到期）
        }
        return task.id;
    }

    /**
     * 调度周期性定时器（setInterval）。
     *
     * @param callback 回调 gscript 函数
     * @param periodMs 周期毫秒（&lt;1 当 1 处理，避免忙等）
     * @param args     回调参数列表
     * @return 任务 id（可用于 clearInterval）
     */
    public int scheduleAtFixedRate(GSFunction callback, long periodMs, ArrayList<GSValue> args) {
        long period = Math.max(periodMs, 1);
        TimerTask task = new TimerTask(nextId.getAndIncrement(), callback, args, period, period);
        synchronized (lock) {
            taskMap.put(task.id, task);
            timerQueue.offer(task);
            lock.notifyAll();
        }
        return task.id;
    }

    /**
     * 取消定时器（clearTimeout/clearInterval 共用）。
     *
     * @param id 任务 id
     * @return true 表示取消成功，false 表示任务不存在或已执行完
     */
    public boolean cancel(int id) {
        synchronized (lock) {
            TimerTask task = taskMap.remove(id);
            if (task != null) {
                task.cancelled = true;
                timerQueue.remove(task);  // O(n)，定时器数量通常不大
                return true;
            }
            return false;
        }
    }

    /**
     * 主线程从 readyQueue 取到期任务（阻塞）。
     *
     * @param timeoutMs 最大等待毫秒（0=非阻塞立即返回，&lt;0 同 0）
     * @return 到期任务，或超时/shutdown 返回 null
     */
    public TimerTask pollReady(long timeoutMs) throws InterruptedException {
        if (timeoutMs <= 0) {
            return readyQueue.poll();
        }
        return readyQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 计算主线程 pollReady 应等待的超时时间。
     *
     * <p>readyQueue 非空返回 0（立即取）；timerQueue 非空返回头部任务剩余毫秒（&gt;=0）；
     * 两者都空返回 -1（不应发生，调用方应先检查 hasPending）。
     */
    public long nextDelayMs() {
        synchronized (lock) {
            if (!readyQueue.isEmpty()) return 0;
            if (timerQueue.isEmpty()) return -1;
            TimerTask head = timerQueue.peek();
            long remaining = head.nextRunTime - System.currentTimeMillis();
            return Math.max(remaining, 0);
        }
    }

    /**
     * 是否还有未完成的定时器任务。
     *
     * <p>timerQueue 和 readyQueue 都空才返回 false。
     * 纯 setInterval 脚本永远返回 true（event loop 永不退出，需 disconnect 终止）。
     */
    public boolean hasPending() {
        synchronized (lock) {
            return !timerQueue.isEmpty() || !readyQueue.isEmpty();
        }
    }

    /**
     * 关闭调度器（停止守护线程）。shutdown 后不应再 schedule。
     */
    public void shutdown() {
        stopped = true;
        synchronized (lock) {
            lock.notifyAll();
        }
        if (timerThread != null) {
            timerThread.interrupt();
        }
    }
}
