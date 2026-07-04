package org.gscript.vm;

import org.gscript.vm.value.GSFunction;
import org.gscript.vm.value.GSValue;

import java.util.ArrayList;

/**
 * 异常对象
 *
 * <p>携带异常抛出点的源码映射信息（sourceLines/sourcePath/baseOffset）和跨函数传播时
 * 追加的调用栈快照（callStack），供顶层未捕获异常打印时输出「文件:行号 + 函数名 + 调用栈」。
 *
 * <p>字段语义：
 * <ul>
 *   <li>{@link #ip}：可变，异常传播时被 {@link #setIp} 重定位到 caller 的 invoke 指令位置，
 *       供 {@link GSFrame#handleException} 做 monitor 范围比对。**不是** throw 点的原始位置。</li>
 *   <li>{@link #originIp}：不变，throw 点的原始字节码 IP，供 {@link #formatMessage} 回退显示。</li>
 *   <li>{@link #originSourceLine}：不变，throw 点源码行（构造时算好），不受 setIp 影响。</li>
 *   <li>{@link #callStack}：异常跨函数传播时追加的 caller 帧信息（从内到外）。</li>
 * </ul>
 */
public class GSException extends RuntimeException {

    /**
     * 抛出异常时所在函数
     */
    private final String function;

    /**
     * 抛出异常时的ip（可变，异常传播时被 setIp 重定位到 caller invoke 指令位置，
     * 供 GSFrame.handleException 做 monitor 范围比对）
     */
    private int ip;

    /**
     * 异常对象
     */
    public GSValue origin;

    // ===== 新增：源码映射快照（throw 点，构造时一次性记录）=====

    /** throw 点 frame.function.sourceLines 引用（可为 null，非调试模式无源码映射） */
    private final int[] sourceLines;

    /** throw 点 frame.function.sourcePath（可为 null） */
    private final String sourcePath;

    /** throw 点 frame.function.baseOffset */
    private final int baseOffset;

    /** throw 点原始字节码 IP（不变，供 formatMessage 回退显示） */
    private final int originIp;

    /** throw 点源码行号（1-based，0=未设置，构造时算好不受 setIp 影响） */
    private final int originSourceLine;

    // ===== 新增：调用栈快照（异常跨函数传播时追加）=====

    /** caller 帧信息列表（从内到外：最先追加的是最近一层 caller） */
    private final ArrayList callStack = new ArrayList();

    /**
     * 是否已因异常断点挂起过（避免同一异常在跨帧传播时反复挂起）。
     * 由 {@link org.gscript.vm.debug.DebugController#checkException} 首次挂起时置 true，
     * 后续传播路径上的 checkException 调用见此标志即跳过，保证一个异常只挂起一次（在 throw 点）。
     * 运行时字段，不参与 gclass 序列化。
     */
    private boolean paused = false;

    /**
     * 调用栈帧信息（caller 快照）。
     */
    public static class StackFrameInfo {
        public final String function;
        public final String sourcePath;
        public final int sourceLine;

        public StackFrameInfo(String function, String sourcePath, int sourceLine) {
            this.function = function;
            this.sourcePath = sourcePath;
            this.sourceLine = sourceLine;
        }
    }

    /**
     * 原始构造器（保留兼容）：仅 function + ip + origin，无源码映射。
     */
    public GSException(String function, int ip, GSValue origin) {
        this.function = function;
        this.ip = ip;
        this.origin = origin;
        this.sourceLines = null;
        this.sourcePath = null;
        this.baseOffset = 0;
        this.originIp = ip;
        this.originSourceLine = 0;
    }

    /**
     * 增强构造器：从 throw 点 frame 提取源码映射信息。
     *
     * @param frame  throw 点的帧（从中取 function.name / sourceLines / sourcePath / baseOffset）
     * @param ip     throw 点字节码 IP（通常为 frame.getIP() - 1）
     * @param origin 异常值
     */
    public GSException(GSFrame frame, int ip, GSValue origin) {
        GSFunction f = frame.function;
        this.function = f.name;
        this.ip = ip;
        this.origin = origin;
        this.sourceLines = f.sourceLines;
        this.sourcePath = f.sourcePath;
        this.baseOffset = f.baseOffset;
        this.originIp = ip;
        this.originSourceLine = computeSourceLine(this.sourceLines, this.baseOffset, ip);
    }

    /**
     * 计算指定指令索引对应的源码行号。
     * 公式与 {@link org.gscript.vm.debug.DebugController#currentLine} 一致：
     * {@code sourceLines[baseOffset + ip]}，其中 ip 已是「当前指令在 src 中的索引」
     * （即 {@code frame.getIP() - 1}，因 getIP 已 incrIP 自增）。
     *
     * @param sourceLines 源码行号数组（与顶级字节码平行，1-based，0=未设置），可为 null
     * @param baseOffset  基偏移（子函数在顶级字节码中的起始偏移）
     * @param ip          当前指令在函数 src 中的索引（= frame.getIP() - 1）
     * @return 源码行号（1-based），无映射时返回 0
     */
    private static int computeSourceLine(int[] sourceLines, int baseOffset, int ip) {
        if (sourceLines == null || sourceLines.length == 0) {
            return 0;
        }
        int idx = baseOffset + ip;
        if (idx < 0 || idx >= sourceLines.length) {
            return 0;
        }
        return sourceLines[idx];
    }

