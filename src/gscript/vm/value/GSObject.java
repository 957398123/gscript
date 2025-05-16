package gscript.vm.value;

import gscript.Interpreter;
import gscript.vm.Env;
import gscript.vm.GSException;

import java.util.HashMap;
import java.util.List;

public class GSObject extends GSValue {

    protected HashMap<String, GSValue> properties;

    public GSObject() {
        type = 4;
        properties = new HashMap<>();
    }

    public GSValue getProperty(String name) {
        GSValue value = properties.get(name);
        if (value == null) {
            return GSNull.NULL;
        } else {
            return value;
        }
    }

    /**
     * 设置属性
     *
     * @param name
     * @param value
     */
    public void setProperty(String name, GSValue value) {
        properties.put(name, value);
    }

    /**
     * 对象调用函数
     *
     * @param context 函数上下文
     * @param funRef  调用函数
     * @param args    参数
     * @return
     */
    public void callFunction(Interpreter context
            , GSFunction funRef, List<GSValue> args) {
        // 调用普通函数
        // 创建函数域
        context.addEnv(new Env("function"));
        // 入参this
        context.setNearEnvVariable("this", this);
        GSFunction function = (GSFunction) funRef;
        context.callFunction(function, args);
        context.restNearFunctionEnv();
    }

    @Override
    public String toValueString() {
        return "[object Object]";
    }
}
