package org.gscript.vm.debug.dap;

import org.gscript.compile.Lexer;
import org.gscript.compile.Parser;
import org.gscript.compile.gen.ByteCodeGenerator;
import org.gscript.compile.gclass.BytecodeEncoder;
import org.gscript.compile.gclass.EncodedBytecode;
import org.gscript.compile.gclass.GSClassData;
import org.gscript.compile.gclass.GSClassReader;
import org.gscript.compile.node.Node;
import org.gscript.util.AtomicCounter;
import org.gscript.vm.GSEnv;
import org.gscript.vm.GSFrame;
import org.gscript.vm.GSInterpreter;
import org.gscript.vm.debug.DebugAbortException;
import org.gscript.vm.debug.DebugAgent;
import org.gscript.vm.debug.DebugController;
import org.gscript.vm.debug.dap.json.JsonArray;
import org.gscript.vm.debug.dap.json.JsonObject;
import org.gscript.vm.debug.dap.json.JsonParser;
import org.gscript.vm.debug.dap.json.JsonValue;
import org.gscript.vm.debug.dap.json.JsonWriter;
import org.gscript.vm.stdlib.Console;
import org.gscript.vm.value.GSNull;
import org.gscript.vm.value.GSObject;
import org.gscript.vm.value.GSValue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
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

    /** 消息序列号计数器（DAP 线程与解释器线程均会发送消息，用原子类型保证线程安全） */
    private final AtomicCounter seq = new AtomicCounter(0);

    /** 解释器实例（configurationDone 时创建） */
    private GSInterpreter interpreter;
    /** 调试控制器（launch/attach 时创建） */
    private DebugController controller;
    /**
     * 脚本文件路径列表（来自 launch/attach 参数的 files 数组，或 program 单文件兼容）。
     * 多文件按顺序加载 eval，共享同一 global 域，后加载文件覆盖前文件同名函数。
     */
    private final List scriptPaths = new ArrayList();
    /**
     * 编译后的字节码列表（与 scriptPaths 一一对应，configurationDone 时填充）。
     * 每个元素是一个文件编译后的二进制字节码数组（byte[][]，每条指令为 byte[]）。
     * 统一使用二进制格式：.script 编译后用 BytecodeEncoder 编码，.gclass 加载后直接是 byte[][]。
     */
    private final List compiledBytecodes = new ArrayList();
    /**
     * 常量池列表（与 compiledBytecodes 一一对应，同文件函数共享引用）。
     */
    private final List compiledConstantPools = new ArrayList();
    /**
     * 源码行号映射列表（与 scriptPaths 一一对应，与各自字节码平行）。
     */
    private final List compiledSourceLines = new ArrayList();

    /** 变量引用映射：refId → GSEnv（作用域）或 GSObject（可展开变量）。DAP 线程与解释器线程均会读写，用同步 Map 防竞态 */
    private final Map varRefs = Collections.synchronizedMap(new HashMap());
    /** 下一个变量引用 ID（从 1 开始，0 表示不可展开） */
    private int nextVarRef = 1;

    /** 源码引用映射：sourceReference → sourcePath（attach 模式 stackTrace 用） */
    private final Map sourceRefs = new HashMap();
    /** 下一个源码引用 ID（从 1 开始，0 表示按 path 读） */
    private int nextSourceRef = 1;

    /**
     * 调试代理（attach 模式非 null，launch 模式 null）。
     * 非 null 时表示 DapServer 由 DebugAgent 创建，解释器由 agent/宿主管理，
     * DapServer 不自建解释器、不编译本地脚本，源码通过 source 请求返回。
     */
    private DebugAgent agent = null;

    /** attach 模式路径映射：本地根目录（VSCode 端，规范化后）。null 表示未配置。 */
    private String localRoot = null;
    /** attach 模式路径映射：远程根目录（gclass sourcePath 前缀）。null 表示未配置。 */
    private String remoteRoot = null;

    /** 栈帧列表（stackTrace 请求时填充，供 scopes 按 frameId 查找） */
    private List frameList = new ArrayList();

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
        this(in, rawOut, null);
    }

    /**
     * 构造 DAP 适配器（attach 模式，带 DebugAgent）。
     *
     * <p>agent 非 null 时为 attach 模式：解释器由 agent/宿主管理，DapServer 不自建解释器、
     * 不编译本地脚本，源码通过 source 请求从 agent.getSourceContents() 返回。
     *
     * @param in    传输层输入流
     * @param rawOut 传输层输出流
     * @param agent 调试代理（attach 模式非 null，launch 模式 null）
     */
    public DapServer(InputStream in, PrintStream rawOut, DebugAgent agent) {
        this.in = in;
        this.rawOut = rawOut;
        this.agent = agent;
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
        for (int ci = 0; ci < candidates.length; ci++) {
            String path = candidates[ci];
            try {
                FileOutputStream fos = new FileOutputStream(path, true);  // 追加模式
                dapLog = new PrintStream(fos, true, "UTF-8");
                dapLog.println("\n==== DapServer 启动 " + new Date() + " ====");
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
            log("[ERROR] DapServer 主循环异常: " + e);
            if (dapLog != null) {
                try { e.printStackTrace(dapLog); } catch (Exception ignore) {}
            } else {
                // dapLog 初始化失败时回退到 stderr，避免致命异常完全丢失
                try { e.printStackTrace(); } catch (Exception ignore) {}
            }
        }
        // 退出前终止解释器（launch 模式）
        // attach 模式：disconnect 已分离调试器（setDebugController(null)），
        //   controller.terminate() 仅设置旧 controller 的 terminated 标志，
        //   解释器不再引用该 controller，故无影响——解释器继续运行
        if (controller != null) {
            controller.terminate();
        }
        // 关闭 DAP 通信日志文件（释放 FileOutputStream）
        if (dapLog != null) {
            try {
                dapLog.println("[FLOW] DapServer 主循环退出，关闭日志");
                dapLog.flush();
                dapLog.close();
            } catch (Exception ignore) {
            } finally {
                dapLog = null;
            }
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
        String headers = new String(headerBuf.toByteArray(), "UTF-8");

        // 解析 Content-Length
        int contentLength = 0;
        String[] lines = headers.split("\r\n");
        for (int li = 0; li < lines.length; li++) {
            String line = lines[li];
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
        return new String(body, 0, read, "UTF-8");
    }

    /**
     * 写一条 DAP 消息（加 Content-Length 帧）。
     */
    private void sendMessage(JsonObject msg) {
        String json = JsonWriter.toJson(msg);
        log(">>> SEND " + json);
        byte[] bytes;
        try {
            bytes = json.getBytes("UTF-8");
        } catch (UnsupportedEncodingException e) {
            // UTF-8 是标准字符集，不会到达此分支
            throw new RuntimeException(e);
        }
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
        msg.addProperty("seq", seq.incrementAndGet());
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
        msg.addProperty("seq", seq.incrementAndGet());
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
        msg.addProperty("seq", seq.incrementAndGet());
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
        String command = null;
        int requestSeq = 0;
        try {
            JsonObject msg = JsonParser.parseString(json).getAsJsonObject();
            String type = msg.get("type").getAsString();
            if (!"request".equals(type)) {
                return;
            }
            command = msg.get("command").getAsString();
            requestSeq = msg.get("seq").getAsInt();
            JsonObject args = msg.has("arguments") && msg.get("arguments").isJsonObject()
                    ? msg.getAsJsonObject("arguments") : new JsonObject();

            if ("initialize".equals(command)) {
                handleInitialize(requestSeq, args);
            } else if ("launch".equals(command) || "attach".equals(command)) {
                handleLaunchAttach(requestSeq, args, command);
            } else if ("setBreakpoints".equals(command)) {
                handleSetBreakpoints(requestSeq, args);
            } else if ("setExceptionBreakpoints".equals(command)) {
                handleSetExceptionBreakpoints(requestSeq, args);
            } else if ("configurationDone".equals(command)) {
                handleConfigurationDone(requestSeq, args);
            } else if ("continue".equals(command)) {
                handleContinue(requestSeq, args);
            } else if ("next".equals(command)) {
                handleStep(requestSeq, "next", DebugController.STEP_OVER);
            } else if ("stepIn".equals(command)) {
                handleStep(requestSeq, "stepIn", DebugController.STEP_IN);
            } else if ("stepOut".equals(command)) {
                handleStep(requestSeq, "stepOut", DebugController.STEP_OUT);
            } else if ("pause".equals(command)) {
                handlePause(requestSeq, args);
            } else if ("terminate".equals(command)) {
                handleTerminate(requestSeq, args);
            } else if ("stackTrace".equals(command)) {
                handleStackTrace(requestSeq, args);
            } else if ("scopes".equals(command)) {
                handleScopes(requestSeq, args);
            } else if ("variables".equals(command)) {
                handleVariables(requestSeq, args);
            } else if ("evaluate".equals(command)) {
                handleEvaluate(requestSeq, args);
            } else if ("disconnect".equals(command)) {
                handleDisconnect(requestSeq, args);
            } else if ("threads".equals(command)) {
                handleThreads(requestSeq);
            } else if ("source".equals(command)) {
                handleSource(requestSeq, args);
            } else {
                sendResponse(requestSeq, command, new JsonObject());
            }
        } catch (Exception e) {
            log("[ERROR] handle " + command + " 异常: " + e);
            if (dapLog != null) {
                try { e.printStackTrace(dapLog); } catch (Exception ignore) {}
            }
            // command==null 表示 JSON 解析失败（畸形消息），无 seq/command 可回复，仅记录日志后继续主循环
            if (command != null) {
                sendErrorResponse(requestSeq, command, e.getMessage() != null ? e.getMessage() : e.toString());
            }
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
        // 异常断点过滤器：VSCode 据此在断点面板显示"被捕获的异常/未捕获的异常"勾选项
        JsonArray excFilters = new JsonArray();
        JsonObject caughtFilter = new JsonObject();
        caughtFilter.addProperty("filter", "caught");
        caughtFilter.addProperty("label", "Caught Exceptions");
        caughtFilter.addProperty("default", false);
        excFilters.add(caughtFilter);
        JsonObject uncaughtFilter = new JsonObject();
        uncaughtFilter.addProperty("filter", "uncaught");
        uncaughtFilter.addProperty("label", "Uncaught Exceptions");
        uncaughtFilter.addProperty("default", false);
        excFilters.add(uncaughtFilter);
        body.add("exceptionBreakpointFilters", excFilters);
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
        // attach 模式（带 agent）：不编译本地脚本，仅记录路径映射 + 创建 controller
        // 解释器由 agent/宿主管理，源码通过 source 请求从 agent.getSourceContents() 返回
        if (agent != null && "attach".equals(command)) {
            localRoot = args.has("localRoot") ? args.get("localRoot").getAsString() : null;
            remoteRoot = args.has("remoteRoot") ? args.get("remoteRoot").getAsString() : null;
            // 规范化 localRoot（与 setBreakpoints 的 source.path 规范化一致，便于前缀匹配）
            if (localRoot != null && localRoot.length() > 0) {
                localRoot = canonicalize(localRoot);
            } else {
                localRoot = null;
            }
            if (remoteRoot == null) {
                remoteRoot = "";
            }
            controller = new DebugController();
            controller.setSuspendListener(this);
            if (args.has("stopOnEntry") && args.get("stopOnEntry").getAsBoolean()) {
                controller.setStopOnEntry(true);
            }
            // 共享 controller 给 agent：attachReady 模式下立即设置到已运行的解释器，
            // waitForDebugger 模式下由 agent.onConfigurationDone 启动解释器时设置。
            // 统一 controller 实例，避免解释器拿到无 SuspendListener 的副本导致死锁。
            agent.setController(controller);
            log("[FLOW] attach(agent): localRoot=" + localRoot + " remoteRoot=" + remoteRoot);
            sendResponse(requestSeq, command, new JsonObject());
            return;
        }
        // 以下为 launch 模式（含无 agent 的伪 attach）：编译本地脚本文件
        // 获取脚本路径列表：优先 files 数组（多文件），回退 program（单文件兼容）
        // 路径规范化（canonicalize）确保与 setBreakpoints 的 source.path 匹配
        // （VSCode 的 ${workspaceFolder} 解析后可能是混合斜杠，而 source.path 是规范化的反斜杠）
        scriptPaths.clear();
        if (args.has("files") && args.get("files").isJsonArray()) {
            JsonArray filesArr = args.getAsJsonArray("files");
            for (int fi = 0; fi < filesArr.size(); fi++) {
                String p = filesArr.get(fi).getAsString();
                if (p != null && p.length() > 0) {
                    scriptPaths.add(canonicalize(p));
                }
            }
        } else if (args.has("program")) {
            String p = args.get("program").getAsString();
            if (p != null && p.length() > 0) {
                scriptPaths.add(canonicalize(p));
            }
        }
        log("[FLOW] " + command + ": scriptPaths=" + scriptPaths + " args=" + JsonWriter.toJson(args));
        // 创建调试控制器
        controller = new DebugController();
        controller.setSuspendListener(this);
        // stopOnEntry
        if (args.has("stopOnEntry") && args.get("stopOnEntry").getAsBoolean()) {
            controller.setStopOnEntry(true);
        }
        sendResponse(requestSeq, command, new JsonObject());
    }

    /** setBreakpoints：设置指定文件的断点（按 source.path 区分多文件） */
    private void handleSetBreakpoints(int requestSeq, JsonObject args) {
        // 从 args.source.path 取断点所属文件路径（DAP 协议：每个文件发一次 setBreakpoints）
        // 规范化路径，确保与 launch 时存的 scriptPaths（sourcePath）匹配
        String path = null;
        if (args.has("source") && args.getAsJsonObject("source").has("path")) {
            path = canonicalize(args.getAsJsonObject("source").get("path").getAsString());
            // attach 模式：本地路径 → 远程 sourcePath（gclass 中的 sourcePath）
            // 例如 localRoot=e:\JProjects\gscript\src\main\resources，remoteRoot=""
            // 本地 e:\...\resources\debug_attach_test.script → 远程 debug_attach_test.script
            if (agent != null && localRoot != null && path != null) {
                path = mapToRemotePath(path);
            }
        }
        List lines = new ArrayList();
        if (args.has("breakpoints")) {
            JsonArray bpArr = args.getAsJsonArray("breakpoints");
            for (int bi = 0; bi < bpArr.size(); bi++) {
                JsonObject bo = bpArr.get(bi).getAsJsonObject();
                if (bo.has("line")) {
                    lines.add(new Integer(bo.get("line").getAsInt()));
                }
            }
        }
        if (controller != null) {
            controller.setBreakpoints(path, lines);
        }
        // 构造断点响应（全部标记为已验证）
        JsonObject body = new JsonObject();
        JsonArray bpArray = new JsonArray();
        for (int i = 0; i < lines.size(); i++) {
            int line = ((Integer) lines.get(i)).intValue();
            JsonObject bp = new JsonObject();
            bp.addProperty("line", line);
            bp.addProperty("verified", true);
            bpArray.add(bp);
        }
        body.add("breakpoints", bpArray);
        sendResponse(requestSeq, "setBreakpoints", body);
    }

    /**
     * setExceptionBreakpoints：根据客户端勾选的异常过滤器开启/关闭异常断点。
     *
     * <p>过滤器：{@code caught}（被捕获的异常）/ {@code uncaught}（未捕获的异常）。
     * 任一勾选即开启 {@code pauseOnException}（当前实现简化为：所有 throw 均挂起，
     * 不区分是否被捕获）。全部取消则关闭。
     */
    private void handleSetExceptionBreakpoints(int requestSeq, JsonObject args) {
        boolean pauseOnException = false;
        if (args.has("filters") && args.get("filters").isJsonArray()) {
            JsonArray filtersArr = args.getAsJsonArray("filters");
            for (int fi = 0; fi < filtersArr.size(); fi++) {
                String filter = filtersArr.get(fi).getAsString();
                if ("caught".equals(filter) || "uncaught".equals(filter)) {
                    pauseOnException = true;
                    break;
                }
            }
        }
        if (controller != null) {
            controller.setPauseOnException(pauseOnException);
        }
        log("[FLOW] setExceptionBreakpoints: pauseOnException=" + pauseOnException);
        sendResponse(requestSeq, "setExceptionBreakpoints", new JsonObject());
    }

    /** configurationDone：编译所有脚本文件并启动解释器（launch），或委托 agent（attach） */
    private void handleConfigurationDone(int requestSeq, JsonObject args) {
        if (started) {
            sendResponse(requestSeq, "configurationDone", new JsonObject());
            return;
        }
        started = true;
        // attach 模式（带 agent）：委托 agent 处理（启动解释器或仅确认附加）
        if (agent != null) {
            log("[FLOW] configurationDone(attach): 委托 DebugAgent");
            agent.onConfigurationDone();
            sendResponse(requestSeq, "configurationDone", new JsonObject());
            return;
        }
        // launch 模式：编译本地脚本 + 启动解释器线程
        log("[FLOW] configurationDone: 开始编译 scriptPaths=" + scriptPaths);
        compiledBytecodes.clear();
        compiledConstantPools.clear();
        compiledSourceLines.clear();
        try {
            for (int si = 0; si < scriptPaths.size(); si++) {
                String path = (String) scriptPaths.get(si);
                compileScript(path);
            }
        } catch (Exception e) {
            log("[ERROR] 编译失败: " + e);
            if (dapLog != null) {
                try { e.printStackTrace(dapLog); } catch (Exception ignore) {}
            }
            sendErrorResponse(requestSeq, "configurationDone", "编译失败: " + e.getMessage());
            return;
        }
        int totalLen = 0;
        for (int bi = 0; bi < compiledBytecodes.size(); bi++) {
            byte[][] bc = (byte[][]) compiledBytecodes.get(bi);
            totalLen += bc.length;
        }
        log("[FLOW] 编译成功，共 " + compiledBytecodes.size() + " 个文件，总 bytecode 长度=" + totalLen + "，启动解释器线程");
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

    /** stackTrace：返回调用栈（每帧按其所属源文件报告 source.path，支持跨文件调试） */
    private void handleStackTrace(int requestSeq, JsonObject args) {
        // 刷新帧列表与变量引用
        // attach 模式下 interpreter 字段可能为 null，从 agent 获取
        GSInterpreter interp = interpreter;
        if (interp == null && agent != null) {
            interp = agent.getInterpreter();
        }
        // 使用同步快照：DAP 线程读取 callStack 时解释器线程已挂起（lock.wait），
        // 但 LinkedList 非线程安全，防御性同步保证内存可见性与并发安全
        frameList = interp != null ? interp.getCallStackSnapshot() : new ArrayList();
        varRefs.clear();
        sourceRefs.clear();

        JsonArray framesArray = new JsonArray();
        for (int i = 0; i < frameList.size(); i++) {
            GSFrame frame = (GSFrame) frameList.get(i);
            int line = DebugController.currentLine(frame);
            String name = frame.function.name;
            if ("null".equals(name) || name == null) {
                name = "<anonymous>";
            }
            // 每帧的源文件路径取自该帧函数的 sourcePath（多文件调试核心），
            // 兼容 null（非调试模式或旧代码）回退到空串
            String framePath = frame.function.sourcePath;
            String frameName = framePath != null
                    ? new File(framePath).getName()
                    : "script";
            JsonObject frameObj = new JsonObject();
            frameObj.addProperty("id", i);
            frameObj.addProperty("name", name);
            JsonObject source = new JsonObject();
            source.addProperty("name", frameName);
            // attach 模式：若该 sourcePath 有源码内容，设置 sourceReference，
            // VSCode 将通过 source 请求获取源码（不从磁盘读）
            String srcContent = (agent != null && framePath != null)
                    ? (String) agent.getSourceContents().get(framePath) : null;
            if (srcContent != null) {
                int ref = nextSourceRef++;
                sourceRefs.put(new Integer(ref), framePath);
                // DAP 规范 Source 对象字段名为 sourceReference（非 reference），
                // VSCode 据此发 source 请求时回填顶层 sourceReference，handleSource 用它查 sourceRefs
                source.addProperty("sourceReference", ref);
                source.addProperty("path", framePath);
            } else {
                // launch 模式或无源码内容：VSCode 从磁盘读
                source.addProperty("path", framePath != null ? framePath : "");
            }
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
        GSFrame frame = (frameId >= 0 && frameId < frameList.size()) ? (GSFrame) frameList.get(frameId) : null;

        JsonArray scopesArray = new JsonArray();

        if (frame != null) {
            // Local 作用域：当前帧的 env 链（到 global 之前）
            int localRef = nextVarRef++;
            varRefs.put(new Integer(localRef), frame.function.env);
            JsonObject localScope = new JsonObject();
            localScope.addProperty("name", "Local");
            localScope.addProperty("variablesReference", localRef);
            localScope.addProperty("expensive", false);
            scopesArray.add(localScope);
        }

        // Global 作用域（attach 模式下从 agent 获取 interpreter）
        GSInterpreter interp = interpreter;
        if (interp == null && agent != null) {
            interp = agent.getInterpreter();
        }
        if (interp != null) {
            int globalRef = nextVarRef++;
            varRefs.put(new Integer(globalRef), interp.global);
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
        Object target = varRefs.get(new Integer(refId));

        JsonArray varsArray = new JsonArray();

        if (target instanceof GSEnv) {
            GSEnv env = (GSEnv) target;
            // 判断是否为 global（global 无 parent 或 name 为 "global"）——只展开直接变量
            boolean isGlobal = env.parent == null || "global".equals(env.name);
            if (isGlobal) {
                addEnvVariables(varsArray, env);
            } else {
                // Local：沿 env 链向上收集到 global 之前（内层遮蔽外层）
                Map merged = new HashMap();
                GSEnv cur = env;
                while (cur != null && !"global".equals(cur.name)) {
                    Map vals = cur.getValues();
                    if (vals != null) {
                        Iterator it = vals.entrySet().iterator();
                        while (it.hasNext()) {
                            Map.Entry e = (Map.Entry) it.next();
                            if (!merged.containsKey(e.getKey())) {
                                merged.put(e.getKey(), e.getValue());
                            }
                        }
                    }
                    cur = cur.parent;
                }
                Iterator it = merged.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry e = (Map.Entry) it.next();
                    varsArray.add(formatVariable((String) e.getKey(), (GSValue) e.getValue()));
                }
            }
        } else if (target instanceof GSObject) {
            GSObject obj = (GSObject) target;
            Map members = obj.getMembers();
            if (members != null) {
                Iterator it = members.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry e = (Map.Entry) it.next();
                    varsArray.add(formatVariable((String) e.getKey(), (GSValue) e.getValue()));
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
        GSFrame frame = (frameId >= 0 && frameId < frameList.size()) ? (GSFrame) frameList.get(frameId) : null;

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

    /** disconnect：终止调试会话（launch）或分离调试器（attach） */
    private void handleDisconnect(int requestSeq, JsonObject args) {
        if (agent != null) {
            // attach 模式：分离调试器，不终止解释器（程序继续运行）
            // 1. 先 continueRun 唤醒可能挂起的解释器线程（suspended=false + notifyAll）
            // 2. 再清除 debugController（volatile），后续 suspendCheck 跳过
            if (controller != null) {
                controller.continueRun();
            }
            GSInterpreter interp = agent.getInterpreter();
            if (interp != null) {
                interp.setDebugController(null);
            }
            log("[FLOW] disconnect(attach): 分离调试器，解释器继续运行");
            sendResponse(requestSeq, "disconnect", new JsonObject());
            return;
        }
        // launch 模式：终止解释器
        if (controller != null) {
            controller.terminate();
        }
        sendResponse(requestSeq, "disconnect", new JsonObject());
    }

    /**
     * terminate：终止脚本执行。
     *
     * <p>initialize 已声明 {@code supportsTerminateRequest=true}，VSCode 点击"停止"按钮时
     * 发送 terminate 请求（而非 disconnect）。原实现未处理该请求，落入 default 分支仅回复
     * 空响应却不调用 {@code controller.terminate()}，导致脚本无法被真正终止。
     * 此处调用 terminate 后，解释器线程会在下一次 suspendCheck 抛出 DebugAbortException，
     * 随后由 {@link #startInterpreterThread} 发送 terminated 事件。
     */
    private void handleTerminate(int requestSeq, JsonObject args) {
        if (controller != null) {
            controller.terminate();
        }
        sendResponse(requestSeq, "terminate", new JsonObject());
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

    /**
     * source：返回源码内容（attach 模式按 sourceReference 从 agent 获取）。
     *
     * <p>attach 模式下 stackTrace 响应中设置了 source.reference > 0，VSCode 据此
     * 发送 source 请求获取源码（不从磁盘读）。launch 模式 source.reference 为 0，
     * VSCode 直接按 source.path 从磁盘读，不会发送 source 请求。
     */
    private void handleSource(int requestSeq, JsonObject args) {
        int ref = args.has("sourceReference") ? args.get("sourceReference").getAsInt() : 0;
        String path = (String) sourceRefs.get(new Integer(ref));
        if (path != null && agent != null) {
            String content = (String) agent.getSourceContents().get(path);
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

    /**
     * 通知解释器线程结束（供 DebugAgent 回调）。
     * 发送 terminated 事件，VSCode 据此结束调试会话。
     */
    public void notifyInterpreterTerminated() {
        log("[FLOW] 解释器线程结束，发送 terminated 事件");
        sendEvent("terminated", new JsonObject());
    }

    // =========================================================================
    //  SuspendListener 实现
    // =========================================================================

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
     * 规范化文件路径（统一斜杠方向、大小写、解析 ./ 和 ../）。
     *
     * <p>多文件调试中断点按文件路径（Map key）匹配：launch 的 files 数组路径设置为
     * {@code GSFunction.sourcePath}，setBreakpoints 的 source.path 作为 breakpoints Map 的 key。
     * 两者必须字符串完全一致才能命中断点。但 VSCode 的 {@code ${workspaceFolder}} 解析后
     * 可能是混合斜杠（如 {@code e:\JProjects\gscript/src/...}），而 source.path 是规范化的
     * Windows 路径（反斜杠）。用 {@code File.getCanonicalPath()} 统一格式避免不匹配。
     *
     * @param path 原始路径（可能含正斜杠、相对路径等）
     * @return 规范化后的绝对路径，规范化失败时返回原始路径
     */
    private String canonicalize(String path) {
        if (path == null) return null;
        try {
            return new File(path).getCanonicalPath();
        } catch (Exception e) {
            return path;
        }
    }

    /**
     * 本地路径 → 远程 sourcePath（attach 模式断点路径映射）。
     *
     * <p>将 VSCode 端的本地绝对路径剥离 localRoot 前缀后拼接 remoteRoot，
     * 得到 gclass 中存储的 sourcePath。例如：
     * <pre>
     * localRoot = "e:\JProjects\gscript\src\main\resources"
     * remoteRoot = ""
     * 本地路径 = "e:\JProjects\gscript\src\main\resources\debug_attach_test.script"
     * → 远程 sourcePath = "debug_attach_test.script"
     * </pre>
     *
     * <p>路径分隔符统一为正斜杠比较，结果保留远程风格（remoteRoot + 相对路径）。
     * 若本地路径不以 localRoot 开头，返回原路径（不映射）。
     */
    private String mapToRemotePath(String localPath) {
        if (localRoot == null) return localPath;
        String normLocal = localPath.replace('\\', '/');
        String normRoot = localRoot.replace('\\', '/');
        if (normLocal.startsWith(normRoot)) {
            String rel = normLocal.substring(normRoot.length());
            // 去掉前导斜杠
            while (rel.startsWith("/")) {
                rel = rel.substring(1);
            }
            return remoteRoot != null ? remoteRoot + rel : rel;
        }
        return localPath;
    }

    /**
     * 编译脚本，生成字节码与源码行号映射，追加到编译列表（支持多文件）。
     *
     * <p>.gclass 缓存优先：若 .gclass 文件存在且比 .script 新（或 .script 不存在），
     * 直接用 {@link GSClassReader} 反序列化加载，跳过编译；否则编译 .script 源码。
     * 加载/编译后统一转为 {@code byte[][]} + {@code Object[]} 常量池，存入
     * {@link #compiledBytecodes} 与 {@link #compiledConstantPools}。
     */
    private void compileScript(String path) throws Exception {
        // .gclass 缓存检查：路径同目录，扩展名替换为 .gclass
        String gclassPath = path.replaceAll("\\.script$", ".gclass");
        File gclassFile = new File(gclassPath);
        File scriptFile = new File(path);
        if (gclassFile.exists() &&
                (!scriptFile.exists() || gclassFile.lastModified() >= scriptFile.lastModified())) {
            // 从 .gclass 加载
            InputStream gin = null;
            int gclassLen;
            try {
                gin = new FileInputStream(gclassFile);
                GSClassReader reader = new GSClassReader();
                GSClassData data = reader.deserialize(gin);
                compiledBytecodes.add(data.src);           // byte[][] 直接用
                compiledConstantPools.add(data.constantPool);  // 常量池
                compiledSourceLines.add(data.sourceLines);
                gclassLen = data.src.length;
            } finally {
                if (gin != null) {
                    try { gin.close(); } catch (Exception ignore) {}
                }
            }
            log("[FLOW] 从 gclass 加载: " + gclassPath + "，bytecode 长度=" + gclassLen);
            return;
        }

        // 回退：编译 .script 源码
        FileInputStream fis = null;
        String content;
        try {
            fis = new FileInputStream(path);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = fis.read(buf)) != -1) {
                bos.write(buf, 0, n);
            }
            content = new String(bos.toByteArray(), "UTF-8");
        } finally {
            if (fis != null) {
                try { fis.close(); } catch (Exception ignore) {}
            }
        }
        Lexer lexer = new Lexer();
        List tokens = lexer.tokenize(content);
        Parser parser = new Parser(tokens);
        Node program = parser.parseProgram();
        ByteCodeGenerator gen = new ByteCodeGenerator();
        program.accept(gen);
        String[] bc1d = (String[]) gen.getByteCode().toArray(new String[0]);
        // 用 BytecodeEncoder 编码为二进制（byte[][] + Object[] 常量池）
        BytecodeEncoder encoder = new BytecodeEncoder();
        EncodedBytecode encoded = encoder.encode(Arrays.asList(bc1d));
        ArrayList sl = gen.getSourceLines();
        int[] slArr = new int[sl.size()];
        for (int i = 0; i < sl.size(); i++) {
            slArr[i] = ((Integer) sl.get(i)).intValue();
        }
        // 追加到列表（与 scriptPaths 顺序一一对应）
        compiledBytecodes.add(encoded.instructions);
        compiledConstantPools.add(encoded.constantPool);
        compiledSourceLines.add(slArr);
        log("[FLOW] 编译完成: " + path + "，bytecode 长度=" + encoded.instructions.length);
    }

    /**
     * 启动解释器线程。
     *
     * <p>多文件按 scriptPaths 顺序依次 eval，共享同一 {@link GSInterpreter} 实例的 global 域，
     * 后加载文件定义的同名函数自然覆盖前文件（global 域变量被覆盖赋值）。
     * 每个文件用各自的字节码与 sourceLines，并传入 sourcePath 供调试器区分文件。
     */
    private void startInterpreterThread() {
        final GSInterpreter interp = new GSInterpreter();
        interp.addVariableToGlobal("console", new Console());
        interp.installTimerGlobals();
        interp.setDebugController(controller);
        this.interpreter = interp;

        // 重定向 System.out/err 到 DAP output 事件
        redirectSystemOutput();

        // 捕获列表快照（避免匿名类闭包直接捕获可变外部列表）
        final List paths = new ArrayList(scriptPaths);
        final List bcs = new ArrayList(compiledBytecodes);
        final List cps = new ArrayList(compiledConstantPools);
        final List sls = new ArrayList(compiledSourceLines);

        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    for (int i = 0; i < paths.size(); i++) {
                        byte[][] bc = (byte[][]) bcs.get(i);
                        Object[] cp = (Object[]) cps.get(i);
                        int[] sl = (int[]) sls.get(i);
                        String path = (String) paths.get(i);
                        log("[FLOW] 解释器线程开始 eval 文件[" + i + "]: " + path + "，bytecode 长度=" + bc.length);
                        interp.eval(bc, cp, sl, path);
                        log("[FLOW] 解释器文件[" + i + "] eval 正常结束: " + path);
                    }
                    log("[FLOW] 所有文件 eval 正常结束");
                    interp.runEventLoop();
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
            }
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
        } catch (UnsupportedEncodingException e) {
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
    private void addEnvVariables(JsonArray varsArray, GSEnv env) {
        Map vals = env.getValues();
        if (vals != null) {
            Iterator it = vals.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry e = (Map.Entry) it.next();
                varsArray.add(formatVariable((String) e.getKey(), (GSValue) e.getValue()));
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
            varRefs.put(new Integer(ref), value);
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
        if (expr == null || expr.trim().length() == 0) {
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

        public void write(int b) {
            buf.write(b);
            if (b == '\n') {
                flushLine();
            }
        }

        public void write(byte[] b, int off, int len) {
            for (int i = off; i < off + len; i++) {
                write(b[i]);
            }
        }

        public void flush() {
            flushLine();
        }

        private void flushLine() {
            if (buf.size() > 0) {
                String text;
                try {
                    text = new String(buf.toByteArray(), "UTF-8");
                } catch (UnsupportedEncodingException e) {
                    // UTF-8 是标准字符集，不会到达此分支
                    throw new RuntimeException(e);
                }
                server.sendOutput(text, category);
                buf.reset();
            }
        }
    }
}
