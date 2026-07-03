package org.gscript.compile.gclass;

import java.util.HashMap;
import java.util.Map;

/**
 * gclass 二进制文件格式的常量定义与映射表。
 *
 * <p>定义：
 * <ul>
 *   <li>文件头 magic / 版本 / flags</li>
 *   <li>常量池 tag（UTF8/Int/Float/Bool）</li>
 *   <li>37 个操作码（0x00-0x24），将文本指令 {@code const} 拆为 5 个独立操作码</li>
 *   <li>带子操作码指令的子码映射（arith_op/comp/rela_op/pushenv/popenv/new）</li>
 *   <li>主操作码文本名 ↔ 二进制码双向映射表</li>
 * </ul>
 *
 * <p>注意：{@code const} 指令在文本形式为 {@code "const a|s|i|f|b value"}，
 * 二进制形式拆为 const_a/const_i/const_f/const_s/const_b 5 个独立操作码，
 * 因此 {@link #TEXT_TO_OPCODE} 中不含 "const" 键——Writer/Reader 需对 const 做特殊分支处理。
 */
public final class GSClassConstants {

    private GSClassConstants() {}

    // ===== 文件头 =====
    /** Magic: ASCII "GSCL" = 0x4753434C */
    public static final int MAGIC = 0x4753434C;
    public static final byte MAJOR_VERSION = 1;
    public static final byte MINOR_VERSION = 1;

    /** flags: bit0 = 有 SourceMap 段 */
    public static final short FLAG_HAS_SOURCE_MAP = 0x0001;
    /** flags: bit1 = 有 sourcePath */
    public static final short FLAG_HAS_SOURCE_PATH = 0x0002;
    /** flags: bit2 = SourceMap 使用 RLE 压缩编码 */
    public static final short FLAG_RLE_SOURCE_MAP = 0x0004;
    /** flags: bit3 = 有 Attributes 段 */
    public static final short FLAG_HAS_ATTRIBUTES = 0x0008;

    // ===== 常量池 tag =====
    public static final byte TAG_UTF8 = 0x01;
    public static final byte TAG_INT = 0x02;
    public static final byte TAG_FLOAT = 0x03;
    public static final byte TAG_BOOL = 0x04;

    // ===== 主操作码（0x00-0x24）=====
    public static final byte OP_NOP = 0x00;
    public static final byte OP_CONST_A = 0x01;  // const a <name>  变量名存 UTF8
    public static final byte OP_CONST_I = 0x02;  // const i <value> 整数值存 Int
    public static final byte OP_CONST_F = 0x03;  // const f <value> 浮点值存 Float
    public static final byte OP_CONST_S = 0x04;  // const s <value> 字符串存 UTF8（解决空格问题）
    public static final byte OP_CONST_B = 0x05;  // const b <value> 布尔值存 Bool
    public static final byte OP_LDA_NULL = 0x06;
    public static final byte OP_LDA_NAN = 0x07;
    public static final byte OP_ARITH_OP = 0x08;
    public static final byte OP_COMP = 0x09;
    public static final byte OP_RELA_OP = 0x0A;
    public static final byte OP_COPY = 0x0B;
    public static final byte OP_COPY2 = 0x0C;
    public static final byte OP_SWAP = 0x0D;
    public static final byte OP_POP = 0x0E;
    public static final byte OP_GETFIELD = 0x0F;
    public static final byte OP_PUTFIELD = 0x10;
    public static final byte OP_DECLARE = 0x11;   // u2 cp_index
    public static final byte OP_STORE = 0x12;     // u2 cp_index
    public static final byte OP_PUSHENV = 0x13;   // u1 sub_op
    public static final byte OP_POPENV = 0x14;    // u1 sub_op
    public static final byte OP_JUMP = 0x15;      // s2 offset
    public static final byte OP_FALSE_JUMP = 0x16;
    public static final byte OP_LOOP_JUMP = 0x17;
    public static final byte OP_BLOCK_JUMP = 0x18;
    public static final byte OP_FUNDEF = 0x19;    // u2 name_cp, u2 body_len
    public static final byte OP_FSTORE = 0x1A;    // u2 name_cp, u2 arg_idx
    public static final byte OP_INVOKE = 0x1B;    // u2 arg_count
    public static final byte OP_CONSTRUCTOR = 0x1C;
    public static final byte OP_RETURN = 0x1D;
    public static final byte OP_NEW = 0x1E;       // u1 sub_op
    public static final byte OP_THROW = 0x1F;
    public static final byte OP_TRY_START = 0x20; // s2×4 offsets
    public static final byte OP_TRY_END = 0x21;
    public static final byte OP_FINALLY_CHECK = 0x22;
    public static final byte OP_INCR = 0x23;
    public static final byte OP_DECR = 0x24;

