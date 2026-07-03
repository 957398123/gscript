# 计划：合并 Test/TestScript + 增强 GSInterpreter

## 1. 背景与目标

用户提出两项相互独立但都属于"运行时入口整理"的工作：

1. **合并 `Test.java` 与 `TestScript.java`**：`Test.java`（91 行）只是一个批量编译入口（遍历 resources 根目录所有 `.script`，调用 `Test.gen` 编译为 `.gtxt`），功能是 `TestScript.gen` 的子集。两个类并存导致：
   - 7 处 `Test.class.getResource(...)` 引用散落在 `TestScript.java`（语义实际上指向 `TestScript` 自身的 classpath 资源）
   - 4 处 `rootUrl.toURI()` 调用（Test.java 行 28/64，TestScript.java 行 88/151）—— **`URL.toURI()` 是 Java 1.5+ API**，与项目 `-source 1.4 -target 1.4` 不兼容。虽然 JDK 8 rt.jar 仍能编译通过（`-source 1.4` 不限制 bootclasspath API），但在真 JDK 1.4 运行时会 `NoSuchMethodError`。项目记忆明确要求"纯 1.4 语法与 API"，须替换为 1.4 兼容方案。
   - `Test.list2File` 是 TestScript.java 行 90 唯一外部依赖的工具方法，须迁移。

2. **增强 `GSInterpreter`**：
   - 当前 GSInterpreter **没有任何文件/流加载方法**——所有文件 I/O 都在外部（TestScript/DapServer）完成。这导致 GSInterpreter 作为可被其他模块集成的"运行时入口"能力不完整：宿主必须自己写 Lexer→Parser→ByteCodeGenerator→BytecodeEncoder 全套编译流水线，或自己读 gclass 二进制。
   - GSInterpreter 已依赖 `org.gscript.vm.debug.DebugController`，但**不直接支持 launch/attach 调试模式**——宿主若想用 GSInterpreter 跑带调试的脚本，必须自己组装 DebugAgent（参考 TestScript.debugAgent 行 304-334 的 30 行模板代码）。这套模板代码封装到 GSInterpreter 后，宿主只需一行调用。

## 2. 现状分析（基于 Phase 1 探索）

### 2.1 Test.java（待删除）
- `main(String[])`（行 24-43）：遍历 resources 根目录 `.script` 文件，批量调 `Test.gen(baseName)` 编译为 `.gtxt`（**不执行**）。
- `gen(String fileName)`（行 45-68）：编译 `.script` → `.gtxt`，是 `TestScript.gen` 的子集（不执行、不 dump）。含 1 处死代码（行 46 `classUrl` 赋值后未使用）。
- `list2File(ArrayList, String)`（行 70-90）：**唯一需迁移的方法**，把 ArrayList 写成 UTF-8 文本文件（覆盖模式，自动 mkdirs）。
- imports 含 2 个未使用项：`URISyntaxException`、`GSToken`。

### 2.2 TestScript.java（合并目标，358 行）
- `main`（行 30-63）：8 种 mode 分发（run/dump/compile/rungclass/dumpgclass/hosttest/debugagent/debugagent-attach）。
- `gen(name)`/`gen(name, dump)`（行 65-112）：编译 + 可选 dump + 执行。
- `compileGclass`/`runGclass`/`dumpGclass`/`loadGclass`（行 122-231）：gclass 序列化/反序列化。
- `hostTest`（行 244-286）：宿主交互 API 测试。
- `debugAgent(name, attachReady)`（行 304-334）：调试模式入口（DebugAgent 模板代码）。
- `padLeft`/`padRight`（行 341-357）：格式化辅助。
- **7 处 `Test.class` 引用**：行 70、72、73、90（gen）、123、124（compileGclass）、219（loadGclass）。
- **1 处 `Test.list2File` 依赖**：行 90。
- **2 处 `toURI()` 调用**：行 88（gen）、151（compileGclass）。
- **Python 测试依赖类名 `org.gscript.TestScript`**（见 `tests/test_timer.py:JAVA=...; cmd=[JAVA, "-cp", CLASSES_DIR, "org.gscript.TestScript", ...]`），合并后**必须保留 `TestScript` 类名**，否则 10 个测试套件全部失效。

