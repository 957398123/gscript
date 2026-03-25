package org.gscript.vm;

import org.gscript.vm.value.GSValue;

import java.util.HashMap;

public class GSEnv {

    public GSEnv parent;

    /**
     * 变量域类型
     * global 顶级域
     * function 函数域
     * block 块级域
     * loop 循环域
     */
    public String name;

    /**
     * 变量存放
     */
    private HashMap<String, GSValue> values = new HashMap<>();

    public GSEnv(String name, GSEnv parent) {
        this.name = name;
        this.parent = parent;
    }

    /**
     * 获取变量值
     *
     * @param name 变量名称
     * @return
     */
    public GSValue getVariableValue(String name) {
        return values.get(name);
    }

    /**
     * 增加变量值
     *
     * @param name  变量名称
     * @param value 变量值
     */
    public void addVariableValue(String name, GSValue value) {
        values.put(name, value);
    }

    /**
     * 变量是否已经声明
     *
     * @param name 变量名称
     * @return 是否声明
     */
    public boolean isDeclareVariable(String name) {
        return values.containsKey(name);
    }
}