    // ===== 子操作码：arith_op =====
    public static final byte ARITH_PLUS = 1;
    public static final byte ARITH_MINUS = 2;
    public static final byte ARITH_MUL = 3;
    public static final byte ARITH_DIV = 4;
    public static final byte ARITH_MODULO = 5;
    public static final byte ARITH_NEG = 6;
    public static final byte ARITH_LS = 7;
    public static final byte ARITH_RS = 8;

    // ===== 子操作码：comp =====
    public static final byte COMP_EQ = 1;
    public static final byte COMP_NEQ = 2;
    public static final byte COMP_SEQ = 3;
    public static final byte COMP_SNEQ = 4;
    public static final byte COMP_GT = 5;
    public static final byte COMP_GE = 6;
    public static final byte COMP_LT = 7;
    public static final byte COMP_LE = 8;

    // ===== 子操作码：rela_op =====
    public static final byte RELA_B_AND = 1;
    public static final byte RELA_B_OR = 2;
    public static final byte RELA_B_XOR = 3;
    public static final byte RELA_B_NOT = 4;
    public static final byte RELA_L_NOT = 5;

    // ===== 子操作码：pushenv =====
    public static final byte PUSHENV_FUNCTION = 1;
    public static final byte PUSHENV_LOOP = 2;
    public static final byte PUSHENV_BLOCK = 3;

    // ===== 子操作码：popenv =====
    public static final byte POPENV_LOOP = 1;
    public static final byte POPENV_BLOCK = 2;

    // ===== 子操作码：new =====
    public static final byte NEW_OBJECT = 1;
    public static final byte NEW_ARRAY = 2;

    // ===== 主操作码文本名 → 二进制码映射（不含 const，const 需特殊处理）=====
    public static final Map TEXT_TO_OPCODE = new HashMap();
    /** 二进制码 → 文本操作码名（反序列化用，const_* 统一映射回 "const"） */
    public static final Map OPCODE_TO_TEXT = new HashMap();

    static {
        // const_* 统一映射回文本 "const"（Reader 还原为 String[]{"const", typeChar, value}）
        OPCODE_TO_TEXT.put(new Byte(OP_CONST_A), "const");
        OPCODE_TO_TEXT.put(new Byte(OP_CONST_I), "const");
        OPCODE_TO_TEXT.put(new Byte(OP_CONST_F), "const");
        OPCODE_TO_TEXT.put(new Byte(OP_CONST_S), "const");
        OPCODE_TO_TEXT.put(new Byte(OP_CONST_B), "const");

        // 其余操作码双向映射
        put("lda_null", OP_LDA_NULL);
        put("lda_nan", OP_LDA_NAN);
        put("arith_op", OP_ARITH_OP);
        put("comp", OP_COMP);
        put("rela_op", OP_RELA_OP);
        put("copy", OP_COPY);
        put("copy2", OP_COPY2);
        put("swap", OP_SWAP);
        put("pop", OP_POP);
        put("getfield", OP_GETFIELD);
        put("putfield", OP_PUTFIELD);
        put("declare", OP_DECLARE);
        put("store", OP_STORE);
        put("pushenv", OP_PUSHENV);
        put("popenv", OP_POPENV);
        put("jump", OP_JUMP);
        put("false_jump", OP_FALSE_JUMP);
        put("loop_jump", OP_LOOP_JUMP);
        put("block_jump", OP_BLOCK_JUMP);
        put("fundef", OP_FUNDEF);
        put("fstore", OP_FSTORE);
        put("invoke", OP_INVOKE);
        put("constructor", OP_CONSTRUCTOR);
        put("return", OP_RETURN);
        put("new", OP_NEW);
        put("throw", OP_THROW);
        put("try_start", OP_TRY_START);
        put("try_end", OP_TRY_END);
        put("finally_check", OP_FINALLY_CHECK);
        put("incr", OP_INCR);
        put("decr", OP_DECR);
        put("nop", OP_NOP);
    }

    private static void put(String text, byte code) {
        TEXT_TO_OPCODE.put(text, new Byte(code));
        OPCODE_TO_TEXT.put(new Byte(code), text);
    }