### 2.3 GSInterpreter.java（926 行，增强目标）
- **5 个 public eval 重载**（已验证签名）：
  - `eval(byte[][], Object[], int[], String)` ← 委托给 5 参版本
  - `eval(byte[][], Object[], int[], String, String)` ← 带 sourceContent 的核心入口（行 674-688）
  - `eval(String[], int[], String)` ← 文本字节码入口（行 700-704）
  - `eval(String[], int[])` ← 重载
  - `eval(String[])` ← 重载
- **宿主交互 API**：`evalScript(String)`（行 758-761，调 `eval(..., null, null)` **无源码映射**）、`evalExpression(String)`、`getVariable`、`setVariable`。
- **private `compile(String code)`**（行 738-747）：返回 `EncodedBytecode`（**不含 sourceLines**），是 evalScript/evalExpression 共用的编译器。
- **调试 API**：`setDebugController`/`getDebugController`、`getCallStackSnapshot`（synchronized）。
- **定时器/事件循环**：`callFunction`、`scheduleTimeout`/`scheduleInterval`/`cancelTimer`、`runEventLoop`、`installTimerGlobals`。
- **字段**：`global`(GSEnv)、`stack`(LinkedList)、`callStack`(LinkedList)、`debugController`(volatile)、`timerScheduler`(lazy)。
- **import 情况**：已 import `org.gscript.vm.debug.DebugController`、`DebugAbortException`，**未 import DebugAgent**。GSInterpreter 引入 DebugAgent 会形成 GSInterpreter ↔ DebugAgent 循环 import（Java 允许，架构上可接受，因二者本就紧耦合）。

### 2.4 DebugAgent.java（增强参考）
- 两种模式：`waitForDebuggerAndRun()`（launch，阻塞）、`startAttachListener()`（attach，后台线程）。
- `setInterpreter(GSInterpreter)`：attachReady 模式必需，waitForDebugger 模式可选（为 null 时 onConfigurationDone 自建）。
- `addGclass(GSClassData)`：注册待执行 gclass（waitForDebugger 模式用）+ 缓存 sourceContent。
- 已有完整模板代码（见 TestScript.debugAgent 行 304-334）。

### 2.5 GSClassData 结构
- 字段：`src`(byte[][])、`constantPool`(Object[])、`sourceLines`(int[])、`sourcePath`(String)、`functions`(FunctionEntry[])、`sourceContent`(String)。
- `GSClassReader.deserialize(InputStream)` 返回 `GSClassData`（自研 `readAllBytes`，非 Java 9 API）。

### 2.6 toURI() 的 1.4 兼容替代方案
- `URL.toURI()` → Java 1.5+ API，1.4 替代：`new File(java.net.URLDecoder.decode(url.getFile(), "UTF-8"))`。
- `URLDecoder.decode(String, String)` 是 Java 1.4 API（throws `UnsupportedEncodingException`，需 catch/declare）。
- `URL.getFile()` 返回的路径已 URL 编码（空格为 `%20` 等），`URLDecoder.decode` 还原为文件系统路径。
- 项目记忆已记录此模式："compile 模式不能用 jar" 警示了 `Test.class.getResource("/")` 在 jar 内返回 null，但文件系统目录 `target/classes` 下返回的 URL 形如 `file:/E:/.../target/classes/`，`URLDecoder.decode(url.getFile(), "UTF-8")` 得到 `/E:/.../target/classes/`，`new File(...)` 可正确构造。

## 3. 实施方案

### Part 1: 合并 Test + TestScript

#### 3.1.1 修改 TestScript.java

**A. 替换 4 处 `toURI()`（Test.java 行 28/64 + TestScript.java 行 88/151）为 1.4 兼容方案**

新增私有静态工具方法（替代 `new File(rootUrl.toURI())`）：

