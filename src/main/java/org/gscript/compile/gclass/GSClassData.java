package org.gscript.compile.gclass;

/**
 * gclass 反序列化结果数据容器。
 *
 * <p>持有从 .gclass 文件解析出的全部信息：
 * <ul>
 *   <li>{@link #src} — 二进制字节码数组（byte[][]，每条指令为 byte[]，code[0]=opcode）</li>
 *   <li>{@link #constantPool} — 常量池（Object[]，索引从 1 开始，0 不用）</li>
 *   <li>{@link #sourceLines} — 顶级字节码索引对应的源码行号（与 {@code src} 平行，1-based）</li>
 *   <li>{@link #sourcePath} — 源文件路径（调试用，可为 null）</li>
 * </ul>
 *
 * <p>反序列化后直接传给 {@link org.gscript.vm.GSInterpreter#eval(byte[][], Object[], int[], String)}
 * 即可执行，无需任何额外转换。
 */
public class GSClassData {

    /** 二进制字节码数组（每条指令为 byte[]，如 {0x04, 0x00, 0x03} = const_s cp[3]） */
    public final byte[][] src;

    /** 常量池（Object[]，索引从 1 开始，0 不用；元素类型：String/Integer/Float/Boolean） */
    public final Object[] constantPool;

    /** 源码行号映射（与 src 平行，1-based，0=未设置），可为 null */
    public final int[] sourceLines;

    /** 源文件路径，可为 null */
    public final String sourcePath;

    /**
     * 函数表（来自 Attributes 段的 FunctionTable 属性），可为 null（无 attributes 段时）。
     *
     * <p>记录文件中所有顶级函数的元数据（名称/起始IP/体长），供调试器和工具链使用。
     * 子函数（函数体内嵌套定义的函数）不在此表中——它们通过 fundef 动态创建。
     */
    public final FunctionEntry[] functions;

    /**
     * 完整源码文本（来自 Attributes 段的 SourceContent 属性），可为 null。
     *
     * <p>attach 调试模式下，VSCode 通过 DAP {@code source} 请求从服务端获取源码，
     * 无需本地源文件。launch 模式下 VSCode 直接从磁盘读源码，此字段不被使用。
     */
    public final String sourceContent;

    /**
     * 函数表条目：记录单个函数的元数据。
     */
    public static class FunctionEntry {
        /** 函数名 */
        public final String name;
        /** 函数体在顶级字节码中的起始索引（fundef 指令的下一指令） */
        public final int startIp;
        /** 函数体指令数（fundef 的 bodyLen 操作数） */
        public final int bodyLen;

        public FunctionEntry(String name, int startIp, int bodyLen) {
            this.name = name;
            this.startIp = startIp;
            this.bodyLen = bodyLen;
        }

        @Override
        public String toString() {
            return name + "@ip" + startIp + "(len=" + bodyLen + ")";
        }
    }

    public GSClassData(byte[][] src, Object[] constantPool, int[] sourceLines, String sourcePath) {
        this(src, constantPool, sourceLines, sourcePath, null, null);
    }

    public GSClassData(byte[][] src, Object[] constantPool, int[] sourceLines, String sourcePath, FunctionEntry[] functions) {
        this(src, constantPool, sourceLines, sourcePath, functions, null);
    }

    public GSClassData(byte[][] src, Object[] constantPool, int[] sourceLines, String sourcePath, FunctionEntry[] functions, String sourceContent) {
        this.src = src;
        this.constantPool = constantPool;
        this.sourceLines = sourceLines;
        this.sourcePath = sourcePath;
        this.functions = functions;
        this.sourceContent = sourceContent;
    }
}