    // ===== 子操作码文本名 → 二进制码映射 =====
    public static final Map ARITH_SUB = new HashMap();
    public static final Map COMP_SUB = new HashMap();
    public static final Map RELA_SUB = new HashMap();
    public static final Map PUSHENV_SUB = new HashMap();
    public static final Map POPENV_SUB = new HashMap();
    public static final Map NEW_SUB = new HashMap();

    /** 子操作码二进制码 → 文本名（反序列化用） */
    public static final Map ARITH_SUB_REV = new HashMap();
    public static final Map COMP_SUB_REV = new HashMap();
    public static final Map RELA_SUB_REV = new HashMap();
    public static final Map PUSHENV_SUB_REV = new HashMap();
    public static final Map POPENV_SUB_REV = new HashMap();
    public static final Map NEW_SUB_REV = new HashMap();

    static {
        ARITH_SUB.put("plus", new Byte(ARITH_PLUS));     ARITH_SUB_REV.put(new Byte(ARITH_PLUS), "plus");
        ARITH_SUB.put("minus", new Byte(ARITH_MINUS));   ARITH_SUB_REV.put(new Byte(ARITH_MINUS), "minus");
        ARITH_SUB.put("mul", new Byte(ARITH_MUL));       ARITH_SUB_REV.put(new Byte(ARITH_MUL), "mul");
        ARITH_SUB.put("div", new Byte(ARITH_DIV));       ARITH_SUB_REV.put(new Byte(ARITH_DIV), "div");
        ARITH_SUB.put("modulo", new Byte(ARITH_MODULO)); ARITH_SUB_REV.put(new Byte(ARITH_MODULO), "modulo");
        ARITH_SUB.put("neg", new Byte(ARITH_NEG));       ARITH_SUB_REV.put(new Byte(ARITH_NEG), "neg");
        ARITH_SUB.put("ls", new Byte(ARITH_LS));         ARITH_SUB_REV.put(new Byte(ARITH_LS), "ls");
        ARITH_SUB.put("rs", new Byte(ARITH_RS));         ARITH_SUB_REV.put(new Byte(ARITH_RS), "rs");

        COMP_SUB.put("eq", new Byte(COMP_EQ));     COMP_SUB_REV.put(new Byte(COMP_EQ), "eq");
        COMP_SUB.put("neq", new Byte(COMP_NEQ));   COMP_SUB_REV.put(new Byte(COMP_NEQ), "neq");
        COMP_SUB.put("seq", new Byte(COMP_SEQ));   COMP_SUB_REV.put(new Byte(COMP_SEQ), "seq");
        COMP_SUB.put("sneq", new Byte(COMP_SNEQ)); COMP_SUB_REV.put(new Byte(COMP_SNEQ), "sneq");
        COMP_SUB.put("gt", new Byte(COMP_GT));     COMP_SUB_REV.put(new Byte(COMP_GT), "gt");
        COMP_SUB.put("ge", new Byte(COMP_GE));     COMP_SUB_REV.put(new Byte(COMP_GE), "ge");
        COMP_SUB.put("lt", new Byte(COMP_LT));     COMP_SUB_REV.put(new Byte(COMP_LT), "lt");
        COMP_SUB.put("le", new Byte(COMP_LE));     COMP_SUB_REV.put(new Byte(COMP_LE), "le");

        RELA_SUB.put("b_and", new Byte(RELA_B_AND)); RELA_SUB_REV.put(new Byte(RELA_B_AND), "b_and");
        RELA_SUB.put("b_or", new Byte(RELA_B_OR));   RELA_SUB_REV.put(new Byte(RELA_B_OR), "b_or");
        RELA_SUB.put("b_xor", new Byte(RELA_B_XOR)); RELA_SUB_REV.put(new Byte(RELA_B_XOR), "b_xor");
        RELA_SUB.put("b_not", new Byte(RELA_B_NOT)); RELA_SUB_REV.put(new Byte(RELA_B_NOT), "b_not");
        RELA_SUB.put("l_not", new Byte(RELA_L_NOT)); RELA_SUB_REV.put(new Byte(RELA_L_NOT), "l_not");

        PUSHENV_SUB.put("function", new Byte(PUSHENV_FUNCTION)); PUSHENV_SUB_REV.put(new Byte(PUSHENV_FUNCTION), "function");
        PUSHENV_SUB.put("loop", new Byte(PUSHENV_LOOP));         PUSHENV_SUB_REV.put(new Byte(PUSHENV_LOOP), "loop");
        PUSHENV_SUB.put("block", new Byte(PUSHENV_BLOCK));       PUSHENV_SUB_REV.put(new Byte(PUSHENV_BLOCK), "block");

        POPENV_SUB.put("loop", new Byte(POPENV_LOOP));   POPENV_SUB_REV.put(new Byte(POPENV_LOOP), "loop");
        POPENV_SUB.put("block", new Byte(POPENV_BLOCK)); POPENV_SUB_REV.put(new Byte(POPENV_BLOCK), "block");

        NEW_SUB.put("Object", new Byte(NEW_OBJECT)); NEW_SUB_REV.put(new Byte(NEW_OBJECT), "Object");
        NEW_SUB.put("Array", new Byte(NEW_ARRAY));    NEW_SUB_REV.put(new Byte(NEW_ARRAY), "Array");
    }

