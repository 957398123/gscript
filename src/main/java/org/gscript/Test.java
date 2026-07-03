package org.gscript;

import org.gscript.compile.Lexer;
import org.gscript.compile.Parser;
import org.gscript.compile.gen.ByteCodeGenerator;
import org.gscript.compile.node.Node;
import org.gscript.compile.token.GSToken;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.net.URISyntaxException;
import java.net.URL;
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
            File scriptsDir = new File(rootUrl.toURI());

            // 遍历目录下所有.script文件
            File[] files = scriptsDir.listFiles(new java.io.FilenameFilter() {
                public boolean accept(File dir, String name) { return name.endsWith(".script"); }
            });
            for (int i = 0; i < files.length; i++) {
                File scriptPath = files[i];
                // 获取文件名（不含扩展名）
                String fileName = scriptPath.getName();
                String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
                // 调用原有的gen方法
                Test.gen(baseName);
            }
        }
    }

    public static void gen(String fileName) throws Exception {
        URL classUrl = Test.class.getResource("Test.class");
        // 获取classpath根目录
        URL rootUrl = Test.class.getResource("/");
        InputStream in = Test.class.getResourceAsStream("/" + fileName + ".script");
        if (in != null) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) { bos.write(buf, 0, n); }
            in.close();
            String content = new String(bos.toByteArray(), "UTF-8");
            Lexer lexer = new Lexer();
            List tokens = lexer.tokenize(content);
            Parser parser = new Parser(tokens);
            Node program = parser.parseProgram();
            ByteCodeGenerator byteCodeGenerator = new ByteCodeGenerator();
            program.accept(byteCodeGenerator);
            // 使用File处理路径
            File outputPath = new File(new File(rootUrl.toURI()), "gtxt");
            outputPath = new File(outputPath, fileName + ".gtxt");
            Test.list2File(byteCodeGenerator.getFormatByteCode(), outputPath.toString());
        }
    }

    public static void list2File(ArrayList list, String filePath) throws IOException {
        // 在写入文件前检查目录
        File path = new File(filePath).getParentFile();
        if (path != null && !path.exists()) {
            path.mkdirs();
        }

        // 写入文件（覆盖模式）
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(filePath), "UTF-8"));
        try {

            // 遍历列表，每个字符串写入一行
            for (int i = 0; i < list.size(); i++) {
                String line = (String) list.get(i);
                writer.write(line);
                writer.newLine(); // 换行
            }
        } finally {
            try { writer.close(); } catch (Exception e) {}
        }
    }
}
