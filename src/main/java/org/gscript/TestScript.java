package org.gscript;

import org.gscript.compile.Lexer;
import org.gscript.compile.Parser;
import org.gscript.compile.gen.ByteCodeGenerator;
import org.gscript.compile.node.Node;
import org.gscript.compile.token.GSToken;
import org.gscript.vm.GSInterpreter;
import org.gscript.vm.stdlib.Console;

import java.io.InputStream;
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
        boolean dump = (args != null && args.length > 1 && "dump".equals(args[1]));
        TestScript.gen(name, dump);
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
}