    public void setIp(int ip) {
        this.ip = ip;
    }

    public int getIp() {
        return ip;
    }

    /**
     * 抛出异常时所在函数名（调试器用）
     *
     * @return 函数名
     */
    public String getFunction() {
        return function;
    }

    /** throw 点源码行号（1-based，0=无映射）。 */
    public int getSourceLine() {
        return originSourceLine;
    }

    /** throw 点源文件路径（可为 null）。 */
    public String getSourcePath() {
        return sourcePath;
    }

    /** throw 点原始字节码 IP（不受 setIp 影响，供回退显示）。 */
    public int getOriginIp() {
        return originIp;
    }

    /** 调用栈快照（caller 帧列表，从内到外）。返回引用，调用方不应修改。 */
    public ArrayList getCallStack() {
        return callStack;
    }

    /** 是否已因异常断点挂起过（checkException 用，避免同一异常跨帧反复挂起）。 */
    public boolean isPaused() {
        return paused;
    }

    /** 设置异常断点挂起标志（由 DebugController.checkException 首次挂起时调用）。 */
    public void setPaused(boolean paused) {
        this.paused = paused;
    }

    /**
     * 异常跨函数传播时追加 caller 帧信息。
     *
     * <p>在 OP_INVOKE/OP_CONSTRUCTOR 的 catch 块中、{@link #setIp} 之后调用。
     * 用 caller frame 当前的 getIP()-1（= invoke 指令位置）+ caller 的 sourceLines/baseOffset
     * 计算 caller 源码行，构造 {@link StackFrameInfo} 加入 callStack。
     *
     * @param callerFrame 调用者帧（异常传播到的上一层）
     */
    public void appendCaller(GSFrame callerFrame) {
        GSFunction f = callerFrame.function;
        int callerIp = callerFrame.getIP() - 1;  // invoke 指令在 caller src 中的索引
        int callerLine = computeSourceLine(f.sourceLines, f.baseOffset, callerIp);
        callStack.add(new StackFrameInfo(f.name, f.sourcePath, callerLine));
    }

    /**
     * 格式化未捕获异常的完整错误信息（含 throw 点 + 调用栈）。
     *
     * <p>格式：
     * <pre>
     * Uncaught Error: TypeError: function not exist.
     *   at script.script:42 (in function add)
     *   at script.script:15 (in function main)
     *   at script.script:3 (in &lt;anonymous&gt;)
     * </pre>
     * - 无 sourceLines 时回退 {@code <anonymous>:<originIp>}
     * - 无 sourcePath 时显示 {@code <anonymous>}
     * - callStack 顺序：throw 点在最前，caller 从内到外依次在后
     *
     * @return 多行错误信息字符串
     */
    public String formatMessage() {
        StringBuffer sb = new StringBuffer();
        sb.append("Uncaught Error: ");
        sb.append(origin != null ? origin.toStringValue() : "?");

        // throw 点
        sb.append("\n  at ");
        sb.append(formatLocation(sourcePath, originSourceLine, originIp, function));

        // 调用栈（从内到外）
        for (int i = 0; i < callStack.size(); i++) {
            StackFrameInfo info = (StackFrameInfo) callStack.get(i);
            sb.append("\n  at ");
            sb.append(formatLocation(info.sourcePath, info.sourceLine, -1, info.function));
        }
        return sb.toString();
    }

    /**
     * 格式化单个栈帧位置。
     *
     * @param path   源文件路径（可为 null）
     * @param line   源码行号（1-based，0=无映射）
     * @param fallbackIp 回退 IP（line<=0 时用，<0 表示不显示 IP 回退）
     * @param func   函数名（可为 null）
     */
    private static String formatLocation(String path, int line, int fallbackIp, String func) {
        StringBuffer sb = new StringBuffer();
        sb.append(path != null ? path : "<anonymous>");
        sb.append(":");
        if (line > 0) {
            sb.append(line);
        } else if (fallbackIp >= 0) {
            sb.append(fallbackIp);
        } else {
            sb.append("?");
        }
        sb.append(" (in ");
        String fname = (func != null && func.length() > 0) ? func : "<anonymous>";
        // 顶级匿名函数 name 为 "null"（GSInterpreter.eval 创建时传 "null"），显示为 <anonymous>
        if ("null".equals(fname)) {
            fname = "<anonymous>";
        }
        sb.append(fname);
        sb.append(")");
        return sb.toString();
    }
}
