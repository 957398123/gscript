package org.gscript.compile.gclass;

/**
 * 编码后的二进制字节码容器。
 *
 * <p>由 {@link BytecodeEncoder} 产出，包含：
 * <ul>
 *   <li>{@link #instructions} — 二维字节数组（每条指令一个 byte[]），直接供解释器执行</li>
 *   <li>{@link #constantPool} — 常量池（Object[]，索引从 1 开始），含 String/Integer/Float/Boolean</li>
 * </ul>
 *
 * <p>此对象可被：
 * <ul>
 *   <li>{@link org.gscript.vm.GSInterpreter#eval(byte[][], Object[], int[], String)} 直接执行</li>
 *   <li>{@link GSClassWriter} 序列化到 .gclass 文件</li>
 *   <li>{@link BytecodeDecoder} 还原为文本（dump 显示）</li>
 * </ul>
 */
public class EncodedBytecode {

    /** 二进制指令数组（每条指令为 byte[]，如 {0x04, 0x00, 0x03} = const_s cp[3]） */
    public final byte[][] instructions;

    /** 常量池（索引从 1 开始，0 不用；元素类型：String/Integer/Float/Boolean） */
    public final Object[] constantPool;

    public EncodedBytecode(byte[][] instructions, Object[] constantPool) {
        this.instructions = instructions;
        this.constantPool = constantPool;
    }
}