```java
/**
 * 从 classpath 资源 URL 构造 File（Java 1.4 兼容，替代 URL.toURI()）。
 * URL.toURI() 是 Java 1.5+ API，1.4 用 URLDecoder.decode(url.getFile(), "UTF-8") 替代。
 */
private static java.io.File resourceUrlToFile(java.net.URL url) throws java.io.IOException {
    return new java.io.File(java.net.URLDecoder.decode(url.getFile(), "UTF-8"));
}
```

替换 4 处调用：
- TestScript.java 行 88：`new java.io.File(new java.io.File(rootUrl.toURI()), "gtxt")` → `new java.io.File(resourceUrlToFile(rootUrl), "gtxt")`
- TestScript.java 行 151：同上
- （Test.java 的 2 处随文件删除自然消失）

**B. 替换 7 处 `Test.class` → `TestScript.class`**

TestScript.java 行 70、72、73、90、123、124、219 全部把 `Test.class` 改为 `TestScript.class`。语义不变（都是获取 TestScript 自身的 classpath 资源），但消除了对 Test 类的依赖。

**C. 内联 `Test.list2File` 方法到 TestScript**

在 TestScript.java 中新增 private static 方法（直接复制 Test.list2File 实现，行 70-90）：

```java
/**
 * 把字符串列表写入文件（UTF-8，覆盖模式，自动 mkdirs）。
 * 原 Test.list2File 迁移，供 gen 模式写出 .gtxt 用。
 */
private static void list2File(ArrayList list, String filePath) throws java.io.IOException {
    java.io.File path = new java.io.File(filePath).getParentFile();
    if (path != null && !path.exists()) {
        path.mkdirs();
    }
    java.io.BufferedWriter writer = new java.io.BufferedWriter(
            new java.io.OutputStreamWriter(new java.io.FileOutputStream(filePath), "UTF-8"));
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
```

TestScript.java 行 90 的 `Test.list2File(...)` 调用改为 `TestScript.list2File(...)`（或直接 `list2File(...)` 因同类静态方法可省略类前缀）。

**D. 新增 `batch` mode（迁移 Test.main 的批量编译能力）**

在 TestScript.main 的 mode 分发链中新增分支：

```java
} else if ("batch".equals(mode)) {
    // 迁移自 Test.main：遍历 resources 根目录所有 .script，批量编译为 .gtxt（不执行）
    TestScript.batchCompile();
}
```

新增方法（迁移 Test.main 行 24-43 的逻辑，使用 `TestScript.class` 和 `resourceUrlToFile`）：

```java
/**
 * 批量编译 resources 根目录下所有 .script 文件为 .gtxt（不执行）。
 * 原 Test.main 迁移，用于一次性刷新所有脚本的字节码 dump。
 */
public static void batchCompile() throws Exception {
    java.net.URL rootUrl = TestScript.class.getResource("/");
    if (rootUrl == null) {
        System.err.println("无法获取 resources 根目录（jar 内不支持，请用文件系统 classes 目录）");
        return;
    }
    java.io.File scriptsDir = resourceUrlToFile(rootUrl);
    java.io.File[] files = scriptsDir.listFiles(new java.io.FilenameFilter() {
        public boolean accept(java.io.File dir, String name) { return name.endsWith(".script"); }
    });
    if (files == null) {
        System.err.println("resources 目录不存在或非目录: " + scriptsDir);
        return;
    }
    for (int i = 0; i < files.length; i++) {
        String fileName = files[i].getName();
        String baseName = fileName.substring(0, fileName.lastIndexOf('.'));
        TestScript.gen(baseName);
        System.err.println("compiled: " + baseName + ".gtxt");
    }
}
```

更新 main 的 usage 提示，新增 `batch`：
```
Usage: TestScript <name> [run|dump|compile|rungclass|dumpgclass|hosttest|debugagent|debugagent-attach|batch]
```

**E. 删除 Test.java**

删除 `e:\JProjects\gscript\src\main\java\org\gscript\Test.java`（91 行）。所有功能已迁移到 TestScript。

#### 3.1.2 Part 1 验证

