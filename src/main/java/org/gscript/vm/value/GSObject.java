package org.gscript.vm.value;

import java.util.HashMap;

public class GSObject extends GSValue {

    protected HashMap members = new HashMap();

    public GSObject() {
        type = 4;
    }

    public String toStringValue() {
        return "[object Object]";
    }

    public int toIntValue() {
        return 0;
    }

    public float toFloatValue() {
        return 0;
    }

    public boolean toBoolean() {
        // 对象总是 truthy（JS 语义：任何对象/数组/函数都为 true）
        return true;
    }

    /**
     * 获取对象成员
     *
     * @param name 成员名称
     * @return 成员值
     */
    public GSValue getProperty(String name) {
        GSValue value = (GSValue) members.get(name);
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

    /**
     * 获取全部成员（调试器变量监视用）
     *
     * @return 成员名 -> 值 的视图
     */
    public java.util.Map getMembers() {
        return members;
    }
}
