package org.gscript.compile.gclass;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

/**
 * gclass 反序列化器：将二进制 .gclass 文件还原为可执行的 {@link GSClassData}。
 *
 * <p>输入：{@code InputStream}（.gclass 二进制流）
 * <br>输出：{@link GSClassData}（含 {@code byte[][]} src + {@code Object[]} constantPool + {@code int[]} sourceLines + {@code String} sourcePath）
 *
 * <p>核心流程：
 * <ol>
 *   <li>读取全部字节，校验 magic + version</li>
 *   <li>CRC32 校验：提取 header 偏移 16 的 CRC 值，置零后重算比对</li>
 *   <li>解析 header（flags/CP count/bytecode length/source_path index）</li>
 *   <li>解析常量池到 {@code Object[]}（UTF8→String, Int→Integer, Float→Float, Bool→Boolean）</li>
 *   <li>解析字节码段：按操作码查 {@link GSClassConstants#instructionLength} 读取原始字节到 {@code byte[][]}（不转 String）</li>
 *   <li>解析 Source Map（可选）</li>
 *   <li>返回 {@link GSClassData}</li>
 * </ol>
 *
 * <p>反序列化得到的 {@code byte[][]} + {@code Object[]} 与 {@link org.gscript.vm.GSInterpreter}
 * 的二进制内存表示完全一致，直接传给 {@code eval(byte[][], Object[], int[], String)} 即可执行。
 */
public class GSClassReader {

    /**
     * 从输入流反序列化 gclass。
     *
     * @param in 输入流（读取完毕后不关闭，由调用方管理）
     * @return 解析出的 GSClassData
     * @throws IOException 文件格式错误或 CRC32 校验失败
     */
    public GSClassData deserialize(InputStream in) throws IOException {
        // 读取全部字节
        byte[] data = readAllBytes(in);

        // 校验最小长度（header 20 字节）
        if (data.length < 20) {
            throw new IOException("Invalid gclass file: too short (" + data.length + " bytes, need >= 20)");
        }

        // 用 DataInputStream 解析（Big-Endian）
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(data));

        // 1. 校验 magic
        int magic = dis.readInt();
        if (magic != GSClassConstants.MAGIC) {
            throw new IOException("Invalid gclass magic: 0x" + Integer.toHexString(magic)
                    + " (expected 0x" + Integer.toHexString(GSClassConstants.MAGIC) + ")");
        }

        // 2. 版本
        int major = dis.readUnsignedByte();
        int minor = dis.readUnsignedByte();
        if (major != GSClassConstants.MAJOR_VERSION) {
            throw new IOException("Unsupported gclass version: " + major + "." + minor
                    + " (expected " + GSClassConstants.MAJOR_VERSION + ".x)");
        }

        // 3. flags（2 字节 Big-Endian，与 Writer 的 writeShort 对应）
        int flags = dis.readUnsignedShort();
        boolean hasSourceMap = (flags & GSClassConstants.FLAG_HAS_SOURCE_MAP) != 0;
        boolean hasSourcePath = (flags & GSClassConstants.FLAG_HAS_SOURCE_PATH) != 0;
        boolean useRle = (flags & GSClassConstants.FLAG_RLE_SOURCE_MAP) != 0;
        boolean hasAttributes = (flags & GSClassConstants.FLAG_HAS_ATTRIBUTES) != 0;

        // 4. CP count
        int cpCount = dis.readUnsignedShort();

        // 5. bytecode length
        int bytecodeLength = dis.readInt();

        // 6. source_path CP index
        int sourcePathIndex = dis.readUnsignedShort();

        // 7. CRC32（header 偏移 16-19）
        int storedCrc = dis.readInt();

        // 8. CRC32 校验：将 offset 16-19 置 0 后重算
        CRC32 crc = new CRC32();
        crc.update(data, 0, 16);  // Header[0-15]
        if (data.length > 20) {
            crc.update(data, 20, data.length - 20);  // Header[20-end]
        }
        int computedCrc = (int) crc.getValue();
        if (storedCrc != computedCrc) {
            throw new IOException("CRC32 mismatch: stored=0x" + Integer.toHexString(storedCrc)
                    + " computed=0x" + Integer.toHexString(computedCrc) + " (file may be corrupted)");
        }

        // 9. 解析常量池
        Object[] cp = new Object[cpCount + 1];  // 索引从 1 开始，0 不用
        for (int i = 1; i <= cpCount; i++) {
            byte tag = dis.readByte();
            switch (tag) {
                case GSClassConstants.TAG_UTF8: {
                    int len = dis.readUnsignedShort();
                    byte[] bytes = new byte[len];
                    dis.readFully(bytes);
                    cp[i] = new String(bytes, "UTF-8");
                    break;
                }
                case GSClassConstants.TAG_INT:
                    cp[i] = new Integer(dis.readInt());
                    break;
                case GSClassConstants.TAG_FLOAT:
                    cp[i] = new Float(dis.readFloat());
                    break;
                case GSClassConstants.TAG_BOOL:
                    cp[i] = new Boolean(dis.readUnsignedByte() != 0);
                    break;
                default:
                    throw new IOException("Unknown CP tag at index " + i + ": 0x" + Integer.toHexString(tag & 0xFF));
            }
        }