- JDK 1.8 + 1.4 编译：`$env:JAVA_HOME="C:\Program Files\Java\jdk1.8.0_202"; mvn clean package -DskipTests`
- 回归测试：`python tests/compare_baseline.py`（期望 179 PASS / 0 FAIL，10/10 MATCH baseline）
- 手测 batch mode：`java -cp target/classes org.gscript.TestScript x batch`（应遍历编译所有 .script）

---

### Part 2: GSInterpreter 文件/流加载方法

#### 3.2.1 新增 import

GSInterpreter.java 新增 import（不与现有冲突）：
```java
import org.gscript.compile.gclass.GSClassData;
import org.gscript.compile.gclass.GSClassReader;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.File;
```

#### 3.2.2 新增 4 个 public 文件/流加载方法

放在 `evalScript`/`evalExpression` 之后、`getVariable` 之前（与现有宿主交互 API 归为一组）。

**A. `evalScriptFile(String filePath)`** — 从文件系统读取 .script，编译 + 执行（**带源码映射**，区别于 `evalScript` 的无映射版本）

```java
/**
 * 从文件系统读取 gscript 源码文件，编译并执行。
 *
 * <p>与 {@link #evalScript(String)} 的区别：本方法保留源码行号映射和源码内容，
 * 供调试器断点/单步/源码查看使用。sourcePath 取文件名（非绝对路径），
 * 与 {@link #eval(byte[][], Object[], int[], String, String)} 的多文件约定一致。
 *
 * <p>调用方负责在调用前 addVariableToGlobal("console", ...) 和 installTimerGlobals()，
 * 以及在需要时调用 runEventLoop() 处理定时器回调。
 *
 * @param filePath .script 文件路径（绝对或相对当前工作目录）
 * @throws IOException 文件读取失败
 * @throws Exception   编译失败
 */
public void evalScriptFile(String filePath) throws Exception {
    File f = new File(filePath);
    byte[] raw = readFileBytes(f);
    String content = new String(raw, "UTF-8");
    String sourcePath = f.getName();
    evalScriptContent(content, sourcePath);
}
```

**B. `evalScriptStream(InputStream in, String sourcePath)`** — 从流读取源码，编译 + 执行

```java
/**
 * 从输入流读取 gscript 源码，编译并执行。
 *
 * <p>适用于 classpath 资源、网络流、zip 条目等非文件系统场景。
 * sourcePath 由调用方指定（如 "myscript.script"），仅供调试器标识用。
 *
 * @param in         输入流（方法内会读取但**不关闭**，由调用方负责）
 * @param sourcePath 源码标识路径（调试用，可为 null）
 * @throws IOException 流读取失败
 * @throws Exception   编译失败
 */
public void evalScriptStream(InputStream in, String sourcePath) throws Exception {
    byte[] raw = readStreamBytes(in);
    String content = new String(raw, "UTF-8");
    evalScriptContent(content, sourcePath);
}
```

**C. `evalGclassFile(String filePath)`** — 从文件系统读取 .gclass，反序列化 + 执行

```java
/**
 * 从文件系统读取 .gclass 二进制文件，反序列化并执行。
 *
 * <p>gclass 文件由 {@link org.gscript.compile.gclass.GSClassWriter} 序列化产生，
 * 含字节码 + 常量池 + 源码映射 + 源码内容 + CRC32 校验。
 * 本方法不调用 runEventLoop，调用方需自行处理定时器回调。
 *
 * @param filePath .gclass 文件路径
 * @throws IOException 文件读取失败
 * @throws Exception   反序列化或 CRC 校验失败
 */
public void evalGclassFile(String filePath) throws Exception {
    FileInputStream in = new FileInputStream(filePath);
    try {
        evalGclassStream(in);
    } finally {
        try { in.close(); } catch (Exception e) {}
    }
}
```

**D. `evalGclassStream(InputStream in)`** — 从流读取 gclass，反序列化 + 执行

```java
/**
 * 从输入流读取 .gclass 二进制数据，反序列化并执行。
 *
 * @param in 输入流（方法内会读取但**不关闭**，由调用方负责）
 * @throws IOException 流读取失败
 * @throws Exception   反序列化或 CRC 校验失败
 */
public void evalGclassStream(InputStream in) throws Exception {
    GSClassReader reader = new GSClassReader();
    GSClassData data = reader.deserialize(in);
    eval(data.src, data.constantPool, data.sourceLines, data.sourcePath, data.sourceContent);
}
```

