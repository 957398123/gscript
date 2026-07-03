# 方案 B：gclass 携带源码内容 + 真正的 Attach 调试模式

## 摘要

实现 gscript 调试器的 attach 调试模式，支持两种启用方式：

1. **debug 模式启用**（waitForDebugger）：宿主程序启动时即阻塞等待 VSCode 连接，连接后才开始执行脚本
2. **运行时 attach**（attachReady）：宿主程序已正常运行，VSCode 随后连接附加调试

核心机制：gclass 文件通过新的 `SourceContent` 属性携带完整源码文本，attach 模式下 VSCode 通过 DAP `source` 请求从服务端获取源码，无需本地源文件。launch 模式保持现有行为（读本地文件）。

## 当前状态分析

### 现有 attach 是"伪 attach"

* [DapServer.java:414](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/debug/dap/DapServer.java#L414) `handleLaunchAttach`：launch 与 attach 走**同一代码路径**，都是 DapServer 自己创建解释器、编译本地脚本文件

* [DapServer.java:845](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/debug/dap/DapServer.java#L845) `startInterpreterThread`：attach 模式下仍由 DapServer `new GSInterpreter()` 并执行本地 `scriptPaths`

* [DapServer.java:739](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/debug/dap/DapServer.java#L739) `handleSource`：直接返回错误 "source request not supported"，VSCode 只能从磁盘读源码

### gclass 不含源码内容

* [GSClassWriter.java:42](file:///e:/JProjects/gscript/src/main/java/org/gscript/compile/gclass/GSClassWriter.java#L42) `write()`：参数只有 bytecode/sourceLines/sourcePath，无源码内容；Attributes 段仅写 FunctionTable

* [GSClassReader.java:186](file:///e:/JProjects/gscript/src/main/java/org/gscript/compile/gclass/GSClassReader.java#L186)：仅解析 FunctionTable 属性，未知属性跳过（前向兼容，可安全添加 SourceContent）

* [GSClassData.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/compile/gclass/GSClassData.java)：无 sourceContent 字段

### debugController 非 volatile

* [GSInterpreter.java:44](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/GSInterpreter.java#L44) `private DebugController debugController;` —— 非 volatile，attachReady 模式下跨线程设置可能不可见

### DapCLIMain 仅接受单次连接

* [DapCLIMain.java:85](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/debug/dap/DapCLIMain.java#L85) `runSocket`：accept 一次后结束，不支持 re-attach

## 实现步骤

### 步骤 1：gclass SourceContent 属性

**目标**：gclass 文件携带完整源码文本，供 attach 模式通过 source 请求返回。

#### 1.1 [GSClassConstants.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/compile/gclass/GSClassConstants.java)

* 在属性名常量区（第 264 行附近）新增：

```java
/** 源码内容属性名：存储完整源码文本（UTF-8），供 attach 调试模式通过 source 请求返回 */
public static final String ATTR_SOURCE_CONTENT = "SourceContent";
```

* **不需要新 flag**：SourceContent 是属性段中的一个属性，`FLAG_HAS_ATTRIBUTES`（bit3）已覆盖其存在性。Reader 解析属性段时按属性名分发。

#### 1.2 [GSClassData.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/compile/gclass/GSClassData.java)

* 新增字段 `public final String sourceContent;`（可为 null）

* 现有 4 参数构造器委托 5 参数构造器（sourcePath 版本）传 null sourceContent —— 但现有已有 4→5 委托链（functions），需改为 5→6 或新增 6 参数构造器

* **方案**：新增 6 参数构造器 `GSClassData(src, cp, sourceLines, sourcePath, functions, sourceContent)`，现有 5 参数构造器委托 6 参数传 `null` sourceContent，4 参数委托 5 参数传 `null` functions（保持现有链）

#### 1.3 [GSClassWriter.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/compile/gclass/GSClassWriter.java)

* `write()` 方法签名新增 `String sourceContent` 参数（放在 sourcePath 之后、out 之前）：

```java
public void write(List<String> bytecode, List<Integer> sourceLines, 
                  String sourcePath, String sourceContent, OutputStream out) throws IOException
```

* 保留旧 4 参数重载，委托新签名传 `null` sourceContent（向后兼容）

* 写入逻辑改动（在 FunctionTable 属性写入之后）：

  * 若 `sourceContent != null && !sourceContent.isEmpty()`：将 `"SourceContent"` 属性名追加到 CP，记录 attrNameCpIndex

  * `hasAttributes` 条件改为 `!funcEntries.isEmpty() || sourceContent != null`

  * 属性段 `attrCount` 改为动态计算（`funcEntries.isEmpty() ? 0 : 1` + `sourceContent != null ? 1 : 0`），不再硬编码 `dos.writeShort(1)`

  * 写完 FunctionTable 后，若 sourceContent 非空，写 SourceContent 属性：

    ```
    dos.writeShort(sourceContentAttrNameCp);
    byte[] srcBytes = sourceContent.getBytes(UTF_8);
    dos.writeInt(srcBytes.length);   // u4 长度，无 65535 限制
    dos.write(srcBytes);
    ```

  * CRC32 自动覆盖新属性（已覆盖 \[20-end]）

#### 1.4 [GSClassReader.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/compile/gclass/GSClassReader.java)

* 属性解析循环（第 180-191 行）新增分支：

```java
if (GSClassConstants.ATTR_FUNCTION_TABLE.equals(attrName)) {
    functions = parseFunctionTable(attrData, cp);
} else if (GSClassConstants.ATTR_SOURCE_CONTENT.equals(attrName)) {
    sourceContent = new String(attrData, StandardCharsets.UTF_8);
}
```

* 在方法开头声明 `String sourceContent = null;`

* 返回语句改为 `new GSClassData(src, cp, sourceLines, sourcePath, functions, sourceContent)`

### 步骤 2：GSFunction / GSInterpreter 源码内容传递 + volatile

#### 2.1 [GSFunction.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/value/GSFunction.java)

* 新增字段（在 sourcePath 字段之后，第 59 行后）：

```java
/**
 * 函数所属源文件的完整源码内容（attach 调试模式用，供 DAP source 请求返回）。
 * 顶级匿名函数在 eval 时设置；子函数通过 fundef 继承。非 attach 调试模式为 null。
 */
public String sourceContent = null;
```

#### 2.2 [GSInterpreter.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/GSInterpreter.java)

* **第 44 行**：`private DebugController debugController;` → `private volatile DebugController debugController;`

  * 原因：attachReady 模式下，DAP 线程设置 controller，解释器线程读取，必须 volatile 保证可见性

* **OP\_FUNDEF 处理（第 399-401 行附近）**：新增 `function.sourceContent = frame.function.sourceContent;`（与 sourceLines/sourcePath 一起继承）

* **eval(byte\[]\[], ...) 方法（第 630 行）**：新增重载，接收 sourceContent：

```java
public void eval(byte[][] codes, Object[] constantPool, int[] sourceLines, 
                 String sourcePath, String sourceContent) {
    GSFunction anonymous = new GSFunction("null", codes, constantPool, global);
    anonymous.sourceLines = sourceLines;
    anonymous.sourcePath = sourcePath;
    anonymous.sourceContent = sourceContent;
    anonymous.baseOffset = 0;
    GSFrame frame = new GSFrame(anonymous);
    try { eval(frame, null); } catch (...) { ... }
}
```

* 现有 4 参数 `eval(byte[][], Object[], int[], String)` 保留，委托新 5 参数传 `null` sourceContent（向后兼容）

### 步骤 3：DebugAgent 类（新增）

**目标**：为嵌入式调试提供统一入口，支持两种启用模式。

**新文件**：`e:\JProjects\gscript\src\main\java\org\gscript\vm\debug\DebugAgent.java`

```java
package org.gscript.vm.debug;

import org.gscript.compile.gclass.GSClassData;
import org.gscript.vm.GSInterpreter;
import org.gscript.vm.debug.dap.DapServer;
import org.gscript.vm.value.GSValue;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 嵌入式调试代理：供 Java 宿主程序接入 VSCode 调试。
 *
 * 两种模式：
 * 1. waitForDebuggerAndRun：宿主启动时调用，阻塞等待 VSCode 连接 + configurationDone，
 *    然后在子线程启动解释器执行已注册的 gclass。适用于"debug 模式启用"。
 * 2. startAttachListener：宿主程序运行中调用，后台线程监听 VSCode 连接，
 *    连接后创建 controller 并设置到已运行的解释器（volatile 字段），解释器在下次
 *    suspendCheck 时挂起。适用于"运行时 attach"。
 */
public class DebugAgent {
    private GSInterpreter interpreter;
    private final int port;
    private final String host;
    private DebugController controller;
    private DapServer dapServer;
    private final List<GSClassData> gclassDataList = new ArrayList<>();
    private ServerSocket serverSocket;
    private Thread acceptThread;
    /** 已加载 gclass 的源码内容映射：sourcePath → sourceContent（供 DapServer source 请求用） */
    private final Map<String, String> sourceContents = new LinkedHashMap<>();
    /** 模式标志：true=attachReady（解释器已运行），false=waitForDebugger（解释器未启动） */
    private boolean interpreterAlreadyRunning = false;
    /** attachReady 模式下连接后是否自动暂停 */
    private boolean pauseOnAttach = true;

    public DebugAgent(int port) { this(port, "localhost"); }
    public DebugAgent(int port, String host) { this.port = port; this.host = host; }

    /** 设置已运行的解释器（attachReady 模式必需，waitForDebugger 模式可选——为 null 则 agent 自建） */
    public void setInterpreter(GSInterpreter interpreter) { this.interpreter = interpreter; }
    /** 注册待执行的 gclass（waitForDebugger 模式用，按顺序执行） */
    public void addGclass(GSClassData data) {
        gclassDataList.add(data);
        if (data.sourcePath != null && data.sourceContent != null) {
            sourceContents.put(data.sourcePath, data.sourceContent);
        }
    }
    /** 设置 attachReady 模式下连接后是否自动暂停（默认 true） */
    public void setPauseOnAttach(boolean pause) { this.pauseOnAttach = pause; }
    /** 获取源码内容映射（DapServer 读取） */
    public Map<String, String> getSourceContents() { return sourceContents; }
    public DebugController getController() { return controller; }

    /**
     * 模式 1：阻塞等待 VSCode 连接，连接后启动解释器执行已注册 gclass。
     * 当前线程阻塞直到 VSCode 断开连接或脚本执行完毕。
     */
    public void waitForDebuggerAndRun() throws Exception {
        interpreterAlreadyRunning = false;
        serverSocket = new ServerSocket(port);
        System.err.println("[DebugAgent] waitForDebugger 模式，监听端口 " + port + "，等待 VSCode 连接...");
        try (Socket socket = serverSocket.accept()) {
            System.err.println("[DebugAgent] VSCode 已连接");
            controller = new DebugController();
            dapServer = new DapServer(socket.getInputStream(),
                    new PrintStream(socket.getOutputStream(), true), this);
            dapServer.run();  // 阻塞直到 disconnect
        } finally {
            if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close();
        }
    }

    /**
     * 模式 2：后台线程监听 VSCode 连接，连接后创建 controller 设置到已运行的解释器。
     * 立即返回，不阻塞宿主线程。解释器在下次 suspendCheck 时挂起（若 pauseOnAttach）。
     */
    public void startAttachListener() {
        if (interpreter == null) {
            throw new IllegalStateException("attachReady 模式必须先 setInterpreter");
        }
        interpreterAlreadyRunning = true;
        acceptThread = new Thread(() -> {
            try {
                serverSocket = new ServerSocket(port);
                System.err.println("[DebugAgent] attachReady 模式，监听端口 " + port + "，等待 VSCode 附加...");
                try (Socket socket = serverSocket.accept()) {
                    System.err.println("[DebugAgent] VSCode 已附加");
                    controller = new DebugController();
                    if (pauseOnAttach) {
                        controller.pause();  // 请求在下次 suspendCheck 暂停
                    }
                    interpreter.setDebugController(controller);  // volatile 字段，解释器线程立即可见
                    dapServer = new DapServer(socket.getInputStream(),
                            new PrintStream(socket.getOutputStream(), true), this);
                    dapServer.run();  // 阻塞直到 disconnect
                }
            } catch (Exception e) {
                System.err.println("[DebugAgent] accept 异常: " + e);
            }
        }, "gscript-debug-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /** 停止代理：关闭 server socket，终止 controller */
    public void stop() {
        if (controller != null) controller.terminate();
        try { if (serverSocket != null && !serverSocket.isClosed()) serverSocket.close(); }
        catch (Exception e) {}
    }

    /**
     * DapServer 在 configurationDone 时回调。
     * - waitForDebugger 模式：启动解释器线程执行 gclass
     * - attachReady 模式：解释器已运行，无需启动（controller 已在 startAttachListener 设置）
     */
    void onConfigurationDone() {
        if (!interpreterAlreadyRunning) {
            if (interpreter == null) {
                interpreter = new GSInterpreter();
                interpreter.addVariableToGlobal("console",
                    new org.gscript.vm.stdlib.Console());
            }
            interpreter.setDebugController(controller);
            final GSInterpreter interp = interpreter;
            final List<GSClassData> datas = new ArrayList<>(gclassDataList);
            Thread t = new Thread(() -> {
                try {
                    for (GSClassData data : datas) {
                        interp.eval(data.src, data.constantPool, data.sourceLines,
                                data.sourcePath, data.sourceContent);
                    }
                } catch (org.gscript.vm.debug.DebugAbortException e) {
                    // 调试会话终止
                } catch (Throwable e) {
                    e.printStackTrace();
                }
                // 解释器结束，通知 DapServer 发送 terminated 事件
                if (dapServer != null) dapServer.notifyInterpreterTerminated();
            }, "gscript-interpreter");
            t.setDaemon(true);
            t.start();
        }
        // attachReady 模式：无需启动解释器，它已在运行
    }

    GSInterpreter getInterpreter() { return interpreter; }
    boolean isInterpreterAlreadyRunning() { return interpreterAlreadyRunning; }
}
```

### 步骤 4：DapServer 支持 attach 模式

**文件**：[DapServer.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/vm/debug/dap/DapServer.java)

#### 4.1 新增字段

```java
/** 代理（attach 模式非 null，launch 模式 null） */
private DebugAgent agent = null;
/** attach 模式路径映射：本地根目录 */
private String localRoot = null;
/** attach 模式路径映射：远程根目录（gclass sourcePath 的前缀） */
private String remoteRoot = null;
/** 源码引用映射：sourceReference → sourcePath */
private final Map<Integer, String> sourceRefs = new HashMap<>();
private int nextSourceRef = 1;
```

#### 4.2 新增构造器

```java
public DapServer(InputStream in, PrintStream rawOut, DebugAgent agent) {
    this.in = in;
    this.rawOut = rawOut;
    this.agent = agent;
    initDapLog();
}
```

#### 4.3 handleLaunchAttach 分流

在方法开头新增 attach 模式分支：

```java
private void handleLaunchAttach(int requestSeq, JsonObject args, String command) {
    // attach 模式（带 agent）：不编译本地脚本，仅记录路径映射 + 创建 controller
    if (agent != null && "attach".equals(command)) {
        localRoot = args.has("localRoot") ? args.get("localRoot").getAsString() : null;
        remoteRoot = args.has("remoteRoot") ? args.get("remoteRoot").getAsString() : null;
        // 规范化 localRoot（canonicalize 失败保留原值）
        if (localRoot != null) localRoot = canonicalize(localRoot);
        controller = new DebugController();
        controller.setSuspendListener(this);
        if (args.has("stopOnEntry") && args.get("stopOnEntry").getAsBoolean()) {
            controller.setStopOnEntry(true);
        }
        log("[FLOW] attach(agent): localRoot=" + localRoot + " remoteRoot=" + remoteRoot);
        sendResponse(requestSeq, command, new JsonObject());
        return;
    }
    // 现有 launch/attach 逻辑保持不变
    ...
}
```

#### 4.4 handleConfigurationDone 分流

```java
private void handleConfigurationDone(int requestSeq, JsonObject args) {
    if (started) { sendResponse(...); return; }
    started = true;
    // attach 模式（带 agent）：委托 agent 处理
    if (agent != null) {
        agent.onConfigurationDone();
        sendResponse(requestSeq, "configurationDone", new JsonObject());
        return;
    }
    // 现有 launch 逻辑（编译 + startInterpreterThread）
    ...
}
```

#### 4.5 handleSetBreakpoints 路径映射

在 `path = canonicalize(...)` 之后，attach 模式下映射为远程路径：

```java
if (path != null) {
    path = canonicalize(args...get("path"));
    if (agent != null && localRoot != null && remoteRoot != null) {
        path = mapToRemotePath(path);
    }
}
```

新增辅助方法：

```java
/** 本地路径 → 远程 sourcePath（attach 模式断点匹配） */
private String mapToRemotePath(String localPath) {
    if (localRoot == null) return localPath;
    String normLocal = localPath.replace('\\', '/');
    String normRoot = localRoot.replace('\\', '/');
    if (normLocal.startsWith(normRoot)) {
        String rel = normLocal.substring(normRoot.length());
        // 去掉前导斜杠
        while (rel.startsWith("/")) rel = rel.substring(1);
        return remoteRoot != null ? remoteRoot + rel : rel;
    }
    return localPath;
}
```

#### 4.6 handleStackTrace 设置 source.reference

attach 模式下，若该 sourcePath 有源码内容，设置 sourceReference：

```java
String framePath = frame.function.sourcePath;
String sourceContent = (agent != null) 
    ? agent.getSourceContents().get(framePath) : null;
JsonObject source = new JsonObject();
source.addProperty("name", frameName);
if (sourceContent != null) {
    // attach 模式：VSCode 通过 source 请求获取源码
    int ref = nextSourceRef++;
    sourceRefs.put(ref, framePath);
    source.addProperty("reference", ref);
    source.addProperty("path", framePath != null ? framePath : "");
} else {
    // launch 模式：VSCode 从磁盘读
    source.addProperty("path", framePath != null ? framePath : "");
}
```

#### 4.7 实现 handleSource

```java
private void handleSource(int requestSeq, JsonObject args) {
    int ref = args.has("sourceReference") ? args.get("sourceReference").getAsInt() : 0;
    String path = sourceRefs.get(ref);
    if (path != null && agent != null) {
        String content = agent.getSourceContents().get(path);
        if (content != null) {
            JsonObject body = new JsonObject();
            body.addProperty("content", content);
            body.addProperty("mimeType", "text/x-gscript");
            sendResponse(requestSeq, "source", body);
            return;
        }
    }
    sendErrorResponse(requestSeq, "source", "source not available");
}
```

#### 4.8 新增 notifyInterpreterTerminated

供 DebugAgent 回调，发送 terminated 事件：

```java
public void notifyInterpreterTerminated() {
    log("[FLOW] 解释器线程结束，发送 terminated 事件");
    sendEvent("terminated", new JsonObject());
}
```

#### 4.9 handleDisconnect 分流

attach 模式下 disconnect 不终止解释器（仅断开调试连接）：

```java
private void handleDisconnect(int requestSeq, JsonObject args) {
    if (agent != null) {
        // attach 模式：分离调试器，不终止解释器
        // 移除 controller（设为 null），解释器继续全速运行
        if (agent.getInterpreter() != null) {
            agent.getInterpreter().setDebugController(null);
        }
        log("[FLOW] disconnect(attach): 分离调试器，解释器继续运行");
        sendResponse(requestSeq, "disconnect", new JsonObject());
        return;
    }
    // 现有逻辑（terminate）
    ...
}
```

**注意**：需在 GSInterpreter 新增 `public void clearDebugController()` 或直接把 setDebugController 改为可传 null（现有 setDebugController 已支持传 null）。但 debugController 是 private，需确认 setter 暴露。当前 `setDebugController(DebugController)` 已是 public，传 null 即可。但需加一个方法让 DAP 线程能清除：实际上现有 `setDebugController` 就行，传 null 即清除。

### 步骤 5：VSCode 扩展配置

**文件**：[vscode-extension/package.json](file:///e:/JProjects/gscript/vscode-extension/package.json)

attach 配置新增 localRoot/remoteRoot/stopOnEntry 字段：

```json
"attach": {
    "required": ["port"],
    "properties": {
        "port": { "type": "number", "description": "..." },
        "host": { "type": "string", "default": "localhost" },
        "localRoot": {
            "type": "string",
            "description": "本地源码根目录（VSCode 端），用于映射断点路径到远程 sourcePath"
        },
        "remoteRoot": {
            "type": "string",
            "description": "远程源码根目录（gclass sourcePath 的前缀），默认空串表示 sourcePath 为相对路径",
            "default": ""
        },
        "stopOnEntry": {
            "type": "boolean",
            "default": false,
            "description": "连接后是否在当前位置暂停"
        }
    }
}
```

initialConfigurations 的 attach 项更新为两种：

```json
{
    "type": "gscript", "request": "attach", "name": "Attach to gscript (debug mode)",
    "host": "localhost", "port": 4711,
    "localRoot": "${workspaceFolder}/src/main/resources",
    "remoteRoot": ""
},
{
    "type": "gscript", "request": "attach", "name": "Attach to gscript (runtime)",
    "host": "localhost", "port": 4711,
    "localRoot": "${workspaceFolder}/src/main/resources",
    "remoteRoot": ""
}
```

### 步骤 6：TestScript debugagent 模式 + 测试脚本

#### 6.1 [TestScript.java](file:///e:/JProjects/gscript/src/main/java/org/gscript/TestScript.java)

* `compileGclass` 方法（第 111 行）：读取的 `content` 传给 writer：

```java
writer.write(bytecode, sourceLines, sourcePath, content, out);  // 新增 content 参数
```

* `runGclass` 方法（第 151 行）：用新 5 参数 eval：

```java
interpreter.eval(data.src, data.constantPool, data.sourceLines, data.sourcePath, data.sourceContent);
```

* 新增 `debugagent` 与 `debugagent-attach` 模式分支：

```java
} else if ("debugagent".equals(mode)) {
    TestScript.debugAgent(name, false);  // waitForDebugger 模式
} else if ("debugagent-attach".equals(mode)) {
    TestScript.debugAgent(name, true);   // attachReady 模式
}
```

* 新增方法：

```java
/**
 * DebugAgent 演示：加载 gclass + 启动调试代理。
 * @param name 脚本名（不含扩展名）
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
        agent.setInterpreter(interpreter);
        agent.addGclass(data);
        agent.startAttachListener();  // 后台监听，立即返回
        System.err.println("[测试] 解释器开始运行，VSCode 可随时附加（端口 " + port + "）");
        // 主线程运行解释器（会因 debugController 设置而挂起）
        interpreter.eval(data.src, data.constantPool, data.sourceLines,
                data.sourcePath, data.sourceContent);
        System.err.println("[测试] 解释器运行结束");
        agent.stop();
    } else {
        // 模式 1：等待 VSCode 连接后运行
        agent.addGclass(data);
        agent.waitForDebuggerAndRun();  // 阻塞直到调试会话结束
    }
}
```

* usage 提示更新：`Usage: TestScript <name> [run|dump|compile|rungclass|dumpgclass|hosttest|debugagent|debugagent-attach]`

#### 6.2 新增测试脚本

**新文件**：`e:\JProjects\gscript\src\main\resources\debug_attach_test.script`

```gscript
// gscript attach 调试测试脚本
// 用法：
//   1. mvn package
//   2. java -cp target/gscript-1.0-SNAPSHOT.jar org.gscript.TestScript debug_attach_test compile
//   3. java -cp target/gscript-1.0-SNAPSHOT.jar org.gscript.TestScript debug_attach_test debugagent
//   4. VSCode attach（端口 4711）

var counter = 0;

function calculateFactorial(n) {
    if (n <= 1) {
        return 1;
    }
    return n * calculateFactorial(n - 1);
}

function processItems(items) {
    var total = 0;
    var i = 0;
    while (i < items.length) {
        total = total + items[i];
        i = i + 1;
    }
    return total;
}

console.log("=== attach 调试测试开始 ===");

var fact5 = calculateFactorial(5);
console.log("5! = " + fact5);

var numbers = [10, 20, 30, 40, 50];
var sum = processItems(numbers);
console.log("sum = " + sum);

counter = fact5 + sum;
console.log("counter = " + counter);

console.log("=== attach 调试测试结束 ===");
```

### 步骤 7：编译 + 回归验证

1. `mvn clean package -DskipTests`（Java 9 + Maven 3.9.16）
2. 编译 debug\_attach\_test.gclass：`java -cp target/gscript-1.0-SNAPSHOT.jar org.gscript.TestScript debug_attach_test compile`
3. 验证 gclass 含 sourceContent：`java -cp ... org.gscript.TestScript debug_attach_test dumpgclass`（输出应含源码）
4. 运行现有回归测试（确保无回归）：

   * `python tests/test_gclass.py`

   * `python tests/test_host_interaction.py`

   * `python tests/test_dap_e2e.py`

   * `python tests/test_multi_file.py`

   * `python tests/test_while_breakpoint.py`

   * `python tests/test_step_catch.py`

   * `python tests/test_cross_file_step.py`

   * `python tests/test_path_mismatch.py`
5. **关键回归点**：GSClassWriter.write 签名变化、GSClassData 构造器变化、eval 重载——确保现有 139 测试全通过

## 假设与决策

1. **SourceContent 存属性段而非常量池**：CP UTF8 用 `writeShort` 长度前缀，限 65535 字节；属性段用 `writeInt`（u4），可存大源码。决策正确。
2. **不加 FLAG\_HAS\_SOURCE\_CONTENT**：HAS\_ATTRIBUTES 已表明属性段存在，Reader 解析属性段时按名分发即可，无需独立 flag。减少 flag 位占用。
3. **debugController 改 volatile 而非 AtomicReference**：仅需可见性保证，无需原子操作（setDebugController 调用频次低）。volatile 足够且语义清晰。
4. **attach 模式 disconnect 不终止解释器**：attach 语义是"分离调试器"，程序应继续运行。launch 模式 disconnect 仍终止（现有行为）。
5. **路径映射用字符串前缀替换**：简单直接，覆盖"本地目录结构 = 远程目录结构"的场景。规范化 localRoot（canonicalize）避免斜杠差异。
6. **sourceReference 仅 attach 模式设置**：launch 模式 VSCode 从磁盘读源码（path-based），attach 模式通过 source 请求（reference-based）。清晰分离。
7. **保留现有"伪 attach"**：DapCLIMain 的 socket 模式（无 agent）仍可用，编译本地脚本。新 true-attach（带 agent）是独立路径，不破坏旧功能。
8. **DebugAgent.stop() 不强制终止解释器**：仅 terminate controller + 关 serverSocket。解释器线程自然结束。

## 验证步骤

### 回归验证

```powershell
cd e:\JProjects\gscript
$env:JAVA_TOOL_OPTIONS="-Dfile.encoding=UTF-8"
mvn clean package -DskipTests
# 逐个运行 Python 测试（确保 139 测试全通过）
python tests/test_gclass.py
python tests/test_host_interaction.py
# ... 其余测试
```

### attach 模式手动验证（VSCode）

**模式 1（waitForDebugger）**：

1. 编译：`java -cp target/gscript-1.0-SNAPSHOT.jar org.gscript.TestScript debug_attach_test compile`
2. 启动 agent：`java -cp target/gscript-1.0-SNAPSHOT.jar org.gscript.TestScript debug_attach_test debugagent`

   * 控制台输出 `[DebugAgent] waitForDebugger 模式，监听端口 4711，等待 VSCode 连接...`
3. VSCode 打开 `src/main/resources/debug_attach_test.script`，设置断点（如 `var fact5 = ...` 行）
4. launch.json 选择 "Attach to gscript (debug mode)"，按 F5
5. 验证：断点命中、变量查看、单步、调用栈、源码显示（来自 gclass 非磁盘）

**模式 2（attachReady）**：

1. 启动：`java -cp target/gscript-1.0-SNAPSHOT.jar org.gscript.TestScript debug_attach_test debugagent-attach`

   * 控制台输出 `[测试] 解释器开始运行，VSCode 可随时附加（端口 4711）`

   * 解释器开始执行（若脚本短可能快速结束，可加 sleep 或在脚本中加循环）
2. VSCode attach（端口 4711）
3. 验证：连接后解释器暂停（pauseOnAttach），可设置断点、继续执行

### launch.json 配置

```json
{
    "version": "0.2.0",
    "configurations": [
        {
            "type": "gscript",
            "request": "attach",
            "name": "Attach to gscript (debug mode)",
            "host": "localhost",
            "port": 4711,
            "localRoot": "${workspaceFolder}/src/main/resources",
            "remoteRoot": "",
            "stopOnEntry": false
        },
        {
            "type": "gscript",
            "request": "attach",
            "name": "Attach to gscript (runtime)",
            "host": "localhost",
            "port": 4711,
            "localRoot": "${workspaceFolder}/src/main/resources",
            "remoteRoot": ""
        }
    ]
}
```

## 文件改动清单

| 文件                                   | 改动类型        | 说明                                                           |
| ------------------------------------ | ----------- | ------------------------------------------------------------ |
| `gclass/GSClassConstants.java`       | 新增常量        | `ATTR_SOURCE_CONTENT = "SourceContent"`                      |
| `gclass/GSClassData.java`            | 新增字段+构造器    | `sourceContent` 字段，6 参数构造器                                   |
| `gclass/GSClassWriter.java`          | 签名变化+逻辑     | `write()` 新增 sourceContent 参数，写 SourceContent 属性             |
| `gclass/GSClassReader.java`          | 新增解析分支      | 解析 SourceContent 属性                                          |
| `vm/value/GSFunction.java`           | 新增字段        | `sourceContent`，fundef 继承                                    |
| `vm/GSInterpreter.java`              | volatile+重载 | debugController volatile，eval 5 参数重载，fundef 继承 sourceContent |
| `vm/debug/DebugAgent.java`           | **新文件**     | 嵌入式调试代理，两种模式                                                 |
| `vm/debug/dap/DapServer.java`        | 多处改动        | attach 分流、handleSource、路径映射、sourceReference                  |
| `vscode-extension/package.json`      | 配置新增        | attach 增加 localRoot/remoteRoot/stopOnEntry                   |
| `TestScript.java`                    | 新增模式        | debugagent/debugagent-attach，compileGclass 传 content         |
| `resources/debug_attach_test.script` | **新文件**     | attach 测试脚本                                                  |

