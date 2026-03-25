package org.gscript.vm.value;

import java.util.HashMap;

public class GSObject extends GSValue {

    protected HashMap<String, GSValue> members = new HashMap<>();

    public GSObject() {
        type = 4;
    }

    @Override
    public String toStringValue() {
        return "[object Object]";
    }

    @Override
    public int toIntValue() {
        return 0;
    }

    @Override
    public float toFloatValue() {
        return 0;
    }

    @Override
    public boolean toBoolean() {
        return false;
    }

    /**
     * 获取对象成员
     *
     * @param name 成员名称
     * @return 成员值
     */
    public GSValue getProperty(String name) {
        GSValue value = members.get(name);
        if (value == null) {
            return GSNull.NULL;
        } else {
            return value;
        }
    }

    /**
     * 设置成员属性
     *
     * @param name  成员名称
     * @param value 成员值
     */
    public void setProperty(String name, GSValue value) {
        members.put(name, value);
    }
}
