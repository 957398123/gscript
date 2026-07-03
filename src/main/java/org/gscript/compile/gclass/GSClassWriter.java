package org.gscript.compile.gclass;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * gclass 序列化器：将 gscript 文本字节码（1D {@code List<String>}）序列化为二进制 .gclass 格式。
 *
 * <p>输入：{@code List<String>} bytecode + {@code List<Integer>} sourceLines + {@code String} sourcePath
 * <br>输出：写入 {@code OutputStream} 的二进制 gclass 流
 *
 * <p>核心流程：
 * <ol>
 *   <li>用 {@link BytecodeEncoder} 将文本字节码编码为二进制内存表示（{@code byte[][]} + {@code Object[]} CP）</li>
 *   <li>把 sourcePath 追加到 CP 末尾（sourcePath 不在指令流中，需单独处理）</li>
 *   <li>写 Header（magic/version/flags/CP count/bytecode length/source_path CP index，CRC32 占位 0）</li>
 *   <li>写常量池（按 tag 序列化 UTF8/Int/Float/Bool）</li>
 *   <li>写字节码段（直接写每条指令的 byte[]，不转换）</li>
 *   <li>写 Source Map（每条指令 u2 源码行号）</li>
 *   <li>回填 CRC32（覆盖 Header 起始到 Source Map 末尾），追加 4 字节 footer</li>
 * </ol>
 *
 * <p>关键：{@code const s "hello world"} 的字符串值含空格，文本形式无法用 split 解析，
 * 二进制形式将值存入常量池 UTF8 条目，指令流只存 CP 索引——彻底解决空格问题。
 */
public class GSClassWriter {