    /**
     * 根据 const 的类型字符返回对应的二进制操作码。
     *
     * @param typeChar "a"|"i"|"f"|"s"|"b"
     * @return 对应的 const_* 操作码
     */
    public static byte constOpcode(String typeChar) {
        if ("a".equals(typeChar)) { return OP_CONST_A; }
        if ("i".equals(typeChar)) { return OP_CONST_I; }
        if ("f".equals(typeChar)) { return OP_CONST_F; }
        if ("s".equals(typeChar)) { return OP_CONST_S; }
        if ("b".equals(typeChar)) { return OP_CONST_B; }
        throw new IllegalArgumentException("Unknown const type: " + typeChar);
    }

    /**
     * 根据 const_* 操作码返回类型字符（反序列化用）。
     */
    public static String constTypeChar(byte opcode) {
        switch (opcode) {
            case OP_CONST_A: return "a";
            case OP_CONST_I: return "i";
            case OP_CONST_F: return "f";
            case OP_CONST_S: return "s";
            case OP_CONST_B: return "b";
            default: throw new IllegalArgumentException("Not a const opcode: 0x" + Integer.toHexString(opcode & 0xFF));
        }
    }

    // ===== 属性名常量 =====
    /** 函数表属性名：记录文件中所有函数的元数据 */
    public static final String ATTR_FUNCTION_TABLE = "FunctionTable";
    /** 源码内容属性名：存储完整源码文本（UTF-8），供 attach 调试模式通过 source 请求返回 */
    public static final String ATTR_SOURCE_CONTENT = "SourceContent";

    /**
     * 返回指定操作码的指令总字节数（含 opcode 字节本身）。
     *
     * <p>用于：
     * <ul>
     *   <li>{@code GSClassReader}：读取时按长度复制原始字节到 {@code byte[]}（不转 String）</li>
     *   <li>{@code BytecodeDecoder}：dump 时按长度解析</li>
     * </ul>
     *
     * @param opcode 操作码
     * @return 指令总字节数（1-9）
     * @throws IllegalArgumentException 未知操作码
     */
    public static int instructionLength(byte opcode) {
        switch (opcode) {
            // 1 字节：无操作数指令
            case OP_NOP:
            case OP_LDA_NULL:
            case OP_LDA_NAN:
            case OP_COPY:
            case OP_COPY2:
            case OP_SWAP:
            case OP_POP:
            case OP_GETFIELD:
            case OP_PUTFIELD:
            case OP_RETURN:
            case OP_THROW:
            case OP_TRY_END:
            case OP_FINALLY_CHECK:
            case OP_INCR:
            case OP_DECR:
                return 1;
            // 2 字节：opcode + 1 字节子操作码
            case OP_ARITH_OP:
            case OP_COMP:
            case OP_RELA_OP:
            case OP_PUSHENV:
            case OP_POPENV:
            case OP_NEW:
                return 2;
            // 3 字节：opcode + u2 操作数（CP 索引 / 跳转偏移 / 计数）
            case OP_CONST_A:
            case OP_CONST_I:
            case OP_CONST_F:
            case OP_CONST_S:
            case OP_CONST_B:
            case OP_DECLARE:
            case OP_STORE:
            case OP_JUMP:
            case OP_FALSE_JUMP:
            case OP_LOOP_JUMP:
            case OP_BLOCK_JUMP:
            case OP_INVOKE:
            case OP_CONSTRUCTOR:
                return 3;
            // 5 字节：opcode + u2 + u2
            case OP_FUNDEF:
            case OP_FSTORE:
                return 5;
            // 9 字节：opcode + s2 × 4
            case OP_TRY_START:
                return 9;
            default:
                throw new IllegalArgumentException("Unknown opcode: 0x" + Integer.toHexString(opcode & 0xFF));
        }
    }
}
