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
