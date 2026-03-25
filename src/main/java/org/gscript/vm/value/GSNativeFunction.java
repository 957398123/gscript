package org.gscript.vm.value;

import org.gscript.vm.GSFrame;

import java.util.ArrayList;

/**
 * 本地函数类
 */
public abstract class GSNativeFunction extends GSObject {

    private String _name;

    public GSNativeFunction(String _name) {
        this.type = 9;
        this._name = _name;
    }

    /**
     * 供解释器调用的接口
     *
     * @param args 传入参数
     * @return 返回值
     */
    public final GSValue eval(ArrayList<GSValue> args) {
        GSValue value = call(args);
        if (value == null) {
            value = GSNull.NULL;
        }
        return value;
    }

    /**
     * 继承本地方法需要实现的调用函数
     *
     * @param args 参数
     * @return 结果
     */
    public abstract GSValue call(ArrayList<GSValue> args);

    @Override
    public String toStringValue() {
        return String.format("ƒ %s() { [native code] }", _name);
    }
}