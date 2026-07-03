package org.gscript.compile.gclass;

import java.util.Map;

/**
 * 二进制指令解码器：将 {@code byte[]} 指令还原为文本形式，供 dump 显示和调试。
 *
 * <p>反向操作于 {@link BytecodeEncoder}——将二进制指令（opcode + operand bytes）
 * 解码为可读文本（如 {@code "const s hello world"}、{@code "arith_op plus"}）。
 *
 * <p>典型用途：
 * <ul>
 *   <li>{@code TestScript dumpgclass} — 打印 gclass 文件的字节码↔源码行映射</li>
 *   <li>调试日志 — 输出当前执行的指令文本</li>
 *   <li>测试对比 — 验证编码/解码往返一致性</li>
 * </ul>
 */
public class BytecodeDecoder {

    /**
     * 将单条二进制指令解码为文本形式。
     *
     * @param code 二进制指令（byte[]，code[0] = opcode）
     * @param cp   常量池（Object[]，索引从 1 开始），可为 null（仅解码无 CP 引用的指令）
     * @return 文本形式（如 "const s hello world"、"jump 5"），与原始文本字节码格式一致
     */
    public static String decode(byte[] code, Object[] cp) {
        byte opcode = code[0];

        // const_* 特殊处理：还原为 "const <typeChar> <value>"
        if (opcode >= GSClassConstants.OP_CONST_A && opcode <= GSClassConstants.OP_CONST_B) {
            String typeChar = GSClassConstants.constTypeChar(opcode);
            int cpIdx = readU2(code, 1);
            Object value = cp != null ? cp[cpIdx] : "?" + cpIdx;
            String valueStr;
            if (opcode == GSClassConstants.OP_CONST_I) {
                valueStr = value instanceof Integer ? Integer.toString(((Integer) value).intValue()) : String.valueOf(value);
            } else if (opcode == GSClassConstants.OP_CONST_F) {
                valueStr = value instanceof Float ? Float.toString(((Float) value).floatValue()) : String.valueOf(value);
            } else if (opcode == GSClassConstants.OP_CONST_B) {
                valueStr = value instanceof Boolean ? ((Boolean) value).toString() : String.valueOf(value);
            } else {
                valueStr = (String) value;  // const_a / const_s → UTF8
            }
            return "const " + typeChar + " " + valueStr;
        }

        String textName = (String) GSClassConstants.OPCODE_TO_TEXT.get(new Byte(opcode));
        if (textName == null) {
            return "unknown_0x" + Integer.toHexString(opcode & 0xFF);
        }

        switch (opcode) {
            case GSClassConstants.OP_ARITH_OP:
                return "arith_op " + subName(GSClassConstants.ARITH_SUB_REV, code[1]);
            case GSClassConstants.OP_COMP:
                return "comp " + subName(GSClassConstants.COMP_SUB_REV, code[1]);
            case GSClassConstants.OP_RELA_OP:
                return "rela_op " + subName(GSClassConstants.RELA_SUB_REV, code[1]);
            case GSClassConstants.OP_PUSHENV:
                return "pushenv " + subName(GSClassConstants.PUSHENV_SUB_REV, code[1]);
            case GSClassConstants.OP_POPENV:
                return "popenv " + subName(GSClassConstants.POPENV_SUB_REV, code[1]);
            case GSClassConstants.OP_NEW:
                return "new " + subName(GSClassConstants.NEW_SUB_REV, code[1]);
            case GSClassConstants.OP_DECLARE:
                return "declare " + cpString(cp, readU2(code, 1));
            case GSClassConstants.OP_STORE:
                return "store " + cpString(cp, readU2(code, 1));
            case GSClassConstants.OP_JUMP:
                return "jump " + readS2(code, 1);
            case GSClassConstants.OP_FALSE_JUMP:
                return "false_jump " + readS2(code, 1);
            case GSClassConstants.OP_LOOP_JUMP:
                return "loop_jump " + readS2(code, 1);
            case GSClassConstants.OP_BLOCK_JUMP:
                return "block_jump " + readS2(code, 1);
            case GSClassConstants.OP_FUNDEF:
                return "fundef " + cpString(cp, readU2(code, 1)) + " " + readU2(code, 3);
            case GSClassConstants.OP_FSTORE:
                return "fstore " + cpString(cp, readU2(code, 1)) + " " + readU2(code, 3);
            case GSClassConstants.OP_INVOKE:
                return "invoke " + readU2(code, 1);
            case GSClassConstants.OP_CONSTRUCTOR:
                return "constructor " + readU2(code, 1);
            case GSClassConstants.OP_TRY_START:
                return "try_start " + readS2(code, 1) + " " + readS2(code, 3) + " "
                        + readS2(code, 5) + " " + readS2(code, 7);
            default:
                // 无操作数指令
                return textName;
        }
    }

    /** 读 u2（无符号 2 字节，Big-Endian） */
    private static int readU2(byte[] code, int offset) {
        return ((code[offset] & 0xFF) << 8) | (code[offset + 1] & 0xFF);
    }

    /** 读 s2（有符号 2 字节，Big-Endian） */
    private static short readS2(byte[] code, int offset) {
        return (short) ((code[offset] << 8) | (code[offset + 1] & 0xFF));
    }

    /** 从常量池取字符串，cp 为 null 时返回占位符 */
    private static String cpString(Object[] cp, int idx) {
        if (cp != null && idx >= 0 && idx < cp.length) {
            return (String) cp[idx];
        }
        return "?" + idx;
    }

    /** 子操作码反查文本名 */
    private static String subName(Map revMap, byte sub) {
        String name = (String) revMap.get(new Byte(sub));
        return name != null ? name : "0x" + Integer.toHexString(sub & 0xFF);
    }
}
