package org.gscript.vm.value;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class GSObject extends GSValue {

    // ===== 静态共享的原生方法实例 =====
    // 所有对象共用同一组函数对象（语义等价于 JS 的 Object.prototype.xxx）。
    // OP_INVOKE 调用时 args[0] 永远是 this（对象本身），故无需闭包捕获，直接操作 args[0]。

    /**
     * keys(): 返回对象全部属性名数组（调试/遍历用，顺序不保证）。
     * <p>对标 JS 的 Object.keys()。成员顺序依赖 {@link HashMap} 实现，不保证一致；
     * 若需有序可改用 LinkedHashMap（当前场景对顺序无要求）。
     */
    private static final GSNativeFunction KEYS = new GSNativeFunction("keys") {
        public GSValue call(ArrayList args) {
            GSObject obj = (GSObject) args.get(0);
            GSArray result = new GSArray();
            int idx = 0;
            Iterator it = obj.members.keySet().iterator();
            while (it.hasNext()) {
                result.members.put(Integer.toString(idx), new GSString((String) it.next()));
                idx++;
            }
            return result;
        }
    };

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
     * 获取对象成员。
     * <p>自有属性优先：成员中已有同名 key 时返回成员值，对标 JS 自有属性优先于原型方法。
     * 这保证业务属性（如名为 {@code "keys"} 的成员）不被 {@code keys()} 方法遮蔽。
     * 仅当成员不存在时，才尝试返回内置方法（当前只有 {@code keys}）。
     *
     * @param name 成员名称
     * @return 成员值；成员不存在且非内置方法名时返回 {@link GSNull#NULL}
     */
    public GSValue getProperty(String name) {
        GSValue value = (GSValue) members.get(name);
        if (value != null) return value;       // 自有属性优先
        if ("keys".equals(name)) return KEYS;  // 缺省才返回 keys 方法
        return GSNull.NULL;
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
    public Map getMembers() {
        return members;
    }
}
