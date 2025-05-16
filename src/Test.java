import gscript.Interpreter;
import gscript.Lexer;
import gscript.Parser;
import gscript.node.Node;
import gscript.token.GSToken;
import gscript.gen.BytecodeGenerator;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public class Test {
    public static void main(String[] args) throws IOException {
        InputStream in = Test.class.getResourceAsStream("/test.script");
        if(in != null){
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            in.close();
            Lexer lexer = new Lexer();
            List<GSToken> tokens =  lexer.tokenize(content);
            Parser parser = new Parser(tokens);
            Node node = parser.parseProgram();
            BytecodeGenerator bytecodeGenerator = new BytecodeGenerator();
            node.accept(bytecodeGenerator);
            // bytecodeGenerator.print();
            // 解释执行
            System.out.println("GS Engine Start!");
            Interpreter interpreter = new Interpreter();
            interpreter.eval(bytecodeGenerator.getByteCode());
            System.out.println("GS Engine End!");
        }
    }
}
