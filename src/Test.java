import gscript.gen.ByteCodeMixuper;
import gscript.gen.ByteCodeSerialize;
import gscript.vm.Interpreter;
import gscript.Lexer;
import gscript.Parser;
import gscript.node.Node;
import gscript.token.GSToken;
import gscript.gen.ByteCodeGenerator;

import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

public class Test {
    public static void main(String[] args) throws IOException {
        String[] list = new String[]{"vars", "chat", "gui", "cons", "tj", "util", "member", "battle", "autoplay"};
        // String[] list = new String[]{"test"};
        Test.gen(list);
    }

    public static void list2File(ArrayList<String> list, String filePath) throws IOException {
        // 在写入文件前检查目录
        Path path = Paths.get(filePath).getParent();
        if (path != null && !Files.exists(path)) {
            Files.createDirectories(path);
        }

        // 写入文件（覆盖模式）
        try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(filePath),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {

            // 遍历列表，每个字符串写入一行
            for (String line : list) {
                writer.write(line);
                writer.newLine(); // 换行
            }
        }
    }

    public static void gen(String[] files) throws IOException {
        for (int i = 0; i < files.length; i++) {
            URL classUrl = Test.class.getResource("Test.class");
            // 提取 Test.class 的所在目录路径
            File classFile = new File(classUrl.getPath());
            String classDir = classFile.getParent();
            String fileName = files[i];
            InputStream in = Test.class.getResourceAsStream(fileName + ".script");
            if (in != null) {
                String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                in.close();
                Lexer lexer = new Lexer();
                List<GSToken> tokens = lexer.tokenize(content);
                Parser parser = new Parser(tokens);
                // 构建节点树
                Node node = parser.parseProgram();
                // 代码混淆
                // ByteCodeMixuper codeMixuper = new ByteCodeMixuper();
                // node.accept(codeMixuper);
                // 字节码生成
                ByteCodeGenerator bytecodeGenerator = new ByteCodeGenerator();
                node.accept(bytecodeGenerator);
                Test.list2File(bytecodeGenerator.getByteCode(), classDir + File.separator + "gtxt" + File.separator + fileName + ".gtxt");
                // 解释执行
                // System.out.println("GS Engine Start!");
                ByteCodeSerialize.serialize(bytecodeGenerator.getByteCode(), classDir + File.separator + "gclass" + File.separator + fileName + ".gclass");
                if ("test".equals(fileName)) {
                    Interpreter interpreter = new Interpreter();
                    interpreter.eval(bytecodeGenerator.getByteCode());
                }
                // System.out.println("GS Engine End!");
            }
        }
    }
}