    /**
     * 序列化并写入输出流。
     *
     * @param bytecode   1D 文本字节码（如 "const s hello world"、"arith_op plus"）
     * @param sourceLines 与 bytecode 平行的源码行号列表（1-based，0=未设置），可为 null
     * @param sourcePath  源文件路径，可为 null
     * @param out         输出流
     */
    public void write(List<String> bytecode, List<Integer> sourceLines, String sourcePath, OutputStream out) throws IOException {
        // 1. 用 BytecodeEncoder 编码（内存 byte[][] + Object[] CP）
        BytecodeEncoder encoder = new BytecodeEncoder();
        EncodedBytecode encoded = encoder.encode(bytecode);
        byte[][] instructions = encoded.instructions;
        Object[] cp = encoded.constantPool;

        // 2. sourcePath 加入 CP（sourcePath 不在指令流中，需单独追加到 CP 末尾）
        int sourcePathCpIndex = 0;
        if (sourcePath != null && !sourcePath.isEmpty()) {
            sourcePathCpIndex = cp.length;  // 追加到末尾，新索引 = 当前数组长度
            Object[] newCp = new Object[cp.length + 1];
            System.arraycopy(cp, 0, newCp, 0, cp.length);
            newCp[sourcePathCpIndex] = sourcePath;
            cp = newCp;
        }

        // 2b. 扫描 fundef 指令，构建 FunctionTable 属性数据。
        // fundef 指令格式：OP_FUNDEF + u2 nameCp + u2 bodyLen（共 5 字节）。
        // startIp = fundef 指令索引 + 1（函数体紧随 fundef 指令）。
        // 仅当存在函数定义时才生成属性段，并把 "FunctionTable" 属性名加入 CP。
        List<int[]> funcEntries = new ArrayList<>();  // each: {nameCp, startIp, bodyLen}
        for (int i = 0; i < instructions.length; i++) {
            if (instructions[i].length > 0 && instructions[i][0] == GSClassConstants.OP_FUNDEF) {
                byte[] instr = instructions[i];
                int nameCp = ((instr[1] & 0xFF) << 8) | (instr[2] & 0xFF);
                int bodyLen = ((instr[3] & 0xFF) << 8) | (instr[4] & 0xFF);
                funcEntries.add(new int[]{nameCp, i + 1, bodyLen});
            }
        }
        boolean hasAttributes = !funcEntries.isEmpty();
        int attrNameCpIndex = 0;
        if (hasAttributes) {
            // 把 "FunctionTable" 属性名追加到 CP 末尾
            attrNameCpIndex = cp.length;
            Object[] newCp = new Object[cp.length + 1];
            System.arraycopy(cp, 0, newCp, 0, cp.length);
            newCp[attrNameCpIndex] = GSClassConstants.ATTR_FUNCTION_TABLE;
            cp = newCp;
        }

        // 3. 写入主体（不含 CRC32 footer）到 buffer
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(buf);

        boolean hasSourceMap = sourceLines != null && !sourceLines.isEmpty();
        boolean hasSourcePath = sourcePathCpIndex > 0;

        // RLE 压缩源码映射：源码行号常有长游程（多条字节码对应同一源码行），
        // RLE 编码为 (count, line) 对（4 字节/对），原始格式为 2 字节/条。
        // 仅当 RLE 对数 < 原始长度的 50% 时启用（4M < 2N → M < N/2），否则 RLE 反而更大。
        List<int[]> rlePairs = null;
        boolean useRle = false;
        if (hasSourceMap) {
            rlePairs = new ArrayList<>();
            int prev = sourceLines.get(0);
            int count = 1;
            for (int i = 1; i < sourceLines.size(); i++) {
                int cur = sourceLines.get(i);
                if (cur == prev) {
                    count++;
                } else {
                    rlePairs.add(new int[]{count, prev});
                    prev = cur;
                    count = 1;
                }
            }
            rlePairs.add(new int[]{count, prev});
            // 仅当 RLE 体积确实更小时启用：对数 < 原始长度的 50%（4 字节/对 vs 2 字节/条）
            useRle = rlePairs.size() < sourceLines.size() * 0.5;
        }

        short flags = 0;
        if (hasSourceMap) flags |= GSClassConstants.FLAG_HAS_SOURCE_MAP;
        if (hasSourcePath) flags |= GSClassConstants.FLAG_HAS_SOURCE_PATH;
        if (useRle) flags |= GSClassConstants.FLAG_RLE_SOURCE_MAP;
        if (hasAttributes) flags |= GSClassConstants.FLAG_HAS_ATTRIBUTES;

        // Header（20 字节，CRC32 占位 0）
        dos.writeInt(GSClassConstants.MAGIC);
        dos.writeByte(GSClassConstants.MAJOR_VERSION);
        dos.writeByte(GSClassConstants.MINOR_VERSION);
        dos.writeShort(flags);
        // CP count = cp.length - 1（索引从 1 开始，0 不用）
        dos.writeShort(cp.length - 1);
        dos.writeInt(instructions.length);
        dos.writeShort(sourcePathCpIndex);
        dos.writeInt(0);  // CRC32 占位，稍后回填

        // 常量池（从索引 1 开始写）
        for (int i = 1; i < cp.length; i++) {
            Object entry = cp[i];
            if (entry instanceof String) {
                dos.writeByte(GSClassConstants.TAG_UTF8);
                byte[] utf8Bytes = ((String) entry).getBytes(StandardCharsets.UTF_8);
                dos.writeShort(utf8Bytes.length);
                dos.write(utf8Bytes);
            } else if (entry instanceof Integer) {
                dos.writeByte(GSClassConstants.TAG_INT);
                dos.writeInt((Integer) entry);
            } else if (entry instanceof Float) {
                dos.writeByte(GSClassConstants.TAG_FLOAT);
                dos.writeFloat((Float) entry);
            } else if (entry instanceof Boolean) {
                dos.writeByte(GSClassConstants.TAG_BOOL);
                dos.writeByte((Boolean) entry ? 1 : 0);
            } else {
                throw new IOException("Unknown CP entry type at index " + i + ": " + entry.getClass());
            }
        }

        // 字节码段（直接写每条指令的 byte[]，无需逐指令编码）
        for (byte[] instr : instructions) {
            dos.write(instr);
        }

        // Source Map（可选，RLE 压缩或原始格式）
        if (hasSourceMap) {
            if (useRle) {
                // RLE 格式：u4 pairCount + pairCount × (u2 count, u2 line)
                dos.writeInt(rlePairs.size());
                for (int[] p : rlePairs) {
                    dos.writeShort(p[0]);
                    dos.writeShort(p[1]);
                }
            } else {
                // 原始格式：u4 lineCount + lineCount × u2 line
                dos.writeInt(sourceLines.size());
                for (Integer line : sourceLines) {
                    dos.writeShort(line);
                }
            }
        }

        // Attributes 段（可选，位于 SourceMap 之后）
        // 格式：u2 attrCount + attrCount × (u2 nameCpIndex, u4 length, byte[length] data)
        if (hasAttributes) {
            dos.writeShort(1);  // attrCount = 1（仅 FunctionTable）
            // FunctionTable 属性
            dos.writeShort(attrNameCpIndex);
            // 属性数据长度 = u2 funcCount + funcCount × (u2 nameCp + u4 startIp + u4 bodyLen)
            int attrDataLen = 2 + funcEntries.size() * 10;
            dos.writeInt(attrDataLen);
            dos.writeShort(funcEntries.size());
            for (int[] fe : funcEntries) {
                dos.writeShort(fe[0]);  // nameCpIndex
                dos.writeInt(fe[1]);    // startIp
                dos.writeInt(fe[2]);    // bodyLen
            }
        }

        dos.flush();

        // 4. 计算 CRC32（覆盖 Header[0-15] + [20-end]，不含 offset 16-19 的 CRC32 字段自身）
        byte[] body = buf.toByteArray();
        CRC32 crc = new CRC32();
        crc.update(body, 0, 16);              // Header[0-15]
        if (body.length > 20) {
            crc.update(body, 20, body.length - 20);  // Header[20-end]（CP+Bytecode+SourceMap+Attributes）
        }
        int crcValue = (int) crc.getValue();

        // 5. 回填 CRC32 到 header 偏移 16（Big-Endian）
        body[16] = (byte) ((crcValue >> 24) & 0xFF);
        body[17] = (byte) ((crcValue >> 16) & 0xFF);
        body[18] = (byte) ((crcValue >> 8) & 0xFF);
        body[19] = (byte) (crcValue & 0xFF);

        // 6. 写出完整文件
        out.write(body);
        out.flush();
    }
}
