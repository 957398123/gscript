package org.gscript.vm.value;

import org.gscript.vm.GSEnv;

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
     * 函数字节码（二进制格式：每条指令为 byte[]，code[0]=opcode，后续为操作数）。
     *
     * <p>由 {@link org.gscript.compile.gclass.BytecodeEncoder} 编码，
     * 或由 {@link org.gscript.compile.gclass.GSClassReader} 从 .gclass 文件反序列化得到。
     * 解释器主循环用 {@code switch(byte opcode)} 分发，操作数通过常量池索引读取。
     */
    public byte[][] src;

    /**
     * 常量池（Object[]，索引从 1 开始，0 不用）。
     *
     * <p>同文件所有函数共享同一个常量池引用——子函数通过 fundef 切片继承父函数的 cp。
     * 元素类型：String（UTF8）/ Integer（Int）/ Float（Float）/ Boolean（Bool）。
     * 解释器执行 const 系列/declare/store/fundef/fstore 时按 CP 索引取值。
     */
    public Object[] constantPool;

    /**
     * 本函数字节码在顶级字节码中的起始偏移（用于调试器把任意帧的 IP 映射回源码行）。
     * 顶级匿名函数该值为 0；通过 fundef 切片得到的子函数该值 = 父函数.baseOffset + 切片起始IP。
     */
    public int baseOffset = 0;

    /**
     * 顶级字节码索引对应的源码行号数组（与顶级字节码平行，1-based，0 表示未设置）。
     *
     * <p>所有函数共享同一个顶级 {@code sourceLines} 数组引用（不随 {@code src} 切片），
     * 配合 {@link #baseOffset} 即可把任意帧的 IP 映射回源码行：
     * {@code sourceLines[baseOffset + (ip - 1)]}。
     *
     * <p>非调试模式下该字段为 null，不影响正常执行。
     */
    public int[] sourceLines = null;

    /**
     * 函数所属源文件路径（调试用，唯一标识文件）。
     *
     * <p>顶级匿名函数在 eval 时设置；子函数通过 fundef 继承父函数的 sourcePath。
     * 非调试模式或单文件兼容场景为 null。调试器据此区分断点所属文件与 stackTrace 报告的源文件，
     * 支持多文件调试（跨文件断点、跨文件调用栈、后加载文件覆盖前文件同名函数）。
     */
    public String sourcePath = null;

    /**
     * 函数所属源文件的完整源码内容（attach 调试模式用，供 DAP source 请求返回）。
     *
     * <p>顶级匿名函数在 eval 时设置；子函数通过 fundef 继承父函数的 sourceContent。
     * 非 attach 调试模式为 null。attach 模式下 VSCode 通过 source 请求从服务端获取源码，
     * 无需本地源文件。
     */
    public String sourceContent = null;

    /**
     * 创建一个函数实例
     *
     * @param name         函数名称
     * @param src          函数字节码（二进制 byte[][]）
     * @param constantPool 常量池（Object[]，同文件函数共享引用）
     * @param env          函数创建时的静态作用域
     */
    public GSFunction(String name, byte[][] src, Object[] constantPool, GSEnv env) {
        this.type = 6;
        this.name = name;
        this.src = src;
        this.constantPool = constantPool;
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
     * 清除到对应类型的域为止
     *
     * @param type 域类型
     */
    public void freeToSpecScope(String type) {
        while (this.env != null && this.env.parent != null) {
            // 这里是清除到对应类型的域
            if (type.equals(env.name)) {
                setEnv(env.parent);
                break;
            }
            setEnv(env.parent);
        }
    }

    /**
     * 将域恢复到指定类型的域为止
     *
     * @param type
     */
    public void returnSpecScope(String type) {
        while (this.env != null && this.env.parent != null) {
            if (type.equals(env.name)) {
                break;
            } else {
                setEnv(env.parent);
            }
        }
    }

}
