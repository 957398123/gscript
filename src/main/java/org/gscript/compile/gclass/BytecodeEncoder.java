package org.gscript.compile.gclass;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文本字节码编码器：将 1D 文本指令（{@code ArrayList<String>}）编码为二进制内存表示
 * （{@code byte[][]} + {@code Object[]} 常量池）。
 *
 * <p>核心逻辑提取自 {@link GSClassWriter}，但输出到内存而非流。
 * 编码结果 {@link EncodedBytecode} 可被：
 * <ul>
 *   <li>{@link org.gscript.vm.GSInterpreter} 直接执行（{@code eval(byte[][], Object[], ...)}）</li>
 *   <li>{@link GSClassWriter} 序列化到 .gclass 文件</li>
 *   <li>{@link BytecodeDecoder} 还原为文本（dump 显示）</li>
 * </ul>
 *
 * <p>关键：{@code const s "hello world"} 的字符串值含空格，文本形式无法用 split 解析，
 * 编码器用 {@code substring} 手工截取（与 GSInterpreter.eval 一致），值存入常量池 UTF8 条目。
 */
public class BytecodeEncoder {

    /** 常量池条目列表（0-based，CP 索引 = 列表位置 + 1，因 CP 从 1 开始） */
    private final List cpList = new ArrayList();
    /** 去重映射：key → CP 索引（1-based）。key 格式 = tag + ":" + valueStr */
    private final Map cpIndex = new LinkedHashMap();

    /**
     * 编码文本字节码为二进制内存表示。
     *
     * @param bytecode 1D 文本指令列表（如 "const s hello world"、"arith_op plus"）
     * @return 编码结果（byte[][] instructions + Object[] constantPool）
     */
    public EncodedBytecode encode(List bytecode) {
        byte[][] instructions = new byte[bytecode.size()][];
        for (int i = 0; i < bytecode.size(); i++) {
            String text = (String) bytecode.get(i);
            String[] parts = parseInstruction(text);
            instructions[i] = encodeInstruction(parts);
        }
        // 构建 Object[]，索引 0 不用（CP 从 1 开始）
        Object[] cp = new Object[cpList.size() + 1];
        for (int i = 0; i < cpList.size(); i++) {
            cp[i + 1] = cpList.get(i);
        }
        return new EncodedBytecode(instructions, cp);
    }

    /**
     * 解析单条文本指令为 String[]（const 特殊处理：value 可能含空格）。
     * 与 GSClassWriter.parseBytecode / GSInterpreter.eval 逻辑一致。
     */
    private String[] parseInstruction(String code) {
        if (code.startsWith("const ") && code.length() > 6) {
            int start = code.indexOf(' ', 0) + 1;
            String typeChar = code.substring(start, start + 1);
            String value = code.substring(start + 2);
            return new String[]{"const", typeChar, value};
        } else {
            return code.split(" ");
        }
    }

