package org.gscript.vm.value;

import java.util.ArrayList;

import org.gscript.vm.GSInterpreter;

/**
 * 本地函数类
 *
 * <p>两种调用入口：
 * <ul>
 *   <li>{@link #eval(ArrayList)} —— 不带 interpreter，供调试器等外部调用方使用（interp=null）</li>
 *   <li>{@link #eval(ArrayList, GSInterpreter)} —— OP_INVOKE/OP_CONSTRUCTOR type==9 调用入口，
 *       传入当前 interpreter，供需要回调 gscript 函数的原生方法（如 forEach/map/filter）使用</li>
 * </ul>
 *
 * <p>子类实现策略：
 * <ul>
 *   <li><b>纯数据操作</b>（push/pop/charAt/...）：只实现 {@link #call(ArrayList)}，
 *       {@link #call(ArrayList, GSInterpreter)} 默认委托给它，无需感知 interpreter</li>
 *   <li><b>需回调 gscript 函数</b>（forEach/map/filter/sort/...）：override
 *       {@link #call(ArrayList, GSInterpreter)}，用 {@code interp.callFunction(cb, cbArgs)} 回调</li>
 * </ul>
 */
public abstract class GSNativeFunction extends GSObject {

    private String _name;

    public GSNativeFunction(String _name) {
        this.type = 9;
        this._name = _name;
    }

    /**
     * 旧入口：不带 interpreter（等价于 {@code eval(args, null)}）。
     * 保留给调试器等不持有 interpreter 引用的调用方使用。
     *
     * @param args 传入参数
     * @return 返回值（null 归一化为 {@link GSNull#NULL}）
     */
    public final GSValue eval(ArrayList args) {
        return eval(args, null);
    }

    /**
     * OP_INVOKE/OP_CONSTRUCTOR type==9 调用入口。
     *
     * @param args   传入参数（args[0] = this，args[1..] = 实参）
     * @param interp 当前解释器实例，供需要回调的原生方法使用；纯数据操作可忽略
     * @return 返回值（null 归一化为 {@link GSNull#NULL}）
     */
    public GSValue eval(ArrayList args, GSInterpreter interp) {
        GSValue value = call(args, interp);
        if (value == null) {
            value = GSNull.NULL;
        }
        return value;
    }

    /**
     * 纯数据操作的实现入口（现有 push/pop/charAt/... 等无需回调的原生方法实现此方法）。
     *
     * @param args 参数（args[0] = this）
     * @return 结果
     */
    public abstract GSValue call(ArrayList args);

    /**
     * 需要回调 gscript 函数的原生方法 override 此方法（如 forEach/map/filter）。
     * 默认实现委托给 {@link #call(ArrayList)}，保证纯数据操作的原生方法无需感知 interpreter。
     *
     * @param args   参数（args[0] = this，args[1..] = 实参）
     * @param interp 当前解释器实例（OP_INVOKE 调用时非 null；旧入口 eval(args) 时为 null）
     * @return 结果
     */
    public GSValue call(ArrayList args, GSInterpreter interp) {
        return call(args);
    }

    public String toStringValue() {
        return "ƒ " + _name + "() { [native code] }";
    }
}