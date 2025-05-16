package gscript.vm.value;

import gscript.vm.Interpreter;
import gscript.vm.Env;

import java.util.HashMap;
import java.util.List;

public class GSObject extends GSValue {

    protected HashMap<String, GSValue> properties;

    public GSObject() {
        type = 4;
        properties = new HashMap<>();
    }

    /**
     * 获取对象成员，属性或者函数
     *
     * @param name
     * @return
     */
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
     * 对象调用普通函数
     *
     * @param context 函数上下文
     * @param funRef  调用函数（脚本内定义的函数）
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

    /**
     * 对象调用本地函数
     * @param context
     * @param funRef
     * @param args
     */
    public void callNativeFunction(Interpreter context
            , GSNativeFunction funRef, List<GSValue> args) {
        // 调用本地函数需要手动设置返回值
        context.runStack.push(funRef.call(this, args));
    }

    @Override
    public String getStringValue() {
        return "[object Object]";
    }

    @Override
    public int getIntValue() {
        return 1;
    }

    @Override
    public float getFloatValue() {
        return 1;
    }

    @Override
    public boolean getBoolean() {
        return true;
    }
}
