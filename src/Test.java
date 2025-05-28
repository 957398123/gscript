import gscript.gen.ByteCodeSerialize;
import gscript.vm.Interpreter;
import gscript.Lexer;
import gscript.Parser;
import gscript.node.Node;
import gscript.token.GSToken;
import gscript.gen.ByteCodeGenerator;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class Test {
    public static void main(String[] args) throws IOException {
        URL classUrl = Test.class.getResource("Test.class");
        // 提取 Test.class 的所在目录路径
        File classFile = new File(classUrl.getPath());
        String classDir = classFile.getParent();
        InputStream in = Test.class.getResourceAsStream("tj.script");
        if (in != null) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            in.close();
            Lexer lexer = new Lexer();
            List<GSToken> tokens = lexer.tokenize(content);
            Parser parser = new Parser(tokens);
            Node node = parser.parseProgram();
            ByteCodeGenerator bytecodeGenerator = new ByteCodeGenerator();
            node.accept(bytecodeGenerator);
            // bytecodeGenerator.print();
            // 解释执行
            // System.out.println("GS Engine Start!");
            ByteCodeSerialize.serialize(bytecodeGenerator.getByteCode(), classDir + File.separator + "tj.gclass");
            Interpreter interpreter = new Interpreter();
            interpreter.eval(bytecodeGenerator.getByteCode());
            // System.out.println("GS Engine End!");
        }
    }
}