#### 3.2.3 新增 2 个 private 辅助方法

**A. `evalScriptContent(String code, String sourcePath)`** — 编译 + 执行（带完整源码映射）

```java
/**
 * 编译 gscript 源码并执行（带完整源码映射，供调试器使用）。
 *
 * <p>与 {@link #evalScript(String)} 的区别：保留 sourceLines + sourceContent + sourcePath，
 * 使调试器能正确映射断点和 source 请求。内部走完整 ByteCodeGenerator 流水线
 * （而非 {@link #compile(String)} 的简化版本，后者不返回 sourceLines）。
 *
 * @param code       gscript 源码
 * @param sourcePath 源码标识路径（调试用，可为 null）
 */
private void evalScriptContent(String code, String sourcePath) {
    Lexer lexer = new Lexer();
    List tokens = lexer.tokenize(code);
    Parser parser = new Parser(tokens);
    Node program = parser.parseProgram();
    ByteCodeGenerator gen = new ByteCodeGenerator();
    program.accept(gen);
    String[] src = (String[]) gen.getByteCode().toArray(new String[0]);
    ArrayList srcLineList = gen.getSourceLines();
    int[] sourceLines = new int[srcLineList.size()];
    for (int i = 0; i < srcLineList.size(); i++) {
        sourceLines[i] = ((Integer) srcLineList.get(i)).intValue();
    }
    BytecodeEncoder encoder = new BytecodeEncoder();
    EncodedBytecode encoded = encoder.encode(Arrays.asList(src));
    eval(encoded.instructions, encoded.constantPool, sourceLines, sourcePath, code);
}
```

**B. `readFileBytes(File)` / `readStreamBytes(InputStream)`** — 1.4 兼容的字节读取（替代 Java 9 `InputStream.readAllBytes()`）

```java
/** 读取文件全部字节（1.4 兼容，替代 Java 9 Files.readAllBytes）。 */
private static byte[] readFileBytes(File f) throws IOException {
    long len = f.length();
    int capacity = (int) Math.min(len, 8192);
    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(capacity);
    java.io.FileInputStream in = new java.io.FileInputStream(f);
    try {
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) { bos.write(buf, 0, n); }
    } finally {
        try { in.close(); } catch (Exception e) {}
    }
    return bos.toByteArray();
}

/** 读取流全部字节直到 EOF（1.4 兼容，替代 Java 9 InputStream.readAllBytes）。
 *  不关闭流（由调用方负责）。 */
private static byte[] readStreamBytes(InputStream in) throws IOException {
    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
    byte[] buf = new byte[4096];
    int n;
    while ((n = in.read(buf)) != -1) { bos.write(buf, 0, n); }
    return bos.toByteArray();
}
```

#### 3.2.4 Part 2 验证

- 编译通过（`-source 1.4`，无 1.5+ API）
- 手测：在 TestScript 新增 mode 或在 hostTest 中调用 `evalScriptFile("src/main/resources/timer_test.script")` 验证能编译执行
- 回归测试 179/179 不变（新方法是纯增量，不改变现有 eval/evalScript 行为）

---

### Part 3: GSInterpreter 调试模式支持

#### 3.3.1 新增 import

```java
import org.gscript.vm.debug.DebugAgent;
import org.gscript.vm.stdlib.Console;
```

#### 3.3.2 新增 2 个 public 调试方法

放在 `setDebugController`/`getDebugController` 附近（与调试 API 归为一组）。

**A. `debugLaunch(GSClassData data, int port)`** — launch 模式：阻塞等待 VSCode 连接后执行