    /**
     * 编码单条指令为 byte[]。
     * 逻辑与 GSClassWriter.writeInstruction 一致，但输出到 byte[] 而非流。
     */
    private byte[] encodeInstruction(String[] code) {
        String cmd = code[0];

        // const 特殊处理：根据类型字符选择对应的 const_* 操作码
        if ("const".equals(cmd)) {
            String typeChar = code[1];
            String value = code[2];
            byte opcode = GSClassConstants.constOpcode(typeChar);
            int cpIdx;
            if ("a".equals(typeChar)) { cpIdx = addUtf8(value); }
            else if ("s".equals(typeChar)) { cpIdx = addUtf8(value); }
            else if ("i".equals(typeChar)) { cpIdx = addInt(Integer.parseInt(value)); }
            else if ("f".equals(typeChar)) { cpIdx = addFloat(Float.parseFloat(value)); }
            else if ("b".equals(typeChar)) { cpIdx = addBool(Boolean.parseBoolean(value)); }
            else { throw new IllegalArgumentException("Unknown const type: " + typeChar); }
            return new byte[]{opcode, (byte) (cpIdx >> 8), (byte) cpIdx};
        }

        Byte opcodeBox = (Byte) GSClassConstants.TEXT_TO_OPCODE.get(cmd);
        if (opcodeBox == null) {
            throw new IllegalArgumentException("Unknown instruction: " + cmd);
        }
        byte opcode = opcodeBox.byteValue();

        switch (opcode) {
            case GSClassConstants.OP_ARITH_OP: {
                Byte sub = (Byte) GSClassConstants.ARITH_SUB.get(code[1]);
                if (sub == null) throw new IllegalArgumentException("Unknown arith_op sub: " + code[1]);
                return new byte[]{opcode, sub.byteValue()};
            }
            case GSClassConstants.OP_COMP: {
                Byte sub = (Byte) GSClassConstants.COMP_SUB.get(code[1]);
                if (sub == null) throw new IllegalArgumentException("Unknown comp sub: " + code[1]);
                return new byte[]{opcode, sub.byteValue()};
            }
            case GSClassConstants.OP_RELA_OP: {
                Byte sub = (Byte) GSClassConstants.RELA_SUB.get(code[1]);
                if (sub == null) throw new IllegalArgumentException("Unknown rela_op sub: " + code[1]);
                return new byte[]{opcode, sub.byteValue()};
            }
            case GSClassConstants.OP_PUSHENV: {
                Byte sub = (Byte) GSClassConstants.PUSHENV_SUB.get(code[1]);
                if (sub == null) throw new IllegalArgumentException("Unknown pushenv sub: " + code[1]);
                return new byte[]{opcode, sub.byteValue()};
            }
            case GSClassConstants.OP_POPENV: {
                Byte sub = (Byte) GSClassConstants.POPENV_SUB.get(code[1]);
                if (sub == null) throw new IllegalArgumentException("Unknown popenv sub: " + code[1]);
                return new byte[]{opcode, sub.byteValue()};
            }
            case GSClassConstants.OP_NEW: {
                Byte sub = (Byte) GSClassConstants.NEW_SUB.get(code[1]);
                if (sub == null) throw new IllegalArgumentException("Unknown new sub: " + code[1]);
                return new byte[]{opcode, sub.byteValue()};
            }
            case GSClassConstants.OP_DECLARE:
            case GSClassConstants.OP_STORE: {
                int cpIdx = addUtf8(code[1]);
                return new byte[]{opcode, (byte) (cpIdx >> 8), (byte) cpIdx};
            }
            case GSClassConstants.OP_JUMP:
            case GSClassConstants.OP_FALSE_JUMP:
            case GSClassConstants.OP_LOOP_JUMP:
            case GSClassConstants.OP_BLOCK_JUMP: {
                short offset = Short.parseShort(code[1]);
                return new byte[]{opcode, (byte) (offset >> 8), (byte) offset};
            }
            case GSClassConstants.OP_FUNDEF: {
                int nameCp = addUtf8(code[1]);
                int bodyLen = Short.parseShort(code[2]);
                return new byte[]{opcode,
                        (byte) (nameCp >> 8), (byte) nameCp,
                        (byte) (bodyLen >> 8), (byte) bodyLen};
            }
            case GSClassConstants.OP_FSTORE: {
                int nameCp = addUtf8(code[1]);
                int argIdx = Short.parseShort(code[2]);
                return new byte[]{opcode,
                        (byte) (nameCp >> 8), (byte) nameCp,
                        (byte) (argIdx >> 8), (byte) argIdx};
            }
            case GSClassConstants.OP_INVOKE:
            case GSClassConstants.OP_CONSTRUCTOR: {
                int argCount = Short.parseShort(code[1]);
                return new byte[]{opcode, (byte) (argCount >> 8), (byte) argCount};
            }
            case GSClassConstants.OP_TRY_START: {
                short o1 = Short.parseShort(code[1]);
                short o2 = Short.parseShort(code[2]);
                short o3 = Short.parseShort(code[3]);
                short o4 = Short.parseShort(code[4]);
                return new byte[]{opcode,
                        (byte) (o1 >> 8), (byte) o1,
                        (byte) (o2 >> 8), (byte) o2,
                        (byte) (o3 >> 8), (byte) o3,
                        (byte) (o4 >> 8), (byte) o4};
            }
            default:
                // 无操作数指令（1 字节）
                return new byte[]{opcode};
        }
    }

    // ===== 常量池操作（与 GSClassWriter 相同的去重逻辑）=====

    private int addUtf8(String value) {
        String key = "U:" + value;
        Integer idx = (Integer) cpIndex.get(key);
        if (idx != null) return idx.intValue();
        int newIdx = cpList.size() + 1;
        cpList.add(value);
        cpIndex.put(key, new Integer(newIdx));
        return newIdx;
    }

    private int addInt(int value) {
        String key = "I:" + value;
        Integer idx = (Integer) cpIndex.get(key);
        if (idx != null) return idx.intValue();
        int newIdx = cpList.size() + 1;
        cpList.add(new Integer(value));
        cpIndex.put(key, new Integer(newIdx));
        return newIdx;
    }

    private int addFloat(float value) {
        String key = "F:" + Float.floatToIntBits(value);
        Integer idx = (Integer) cpIndex.get(key);
        if (idx != null) return idx.intValue();
        int newIdx = cpList.size() + 1;
        cpList.add(new Float(value));
        cpIndex.put(key, new Integer(newIdx));
        return newIdx;
    }

    private int addBool(boolean value) {
        String key = "B:" + value;
        Integer idx = (Integer) cpIndex.get(key);
        if (idx != null) return idx.intValue();
        int newIdx = cpList.size() + 1;
        cpList.add(new Boolean(value));
        cpIndex.put(key, new Integer(newIdx));
        return newIdx;
    }
}
