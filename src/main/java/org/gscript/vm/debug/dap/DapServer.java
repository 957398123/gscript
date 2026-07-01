package org.gscript.vm.debug.dap;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.gscript.compile.Lexer;
import org.gscript.compile.Parser;
import org.gscript.compile.gen.ByteCodeGenerator;
import org.gscript.compile.node.Node;
import org.gscript.compile.token.GSToken;
import org.gscript.vm.GSEnv;
import org.gscript.vm.GSFrame;
import org.gscript.vm.GSInterpreter;
import org.gscript.vm.debug.DebugAbortException;
import org.gscript.vm.debug.DebugController;
import org.gscript.vm.stdlib.Console;
import org.gscript.vm.value.GSFunction;
import org.gscript.vm.value.GSNull;
import org.gscript.vm.value.GSObject;
import org.gscript.vm.value.GSValue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DAP（Debug Adapter Protocol）适配器。
 *
 * <p>连接 VSCode（或其他 DAP 客户端）与 gscript 解释器：
 * <ul>
 *   <li>从传输层（stdio 或 socket）读取 DAP 消息（Content-Length 分帧的 JSON-RPC）。</li>
 *   <li>分发请求（initialize/launch/attach/setBreakpoints/configurationDone/continue/
 *       next/stepIn/stepOut/pause/stackTrace/scopes/variables/evaluate/disconnect）。</li>
 *   <li>在 {@code configurationDone} 时编译脚本、启动解释器线程。</li>
 *   <li>解释器挂起时（通过 {@link DebugController.SuspendListener}）发送 "stopped" 事件。</li>
 *   <li>程序输出（{@code console.log}）通过重定向 {@code System.out} 转为 "output" 事件
 *       （DAP 协议占用 stdout，程序输出不能直接写 stdout）。</li>
 * </ul>
 *
 * <p>线程模型：
 * <ul>
 *   <li>DAP 线程（调用 {@link #run()}）：读消息、分发请求、写响应/事件。</li>
 *   <li>解释器线程：执行脚本，挂起时通过回调通知 DAP 线程。</li>
 *   <li>所有对传输层的写操作通过 {@link #writeLock} 同步。</li>
 * </ul>
 *
 * <p>变量引用系统：DAP 用 {@code variablesReference}（正整数）标识可展开的作用域/对象。
 * 本类维护 {@link #varRefs} 映射，在每次新挂起时清空（客户端会重新请求 scopes/variables）。
 */
public class DapServer implements DebugController.SuspendListener {

    /** 传输层输入流（DAP 消息来源） */
    private final InputStream in;
    /** 传输层输出流（DAP 消息去向，stdio 模式下为原始 stdout） */
    private final PrintStream rawOut;
    /** 写锁：DAP 线程与解释器线程均可能写传输层，需同步 */
    private final Object writeLock = new Object();
    /** Gson 实例（JSON 序列化/反序列化） */
    private final Gson gson = new Gson();

    /** 消息序列号计数器 */
    private int seq = 0;

    /** 解释器实例（configurationDone 时创建） */
    private GSInterpreter interpreter;
    /** 调试控制器（launch/attach 时创建） */
    private DebugController controller;
    /** 脚本文件路径（来自 launch/attach 参数） */
    private String scriptPath;
    /** 字节码（编译后） */
    private String[] bytecode;
    /** 源码行号映射（编译后，与字节码平行） */
    private int[] sourceLines;

    /** 变量引用映射：refId → GSEnv（作用域）或 GSObject（可展开变量） */
    private final Map<Integer, Object> varRefs = new HashMap<>();
    /** 下一个变量引用 ID（从 1 开始，0 表示不可展开） */
    private int nextVarRef = 1;

    /** 栈帧列表（stackTrace 请求时填充，供 scopes 按 frameId 查找） */
    private List<GSFrame> frameList = new ArrayList<>();

    /** DAP 线程 ID（gscript 单线程，固定为 1） */
    private static final int THREAD_ID = 1;

    /** 是否已启动（configurationDone 已处理） */
    private boolean started = false;

    /**
     * DAP 通信日志（诊断用）。记录所有收发的 DAP 消息到文件，便于排查 VSCode 与适配器间的通信问题。
     * 写日志失败不影响 DAP 通信（catch 静默）。
     */
    private PrintStream dapLog = null;

    public DapServer(InputStream in, PrintStream rawOut) {
        this.in = in;
        this.rawOut = rawOut;
        initDapLog();
    }

    /**
     * 打开 DAP 通信日志文件。尝试多个位置（user.dir、jar 同级、临时目录），
     * 任一成功即停止；全部失败则 dapLog 保持 null（不记录，不影响通信）。
     */
    private void initDapLog() {
        String[] candidates = {
            System.getProperty("user.dir") + File.separator + "dap_debug.log",
            "e:" + File.separator + "JProjects" + File.separator + "gscript" + File.separator + "dap_debug.log",
            System.getProperty("java.io.tmpdir") + File.separator + "gscript_dap_debug.log",
        };
        for (String path : candidates) {
            try {
                FileOutputStream fos = new FileOutputStream(path, true);  // 追加模式
                dapLog = new PrintStream(fos, true, "UTF-8");
                dapLog.println("\n==== DapServer 启动 " + new java.util.Date() + " ====");
                dapLog.println("user.dir=" + System.getProperty("user.dir"));
                dapLog.println("日志文件: " + path);
                dapLog.flush();
                break;
            } catch (Exception e) {
                // 该路径不可写，尝试下一个
            }
        }
    }

    /** 记录一条日志（带时间戳）。dapLog 为 null 时静默跳过。 */
    private void log(String text) {
        if (dapLog != null) {
            try {
                dapLog.println("[" + System.currentTimeMillis() + "] " + text);
                dapLog.flush();
            } catch (Exception e) {
                // 忽略日志写入失败
            }
        }
    }

    // =========================================================================
    //  主循环与协议分帧
    // =========================================================================

    /**
     * DAP 主循环：持续读取并处理消息，直到输入结束或 disconnect。
     */
    public void run() {
        try {
            while (true) {
                String json = readMessage();
                if (json == null) {
                    break;
                }
                handleMessage(json);
            }
        } catch (Exception e) {
            // 主循环异常（如传输层断开），静默退出
        }
        // 退出前终止解释器
        if (controller != null) {
            controller.terminate();
        }
    }

    /**
     * 读取一条 DAP 消息（Content-Length 分帧）。
     *
     * @return JSON 文本，输入结束时返回 null
     */
    private String readMessage() throws Exception {
        // 读Headers：逐字节读取直到 \r\n\r\n
        ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
        int b;
        int state = 0;  // 状态机：0=正常, 1=\r, 2=\r\n, 3=\r\n\r, 4=\r\n\r\n(完成)
        while ((b = in.read()) != -1) {
            headerBuf.write(b);
            char c = (char) b;
            switch (state) {
                case 0: state = (c == '\r') ? 1 : 0; break;
                case 1: state = (c == '\n') ? 2 : 0; break;
                case 2: state = (c == '\r') ? 3 : 0; break;
                case 3: state = (c == '\n') ? 4 : 0; break;
            }
            if (state == 4) {
                break;
            }
        }
        if (b == -1 && headerBuf.size() == 0) {
            return null;
        }
        String headers = new String(headerBuf.toByteArray(), StandardCharsets.UTF_8);

        // 解析 Content-Length
        int contentLength = 0;
        for (String line : headers.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring(15).trim());
            }
        }

        if (contentLength == 0) {
            return "{}";
        }

        // 读Body
        byte[] body = new byte[contentLength];
        int read = 0;
        while (read < contentLength) {
            int n = in.read(body, read, contentLength - read);
            if (n == -1) {
                break;
            }
            read += n;
        }
        return new String(body, 0, read, StandardCharsets.UTF_8);
    }

    /**
     * 写一条 DAP 消息（加 Content-Length 帧）。
     */
    private void sendMessage(JsonObject msg) {
        String json = gson.toJson(msg);
        log(">>> SEND " + json);
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        synchronized (writeLock) {
            rawOut.print("Content-Length: " + bytes.length + "\r\n\r\n");
            rawOut.write(bytes, 0, bytes.length);
            rawOut.flush();
        }
    }

    // =========================================================================
    //  消息构造辅助
    // =========================================================================

    /** 发送成功响应 */
    private void sendResponse(int requestSeq, String command, JsonObject body) {
        JsonObject msg = new JsonObject();
        msg.addProperty("seq", ++seq);
        msg.addProperty("type", "response");
        msg.addProperty("request_seq", requestSeq);
        msg.addProperty("success", true);
        msg.addProperty("command", command);
        if (body != null) {
            msg.add("body", body);
        }
        sendMessage(msg);
    }

    /** 发送错误响应 */
    private void sendErrorResponse(int requestSeq, String command, String message) {
        JsonObject msg = new JsonObject();
        msg.addProperty("seq", ++seq);
        msg.addProperty("type", "response");
        msg.addProperty("request_seq", requestSeq);
        msg.addProperty("success", false);
        msg.addProperty("command", command);
        msg.addProperty("message", message);
        sendMessage(msg);
    }

    /** 发送事件 */
    private void sendEvent(String event, JsonObject body) {
        JsonObject msg = new JsonObject();
        msg.addProperty("seq", ++seq);
        msg.addProperty("type", "event");
        msg.addProperty("event", event);
        if (body != null) {
            msg.add("body", body);
        }
        sendMessage(msg);
    }

    /**
     * 发送 output 事件（程序输出）。
     * 由解释器线程（重定向的 System.out）和解释器自身调用。
     */
    public void sendOutput(String text, String category) {
        JsonObject body = new JsonObject();
        body.addProperty("category", category);
        body.addProperty("output", text);
        sendEvent("output", body);
    }

    // =========================================================================
    //  消息分发
    // =========================================================================

    private void handleMessage(String json) {
        log("<<< RECV " + json);
        JsonObject msg = JsonParser.parseString(json).getAsJsonObject();
        String type = msg.get("type").getAsString();
        if (!"request".equals(type)) {
            return;
        }
        String command = msg.get("command").getAsString();
        int requestSeq = msg.get("seq").getAsInt();
        JsonObject args = msg.has("arguments") && msg.get("arguments").isJsonObject()
                ? msg.getAsJsonObject("arguments") : new JsonObject();

        try {
            switch (command) {
                case "initialize":           handleInitialize(requestSeq, args); break;
                case "launch":               handleLaunchAttach(requestSeq, args, command); break;
                case "attach":               handleLaunchAttach(requestSeq, args, command); break;
                case "setBreakpoints":       handleSetBreakpoints(requestSeq, args); break;
                case "configurationDone":    handleConfigurationDone(requestSeq, args); break;
                case "continue":             handleContinue(requestSeq, args); break;
                case "next":                 handleStep(requestSeq, "next", DebugController.STEP_OVER); break;
                case "stepIn":               handleStep(requestSeq, "stepIn", DebugController.STEP_IN); break;
                case "stepOut":              handleStep(requestSeq, "stepOut", DebugController.STEP_OUT); break;
                case "pause":                handlePause(requestSeq, args); break;
                case "stackTrace":           handleStackTrace(requestSeq, args); break;
                case "scopes":               handleScopes(requestSeq, args); break;
                case "variables":            handleVariables(requestSeq, args); break;
                case "evaluate":             handleEvaluate(requestSeq, args); break;
                case "disconnect":           handleDisconnect(requestSeq, args); break;
                case "threads":              handleThreads(requestSeq); break;
                case "source":               handleSource(requestSeq, args); break;
                default:
                    sendResponse(requestSeq, command, new JsonObject());
                    break;
            }
        } catch (Exception e) {
            log("[ERROR] handle " + command + " 异常: " + e);
            if (dapLog != null) {
                try { e.printStackTrace(dapLog); } catch (Exception ignore) {}
            }
            sendErrorResponse(requestSeq, command, e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }

    // =========================================================================
    //  请求处理器
    // =========================================================================

    /** initialize：返回调试器能力，并发送 initialized 事件通知客户端可继续 */
    private void handleInitialize(int requestSeq, JsonObject args) {
        JsonObject body = new JsonObject();
        body.addProperty("supportsConfigurationDoneRequest", true);
        body.addProperty("supportsEvaluateForHovers", false);
        body.addProperty("supportsStepBack", false);
        body.addProperty("supportsConditionalBreakpoints", false);
        body.addProperty("supportsHitConditionalBreakpoints", false);
        body.addProperty("supportsExceptionInfoRequest", false);
        body.addProperty("supportsSetVariable", false);
        body.addProperty("supportsTerminateRequest", true);
        body.addProperty("supportsLoadedSourcesRequest", false);
        // 行号/列号从 1 开始（与 gscript 源码行号一致）
        body.addProperty("linesStartAt1", true);
        body.addProperty("columnsStartAt1", true);
        sendResponse(requestSeq, "initialize", body);
        // DAP 协议要求：initialize 响应后必须发送 initialized 事件，
        // 客户端（VSCode）收到此事件后才会发送 launch/attach 及后续配置请求
        sendEvent("initialized", null);
    }

    /** launch / attach：记录脚本路径，创建调试控制器 */
    private void handleLaunchAttach(int requestSeq, JsonObject args, String command) {
        // 获取脚本路径
        if (args.has("program")) {
            scriptPath = args.get("program").getAsString();
        }
        log("[FLOW] " + command + ": scriptPath=" + scriptPath + " args=" + gson.toJson(args));
        // 创建调试控制器
        controller = new DebugController();
        controller.setSuspendListener(this);
        // stopOnEntry
        if (args.has("stopOnEntry") && args.get("stopOnEntry").getAsBoolean()) {
            controller.setStopOnEntry(true);
        }
        sendResponse(requestSeq, command, new JsonObject());
    }

    /** setBreakpoints：设置断点 */
    private void handleSetBreakpoints(int requestSeq, JsonObject args) {
        List<Integer> lines = new ArrayList<>();
        if (args.has("breakpoints")) {
            for (JsonElement be : args.getAsJsonArray("breakpoints")) {
                JsonObject bo = be.getAsJsonObject();
                if (bo.has("line")) {
                    lines.add(bo.get("line").getAsInt());
                }
            }
        }
        if (controller != null) {
            controller.setBreakpoints(lines);
        }
        // 构造断点响应（全部标记为已验证）
        JsonObject body = new JsonObject();
        JsonArray bpArray = new JsonArray();
        for (int line : lines) {
            JsonObject bp = new JsonObject();
            bp.addProperty("line", line);
            bp.addProperty("verified", true);
            bpArray.add(bp);
        }
        body.add("breakpoints", bpArray);
        sendResponse(requestSeq, "setBreakpoints", body);
    }

    /** configurationDone：编译脚本并启动解释器 */
    private void handleConfigurationDone(int requestSeq, JsonObject args) {
        if (started) {
            sendResponse(requestSeq, "configurationDone", new JsonObject());
            return;
        }
        started = true;
        log("[FLOW] configurationDone: 开始编译 scriptPath=" + scriptPath);
        try {
            compileScript(scriptPath);
        } catch (Exception e) {
            log("[ERROR] 编译失败: " + e);
            if (dapLog != null) {
                try { e.printStackTrace(dapLog); } catch (Exception ignore) {}
            }
            sendErrorResponse(requestSeq, "configurationDone", "编译失败: " + e.getMessage());
            return;
        }
        log("[FLOW] 编译成功，bytecode 长度=" + bytecode.length + "，启动解释器线程");
        startInterpreterThread();
        sendResponse(requestSeq, "configurationDone", new JsonObject());
    }

    /** continue：恢复执行 */
    private void handleContinue(int requestSeq, JsonObject args) {
        if (controller != null) {
            controller.continueRun();
        }
        JsonObject body = new JsonObject();
        body.addProperty("allThreadsContinued", true);
        sendResponse(requestSeq, "continue", body);
    }

    /** next/stepIn/stepOut：单步 */
    private void handleStep(int requestSeq, String command, int stepMode) {
        if (controller != null) {
            controller.step(stepMode);
        }
        sendResponse(requestSeq, command, new JsonObject());
    }

    /** pause：请求暂停 */
    private void handlePause(int requestSeq, JsonObject args) {
        if (controller != null) {
            controller.pause();
        }
        sendResponse(requestSeq, "pause", new JsonObject());
    }

    /** stackTrace：返回调用栈 */
    private void handleStackTrace(int requestSeq, JsonObject args) {
        // 刷新帧列表与变量引用
        frameList = new ArrayList<>(interpreter != null ? interpreter.getCallStack() : new ArrayList<>());
        varRefs.clear();

        String sourceName = scriptPath != null ? Paths.get(scriptPath).getFileName().toString() : "script";

        JsonArray framesArray = new JsonArray();
        for (int i = 0; i < frameList.size(); i++) {
            GSFrame frame = frameList.get(i);
            int line = DebugController.currentLine(frame);
            String name = frame.function.name;
            if ("null".equals(name) || name == null) {
                name = "<anonymous>";
            }
            JsonObject frameObj = new JsonObject();
            frameObj.addProperty("id", i);
            frameObj.addProperty("name", name);
            JsonObject source = new JsonObject();
            source.addProperty("name", sourceName);
            source.addProperty("path", scriptPath != null ? scriptPath : "");
            frameObj.add("source", source);
            frameObj.addProperty("line", line > 0 ? line : 1);
            frameObj.addProperty("column", 1);
            frameObj.addProperty("endLine", line > 0 ? line : 1);
            frameObj.addProperty("endColumn", 1);
            framesArray.add(frameObj);
        }

        JsonObject body = new JsonObject();
        body.add("stackFrames", framesArray);
        body.addProperty("totalFrames", framesArray.size());
        sendResponse(requestSeq, "stackTrace", body);
    }

    /** scopes：返回某帧的变量作用域 */
    private void handleScopes(int requestSeq, JsonObject args) {
        int frameId = args.has("frameId") ? args.get("frameId").getAsInt() : 0;
        GSFrame frame = (frameId >= 0 && frameId < frameList.size()) ? frameList.get(frameId) : null;

        JsonArray scopesArray = new JsonArray();

        if (frame != null) {
            // Local 作用域：当前帧的 env 链（到 global 之前）
            int localRef = nextVarRef++;
            varRefs.put(localRef, frame.function.env);
            JsonObject localScope = new JsonObject();
            localScope.addProperty("name", "Local");
            localScope.addProperty("variablesReference", localRef);
            localScope.addProperty("expensive", false);
            scopesArray.add(localScope);
        }

        // Global 作用域
        if (interpreter != null) {
            int globalRef = nextVarRef++;
            varRefs.put(globalRef, interpreter.global);
            JsonObject globalScope = new JsonObject();
            globalScope.addProperty("name", "Global");
            globalScope.addProperty("variablesReference", globalRef);
            globalScope.addProperty("expensive", false);
            scopesArray.add(globalScope);
        }

        JsonObject body = new JsonObject();
        body.add("scopes", scopesArray);
        sendResponse(requestSeq, "scopes", body);
    }

    /** variables：返回某变量引用下的变量列表 */
    private void handleVariables(int requestSeq, JsonObject args) {
        int refId = args.has("variablesReference") ? args.get("variablesReference").getAsInt() : 0;
        Object target = varRefs.get(refId);

        JsonArray varsArray = new JsonArray();

        if (target instanceof GSEnv) {
            GSEnv env = (GSEnv) target;
            // 判断是否为 global（global 无 parent 或 name 为 "global"）——只展开直接变量
            boolean isGlobal = env.parent == null || "global".equals(env.name);
            if (isGlobal) {
                addEnvVariables(varsArray, env);
            } else {
                // Local：沿 env 链向上收集到 global 之前（内层遮蔽外层）
                Map<String, GSValue> merged = new HashMap<>();
                GSEnv cur = env;
                while (cur != null && !"global".equals(cur.name)) {
                    Map<String, GSValue> vals = cur.getValues();
                    if (vals != null) {
                        for (Map.Entry<String, GSValue> e : vals.entrySet()) {
                            if (!merged.containsKey(e.getKey())) {
                                merged.put(e.getKey(), e.getValue());
                            }
                        }
                    }
                    cur = cur.parent;
                }
                for (Map.Entry<String, GSValue> e : merged.entrySet()) {
                    varsArray.add(formatVariable(e.getKey(), e.getValue()));
                }
            }
        } else if (target instanceof GSObject) {
            GSObject obj = (GSObject) target;
            Map<String, GSValue> members = obj.getMembers();
            if (members != null) {
                for (Map.Entry<String, GSValue> e : members.entrySet()) {
                    varsArray.add(formatVariable(e.getKey(), e.getValue()));
                }
            }
        }

        JsonObject body = new JsonObject();
        body.add("variables", varsArray);
        sendResponse(requestSeq, "variables", body);
    }

    /** evaluate：轻量求值（标识符 + 属性访问） */
    private void handleEvaluate(int requestSeq, JsonObject args) {
        String expression = args.has("expression") ? args.get("expression").getAsString() : "";
        int frameId = args.has("frameId") ? args.get("frameId").getAsInt() : 0;
        GSFrame frame = (frameId >= 0 && frameId < frameList.size()) ? frameList.get(frameId) : null;

        JsonObject body = new JsonObject();
        if (frame != null) {
            GSValue value = evaluateExpression(expression, frame);
            if (value == null) {
                value = GSNull.NULL;
            }
            body.addProperty("result", value.toStringValue());
            body.addProperty("type", typeName(value));
            body.addProperty("variablesReference", getVarRefForValue(value));
        } else {
            body.addProperty("result", "<no frame>");
            body.addProperty("variablesReference", 0);
        }
        sendResponse(requestSeq, "evaluate", body);
    }

    /** disconnect：终止调试会话 */
    private void handleDisconnect(int requestSeq, JsonObject args) {
        if (controller != null) {
            controller.terminate();
        }
        sendResponse(requestSeq, "disconnect", new JsonObject());
    }

    /** threads：返回线程列表（gscript 单线程） */
    private void handleThreads(int requestSeq) {
        JsonObject body = new JsonObject();
        JsonArray threadsArray = new JsonArray();
        JsonObject thread = new JsonObject();
        thread.addProperty("id", THREAD_ID);
        thread.addProperty("name", "main");
        threadsArray.add(thread);
        body.add("threads", threadsArray);
        sendResponse(requestSeq, "threads", body);
    }

    /** source：返回源码内容（按需加载，此处返回空表示不支持） */
    private void handleSource(int requestSeq, JsonObject args) {
        sendErrorResponse(requestSeq, "source", "source request not supported");
    }

    // =========================================================================
    //  SuspendListener 实现
    // =========================================================================

    @Override
    public void onSuspended(String reason, GSFrame frame, int depth, int line) {
        log("[FLOW] onSuspended: reason=" + reason + " depth=" + depth + " line=" + line
                + " frame.func=" + (frame != null && frame.function != null ? frame.function.name : "null"));
        // 清空变量引用（新挂起，客户端会重新请求）
        varRefs.clear();
        JsonObject body = new JsonObject();
        body.addProperty("reason", reason);
        body.addProperty("threadId", THREAD_ID);
        body.addProperty("allThreadsStopped", true);
        sendEvent("stopped", body);
    }

    // =========================================================================
    //  编译与解释器启动
    // =========================================================================

    /**
     * 编译脚本，生成字节码与源码行号映射。
     */
    private void compileScript(String path) throws Exception {
        String content = new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8);
        Lexer lexer = new Lexer();
        List<GSToken> tokens = lexer.tokenize(content);
        Parser parser = new Parser(tokens);
        Node program = parser.parseProgram();
        ByteCodeGenerator gen = new ByteCodeGenerator();
        program.accept(gen);
        this.bytecode = gen.getByteCode().toArray(new String[0]);
        ArrayList<Integer> sl = gen.getSourceLines();
        this.sourceLines = new int[sl.size()];
        for (int i = 0; i < sl.size(); i++) {
            this.sourceLines[i] = sl.get(i);
        }
    }

    /**
     * 启动解释器线程。
     */
    private void startInterpreterThread() {
        interpreter = new GSInterpreter();
        interpreter.addVariableToGlobal("console", new Console());
        interpreter.setDebugController(controller);

        // 重定向 System.out/err 到 DAP output 事件
        redirectSystemOutput();

        Thread t = new Thread(() -> {
            try {
                log("[FLOW] 解释器线程开始 eval，bytecode 长度=" + bytecode.length);
                interpreter.eval(bytecode, sourceLines);
                log("[FLOW] 解释器 eval 正常结束");
            } catch (DebugAbortException e) {
                log("[FLOW] 解释器被 DebugAbortException 终止（调试会话结束）");
            } catch (Throwable e) {
                log("[ERROR] 解释器抛出异常: " + e);
                if (dapLog != null) {
                    try { e.printStackTrace(dapLog); } catch (Exception ignore) {}
                }
                sendOutput(e.toString() + "\n", "stderr");
            }
            // 解释器结束，发送 terminated 事件
            log("[FLOW] 发送 terminated 事件");
            sendEvent("terminated", new JsonObject());
        }, "gscript-interpreter");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 重定向 System.out/err 到 DAP output 事件。
     * DAP 协议占用 stdout，程序输出（console.log）必须转为 output 事件。
     */
    private void redirectSystemOutput() {
        try {
            // Java 9 的 PrintStream 无 (OutputStream, boolean, Charset) 构造器，用字符集名称重载
            PrintStream stdout = new PrintStream(new DapOutputOutputStream(this, "stdout"), true, "UTF-8");
            PrintStream stderr = new PrintStream(new DapOutputOutputStream(this, "stderr"), true, "UTF-8");
            System.setOut(stdout);
            System.setErr(stderr);
        } catch (java.io.UnsupportedEncodingException e) {
            // UTF-8 是标准字符集，不会到达此分支
            throw new RuntimeException(e);
        }
    }

    // =========================================================================
    //  变量格式化与求值
    // =========================================================================

    /**
     * 将环境中的变量添加到变量数组。
     */
    private void addEnvVariables(com.google.gson.JsonArray varsArray, GSEnv env) {
        Map<String, GSValue> vals = env.getValues();
        if (vals != null) {
            for (Map.Entry<String, GSValue> e : vals.entrySet()) {
                varsArray.add(formatVariable(e.getKey(), e.getValue()));
            }
        }
    }

    /**
     * 格式化一个变量为 DAP Variable 对象。
     */
    private JsonObject formatVariable(String name, GSValue value) {
        if (value == null) {
            value = GSNull.NULL;
        }
        JsonObject variable = new JsonObject();
        variable.addProperty("name", name);
        variable.addProperty("value", value.toStringValue());
        variable.addProperty("type", typeName(value));
        variable.addProperty("variablesReference", getVarRefForValue(value));
        return variable;
    }

    /**
     * 获取值对应的变量引用 ID（可展开则分配，否则 0）。
     */
    private int getVarRefForValue(GSValue value) {
        if (value == null) {
            return 0;
        }
        // 对象、数组、函数（GSObject 子类）可展开成员
        if (value instanceof GSObject) {
            // null 类型（type=8）虽是 GSObject 子类但不可展开
            if (value.type == 8) {
                return 0;
            }
            int ref = nextVarRef++;
            varRefs.put(ref, value);
            return ref;
        }
        return 0;
    }

    /**
     * 返回值的类型名。
     */
    private String typeName(GSValue value) {
        if (value == null) {
            return "null";
        }
        switch (value.type) {
            case 1: return "boolean";
            case 2: return "number";
            case 3: return "number";
            case 4: return "object";
            case 5: return "string";
            case 6: return "function";
            case 7: return "array";
            case 8: return "null";
            case 9: return "function";
            case 10: return "nan";
            default: return "unknown";
        }
    }

    /**
     * 轻量求值：支持标识符与点号属性访问（a.b.c）。
     * 在 DAP 线程读取 env/members，不经过 VM 执行，避免污染暂停状态的栈。
     */
    private GSValue evaluateExpression(String expr, GSFrame frame) {
        if (expr == null || expr.trim().isEmpty()) {
            return GSNull.NULL;
        }
        expr = expr.trim();
        String[] parts = expr.split("\\.");
        GSValue value = frame.function.getVariableFromScope(parts[0]);
        if (value == null) {
            value = GSNull.NULL;
        }
        for (int i = 1; i < parts.length; i++) {
            if (value == GSNull.NULL || value == null) {
                return GSNull.NULL;
            }
            if (value instanceof GSObject) {
                value = ((GSObject) value).getProperty(parts[i]);
            } else {
                return GSNull.NULL;
            }
        }
        return value;
    }

    // =========================================================================
    //  输出重定向流
    // =========================================================================

    /**
     * 将写入的字节按行转为 DAP output 事件。
     * UTF-8 安全：按字节缓冲，遇 '\n'（0x0A，UTF-8 中不会出现在多字节序列内）整行解码。
     */
    private static class DapOutputOutputStream extends OutputStream {
        private final DapServer server;
        private final String category;
        private final ByteArrayOutputStream buf = new ByteArrayOutputStream();

        DapOutputOutputStream(DapServer server, String category) {
            this.server = server;
            this.category = category;
        }

        @Override
        public void write(int b) {
            buf.write(b);
            if (b == '\n') {
                flushLine();
            }
        }

        @Override
        public void write(byte[] b, int off, int len) {
            for (int i = off; i < off + len; i++) {
                write(b[i]);
            }
        }

        @Override
        public void flush() {
            flushLine();
        }

        private void flushLine() {
            if (buf.size() > 0) {
                String text = new String(buf.toByteArray(), StandardCharsets.UTF_8);
                server.sendOutput(text, category);
                buf.reset();
            }
        }
    }
}