```java
/**
 * launch 调试模式：阻塞等待 VSCode 连接后执行 gclass。
 *
 * <p>封装 {@link DebugAgent#waitForDebuggerAndRun()} 的标准模板：
 * <ol>
 *   <li>创建 DebugAgent 监听端口</li>
 *   <li>注册 gclass（含 sourceContent，供 VSCode source 请求）</li>
 *   <li>设置本解释器为 agent 的执行解释器（使用当前实例的 global env、console、timer 等设置）</li>
 *   <li>阻塞等待 VSCode 连接 + configurationDone</li>
 *   <li>VSCode 连接后由 DapServer 创建 controller 并共享，解释器在子线程执行</li>
 *   <li>阻塞直到 VSCode disconnect 或脚本执行完毕</li>
 * </ol>
 *
 * <p>调用前应完成：addVariableToGlobal("console", new Console())、installTimerGlobals()、
 * 其他自定义全局变量注入。本方法**不返回**直到调试会话结束。
 *
 * <p>线程模型：调用线程阻塞在 accept + DapServer.run；解释器在 DapServer 触发的
 * "gscript-interpreter" 守护线程中执行。controller 由 DapServer.handleLaunchAttach 创建
 * 并通过 agent.setController 共享，避免解释器拿到无 SuspendListener 的副本。
 *
 * @param data 待调试的 gclass 数据（须含 sourceContent）
 * @param port VSCode DAP 连接端口（如 4711）
 * @throws Exception 网络/调试协议异常
 */
public void debugLaunch(GSClassData data, int port) throws Exception {
    DebugAgent agent = new DebugAgent(port);
    agent.setInterpreter(this);  // 使用当前解释器实例（保留 caller 的 console/timer/globals 设置）
    agent.addGclass(data);
    agent.waitForDebuggerAndRun();
}
```

**B. `debugAttach(GSClassData data, int port)`** — attach 模式：立即执行，VSCode 可后附加

```java
/**
 * attach 调试模式：解释器立即开始执行，VSCode 可随时附加。
 *
 * <p>封装 {@link DebugAgent#startAttachListener()} 的标准模板：
 * <ol>
 *   <li>创建 DebugAgent 监听端口（后台 "gscript-debug-accept" 守护线程）</li>
 *   <li>设置本解释器为 agent 的执行解释器</li>
 *   <li>注册 gclass（含 sourceContent）</li>
 *   <li>startAttachListener 立即返回</li>
 *   <li>当前线程执行 eval + runEventLoop（解释器全速运行）</li>
 *   <li>VSCode 连接后由 DapServer 创建 controller，setController 立即设置到本解释器
 *       （volatile 字段），下次 suspendCheck 时按 pauseOnAttach 挂起</li>
 *   <li>runEventLoop 返回后调用 agent.stop() 清理</li>
 * </ol>
 *
 * <p>调用前应完成：addVariableToGlobal("console", new Console())、installTimerGlobals()。
 * 本方法**阻塞**直到脚本执行完毕（VSCode disconnect 仅分离调试器，不终止脚本——
 * 若需在 disconnect 时终止，调用方应自行检查 controller 状态）。
 *
 * <p>线程模型：调用线程执行解释器；"gscript-debug-accept" 守护线程 accept VSCode 连接。
 * 无死锁：pollReady 不持 TimerScheduler.lock，DebugAgent 线程不接触解释器字段。
 *
 * @param data 待调试的 gclass 数据（须含 sourceContent）
 * @param port VSCode DAP 连接端口（如 4711）
 * @throws Exception 网络/调试协议异常
 */
public void debugAttach(GSClassData data, int port) throws Exception {
    DebugAgent agent = new DebugAgent(port);
    agent.setInterpreter(this);
    agent.addGclass(data);
    agent.startAttachListener();  // 后台监听，立即返回
    try {
        eval(data.src, data.constantPool, data.sourceLines, data.sourcePath, data.sourceContent);
        runEventLoop();
    } finally {
        agent.stop();
    }
}
```

#### 3.3.3 可调试性保障

