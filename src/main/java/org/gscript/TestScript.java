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
import org.gscript.vm.value.GSFunction;
import org.gscript.vm.value.GSNull;
import org.gscript.vm.value.GSValue;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FilenameFilter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.URL;
import java.net.URLDecoder;
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
        } else if ("debugagent-waitattach".equals(mode)) {
            // 新增：DebugAgent waitForDebuggerAndAttach 模式（主线程驱动 attach）
            TestScript.debugAgentWaitAttach(name);
        } else if ("debugagent-eval".equals(mode)) {
            // 新增：enableDebugMode + startAttachListener + 多次 eval 演示（交互式调试）
            TestScript.debugAgentEval(name);
        } else if ("debugagent-callfn".equals(mode)) {
            // 新增：waitForDebuggerAndAttach + eval + callFunction 演示
            // 验证 attach 模式下宿主 callFunction 触发函数体内断点（虚拟源路径 sourceReference>0）
            TestScript.debugAgentCallFn(name);
        } else if ("batch".equals(mode)) {
            // 迁移自 Test.main：遍历 resources 根目录所有 .script，批量编译为 .gtxt（不执行）
            TestScript.batchCompile();
        } else {
            System.err.println("Unknown mode: " + mode);
            System.err.println("Usage: TestScript <name> [run|dump|compile|rungclass|dumpgclass|hosttest|debugagent|debugagent-attach|debugagent-waitattach|debugagent-eval|debugagent-callfn|batch]");
        }
    }

    public static void gen(String fileName) throws Exception {
        gen(fileName, false);
    }

    public static void gen(String fileName, boolean dump) throws Exception {
        // 获取classpath根目录
        URL rootUrl = TestScript.class.getResource("/");
        InputStream in = TestScript.class.getResourceAsStream("/" + fileName + ".script");
        if (in != null) {
            String content;
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) { bos.write(buf, 0, n); }
                content = new String(bos.toByteArray(), "UTF-8");
            } finally {
                try { in.close(); } catch (Exception e) { /* close 失败忽略 */ }
            }
            Lexer lexer = new Lexer();
            List tokens = lexer.tokenize(content);
            Parser parser = new Parser(tokens);
            Node program = parser.parseProgram();
            ByteCodeGenerator byteCodeGenerator = new ByteCodeGenerator();
            program.accept(byteCodeGenerator);
            // 使用File处理路径
            File outputPath = new File(resourceUrlToFile(rootUrl), "gtxt");
            outputPath = new File(outputPath, fileName + ".gtxt");
            list2File(byteCodeGenerator.getFormatByteCode(), outputPath.toString());
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
            GSInterpreter interpreter = new GSInterpreter();  // 构造器已自动初始化定时器
            ArrayList src =  byteCodeGenerator.getByteCode();
            ArrayList srcLines = byteCodeGenerator.getSourceLines();
            // 增加控制台输出
            interpreter.addVariableToGlobal("console", new Console());
            // 传 sourceLines + sourcePath，使异常信息能输出源码行号（与 rungclass 路径一致）
            int[] srcLineArr = null;
            if (srcLines != null && !srcLines.isEmpty()) {
                srcLineArr = new int[srcLines.size()];
                for (int i = 0; i < srcLines.size(); i++) {
                    srcLineArr[i] = ((Integer) srcLines.get(i)).intValue();
                }
            }
            interpreter.eval((String[]) src.toArray(new String[src.size()]), srcLineArr, fileName + ".script");
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
        URL rootUrl = TestScript.class.getResource("/");
        InputStream in = TestScript.class.getResourceAsStream("/" + name + ".script");
        if (in == null) {
            System.err.println("Script not found: /" + name + ".script");
            return;
        }
        String content;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) { bos.write(buf, 0, n); }
            content = new String(bos.toByteArray(), "UTF-8");
        } finally {
            try { in.close(); } catch (Exception e) { /* close 失败忽略 */ }
        }

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
        File gclassPath = new File(resourceUrlToFile(rootUrl), "gtxt");
        gclassPath = new File(gclassPath, name + ".gclass");
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

        GSInterpreter interpreter = new GSInterpreter();  // 构造器已自动初始化定时器
        interpreter.addVariableToGlobal("console", new Console());
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
        InputStream in = TestScript.class.getResourceAsStream("/gtxt/" + name + ".gclass");
        if (in == null) {
            System.err.println("gclass not found: /gtxt/" + name + ".gclass");
            System.err.println("请先运行: TestScript " + name + " compile");
            return null;
        }
        try {
            GSClassReader reader = new GSClassReader();
            GSClassData data = reader.deserialize(in);
            System.err.println("gclass loaded: /gtxt/" + name + ".gclass"
                    + " (bytecode=" + data.src.length + " instructions)");
            return data;
        } finally {
            try { in.close(); } catch (Exception e) { /* close 失败忽略 */ }
        }
    }

    // =========================================================================
    //  批量编译模式（迁移自 Test.java）
    // =========================================================================

    /**
     * 批量编译 resources 根目录下所有 .script 文件为 .gtxt（不执行）。
     * 原 Test.main 迁移，用于一次性刷新所有脚本的字节码 dump。
     *
     * <p>注意：本方法只编译不执行（与原 Test.gen 行为一致），
     * 不能调用 {@link #gen(String)} 因为后者会执行脚本。
     */
    public static void batchCompile() throws Exception {
        URL rootUrl = TestScript.class.getResource("/");
        if (rootUrl == null) {
            System.err.println("无法获取 resources 根目录（jar 内不支持，请用文件系统 classes 目录）");
            return;
        }
        File scriptsDir = resourceUrlToFile(rootUrl);
        File[] files = scriptsDir.listFiles(new FilenameFilter() {
            public boolean accept(File dir, String name) { return name.endsWith(".script"); }
        });
        if (files == null) {
            System.err.println("resources 目录不存在或非目录: " + scriptsDir);
            return;
        }
        File gtxtDir = new File(resourceUrlToFile(rootUrl), "gtxt");
        for (int i = 0; i < files.length; i++) {
            String fileName = files[i].getName();
            String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
            // 读取 .script 源码
            InputStream in = TestScript.class.getResourceAsStream("/" + baseName + ".script");
            if (in == null) {
                System.err.println("skip (not found): " + baseName);
                continue;
            }
            String content;
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) { bos.write(buf, 0, n); }
                content = new String(bos.toByteArray(), "UTF-8");
            } finally {
                try { in.close(); } catch (Exception e) { /* close 失败忽略 */ }
            }
            // 编译
            Lexer lexer = new Lexer();
            List tokens = lexer.tokenize(content);
            Parser parser = new Parser(tokens);
            Node program = parser.parseProgram();
            ByteCodeGenerator byteCodeGenerator = new ByteCodeGenerator();
            program.accept(byteCodeGenerator);
            // 写出 .gtxt（不执行）
            File outputPath = new File(gtxtDir, baseName + ".gtxt");
            list2File(byteCodeGenerator.getFormatByteCode(), outputPath.toString());
            System.err.println("compiled: " + baseName + ".gtxt");
        }
    }

    // =========================================================================
    //  宿主交互 API 测试（hosttest 模式）
    // =========================================================================

    /**
     * 宿主交互 API 演示与测试入口。
     *
     * <p>逐项打印结果到 stdout（格式 "key=value"），供 tests/test_host_interaction.py 校验。
     * 验证 Java 调用 gscript 解释器的 6 个 API：evalScript / evalExpression / getVariable /
     * setVariable / toJavaObject / fromJavaObject
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
            GSInterpreter interpreter = new GSInterpreter();  // 构造器已自动初始化定时器
            interpreter.addVariableToGlobal("console", new Console());
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

    /**
     * DebugAgent waitForDebuggerAndAttach 演示：主线程驱动 attach 模式。
     *
     * <p>验证模式 3：主线程阻塞等待 VSCode 连接，连接后 controller 注入 + pauseRequested，
     * 主线程继续执行脚本，首次 eval 即挂起（reason=pause），continue 后执行完毕。
     *
     * <p>典型场景模拟：宿主 static 块加载脚本——主线程需先等调试器连接，再依次执行脚本。
     *
     * @param name 脚本名（不含扩展名，需先 compile 生成 gclass）
     */
    public static void debugAgentWaitAttach(String name) throws Exception {
        GSClassData data = loadGclass(name);
        if (data == null) return;
        if (data.sourceContent == null) {
            System.err.println("警告: gclass 不含源码内容，attach 模式无法在 VSCode 显示源码");
            System.err.println("请重新编译: TestScript " + name + " compile");
        }
        int port = 4711;
        GSInterpreter interpreter = new GSInterpreter();  // 构造器已自动初始化定时器
        interpreter.addVariableToGlobal("console", new Console());

        DebugAgent agent = new DebugAgent(port);
        interpreter.enableDebugMode(agent);  // 主入口：setInterpreter + setDebugMode(true)
        agent.addGclass(data);          // 注册 sourceContent 供 VSCode source 请求

        System.err.println("[测试] waitForDebuggerAndAttach: 阻塞等待 VSCode 连接（端口 " + port + "）...");
        agent.waitForDebuggerAndAttach();  // 阻塞直到 VSCode 连接 + configurationDone

        // 连接后主线程继续执行脚本（首次 eval 命中 entryStopRequested → 挂起，reason=entry）
        System.err.println("[测试] 主线程开始执行脚本");
        interpreter.eval(data.src, data.constantPool, data.sourceLines,
                data.sourcePath, data.sourceContent);
        interpreter.runEventLoop();
        System.err.println("[测试] 主线程执行结束");
        agent.notifyScriptCompleted();  // 通知 DapServer 发 terminated 事件
        agent.stop();
    }

    /**
     * enableDebugMode + startAttachListener + 多次 eval 演示（交互式调试）。
     *
     * <p>验证调试模式核心语义：
     * <ol>
     *   <li>enableDebugMode 启用调试模式（主入口）</li>
     *   <li>startAttachListener 后台监听，立即返回</li>
     *   <li>VSCode 连接后，后续每次 eval（gclass/evalScript/evalExpression）都断第一行</li>
     *   <li>evalScript/evalExpression 的 sourcePath=eval-N.script，VSCode 可查看源码</li>
     *   <li>不调用 runEventLoop（主线程不被阻塞，适合宿主业务线程场景）</li>
     * </ol>
     *
     * <p>典型场景：宿主主线程有自己的业务逻辑，偶尔调用解释器执行脚本/表达式，
     * 调试模式下每次执行都断第一行，便于交互式调试。
     *
     * @param name 脚本名（不含扩展名，需先 compile 生成 gclass）
     */
    public static void debugAgentEval(String name) throws Exception {
        GSClassData data = loadGclass(name);
        if (data == null) return;
        if (data.sourceContent == null) {
            System.err.println("警告: gclass 不含源码内容，attach 模式无法在 VSCode 显示源码");
            System.err.println("请重新编译: TestScript " + name + " compile");
        }
        int port = 4711;
        GSInterpreter interp = new GSInterpreter();  // 构造器已自动初始化定时器
        interp.addVariableToGlobal("console", new Console());

        DebugAgent agent = new DebugAgent(port);
        interp.enableDebugMode(agent);  // 主入口：setInterpreter + setDebugMode(true)
        agent.addGclass(data);
        agent.startAttachListener();  // 后台监听，立即返回

        System.err.println("[测试] enableDebugMode 已启用，VSCode 可随时附加（端口 " + port + "）");
        System.err.println("[测试] VSCode 未连：eval 正常执行；连接后：每次 eval 断第一行");

        // 三次 eval 之间 sleep 1 秒，给 VSCode 附加留出时间（模拟宿主业务事件回调）
        // 第一次 eval：从 gclass 执行（VSCode 连接后断第一行）
        interp.eval(data.src, data.constantPool, data.sourceLines,
                data.sourcePath, data.sourceContent);
        Thread.sleep(1000);
        // 第二次 eval：evalScript（sourcePath=eval-1.script）
        interp.evalScript("console.log(\"second eval\");");
        Thread.sleep(1000);
        // 第三次 eval：表达式（sourcePath=eval-2.script，VSCode 可查看 "return (1 + 2);" 源码）
        interp.evalExpression("1 + 2");

        System.err.println("[测试] 所有 eval 执行结束");
        agent.notifyScriptCompleted();  // 通知 DapServer 发送 terminated 事件
        System.err.println("[测试] notifyScriptCompleted 已调用");
        agent.stop();
    }

    /**
     * waitForDebuggerAndAttach + eval + callFunction 演示。
     *
     * <p>验证 attach 模式下宿主通过 {@link GSInterpreter#callFunction} 回调 gscript 函数时，
     * 函数体内断点能正确命中。复现用户场景：脚本加载时函数仅定义不调用，
     * 后续由宿主 Java 代码直接 callFunction 触发执行。
     *
     * <p>关键验证点：
     * <ol>
     *   <li>脚本 eval 时函数体内断点不触发（函数未被调用）</li>
     *   <li>callFunction 调用时断点命中（reason=breakpoint）</li>
     *   <li>VSCode 通过 sourceReference（虚拟源）设置断点时路径匹配正确</li>
     * </ol>
     *
     * <p>测试脚本 callfn_test.script 定义 addNumbers(a,b) 函数，eval 时仅 console.log，
     * 函数体未被调用。eval 完成后宿主从 global 取出 addNumbers 并 callFunction 触发。
     *
     * @param name 脚本名（不含扩展名，需先 compile 生成 gclass）
     */
    public static void debugAgentCallFn(String name) throws Exception {
        GSClassData data = loadGclass(name);
        if (data == null) return;
        if (data.sourceContent == null) {
            System.err.println("警告: gclass 不含源码内容，attach 模式无法在 VSCode 显示源码");
            System.err.println("请重新编译: TestScript " + name + " compile");
        }
        int port = 4711;
        GSInterpreter interpreter = new GSInterpreter();  // 构造器已自动初始化定时器
        interpreter.addVariableToGlobal("console", new Console());

        DebugAgent agent = new DebugAgent(port);
        interpreter.enableDebugMode(agent);  // 主入口：setInterpreter + setDebugMode(true)
        agent.addGclass(data);          // 注册 sourceContent 供 VSCode source 请求

        System.err.println("[测试] debugagent-callfn: 阻塞等待 VSCode 连接（端口 " + port + "）...");
        agent.waitForDebuggerAndAttach();  // 阻塞直到 VSCode 连接 + configurationDone

        // 连接后主线程继续执行脚本（首次 eval 命中 entryStopRequested → 挂起，reason=entry）
        System.err.println("[测试] 主线程开始执行脚本（eval）");
        interpreter.eval(data.src, data.constantPool, data.sourceLines,
                data.sourcePath, data.sourceContent);
        interpreter.runEventLoop();
        System.err.println("[测试] eval 完成，addNumbers 已定义");

        // 宿主从 global 取出 addNumbers 函数，通过 callFunction 回调
        // 此时函数体内若有断点（通过 sourceReference 设置的虚拟源断点），应在此触发
        GSValue fnVal = interpreter.getVariable("addNumbers");
        if (fnVal instanceof GSFunction) {
            GSFunction fn = (GSFunction) fnVal;
            // OP_INVOKE 约定：args[0]=this（全局函数用 null），args[1..]=实际参数
            ArrayList args = new ArrayList();
            args.add(GSNull.NULL);              // args[0] = this
            args.add(new org.gscript.vm.value.GSInt(10));   // a = 10
            args.add(new org.gscript.vm.value.GSInt(20));   // b = 20
            System.err.println("[测试] callFunction(addNumbers, [10, 20])");
            GSValue result = interpreter.callFunction(fn, args);
            System.err.println("[测试] callFunction 返回: " + result.toStringValue());
        } else {
            System.err.println("[测试] 未找到 addNumbers 函数: " + fnVal);
        }

        agent.notifyScriptCompleted();  // 通知 DapServer 发 terminated 事件
        agent.stop();
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

    // =========================================================================
    //  文件系统辅助（Java 1.4 兼容）
    // =========================================================================

    /**
     * 从 classpath 资源 URL 构造 File（Java 1.4 兼容，替代 URL.toURI()）。
     * URL.toURI() 是 Java 1.5+ API，1.4 用 URLDecoder.decode(url.getFile(), "UTF-8") 替代。
     */
    private static File resourceUrlToFile(URL url) throws IOException {
        return new File(URLDecoder.decode(url.getFile(), "UTF-8"));
    }

    /**
     * 把字符串列表写入文件（UTF-8，覆盖模式，自动 mkdirs）。
     * 原 Test.list2File 迁移，供 gen 模式写出 .gtxt 用。
     */
    private static void list2File(ArrayList list, String filePath) throws IOException {
        File path = new File(filePath).getParentFile();
        if (path != null && !path.exists()) {
            path.mkdirs();
        }
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(filePath), "UTF-8"));
        try {
            for (int i = 0; i < list.size(); i++) {
                String line = (String) list.get(i);
                writer.write(line);
                writer.newLine();
            }
        } finally {
            try { writer.close(); } catch (Exception e) {}
        }
    }
}
