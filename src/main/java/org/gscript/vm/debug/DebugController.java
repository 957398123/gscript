package org.gscript.vm.debug;

import org.gscript.vm.GSException;
import org.gscript.vm.GSFrame;
import org.gscript.vm.value.GSFunction;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 调试控制器：管理断点、单步模式与解释器线程的挂起/恢复同步。
 *
 * <p>本类是调试器的控制中枢，连接 {@link org.gscript.vm.GSInterpreter}（解释器线程，
 * 每条指令前调用 {@link #suspendCheck}）与 DAP 适配器（DAP 线程，调用
 * {@link #continueRun}/{@link #step}/{@link #pause}/{@link #terminate} 等控制方法）。
 *
 * <p>线程模型：
 * <ul>
 *   <li>解释器线程：执行脚本，每条指令前调用 {@link #suspendCheck}，若命中断点/单步/暂停请求，
 *       则在 {@link #lock} 上 {@code wait()} 阻塞，直到 DAP 线程唤醒。</li>
 *   <li>DAP 线程：处理客户端请求，通过 {@code continue/step/terminate} 唤醒解释器；
 *       在解释器挂起期间读取其调用栈与变量（此时解释器阻塞，状态稳定）。</li>
 * </ul>
 *
 * <p>断点重触发避免：用 {@link #prevLine} 记录上一条已执行指令的源码行，仅当"行号发生变化
 * 且新行是断点"时才挂起，避免同一行多条指令反复触发断点。
 *
 * <p>单步语义（基于源码行号 + 调用栈深度）：
 * <ul>
 *   <li>STEP_IN：任意帧，行号变化即挂起。</li>
 *   <li>STEP_OVER：仅当深度 ≤ 步进起始深度且行号变化时挂起（跳过函数调用的内部执行）。</li>
 *   <li>STEP_OUT：仅当深度 &lt; 步进起始深度时挂起（从当前帧返回到调用方）。</li>
 * </ul>
 */
public class DebugController {

    /** 单步模式：无 */
    public static final int STEP_NONE = 0;
    /** 单步模式：步入 */
    public static final int STEP_IN = 1;
    /** 单步模式：步过 */
    public static final int STEP_OVER = 2;
    /** 单步模式：步出 */
    public static final int STEP_OUT = 3;

    /**
     * 当前断点集合：文件路径 → 该文件的断点行号集合（源码行，1-based）。
     *
     * <p>多文件调试时按文件路径区分断点，避免不同文件相同行号互相干扰。
     * 单文件场景只有一个 key。key 为 null 时表示非多文件调试的兼容断点。
     */
    private final Map<String, Set<Integer>> breakpoints = new HashMap<>();

    /** 当前单步模式 */
    private int stepMode = STEP_NONE;
    /** 单步起始时的调用栈深度（用于 STEP_OVER / STEP_OUT） */
    private int stepDepth = 0;
    /** 单步起始时的源码行号（用于检测行号变化） */
    private int stepOriginLine = 0;
    /** 单步起始时的源文件路径（用于检测跨文件行号相同的情况） */
    private String stepOriginPath = null;

    /**
     * 每帧的"上一条已执行指令源码行号"（断点重触发避免）。
     *
     * <p>用 IdentityHashMap 而非全局变量，因为函数调用会改变当前行号，
     * 返回后若用全局 prevLine 会误判"行号变化"导致断点重触发。
     * per-frame 方案确保函数调用不影响外层帧的断点判断。
     */
    private final java.util.IdentityHashMap<GSFrame, Integer> framePrevLines = new java.util.IdentityHashMap<>();

    /** 解释器是否处于挂起状态 */
    private volatile boolean suspended = false;
    /** 用户请求暂停（运行中点击 pause） */
    private volatile boolean pauseRequested = false;
    /** 调试会话是否已终止 */
    private volatile boolean terminated = false;
    /** 是否在脚本入口处暂停（stopOnEntry） */
    private volatile boolean stopOnEntry = false;
    /** stopOnEntry 是否已触发（确保只在首条指令触发一次，而非每个函数帧） */
    private boolean stopOnEntryTriggered = false;
    /** 是否在抛出异常时暂停（exception breakpoints） */
    private volatile boolean pauseOnException = false;

    /** 同步锁：解释器线程 wait / DAP 线程 notify 均基于此对象 */
    private final Object lock = new Object();

    // ---- 挂起时的快照状态（供 DAP 线程读取）----
    /** 挂起时的栈顶帧 */
    private GSFrame suspendedFrame;
    /** 挂起时的栈深度 */
    private int suspendedDepth;
    /** 挂起时的源码行号 */
    private int suspendedLine;
    /** 挂起原因（DAP "stopped" 事件的 reason 字段） */
    private String suspendedReason;

    /** 挂起回调：解释器线程挂起时通知 DAP 线程发送 "stopped" 事件 */
    private SuspendListener suspendListener;

    /**
     * 挂起监听器。
     * 解释器线程在挂起时调用 {@link #onSuspended}，DAP 适配器据此向客户端发送 "stopped" 事件。
     */
    public interface SuspendListener {
        /**
         * @param reason  挂起原因（"breakpoint" / "step" / "pause" / "entry" / "exception"）
         * @param frame   挂起时的栈顶帧
         * @param depth   挂起时的栈深度
         * @param line    挂起时的源码行号
         */
        void onSuspended(String reason, GSFrame frame, int depth, int line);
    }

    public void setSuspendListener(SuspendListener listener) {
        this.suspendListener = listener;
    }

    // =========================================================================
    //  DAP 线程调用的控制方法
    // =========================================================================

    /**
     * 设置指定文件的断点行号集合（整体替换该文件的断点）。
     *
     * @param path  源文件路径（断点所属文件，作为 key 区分多文件），可为 null
     * @param lines 断点行号集合（1-based），可为空或 null（清空该文件断点）
     */
    public void setBreakpoints(String path, Collection<Integer> lines) {
        synchronized (lock) {
            if (lines == null || lines.isEmpty()) {
                breakpoints.remove(path);
            } else {
                breakpoints.put(path, new HashSet<>(lines));
            }
        }
    }

    /**
     * 设置是否在脚本入口处暂停。
     */
    public void setStopOnEntry(boolean stopOnEntry) {
        this.stopOnEntry = stopOnEntry;
    }

    /**
     * 设置是否在抛出异常时暂停。
     */
    public void setPauseOnException(boolean pauseOnException) {
        this.pauseOnException = pauseOnException;
    }

    /**
     * 继续执行（从挂起状态恢复，全速运行直到下一个断点/暂停请求）。
     */
    public void continueRun() {
        synchronized (lock) {
            stepMode = STEP_NONE;
            suspended = false;
            lock.notifyAll();
        }
    }

    /**
     * 单步执行。调用此方法前解释器必须处于挂起状态。
     *
     * @param mode 单步模式（{@link #STEP_IN} / {@link #STEP_OVER} / {@link #STEP_OUT}）
     */
    public void step(int mode) {
        synchronized (lock) {
            stepMode = mode;
            stepDepth = suspendedDepth;
            stepOriginLine = suspendedLine;
            stepOriginPath = suspendedFrame != null ? suspendedFrame.function.sourcePath : null;
            suspended = false;
            lock.notifyAll();
        }
    }

    /**
     * 请求暂停（运行中点击 pause）。解释器在下一次 {@link #suspendCheck} 时挂起。
     */
    public void pause() {
        synchronized (lock) {
            pauseRequested = true;
        }
    }

    /**
     * 终止调试会话。唤醒挂起的解释器线程，使其抛出 {@link DebugAbortException} 终止脚本。
     */
    public void terminate() {
        synchronized (lock) {
            terminated = true;
            suspended = false;
            pauseRequested = false;
            lock.notifyAll();
        }
    }

    // =========================================================================
    //  解释器线程调用的检查方法
    // =========================================================================

    /**
     * 解释器在每条指令执行前调用。若命中断点/单步/暂停请求，则阻塞直到 DAP 线程唤醒。
     *
     * @param frame 当前栈顶帧
     * @param depth 当前调用栈深度
     */
    public void suspendCheck(GSFrame frame, int depth) {
        if (terminated) {
            throw new DebugAbortException();
        }

        int line = currentLine(frame);

        boolean shouldSuspend = false;
        String reason = null;

        synchronized (lock) {
            if (terminated) {
                throw new DebugAbortException();
            }

            // 获取当前帧的上一条已执行行号（per-frame，避免函数调用返回后误判行号变化）
            Integer prevLineObj = framePrevLines.get(frame);
            int prevLine = (prevLineObj != null) ? prevLineObj : 0;

            // 1. 入口暂停（仅触发一次，确保只在脚本首条指令而非每个函数帧）
            if (stopOnEntry && !stopOnEntryTriggered) {
                stopOnEntryTriggered = true;
                shouldSuspend = true;
                reason = "entry";
            }
            // 2. 用户请求暂停
            else if (pauseRequested) {
                shouldSuspend = true;
                reason = "pause";
                pauseRequested = false;
            }
            // 3. 断点命中（行号变化 + 当前文件该行是断点）
            //    按当前帧函数所属文件取断点集合，避免不同文件相同行号互相干扰
            else if (line > 0 && line != prevLine) {
                Set<Integer> bps = breakpoints.get(frame.function.sourcePath);
                if (bps != null && bps.contains(line)) {
                    shouldSuspend = true;
                    reason = "breakpoint";
                }
            }
            // 4. 单步命中
            else if (stepMode != STEP_NONE) {
                switch (stepMode) {
                    case STEP_IN: {
                        // 任意帧，行号变化或文件变化即挂起
                        // （跨文件调用时行号可能相同，如 multi_b:9 → multi_a:9，需额外比较文件路径）
                        String currentPath = frame.function.sourcePath;
                        boolean pathChanged = (stepOriginPath == null) ? currentPath != null
                                : !stepOriginPath.equals(currentPath);
                        if (line > 0 && (line != stepOriginLine || pathChanged)) {
                            shouldSuspend = true;
                            reason = "step";
                        }
                        break;
                    }
                    case STEP_OVER: {
                        // 深度不超过起始深度（跳过函数调用内部），且行号变化或文件变化
                        String currentPath = frame.function.sourcePath;
                        boolean pathChanged = (stepOriginPath == null) ? currentPath != null
                                : !stepOriginPath.equals(currentPath);
                        if (depth <= stepDepth && line > 0
                                && (line != stepOriginLine || pathChanged)) {
                            shouldSuspend = true;
                            reason = "step";
                        }
                        break;
                    }
                    case STEP_OUT: {
                        // 深度小于起始深度（已返回到调用方）
                        if (depth < stepDepth && line > 0) {
                            shouldSuspend = true;
                            reason = "step";
                        }
                        break;
                    }
                }
            }

            // 更新当前帧的上一条已执行行号（无论是否挂起都更新，保证断点重触发判断正确）
            framePrevLines.put(frame, line);

            if (shouldSuspend) {
                stepMode = STEP_NONE;
                suspended = true;
                suspendedFrame = frame;
                suspendedDepth = depth;
                suspendedLine = line;
                suspendedReason = reason;
            }
        }

        if (shouldSuspend) {
            // 在锁外通知 DAP 线程发送 "stopped" 事件（避免持锁阻塞 I/O）
            // 状态已在锁内设置完毕，DAP 线程可安全读取
            if (suspendListener != null) {
                suspendListener.onSuspended(reason, frame, depth, line);
            }
            // 阻塞等待 DAP 线程唤醒
            synchronized (lock) {
                while (suspended && !terminated) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new DebugAbortException();
                    }
                }
                if (terminated) {
                    throw new DebugAbortException();
                }
            }
        }
    }

    /**
     * 异常断点检查。解释器在 catch 到 {@link GSException} 时调用。
     * 若开启了异常断点且异常未被本帧 try/catch 捕获（将向上抛出），则挂起。
     *
     * @param frame     当前帧
     * @param depth     当前栈深度
     * @param exception 异常对象
     * @return true 表示应挂起（解释器将阻塞）；false 表示不挂起，继续正常异常处理
     */
    public boolean checkException(GSFrame frame, int depth, GSException exception) {
        if (!pauseOnException) {
            return false;
        }
        synchronized (lock) {
            if (terminated) {
                return false;
            }
            // 仅在异常将向上抛出（未被本帧捕获）时挂起；这里简化处理：所有 throw 都挂起
            suspended = true;
            suspendedFrame = frame;
            suspendedDepth = depth;
            suspendedLine = currentLine(frame);
            suspendedReason = "exception";
        }
        if (suspendListener != null) {
            suspendListener.onSuspended("exception", frame, depth, suspendedLine);
        }
        synchronized (lock) {
            while (suspended && !terminated) {
                try {
                    lock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new DebugAbortException();
                }
            }
            if (terminated) {
                throw new DebugAbortException();
            }
        }
        return true;
    }

    // =========================================================================
    //  状态读取（DAP 线程在解释器挂起时调用）
    // =========================================================================

    /** 解释器是否处于挂起状态。 */
    public boolean isSuspended() {
        return suspended;
    }

    /** 调试会话是否已终止。 */
    public boolean isTerminated() {
        return terminated;
    }

    /** 获取挂起时的栈顶帧。 */
    public GSFrame getSuspendedFrame() {
        return suspendedFrame;
    }

    /** 获取挂起时的栈深度。 */
    public int getSuspendedDepth() {
        return suspendedDepth;
    }

    /** 获取挂起时的源码行号。 */
    public int getSuspendedLine() {
        return suspendedLine;
    }

    /** 获取挂起原因。 */
    public String getSuspendedReason() {
        return suspendedReason;
    }

    // =========================================================================
    //  工具方法
    // =========================================================================

    /**
     * 计算帧当前指令对应的源码行号。
     *
     * <p>解释器在 {@code incrIP()} 之后调用本方法，因此当前指令索引为 {@code getIP() - 1}。
     * 映射公式：{@code sourceLines[baseOffset + (ip - 1)]}。
     *
     * @param frame 帧
     * @return 源码行号（1-based），无源码映射时返回 0
     */
    public static int currentLine(GSFrame frame) {
        GSFunction function = frame.function;
        if (function.sourceLines == null || function.sourceLines.length == 0) {
            return 0;
        }
        int idx = function.baseOffset + frame.getIP() - 1;
        if (idx < 0 || idx >= function.sourceLines.length) {
            return 0;
        }
        return function.sourceLines[idx];
    }
}