1. **异常传播**：`debugLaunch` 让 `waitForDebuggerAndRun` 的异常自然传播（包括 `DebugAbortException` 在子线程被捕获后通过 DapServer 通知 terminated）；`debugAttach` 用 try-finally 确保 `agent.stop()` 即使脚本异常也能清理。
2. **controller 共享**：两个方法都**不自建 controller**——由 DapServer.handleLaunchAttach 创建并通过 `agent.setController` 共享（与现有架构一致，避免断点/单步事件无法通知 DAP 线程的"continue 死锁"问题）。
3. **日志**：DebugAgent 已有 `[DebugAgent] ...` stderr 日志，本方法不额外加日志避免冗余。
4. **集成示例**：TestScript.debugAgent（行 304-334）现有 30 行模板代码可在文档注释中作为"等价于"示例，便于宿主理解封装语义。

#### 3.3.4 Part 3 验证

- 编译通过
- 手测 launch：`java -cp target/classes org.gscript.TestScript timer_test debugagent`（VSCode 连接 4711 端口调试）
- 手测 attach：`java -cp target/classes org.gscript.TestScript timer_test debugagent-attach`（解释器先运行，VSCode 附加后挂起）
- 回归测试 179/179（特别是 test_dap_e2e 17 项、test_timer_debug 22 项、test_multi_file 19 项须全 PASS）

---

## 4. 假设与决策

### 4.1 关键决策

| 决策点 | 选择 | 理由 |
|--------|------|------|
| 合并后类名 | `TestScript`（保留） | Python 测试依赖 `org.gscript.TestScript` 类名，改名会破坏 10 个测试套件 |
| `toURI()` 替代 | `URLDecoder.decode(url.getFile(), "UTF-8")` | Java 1.4 API，与项目 `-source 1.4` 兼容；URLDecoder.decode(String,String) 自 1.4 存在 |
| `Test.list2File` 归属 | 迁移为 `TestScript.list2File`（private static） | 仅 TestScript.gen 行 90 使用，无需 public |
| `Test.main` 批量编译 | 迁移为 `TestScript.batchCompile()` + `batch` mode | 保留原有批量编译能力，统一入口 |
| `evalScriptFile` 的 sourcePath | `f.getName()`（文件名，非绝对路径） | 与 TestScript.compileGclass 行 150 的 `sourcePath = name + ".script"` 约定一致 |
| `evalScriptStream` 是否关闭流 | **不关闭**（调用方负责） | 与 GSClassReader.deserialize 行为一致，允许调用方复用流 |
| `debugLaunch`/`debugAttach` 是否实例方法 | 实例方法（非 static） | 使用 `this` 解释器，调用方可预先配置 console/timer/globals；DebugAgent 也支持 setInterpreter |
| `debugLaunch` 是否调用 `runEventLoop` | **不调用**（由 DebugAgent.onConfigurationDone 子线程调用） | waitForDebugger 模式解释器在子线程运行，本方法阻塞在 DapServer.run |
| `debugAttach` 是否调用 `runEventLoop` | **调用** | attachReady 模式解释器在当前线程运行，须由本方法驱动事件循环 |
| `evalScriptContent` 可见性 | private | 内部共享实现，外部用 evalScriptFile/evalScriptStream |
| 循环 import（GSInterpreter ↔ DebugAgent） | 接受 | Java 允许；二者本就紧耦合（DebugAgent 已 import GSInterpreter）；GSInterpreter 已 import 同包的 DebugController |

### 4.2 不做的事（明确范围）

- **不改变现有 eval/evalScript/evalExpression 行为**：新方法是纯增量，避免破坏 179 项回归测试
- **不改变 gclass 字节码格式**：仅消费 GSClassData，不修改 GSClassWriter/Reader
- **不改变 DebugAgent/DapServer/DebugController 架构**：仅封装调用模板
- **不删除 TestScript.debugAgent 方法**：保留作为 debugLaunch/debugAttach 的等价示例和测试入口
- **不为新方法单独写 Python 测试**：通过现有 179 项回归 + 手测验证；若用户需要可后续补充

### 4.3 风险与缓解

