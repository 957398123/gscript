package gscript.gen;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

public class ByteCodeSerialize {
    public static void serialize(List<String> bytecode, String filePath) {
        try {
            ByteArrayOutputStream bs = new ByteArrayOutputStream();
            DataOutputStream ds = new DataOutputStream(bs);
            for (String code : bytecode) {
                String[] bytes = code.split(" ");
                switch (bytes[0]) {
                    case "arith_op": {
                        ds.writeByte(0x01);
                        switch (bytes[1]) {
                            case "plus": {
                                ds.writeByte(0x01);
                                break;
                            }
                            case "minus": {
                                ds.writeByte(0x02);
                                break;
                            }
                            case "mul": {
                                ds.writeByte(0x03);
                                break;
                            }
                            case "div": {
                                ds.writeByte(0x04);
                                break;
                            }
                            case "modulo": {
                                ds.writeByte(0x05);
                                break;
                            }
                            case "neg": {
                                ds.writeByte(0x06);
                                break;
                            }
                            case "ls": {
                                ds.writeByte(0x07);
                                break;
                            }
                            case "rs": {
                                ds.writeByte(0x08);
                                break;
                            }
                            default: {
                                error("arith_op指令出现未知选项: " + bytes[1]);
                            }
                        }
                        break;
                    }
                    case "aaload": {
                        ds.writeByte(0x02);
                        break;
                    }
                    case "aastore": {
                        ds.writeByte(0x03);
                        break;
                    }
                    case "avstore": {
                        ds.writeByte(0x04);
                        break;
                    }
                    case "const": {
                        ds.writeByte(0x05);
                        switch (bytes[1]) {
                            case "a": {
                                String str = bytes[2];
                                byte[] data = str.getBytes(StandardCharsets.UTF_8);
                                ds.writeByte(0x01);
                                ds.writeInt(data.length);
                                ds.write(data);
                                break;
                            }
                            case "i": {
                                ds.writeByte(0x02);
                                // 整数长度4
                                ds.writeInt(0x04);
                                // 写入数据
                                ds.writeInt(Integer.parseInt(bytes[2]));
                                break;
                            }
                            case "f": {
                                ds.writeByte(0x03);
                                // 浮点数长度4
                                ds.writeInt(0x04);
                                // 写入数据
                                ds.writeFloat(Float.parseFloat(bytes[2]));
                                break;
                            }
                            case "s": {
                                String str = code.substring(8);
                                byte[] data = str.getBytes(StandardCharsets.UTF_8);
                                ds.writeByte(0x04);
                                ds.writeInt(data.length);
                                ds.write(data);
                                break;
                            }
                            case "b": {
                                ds.writeByte(0x05);
                                ds.writeInt(0x01);
                                // 写入布尔数据
                                String bool = bytes[2];
                                if ("true".equals(bool)) {
                                    ds.writeBoolean(true);
                                } else if ("false".equals(bool)) {
                                    ds.writeBoolean(false);
                                } else {
                                    error("const b 参数不正确，type=" + bool);
                                }
                                break;
                            }
                            default: {
                                error("const指令出现未知选项: " + bytes[1]);
                            }
                        }
                        break;
                    }
                    case "copy": {
                        ds.writeByte(0x06);
                        break;
                    }
                    case "comp": {
                        ds.writeByte(0x07);
                        switch (bytes[1]) {
                            case "eq": {
                                ds.writeByte(0x01);
                                break;
                            }
                            case "neq": {
                                ds.writeByte(0x02);
                                break;
                            }
                            case "gt": {
                                ds.writeByte(0x03);
                                break;
                            }
                            case "ge": {
                                ds.writeByte(0x04);
                                break;
                            }
                            case "lt": {
                                ds.writeByte(0x05);
                                break;
                            }
                            case "le": {
                                ds.writeByte(0x06);
                                break;
                            }
                            default: {
                                error("comp指令出现未知的选项: " + bytes[1]);
                            }
                        }
                        break;
                    }
                    case "declare": {
                        ds.writeByte(0x08);
                        byte[] data = bytes[1].getBytes(StandardCharsets.UTF_8);
                        ds.writeInt(data.length);
                        ds.write(data);
                        break;
                    }
                    case "decr": {
                        ds.writeByte(0x09);
                        break;
                    }
                    case "false_jump": {
                        ds.writeByte(0x0a);
                        ds.writeInt(Integer.parseInt(bytes[1]));
                        break;
                    }
                    case "fstore": {
                        ds.writeByte(0x0b);
                        byte[] data = bytes[1].getBytes(StandardCharsets.UTF_8);
                        int index = Integer.parseInt(bytes[2]);
                        ds.writeInt(data.length);
                        ds.write(data);
                        ds.writeInt(index);
                        break;
                    }
                    case "fundef": {
                        ds.writeByte(0x0c);
                        ds.writeInt(Integer.parseInt(bytes[1]));
                        byte[] data = bytes[1].getBytes(StandardCharsets.UTF_8);
                        ds.writeInt(data.length);
                        ds.write(data);
                        break;
                    }
                    case "fundefload": {
                        ds.writeByte(0x0d);
                        ds.writeInt(Integer.parseInt(bytes[1]));
                        byte[] data = bytes[1].getBytes(StandardCharsets.UTF_8);
                        ds.writeInt(data.length);
                        ds.write(data);
                        break;
                    }
                    case "getfield": {
                        ds.writeByte(0x0e);
                        byte[] data = bytes[1].getBytes(StandardCharsets.UTF_8);
                        ds.writeInt(data.length);
                        ds.write(data);
                        break;
                    }
                    case "incr": {
                        ds.writeByte(0x0f);
                        break;
                    }
                    case "invoke": {
                        ds.writeByte(0x10);
                        break;
                    }
                    case "invokeMember": {
                        ds.writeByte(0x11);
                        break;
                    }
                    case "jump": {
                        ds.writeByte(0x12);
                        ds.writeInt(Integer.parseInt(bytes[1]));
                        break;
                    }
                    case "loop_jump": {
                        ds.writeByte(0x13);
                        ds.writeInt(Integer.parseInt(bytes[1]));
                        break;
                    }
                    case "lda_null": {
                        ds.writeByte(0x14);
                        break;
                    }
                    case "new": {
                        ds.writeByte(0x15);
                        byte[] data = bytes[1].getBytes(StandardCharsets.UTF_8);
                        ds.writeInt(data.length);
                        ds.write(data);
                    }
                    case "pop": {
                        ds.writeByte(0x16);
                        break;
                    }
                    case "popenv": {
                        ds.writeByte(0x17);
                        byte[] data = bytes[1].getBytes(StandardCharsets.UTF_8);
                        ds.writeInt(data.length);
                        ds.write(data);
                        break;
                    }
                    case "pushenv": {
                        ds.writeByte(0x18);
                        byte[] data = bytes[1].getBytes(StandardCharsets.UTF_8);
                        ds.writeInt(data.length);
                        ds.write(data);
                        break;
                    }
                    case "putfield": {
                        ds.writeByte(0x19);
                        byte[] data = bytes[1].getBytes(StandardCharsets.UTF_8);
                        ds.writeInt(data.length);
                        ds.write(data);
                        break;
                    }
                    case "putfieldExpr": {
                        ds.writeByte(0x1a);
                        break;
                    }
                    case "rela_op": {
                        ds.writeByte(0x1b);
                        switch (bytes[1]) {
                            case "b_and": {
                                ds.writeByte(0x01);
                                break;
                            }
                            case "b_or": {
                                ds.writeByte(0x02);
                                break;
                            }
                            case "b_xor": {
                                ds.writeByte(0x03);
                                break;
                            }
                            case "b_not": {
                                ds.writeByte(0x04);
                                break;
                            }
                            case "l_and": {
                                ds.writeByte(0x05);
                                break;
                            }
                            case "l_or": {
                                ds.writeByte(0x06);
                                break;
                            }
                            case "l_not": {
                                ds.writeByte(0x07);
                                break;
                            }
                            default: {
                                error("rela_op指令出现未知选项: " + bytes[1]);
                            }
                        }
                        break;
                    }
                    case "return": {
                        ds.writeByte(0x1c);
                        break;
                    }
                    case "store": {
                        ds.writeByte(0x1d);
                        byte[] data = bytes[1].getBytes(StandardCharsets.UTF_8);
                        ds.writeInt(data.length);
                        ds.write(data);
                        break;
                    }
                    case "swap": {
                        ds.writeByte(0x1e);
                        break;
                    }
                    default: {
                        error("不支持的指令操作: " + bytes[0]);
                    }
                }
            }
            // 在写入文件前检查目录
            Path path = Paths.get(filePath).getParent();
            if (!Files.exists(path)) {
                Files.createDirectories(path);
            }
            // 2. 获取字节数据
            byte[] data = bs.toByteArray();
            // 3. 写入文件（全路径）
            try (FileOutputStream fos = new FileOutputStream(filePath, false)) { // false表示覆盖
                fos.write(data);
            }
            ds.close();
            bs.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static void error(String message) {
        throw new RuntimeException(message);
    }
}
