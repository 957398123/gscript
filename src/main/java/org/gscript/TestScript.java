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
import org.gscript.vm.debug.DebugAgent;
import org.gscript.vm.stdlib.Console;
import org.gscript.vm.value.GSNull;
import org.gscript.vm.value.GSValue;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileOutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        } else if ("hosttest".equals(mode)) {
            // 新增：宿主交互 API 测试（Java 调用 gscript 解释器）
            TestScript.hostTest();
        } else if ("debugagent".equals(mode)) {
            // 新增：DebugAgent waitForDebugger 模式（debug 模式启用）
            TestScript.debugAgent(name, false);
        } else if ("debugagent-attach".equals(mode)) {
            // 新增：DebugAgent attachReady 模式（运行时附加）
            TestScript.debugAgent(name, true);
        } else {
            System.err.println("Unknown mode: " + mode);
            System.err.println("Usage: TestScript <name> [run|dump|compile|rungclass|dumpgclass|hosttest|debugagent|debugagent-attach]");
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
            java.io.File outputPath = new java.io.File(new java.io.File(rootUrl.toURI()), "gtxt");
            outputPath = new java.io.File(outputPath, fileName + ".gtxt");
            Test.list2File(byteCodeGenerator.getFormatByteCode(), outputPath.toString());
            // dump 模式：打印字节码索引和 sourceLine 对照（调试器源码映射验证用）
            if (dump) {
                ArrayList bc = byteCodeGenerator.getByteCode();
                ArrayList lines = byteCodeGenerator.getSourceLines();
                System.err.println("==== bytecode<->sourceLine dump ====");
                for (int i = 0; i < bc.size(); i++) {
                    int ln = (i < lines.size()) ? ((Integer) lines.get(i)).intValue() : 0;
                    System.err.println(padLeft(String.valueOf(i), 4) + " [line " + padRight(String.valueOf(ln), 3) + "] " + bc.get(i));
                }
                System.err.println("==== end dump ====");
                return;  // dump 模式只打印不执行
            }
            // 创建解释器，并执行脚本
            GSInterpreter interpreter = new GSInterpreter();
            ArrayList src =  byteCodeGenerator.getByteCode();
            // 增加控制台输出
            interpreter.addVariableToGlobal("console", new Console());
            interpreter.installTimerGlobals();
            interpreter.eval((String[]) src.toArray(new String[src.size()]));
            interpreter.runEventLoop();
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
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) { bos.write(buf, 0, n); }
        in.close();
        String content = new String(bos.toByteArray(), "UTF-8");

        // 编译
        Lexer lexer = new Lexer();
        List tokens = lexer.tokenize(content);
        Parser parser = new Parser(tokens);
        Node program = parser.parseProgram();
        ByteCodeGenerator gen = new ByteCodeGenerator();
        program.accept(gen);

        ArrayList bytecode = gen.getByteCode();
        ArrayList sourceLines = gen.getSourceLines();

        // 序列化为 .gclass
        // sourcePath 设为 name + ".script"（调试用标识，非绝对路径）
        // sourceContent 传入完整源码文本（attach 调试模式通过 source 请求返回）
        String sourcePath = name + ".script";
        java.io.File gclassPath = new java.io.File(new java.io.File(rootUrl.toURI()), "gtxt");
        gclassPath = new java.io.File(gclassPath, name + ".gclass");
        File gclassFile = gclassPath;
        gclassFile.getParentFile().mkdirs();  // 确保 gtxt 目录存在
        GSClassWriter writer = new GSClassWriter();
        OutputStream out = new FileOutputStream(gclassFile);
        try {
            writer.write(bytecode, sourceLines, sourcePath, content, out);
        } finally {
            try { out.close(); } catch (Exception e) {}
        }
        System.err.println("gclass written: " + gclassPath);
        System.err.println("  bytecode instructions: " + bytecode.size());
        System.err.println("  source lines: " + (sourceLines != null ? sourceLines.size() : 0));
        System.err.println("  source content: " + (content != null ? content.length() + " chars" : "null"));
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
        interpreter.installTimerGlobals();
        interpreter.eval(data.src, data.constantPool, data.sourceLines, data.sourcePath, data.sourceContent);
        interpreter.runEventLoop();
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
            System.err.println(padLeft(String.valueOf(i), 4) + " [line " + padRight(String.valueOf(ln), 3) + "] " + text);
        }
        System.err.println("==== end gclass dump ====");

        // 打印 FunctionTable（若有 attributes 段）
        if (data.functions != null && data.functions.length > 0) {
            System.err.println("==== function table ====");
            for (int i = 0; i < data.functions.length; i++) {
                GSClassData.FunctionEntry fe = data.functions[i];
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

    // =========================================================================
    //  宿主交互 API 测试（hosttest 模式）
    // =========================================================================

    /**
     * 宿主交互 API 演示与测试入口。
     *
     * <p>逐项打印结果到 stdout（格式 "key=value"），供 tests/test_host_interaction.py 校验。
     * 验证 Java 调用 gscript 解释器的 6 个 API：evalScript / evalExpression / getVariable /
     * setVariable / toJavaObject / fromJavaObject。
     */
    public static void hostTest() {
        GSInterpreter interpreter = new GSInterpreter();
        interpreter.addVariableToGlobal("console", new Console());

        // 1. evalScript 执行语句 + getVariable 读取变量
        interpreter.evalScript("var x = 10; var y = 20; function add(a, b) { return a + b; }");
        System.out.println("x=" + interpreter.getVariable("x").toIntValue());
        System.out.println("y=" + interpreter.getVariable("y").toIntValue());

        // 2. evalExpression 求值表达式（调用上面定义的 add）
        GSValue sum = interpreter.evalExpression("add(x, y)");
        System.out.println("sum=" + sum.toIntValue());

        // 3. setVariable 注入 Java 值 + gscript 读取
        interpreter.setVariable("z", new Integer(100));
        interpreter.setVariable("greeting", "hello");
        interpreter.evalScript("console.log(z); console.log(greeting);");

        // 4. toJavaObject：对象 + 嵌套数组递归转换
        GSValue obj = interpreter.evalExpression("{name: \"Alice\", age: 30, scores: [90, 85, 95]}");
        Map javaObj = (Map) obj.toJavaObject();
        System.out.println("obj.name=" + javaObj.get("name"));
        System.out.println("obj.age=" + javaObj.get("age"));
        System.out.println("obj.scores=" + javaObj.get("scores"));

        // 5. fromJavaObject：注入 Map/List + gscript 访问
        Map config = new LinkedHashMap();
        config.put("timeout", new Integer(5000));
        config.put("retries", new Integer(3));
        List tags = new ArrayList(Arrays.asList(new Integer[]{new Integer(1), new Integer(2), new Integer(3)}));
        interpreter.setVariable("config", config);
        interpreter.setVariable("tags", tags);
        GSValue timeout = interpreter.evalExpression("config.timeout");
        GSValue retries = interpreter.evalExpression("config.retries");
        GSValue tag0 = interpreter.evalExpression("tags[0]");
        System.out.println("config.timeout=" + timeout.toIntValue());
        System.out.println("config.retries=" + retries.toIntValue());
        System.out.println("tags[0]=" + tag0.toIntValue());

        // 6. 表达式运行时出错（调用非函数值）→ 返回 GSNull.NULL
        GSValue err = interpreter.evalExpression("x()");
        System.out.println("err_is_null=" + (err == GSNull.NULL));
    }

    // =========================================================================
    //  DebugAgent 调试模式（debugagent / debugagent-attach）
    // =========================================================================

    /**
     * DebugAgent 演示：加载 gclass + 启动调试代理。
     *
     * <p>验证 attach 调试两种启用方式：
     * <ul>
     *   <li>attachReady=false：waitForDebugger 模式，阻塞等待 VSCode 连接后执行（"debug 模式启用"）</li>
     *   <li>attachReady=true：attachReady 模式，解释器先运行，VSCode 随后附加（"运行时 attach"）</li>
     * </ul>
     *
     * @param name 脚本名（不含扩展名，需先 compile 生成 gclass）
     * @param attachReady true=运行时附加模式，false=等待调试器模式
     */
    public static void debugAgent(String name, boolean attachReady) throws Exception {
        GSClassData data = loadGclass(name);
        if (data == null) return;
        if (data.sourceContent == null) {
            System.err.println("警告: gclass 不含源码内容，attach 模式无法在 VSCode 显示源码");
            System.err.println("请重新编译: TestScript " + name + " compile");
        }
        int port = 4711;
        DebugAgent agent = new DebugAgent(port);
        if (attachReady) {
            // 模式 2：先启动解释器运行，再监听附加
            GSInterpreter interpreter = new GSInterpreter();
            interpreter.addVariableToGlobal("console", new Console());
            interpreter.installTimerGlobals();
            agent.setInterpreter(interpreter);
            agent.addGclass(data);
            agent.startAttachListener();  // 后台监听，立即返回
            System.err.println("[测试] 解释器开始运行，VSCode 可随时附加（端口 " + port + "）");
            // 主线程运行解释器（VSCode 连接后因 controller 设置而挂起）
            interpreter.eval(data.src, data.constantPool, data.sourceLines,
                    data.sourcePath, data.sourceContent);
            interpreter.runEventLoop();
            System.err.println("[测试] 解释器运行结束");
            agent.stop();
        } else {
            // 模式 1：等待 VSCode 连接后运行
            agent.addGclass(data);
            agent.waitForDebuggerAndRun();  // 阻塞直到调试会话结束
            System.err.println("[测试] 调试会话结束");
        }
    }

    // =========================================================================
    //  格式化辅助（替代 String.format，Java 1.4 兼容）
    // =========================================================================

    /** 右对齐：左侧补空格到 width（替代 String.format("%4d")）。 */
    private static String padLeft(String s, int width) {
        StringBuffer sb = new StringBuffer();
        while (sb.length() + s.length() < width) {
            sb.append(' ');
        }
        sb.append(s);
        return sb.toString();
    }

    /** 左对齐：右侧补空格到 width（替代 String.format("%-3d")）。 */
    private static String padRight(String s, int width) {
        StringBuffer sb = new StringBuffer(s);
        while (sb.length() < width) {
            sb.append(' ');
        }
        return sb.toString();
    }
}