| 风险 | 缓解 |
|------|------|
| `toURI()` 替换后路径格式变化导致 .gtxt 写到错误位置 | `URLDecoder.decode(url.getFile())` 与 `url.toURI()` 在文件系统 URL 下结果一致；手测 batch mode 验证 |
| 循环 import 导致编译警告 | Java 不报错；若 IDE 警告可忽略（架构上 DebugAgent 是 GSInterpreter 的调试适配器，紧耦合合理） |
| `debugAttach` 中 eval 抛 DebugAbortException 跳过 agent.stop | try-finally 保证 agent.stop 必执行（DebugAbortException 是 RuntimeException 子类，finally 会执行） |
| `readFileBytes` 在大文件下 OOM | gscript 脚本通常 <100KB，ByteArrayOutputStream 容量初始 8KB 已够；不预先优化 |
| `evalScriptContent` 与 `evalScript` 行为不一致（前者有源码映射后者无） | 这是设计意图（文件加载支持调试，evalScript 是 REPL 风格），文档已明确区分 |

## 5. 实施步骤（执行顺序）

1. **修改 TestScript.java**（串行 Edit，一次一个，避免并行 Edit 丢失）：
   - 新增 `resourceUrlToFile` private static 方法
   - 替换行 88 `toURI()` → `resourceUrlToFile(rootUrl)`
   - 替换行 151 `toURI()` → `resourceUrlToFile(rootUrl)`
   - 替换 7 处 `Test.class` → `TestScript.class`（行 70、72、73、90、123、124、219）
   - 替换行 90 `Test.list2File` → `list2File`
   - 新增 `list2File` private static 方法
   - main 新增 `batch` mode 分支
   - 新增 `batchCompile` public static 方法
   - 更新 usage 提示字符串

2. **删除 Test.java**

3. **修改 GSInterpreter.java**（串行 Edit）：
   - 新增 import（GSClassData/GSClassReader/DebugAgent/Console/InputStream/IOException/File/FileInputStream）
   - 新增 `evalScriptFile`、`evalScriptStream`、`evalGclassFile`、`evalGclassStream` 4 个 public 方法
   - 新增 `evalScriptContent`、`readFileBytes`、`readStreamBytes` 3 个 private 方法
   - 新增 `debugLaunch`、`debugAttach` 2 个 public 方法

4. **编译验证**：`$env:JAVA_HOME="C:\Program Files\Java\jdk1.8.0_202"; mvn clean package -DskipTests`
   - 期望 0 错误 0 警告
   - class 文件 major version=48

5. **回归测试**：`python tests/compare_baseline.py`
   - 期望 179 PASS / 0 FAIL
   - 期望 10/10 套件 MATCH baseline

6. **手测新功能**：
   - `java -cp target/classes org.gscript.TestScript timer_test batch`（验证 batch mode 编译所有 .script）
   - `java -cp target/classes org.gscript.TestScript timer_test debugagent`（验证 debugLaunch 等价路径）
   - `java -cp target/classes org.gscript.TestScript timer_test debugagent-attach`（验证 debugAttach 等价路径）

7. **更新项目记忆**（`project_memory.md`）：
   - 新增"测试类合并"章节（Test.java 已删除，TestScript 统一入口，batch mode）
   - 新增"GSInterpreter 文件加载 API"章节（4 个新方法）
   - 新增"GSInterpreter 调试模式 API"章节（debugLaunch/debugAttach 封装 DebugAgent）
   - 新增"`URL.toURI()` → `URLDecoder.decode` 1.4 兼容"教训

## 6. 验证清单

- [ ] JDK 1.8 + `-source 1.4 -target 1.4` 编译通过，0 错误
- [ ] `target/gscript-1.0-SNAPSHOT.jar` 生成，main class=org.gscript.vm.debug.dap.DapCLIMain
- [ ] class 文件 major version=48（真 1.4 字节码）
- [ ] `python tests/compare_baseline.py` 输出 179 PASS / 0 FAIL，10/10 MATCH
- [ ] `java -cp target/classes org.gscript.TestScript timer_test batch` 能遍历编译所有 .script 生成 .gtxt
- [ ] `java -cp target/classes org.gscript.TestScript timer_test debugagent` 可被 VSCode 连接调试（launch 模式）
- [ ] `java -cp target/classes org.gscript.TestScript timer_test debugagent-attach` 解释器先运行，VSCode 附加后挂起（attach 模式）
- [ ] Test.java 已删除
- [ ] 项目记忆文件已更新
