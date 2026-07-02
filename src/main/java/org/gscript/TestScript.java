package org.gscript;

import org.gscript.compile.Lexer;
import org.gscript.compile.Parser;
import org.gscript.compile.gen.ByteCodeGenerator;
import org.gscript.compile.gclass.BytecodeDecoder;
import org.gscript.compile.gclass.GSClassData;
import org.gscript.compile.gclass.GSClassReader;
import org.gscript.compile.gclass.GSClassWriter;
import org.gscript.compile.node.Node;
import org.gscript.compile.token.GSToken;
import org.gscript.vm.GSInterpreter;
import org.gscript.vm.stdlib.Console;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class TestScript {
    public static void main(String[] args) throws Exception {
        // 支持命令行参数指定脚本名，默认 debug_test
        String name = (args != null && args.length > 0) ? args[0] : "debug_test";
        String mode = (args != null && args.length > 1) ? args[1] : null;

        if (mode == null || "run".equals(mode)) {
            // 现有：编译 + 执行
            TestScript.gen(name, false);
        } else if ("dump".equals(mode)) {
            // 现有：打印字节码映射
            TestScript.gen(name, true);
        } else if ("compile".equals(mode)) {
            // 新增：编译 + 写出 .gclass 文件
            TestScript.compileGclass(name);
        } else if ("rungclass".equals(mode)) {
            // 新增：加载 .gclass + 执行
            TestScript.runGclass(name);
        } else if ("dumpgclass".equals(mode)) {
            // 新增：加载 .gclass + dump 字节码映射（验证反序列化正确性）
            TestScript.dumpGclass(name);
        } else {
            System.err.println("Unknown mode: " + mode);
            System.err.println("Usage: TestScript <name> [run|dump|compile|rungclass|dumpgclass]");
        }
    }

    public static void gen(String fileName) throws Exception {
        gen(fileName, false);
    }

    public static void gen(String fileName, boolean dump) throws Exception {
        URL classUrl = Test.class.getResource("Test.class");
        // 获取classpath根目录
        URL rootUrl = Test.class.getResource("/");
        InputStream in = Test.class.getResourceAsStream("/" + fileName + ".script");
        if (in != null) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            in.close();
            Lexer lexer = new Lexer();
            List<GSToken> tokens = lexer.tokenize(content);
            Parser parser = new Parser(tokens);
            Node program = parser.parseProgram();
            ByteCodeGenerator byteCodeGenerator = new ByteCodeGenerator();
            program.accept(byteCodeGenerator);
            // 使用Paths和Files处理路径
            Path outputPath = Paths.get(rootUrl.toURI()).resolve("gtxt").resolve(fileName + ".gtxt");
            Test.list2File(byteCodeGenerator.getFormatByteCode(), outputPath.toString());
            // dump 模式：打印字节码索引和 sourceLine 对照（调试器源码映射验证用）
            if (dump) {
                ArrayList<String> bc = byteCodeGenerator.getByteCode();
                ArrayList<Integer> lines = byteCodeGenerator.getSourceLines();
                System.err.println("==== bytecode<->sourceLine dump ====");
                for (int i = 0; i < bc.size(); i++) {
                    int ln = (i < lines.size()) ? lines.get(i) : 0;
                    System.err.println(String.format("%4d [line %-3d] %s", i, ln, bc.get(i)));
                }
                System.err.println("==== end dump ====");
                return;  // dump 模式只打印不执行
            }
            // 创建解释器，并执行脚本
            GSInterpreter interpreter = new GSInterpreter();
            ArrayList<String> src =  byteCodeGenerator.getByteCode();
            // 增加控制台输出
            interpreter.addVariableToGlobal("console", new Console());
            interpreter.eval(src.toArray(new String[src.size()]));
        }
    }

    // =========================================================================
    //  gclass 命令行模式
    // =========================================================================

    /**
     * 编译 .script 源码并写出 .gclass 二进制文件。
     * .gclass 写到 classpath 根目录下的 gtxt/<name>.gclass（与 .gtxt 同目录）。
     */
    public static void compileGclass(String name) throws Exception {
        URL rootUrl = Test.class.getResource("/");
        InputStream in = Test.class.getResourceAsStream("/" + name + ".script");
        if (in == null) {
            System.err.println("Script not found: /" + name + ".script");
            return;
        }
        String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        in.close();

        // 编译
        Lexer lexer = new Lexer();
        List<GSToken> tokens = lexer.tokenize(content);
        Parser parser = new Parser(tokens);
        Node program = parser.parseProgram();
        ByteCodeGenerator gen = new ByteCodeGenerator();
        program.accept(gen);

        ArrayList<String> bytecode = gen.getByteCode();
        ArrayList<Integer> sourceLines = gen.getSourceLines();

        // 序列化为 .gclass
        // sourcePath 设为 name + ".script"（调试用标识，非绝对路径）
        String sourcePath = name + ".script";
        Path gclassPath = Paths.get(rootUrl.toURI()).resolve("gtxt").resolve(name + ".gclass");
        File gclassFile = gclassPath.toFile();
        gclassFile.getParentFile().mkdirs();  // 确保 gtxt 目录存在
        GSClassWriter writer = new GSClassWriter();
        try (OutputStream out = new FileOutputStream(gclassFile)) {
            writer.write(bytecode, sourceLines, sourcePath, out);
        }
        System.err.println("gclass written: " + gclassPath);
        System.err.println("  bytecode instructions: " + bytecode.size());
        System.err.println("  source lines: " + (sourceLines != null ? sourceLines.size() : 0));
    }

    /**
     * 加载 .gclass 文件并执行。
     * 从 classpath 资源 /gtxt/<name>.gclass 读取。
     */
    public static void runGclass(String name) throws Exception {
        GSClassData data = loadGclass(name);
        if (data == null) return;

        GSInterpreter interpreter = new GSInterpreter();
        interpreter.addVariableToGlobal("console", new Console());
        interpreter.eval(data.src, data.constantPool, data.sourceLines, data.sourcePath);
    }

    /**
     * 加载 .gclass 文件并 dump 字节码↔源码行映射（验证反序列化正确性）。
     * 输出格式与 gen(name, true) 的 dump 模式一致，便于对比。
     */
    public static void dumpGclass(String name) throws Exception {
        GSClassData data = loadGclass(name);
        if (data == null) return;

        System.err.println("==== gclass bytecode<->sourceLine dump ====");
        System.err.println("sourcePath: " + data.sourcePath);
        for (int i = 0; i < data.src.length; i++) {
            int ln = (data.sourceLines != null && i < data.sourceLines.length) ? data.sourceLines[i] : 0;
            // 用 BytecodeDecoder 将二进制指令还原为文本形式
            String text = BytecodeDecoder.decode(data.src[i], data.constantPool);
            System.err.println(String.format("%4d [line %-3d] %s", i, ln, text));
        }
        System.err.println("==== end gclass dump ====");

        // 打印 FunctionTable（若有 attributes 段）
        if (data.functions != null && data.functions.length > 0) {
            System.err.println("==== function table ====");
            for (GSClassData.FunctionEntry fe : data.functions) {
                System.err.println("  " + fe);
            }
            System.err.println("==== end function table ====");
        }
    }

    /**
     * 从 classpath 加载 .gclass 文件并反序列化。
     *
     * @param name 脚本名（不含扩展名）
     * @return GSClassData，文件不存在时返回 null
     */
    private static GSClassData loadGclass(String name) throws Exception {
        InputStream in = Test.class.getResourceAsStream("/gtxt/" + name + ".gclass");
        if (in == null) {
            System.err.println("gclass not found: /gtxt/" + name + ".gclass");
            System.err.println("请先运行: TestScript " + name + " compile");
            return null;
        }
        GSClassReader reader = new GSClassReader();
        GSClassData data = reader.deserialize(in);
        in.close();
        System.err.println("gclass loaded: /gtxt/" + name + ".gclass"
                + " (bytecode=" + data.src.length + " instructions)");
        return data;
    }
}
