package org.gscript.vm;

import org.gscript.vm.value.GSFunction;
import org.gscript.util.AtomicCounter;
import org.gscript.util.MinPriorityQueue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * 定时器调度器（worker 模型：守护线程计时 + TaskDispatcher 投递到 worker 队列）。
 *
 * <p>架构：
 * <ul>
 *   <li>守护线程 {@code gscript-timer}：负责计时。peek {@link #timerQueue} 头部任务，
 *       {@code wait} 到到期时间，到期后通过 {@link TaskDispatcher#dispatch} 把任务投递到
 *       GSInterpreter worker 的 taskQueue。若是 setInterval（period>0），重新计算下次到期
 *       时间塞回 timerQueue。<b>该线程不跑 gscript 字节码</b>，与解释器解耦。</li>
 *   <li>worker 线程 {@code gscript-worker}：从 taskQueue 取任务（含外部 eval/callFunction
 *       提交的 EvalTask + 本调度器 dispatch 的 TimerTask），串行执行。
 *       单线程串行，与现有调试器（THREAD_ID=1）兼容。</li>
 * </ul>
 *
 * <p>线程安全：{@link #timerQueue} 和 {@link #taskMap} 通过 {@link #lock} 保护；
 * dispatch 调用必须在 synchronized(lock) 内（保留原 readyQueue.offer 的窗口期修复语义）。
 *
 * <p>注意：所有 gscript 回调都在 worker 线程执行（callFunctionDirect 走 eval→push frame→
 * suspendCheck），调试器断点/单步在回调里天然工作。守护线程不接触 GSInterpreter 任何字段。
 */
public class TimerScheduler {

    /**
     * 定时器任务。
     * period==0 表示 setTimeout（一次性）；period>0 表示 setInterval（周期性）。
     */
    static class TimerTask {
        final int id;
        final GSFunction callback;
        final ArrayList args;  // 已按 OP_INVOKE 约定构造：args[0]=this(GSNull), args[1..]=实际参数
        long nextRunTime;  // System.currentTimeMillis() 到期时间戳
        final long period;  // 周期（ms），0=setTimeout
        volatile boolean cancelled;

        TimerTask(int id, GSFunction callback, ArrayList args, long delay, long period) {
            this.id = id;
            this.callback = callback;
            this.args = args;
            this.nextRunTime = System.currentTimeMillis() + Math.max(delay, 0);
            this.period = period;
            this.cancelled = false;
        }
    }

    /** TimerTask 按到期时间排序的比较器（替代 lambda Comparator）。 */
    static class TimerTaskComparator implements MinPriorityQueue.Comparator {
        public int compare(Object a, Object b) {
            TimerTask ta = (TimerTask) a;
            TimerTask tb = (TimerTask) b;
            long diff = ta.nextRunTime - tb.nextRunTime;
            if (diff < 0) return -1;
            if (diff > 0) return 1;
            return 0;
        }
    }

    /**
     * 任务投递接口：到期任务通过此接口投递到执行方（GSInterpreter 的 worker 队列）。
     *
     * <p>解耦设计：TimerScheduler 不直接持有 GSInterpreter 引用，仅依赖此接口。
     * 实现方在 dispatch 内把 TimerTask 包装成 EvalTask offer 到 worker 的 taskQueue。
     */
    public static interface TaskDispatcher {
        void dispatch(TimerTask task);
    }

    /** 保护 timerQueue 和 taskMap 的监视器锁。 */
    private final Object lock = new Object();
    /** 按到期时间排序的待触发队列（守护线程 peek/wait/poll）。 */
    private final MinPriorityQueue timerQueue = new MinPriorityQueue(new TimerTaskComparator());
    /** 任务投递器：到期任务通过此投递到 worker 的 taskQueue（替代原 readyQueue）。 */
    private final TaskDispatcher dispatcher;
    /** id → task 映射，供 cancel 查找。 */
    private final Map taskMap = new HashMap();
    /** 下一个任务 id（从 1 开始，0 保留给"无任务"语义）。 */
    private final AtomicCounter nextId = new AtomicCounter(1);

    private volatile boolean stopped = false;
    private Thread timerThread;

    public TimerScheduler(TaskDispatcher dispatcher) {
        this.dispatcher = dispatcher;
        startTimerThread();
    }

    private void startTimerThread() {
        timerThread = new Thread(new Runnable() {
            public void run() {
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
                        task = (TimerTask) timerQueue.peek();
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
                        taskMap.remove(new Integer(task.id));
                        // setInterval：必须在释放锁前重新入队，否则存在窗口期
                        // （task 已从 timerQueue 移出、尚未塞回，dispatch 也未调用），
                        // worker hasPending 在此窗口看到空 → 误判无任务 → awaitIdle 提前返回。
                        if (task.period > 0 && !task.cancelled && !stopped) {
                            task.nextRunTime = System.currentTimeMillis() + task.period;
                            timerQueue.offer(task);
                            taskMap.put(new Integer(task.id), task);
                        }
                        // 投递给 dispatcher（worker 的 taskQueue）。必须在锁内完成，否则存在窗口期:
                        // task 已从 timerQueue 移出、dispatch 尚未 offer 到 taskQueue，
                        // worker awaitIdle 在此窗口看到双空（timerQueue 空 + taskQueue 空）
                        // → 误判 idle → 提前返回，setTimeout 回调丢失）
                        if (!task.cancelled && dispatcher != null) {
                            dispatcher.dispatch(task);
                        }
                    }
                    // 锁已释放: 此时 task 必在 taskQueue（setTimeout/到期 setInterval，经 dispatch 投递）
                    // 或 timerQueue（setInterval 重入队）中
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
    public int schedule(GSFunction callback, long delayMs, ArrayList args) {
        TimerTask task = new TimerTask(nextId.getAndIncrement(), callback, args, delayMs, 0);
        synchronized (lock) {
            taskMap.put(new Integer(task.id), task);
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
    public int scheduleAtFixedRate(GSFunction callback, long periodMs, ArrayList args) {
        long period = Math.max(periodMs, 1);
        TimerTask task = new TimerTask(nextId.getAndIncrement(), callback, args, period, period);
        synchronized (lock) {
            taskMap.put(new Integer(task.id), task);
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
            TimerTask task = (TimerTask) taskMap.remove(new Integer(id));
            if (task != null) {
                task.cancelled = true;
                timerQueue.remove(task);  // O(n)，定时器数量通常不大
                return true;
            }
            return false;
        }
    }

    /**
     * 计算 worker 主循环应等待的超时时间（用于 taskQueue.poll 超时）。
     *
     * <p>timerQueue 非空返回头部任务剩余毫秒（&gt;=0，到期返回 0）；
     * timerQueue 空（无定时器）返回 -1（worker 应改用 taskQueue.take 无限阻塞等外部任务）。
     *
     * <p>注：到期任务已通过 dispatch 投递到 taskQueue，本方法只反映 timerQueue 状态。
     * worker 用此值作 poll 超时，到期后 taskQueue 应已有 TimerEvalTask。
     */
    public long nextDelayMs() {
        synchronized (lock) {
            if (timerQueue.isEmpty()) return -1;
            TimerTask head = (TimerTask) timerQueue.peek();
            long remaining = head.nextRunTime - System.currentTimeMillis();
            return Math.max(remaining, 0);
        }
    }

    /**
     * 是否还有未触发的定时器任务（timerQueue 非空）。
     *
     * <p>注意：已 dispatch 到 taskQueue 但 worker 尚未执行的任务不在本方法视野内，
     * 需由 GSInterpreter.awaitIdle 联合检查 taskQueue.isEmpty()。
     * 纯 setInterval 脚本永远返回 true（awaitIdle 永不退出，需 DAP terminate 终止）。
     */
    public boolean hasPending() {
        synchronized (lock) {
            return !timerQueue.isEmpty();
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