        // 10. 解析字节码段：按 instructionLength 读取原始字节（不转 String）
        byte[][] src = new byte[bytecodeLength][];
        for (int i = 0; i < bytecodeLength; i++) {
            byte opcode = dis.readByte();
            int len = GSClassConstants.instructionLength(opcode);
            byte[] instr = new byte[len];
            instr[0] = opcode;
            if (len > 1) {
                dis.readFully(instr, 1, len - 1);  // 读取剩余操作数字节
            }
            src[i] = instr;
        }

        // 11. 解析 Source Map（可选，RLE 压缩或原始格式）
        int[] sourceLines = null;
        if (hasSourceMap) {
            if (useRle) {
                // RLE 格式：u4 pairCount + pairCount × (u2 count, u2 line)
                int pairCount = dis.readInt();
                List pairs = new ArrayList();
                int total = 0;
                for (int i = 0; i < pairCount; i++) {
                    int count = dis.readUnsignedShort();
                    int line = dis.readUnsignedShort();
                    pairs.add(new int[]{count, line});
                    total += count;
                }
                sourceLines = new int[total];
                int idx = 0;
                for (int i = 0; i < pairs.size(); i++) {
                    int[] p = (int[]) pairs.get(i);
                    Arrays.fill(sourceLines, idx, idx + p[0], p[1]);
                    idx += p[0];
                }
            } else {
                // 原始格式：u4 lineCount + lineCount × u2 line
                int lineCount = dis.readInt();
                sourceLines = new int[lineCount];
                for (int i = 0; i < lineCount; i++) {
                    sourceLines[i] = dis.readUnsignedShort();
                }
            }
        }

        // 12. 解析 sourcePath
        String sourcePath = null;
        if (hasSourcePath && sourcePathIndex > 0) {
            sourcePath = (String) cp[sourcePathIndex];
        }

        // 13. 解析 Attributes 段（可选，位于 SourceMap 之后）
        // 格式：u2 attrCount + attrCount × (u2 nameCpIndex, u4 length, byte[length] data)
        GSClassData.FunctionEntry[] functions = null;
        String sourceContent = null;
        if (hasAttributes) {
            int attrCount = dis.readUnsignedShort();
            for (int a = 0; a < attrCount; a++) {
                int nameCp = dis.readUnsignedShort();
                int len = dis.readInt();
                byte[] attrData = new byte[len];
                dis.readFully(attrData);
                String attrName = (nameCp > 0 && nameCp < cp.length) ? (String) cp[nameCp] : "";
                if (GSClassConstants.ATTR_FUNCTION_TABLE.equals(attrName)) {
                    functions = parseFunctionTable(attrData, cp);
                } else if (GSClassConstants.ATTR_SOURCE_CONTENT.equals(attrName)) {
                    sourceContent = new String(attrData, "UTF-8");
                }
                // 未知属性：跳过（前向兼容，旧 reader 不崩溃）
            }
        }

        return new GSClassData(src, cp, sourceLines, sourcePath, functions, sourceContent);
    }

    /**
     * 解析 FunctionTable 属性数据。
     *
     * <p>格式：u2 funcCount + funcCount × (u2 nameCpIndex, u4 startIp, u4 bodyLen)
     *
     * @param data 属性数据字节
     * @param cp   常量池（用于解析函数名）
     * @return 函数表条目数组
     */
    private GSClassData.FunctionEntry[] parseFunctionTable(byte[] data, Object[] cp) throws IOException {
        DataInputStream adis = new DataInputStream(new ByteArrayInputStream(data));
        int funcCount = adis.readUnsignedShort();
        GSClassData.FunctionEntry[] entries = new GSClassData.FunctionEntry[funcCount];
        for (int i = 0; i < funcCount; i++) {
            int nameCp = adis.readUnsignedShort();
            int startIp = adis.readInt();
            int bodyLen = adis.readInt();
            String name = (nameCp > 0 && nameCp < cp.length) ? (String) cp[nameCp] : "<unknown>";
            entries[i] = new GSClassData.FunctionEntry(name, startIp, bodyLen);
        }
        return entries;
    }

    /** 读取输入流全部字节 */
    private byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[4096];
        int n;
        while ((n = in.read(tmp)) != -1) {
            buf.write(tmp, 0, n);
        }
        return buf.toByteArray();
    }
}
