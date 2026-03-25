package org.gscript.vm.value;

import org.gscript.vm.GSEnv;
import org.gscript.vm.GSException;

public class GSFunction extends GSObject {

    /**
     * 函数名称
     */
    public String name;

    /**
     * 函数定义时的静态作用域
     */
    public GSEnv env;

    /**
     * 函数字节码
     */
    public String src[][];

    /**
     * 创建一个函数实例
     *
     * @param name 函数名称
     * @param env  函数创建时的静态作用域
     * @param src  函数字节码
     */
    public GSFunction(String name, String[][] src, GSEnv env) {
        this.type = 6;
        this.name = name;
        this.src = src;
        this.env = env;
    }

    /**
     * 从当前函数定义域中找变量的值
     *
     * @param name 变量名称
     * @return 变量值
     */
    public GSValue getVariableFromScope(String name) {
        GSEnv env = this.env;
        while (env != null) {
            GSValue value = env.getVariableValue(name);
            if (value != null) {
                return value;
            }
            env = env.parent;
        }
        return GSNull.NULL;
    }

    /**
     * 往当前域设置值
     *
     * @param name  变量名
     * @param value 变量值
     */
    public void setVariableToScope(String name, GSValue value) throws RuntimeException {
        // 这里必须遍历往上查找
        GSEnv env = this.env;
        while (env != null) {
            if (env.isDeclareVariable(name)) {
                env.addVariableValue(name, value);
                return;
            }
            env = env.parent;
        }
        // 这里模仿严格模式，抛出异常，这里必须抛出虚拟机异常，这样才能获取到异常信息
        throw new RuntimeException(String.format("ReferenceError: %s is not defined", name));
    }

    /**
     * 声明变量到当前域
     *
     * @param name
     */
    public void declareVariableToScope(String name) {
        // 如果变量没有声明，声明变量
        if (!env.isDeclareVariable(name)) {
            env.addVariableValue(name, GSNull.NULL);
        }
    }

    /**
     * 声明并赋值变量到当前域
     *
     * @param name
     */
    public void assignmentVariableToScope(String name, GSValue value) {
        env.addVariableValue(name, value);
    }

    /**
     * 增加域
     *
     * @param type 域类型
     */
    public GSEnv addEnv(String type) {
        return new GSEnv(type, this.env);
    }

    /**
     * 赋值域
     *
     * @param env 域
     */
    public void setEnv(GSEnv env) {
        this.env = env;
    }


    /**
     * 获取当前域
     *
     * @return 域
     */
    public GSEnv getEnv() {
        return env;
    }

    /**
     * 清理域
     *
     * @param type 域类型
     */
    public void freeScope(String type) {
        while (this.env != null && this.env.parent != null) {
            // 这里是清除到对应类型的域
            setEnv(env.parent);
            if (type.equals(env.name)) {
                break;
            }
        }
    }

    /**
     * 将函数域恢复至调用前状态
     * 清理域到function域
     */
    public void resetScope() {
        while (this.env != null && this.env.parent != null) {
            // 这里跟前面不一样，这里是遇到function停下来
            if ("function".equals(env.name)) {
                break;
            } else {
                setEnv(env.parent);
            }
        }
    }

}
