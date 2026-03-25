package org.gscript;

import org.gscript.compile.Lexer;
import org.gscript.compile.Parser;
import org.gscript.compile.gen.ByteCodeGenerator;
import org.gscript.compile.node.Node;
import org.gscript.compile.token.GSToken;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 测试类
 */
public class Test {
    public static void main(String[] args) throws Exception {
        // 获取resources根目录
        URL rootUrl = Test.class.getResource("/");
        if (rootUrl != null) {
            Path scriptsDir = Paths.get(rootUrl.toURI());

            // 遍历目录下所有.script文件
            try (java.nio.file.DirectoryStream<Path> stream = Files.newDirectoryStream(scriptsDir, "*.script")) {
                for (Path scriptPath : stream) {
                    // 获取文件名（不含扩展名）
                    String fileName = scriptPath.getFileName().toString();
                    String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
                    // 调用原有的gen方法
                    Test.gen(baseName);
                }
            }
        }
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
        }
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
}
