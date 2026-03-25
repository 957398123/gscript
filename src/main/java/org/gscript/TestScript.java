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
        TestScript.gen("test");
    }

    public static void gen(String fileName) throws Exception {
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
            // 创建解释器，并执行脚本
            GSInterpreter interpreter = new GSInterpreter();
            ArrayList<String> src =  byteCodeGenerator.getByteCode();
            // 增加控制台输出
            interpreter.addVariableToGlobal("console", new Console());
            interpreter.eval(src.toArray(new String[src.size()]));
        }
    }
}
