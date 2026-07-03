package org.gscript.vm.stdlib;

import org.gscript.vm.GSInterpreter;
import org.gscript.vm.value.GSFunction;
import org.gscript.vm.value.GSInt;
import org.gscript.vm.value.GSNativeFunction;
import org.gscript.vm.value.GSNull;
import org.gscript.vm.value.GSValue;

import java.util.ArrayList;

/**
 * 定时器库：向 gscript 暴露 setTimeout/setInterval/clearTimeout/clearInterval 全局函数。
 *
 * <p>仿 {@link Console} 模式：构造时捕获 {@link GSInterpreter} 引用，
 * 4 个 {@link GSNativeFunction} 通过 {@link GSInterpreter#installTimerGlobals()} 注册到全局域。
 *
 * <p>调用约定（沿用 {@code OP_INVOKE}）：
 * {@code args[0]} = this（全局函数调用时为 global 或 null，本库忽略），
 * {@code args[1..]} = 实际参数。故回调函数位于 args[1]，延迟/周期位于 args[2]，附加参数从 args[3] 开始。
 *
 * <p>回调执行时，传给 {@link GSInterpreter#callFunction} 的参数列表按 {@code OP_INVOKE}
 * 约定构造：{@code [GSNull.NULL(this), ...extraArgs]}。
 */
public class TimerLib {

    private final GSInterpreter interpreter;

    public TimerLib(GSInterpreter interpreter) {
        this.interpreter = interpreter;
    }

    /** setTimeout(callback, delay, ...args) → 返回 timer id（GSInt） */
    public GSNativeFunction setTimeout() {
        return new GSNativeFunction("setTimeout") {
            @Override
            public GSValue call(ArrayList<GSValue> args) {
                GSFunction cb = extractCallback(args);
                if (cb == null) return GSNull.NULL;
                long delay = extractLong(args, 2, 0);
                ArrayList<GSValue> cbArgs = buildCallbackArgs(args, 3);
                int id = interpreter.scheduleTimeout(cb, delay, cbArgs);
                return new GSInt(id);
            }
        };
    }

    /** setInterval(callback, period, ...args) → 返回 timer id（GSInt） */
    public GSNativeFunction setInterval() {
        return new GSNativeFunction("setInterval") {
            @Override
            public GSValue call(ArrayList<GSValue> args) {
                GSFunction cb = extractCallback(args);
                if (cb == null) return GSNull.NULL;
                long period = extractLong(args, 2, 0);
                ArrayList<GSValue> cbArgs = buildCallbackArgs(args, 3);
                int id = interpreter.scheduleInterval(cb, period, cbArgs);
                return new GSInt(id);
            }
        };
    }

    /** clearTimeout(id) → 返回 null */
    public GSNativeFunction clearTimeout() {
        return new GSNativeFunction("clearTimeout") {
            @Override
            public GSValue call(ArrayList<GSValue> args) {
                int id = extractInt(args, 1, 0);
                interpreter.cancelTimer(id);
                return GSNull.NULL;
            }
        };
    }

    /** clearInterval(id) → 返回 null */
    public GSNativeFunction clearInterval() {
        return new GSNativeFunction("clearInterval") {
            @Override
            public GSValue call(ArrayList<GSValue> args) {
                int id = extractInt(args, 1, 0);
                interpreter.cancelTimer(id);
                return GSNull.NULL;
            }
        };
    }

    // ===== 内部工具 =====

    /** 提取回调函数（args[1]，必须 type==6 GSFunction），非法返回 null 并打印警告。 */
    private GSFunction extractCallback(ArrayList<GSValue> args) {
        if (args.size() < 2) {
            System.err.println("TypeError: timer callback expected");
            return null;
        }
        GSValue cb = args.get(1);
        if (cb.type != 6) {
            System.err.println("TypeError: timer callback is not a function (got type " + cb.type + ")");
            return null;
        }
        return (GSFunction) cb;
    }

    /** 从 args[idx] 提取 long，缺省返回 defaultVal。 */
    private long extractLong(ArrayList<GSValue> args, int idx, long defaultVal) {
        if (idx >= args.size()) return defaultVal;
        return args.get(idx).toIntValue();
    }

    /** 从 args[idx] 提取 int，缺省返回 defaultVal。 */
    private int extractInt(ArrayList<GSValue> args, int idx, int defaultVal) {
        if (idx >= args.size()) return defaultVal;
        return args.get(idx).toIntValue();
    }

    /**
     * 构造传给回调的参数列表（OP_INVOKE 约定）。
     *
     * @param nativeArgs setTimeout/setInterval 收到的参数列表
     * @param extraStart 附加参数在 nativeArgs 中的起始索引（setTimeout/setInterval 为 3）
     * @return [GSNull.NULL(this), ...extraArgs]
     */
    private ArrayList<GSValue> buildCallbackArgs(ArrayList<GSValue> nativeArgs, int extraStart) {
        ArrayList<GSValue> cbArgs = new ArrayList<>();
        cbArgs.add(GSNull.NULL);  // args[0] = this（回调无对象上下文，用 null）
        for (int i = extraStart; i < nativeArgs.size(); i++) {
            cbArgs.add(nativeArgs.get(i));
        }
        return cbArgs;
    }
}
