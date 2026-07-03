package org.gscript.vm.debug;

import org.gscript.compile.gclass.GSClassData;
import org.gscript.vm.GSInterpreter;
import org.gscript.vm.debug.dap.DapServer;
import org.gscript.vm.stdlib.Console;

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
 * <p>两种模式：
 * <ol>
 *   <li><b>waitForDebuggerAndRun</b>（debug 模式启用）：宿主启动时调用，阻塞等待 VSCode 连接 +
 *       configurationDone，然后在子线程启动解释器执行已注册的 gclass。适用于"一开始 debug 模式启用"。</li>
 *   <li><b>startAttachListener</b>（运行时 attach）：宿主程序运行中调用，后台线程监听 VSCode 连接，
 *       连接后创建 controller 并设置到已运行的解释器（volatile 字段），解释器在下次 suspendCheck 时挂起。
 *       适用于"后面运行时再 attach"。</li>
 * </ol>
 *
 * <p>典型用法：
 * <pre>
 * // 模式 1：debug 模式启用
 * DebugAgent agent = new DebugAgent(4711);
 * agent.addGclass(data);  // 注册待执行的 gclass（含 sourceContent）
 * agent.waitForDebuggerAndRun();  // 阻塞直到调试会话结束
 *
 * // 模式 2：运行时 attach
 * GSInterpreter interp = new GSInterpreter();
 * interp.addVariableToGlobal("console", new Console());
 * DebugAgent agent = new DebugAgent(4711);
 * agent.setInterpreter(interp);
 * agent.addGclass(data);
 * agent.startAttachListener();  // 立即返回，后台监听
 * interp.eval(data.src, ...);  // 解释器运行，VSCode 连接后自动挂起
 * </pre>
 *
 * <p>线程模型：
 * <ul>
 *   <li>模式 1：调用线程阻塞在 {@link #waitForDebuggerAndRun}（accept + DapServer.run）；
 *       解释器在 DapServer 触发的 "gscript-interpreter" 守护线程中运行。</li>
 *   <li>模式 2：{@link #startAttachListener} 启动 "gscript-debug-accept" 守护线程（accept + DapServer.run）；
 *       解释器在调用线程运行，controller 通过 volatile 字段附加。</li>
 * </ul>
 */
public class DebugAgent {

    private GSInterpreter interpreter;
    private final int port;
    private final String host;
    private DebugController controller;
    private DapServer dapServer;
    private final List gclassDataList = new ArrayList();
    private ServerSocket serverSocket;
    private Thread acceptThread;

    /** 已加载 gclass 的源码内容映射：sourcePath → sourceContent（供 DapServer source 请求用） */
    private final Map sourceContents = new LinkedHashMap();

    /** 模式标志：true=attachReady（解释器已运行），false=waitForDebugger（解释器未启动） */
    private boolean interpreterAlreadyRunning = false;

    /** attachReady 模式下连接后是否自动暂停（默认 true） */
    private boolean pauseOnAttach = true;

    public DebugAgent(int port) {
        this(port, "localhost");
    }

    public DebugAgent(int port, String host) {
        this.port = port;
        this.host = host;
    }

    /**
     * 设置已运行的解释器。
     *
     * <p>attachReady 模式必需（解释器已由宿主启动并运行）；
     * waitForDebugger 模式可选——为 null 时 agent 在 configurationDone 时自建解释器。
     */
    public void setInterpreter(GSInterpreter interpreter) {
        this.interpreter = interpreter;
    }

    /**
     * 注册待执行的 gclass（waitForDebugger 模式用，按顺序执行）。
     * 同时记录 sourcePath → sourceContent 映射供 DAP source 请求。
     */
    public void addGclass(GSClassData data) {
        gclassDataList.add(data);
        if (data.sourcePath != null && data.sourceContent != null) {
            sourceContents.put(data.sourcePath, data.sourceContent);
        }
    }

    /** 设置 attachReady 模式下连接后是否自动暂停（默认 true）。 */
    public void setPauseOnAttach(boolean pause) {
        this.pauseOnAttach = pause;
    }

    /** 获取源码内容映射（DapServer 读取，用于响应 source 请求）。 */
    public Map getSourceContents() {
        return sourceContents;
    }

    /** 获取调试控制器（attachReady 模式连接后非 null）。 */
    public DebugController getController() {
        return controller;
    }

    /** 获取解释器实例（attachReady 模式连接后非 null）。 */
    public GSInterpreter getInterpreter() {
        return interpreter;
    }

    boolean isInterpreterAlreadyRunning() {
        return interpreterAlreadyRunning;
    }

    /**
     * 模式 1：阻塞等待 VSCode 连接，连接后启动解释器执行已注册 gclass。
     *
     * <p>当前线程阻塞直到 VSCode 断开连接或脚本执行完毕。
     * 解释器在 configurationDone 时由 {@link #onConfigurationDone()} 启动到守护线程。
     */
    public void waitForDebuggerAndRun() throws Exception {
        interpreterAlreadyRunning = false;
        serverSocket = new ServerSocket(port);
        System.err.println("[DebugAgent] waitForDebugger 模式，监听端口 " + port + "，等待 VSCode 连接...");
        Socket socket = null;
        try {
            socket = serverSocket.accept();
            System.err.println("[DebugAgent] VSCode 已连接");
            // 注意：controller 不在此创建，由 DapServer.handleLaunchAttach 创建并通过
            // setController 共享，确保解释器拿到的是带 SuspendListener 的同一实例
            // （否则断点/单步事件无法通知 DAP 线程，导致 "continue" 死锁）
            dapServer = new DapServer(socket.getInputStream(),
                    new PrintStream(socket.getOutputStream(), true), this);
            dapServer.run();  // 阻塞直到 disconnect
        } finally {
            if (socket != null) {
                try { socket.close(); } catch (Exception e) { /* socket 关闭失败忽略 */ }
            }
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        }
    }

    /**
     * 模式 2：后台线程监听 VSCode 连接，连接后创建 controller 设置到已运行的解释器。
     *
     * <p>立即返回，不阻塞宿主线程。解释器在下次 suspendCheck 时挂起（若 pauseOnAttach）。
     * 必须先 {@link #setInterpreter(GSInterpreter)} 设置已运行的解释器。
     */
    public void startAttachListener() {
        if (interpreter == null) {
            throw new IllegalStateException("attachReady 模式必须先 setInterpreter");
        }
        interpreterAlreadyRunning = true;
        acceptThread = new Thread(new Runnable() {
            public void run() {
                try {
                    serverSocket = new ServerSocket(port);
                    System.err.println("[DebugAgent] attachReady 模式，监听端口 " + port + "，等待 VSCode 附加...");
                    Socket socket = serverSocket.accept();
                    try {
                        System.err.println("[DebugAgent] VSCode 已附加");
                        // 注意：controller 不在此创建，由 DapServer.handleLaunchAttach 创建并通过
                        // setController 共享。setController 内部会根据 interpreterAlreadyRunning
                        // 立即设置到已运行的解释器（volatile 字段）并按需 pause()
                        dapServer = new DapServer(socket.getInputStream(),
                                new PrintStream(socket.getOutputStream(), true), DebugAgent.this);
                        dapServer.run();  // 阻塞直到 disconnect
                    } finally {
                        try {
                            socket.close();
                        } catch (Exception e) {
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[DebugAgent] accept 异常: " + e);
                }
            }
        }, "gscript-debug-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /** 停止代理：关闭 server socket，终止 controller。解释器线程自然结束。 */
    public void stop() {
        if (controller != null) {
            controller.terminate();
        }
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (Exception e) {
            // 忽略关闭异常
        }
    }

    /**
     * 由 DapServer 在 handleLaunchAttach 时回调，共享其创建的 controller。
     *
     * <p>统一 controller 归属，避免 DebugAgent 与 DapServer 各创建一个 controller 导致
     * 解释器拿到无 SuspendListener 的实例（断点/单步事件无法通知 DAP 线程，"continue" 死锁）。
     *
     * <p>两种模式的行为：
     * <ul>
     *   <li>waitForDebugger 模式（interpreterAlreadyRunning=false）：仅存储 controller，
     *       解释器尚未启动，{@link #onConfigurationDone()} 启动解释器前会设置到解释器。</li>
     *   <li>attachReady 模式（interpreterAlreadyRunning=true）：立即设置到已运行的解释器
     *       （volatile 字段，解释器线程下次 suspendCheck 可见），并按 pauseOnAttach 请求暂停。</li>
     * </ul>
     *
     * @param c DapServer 创建的 controller（已 setSuspendListener）
     */
    public void setController(DebugController c) {
        this.controller = c;
        if (interpreterAlreadyRunning && interpreter != null) {
            if (pauseOnAttach) {
                c.pause();  // 请求在下次 suspendCheck 暂停
            }
            // volatile 字段，解释器线程立即可见
            interpreter.setDebugController(c);
        }
        // waitForDebugger 模式：解释器尚未启动，onConfigurationDone 会设置 controller
    }

    /**
     * DapServer 在 configurationDone 时回调（供 DapServer 跨包调用，故 public）。
     *
     * <p>waitForDebugger 模式：启动解释器线程执行已注册 gclass。
     * <p>attachReady 模式：解释器已运行，无需启动（controller 已在 setController 设置）。
     */
    public void onConfigurationDone() {
        if (!interpreterAlreadyRunning) {
            // 模式 1：启动解释器线程
            if (interpreter == null) {
                interpreter = new GSInterpreter();
                interpreter.addVariableToGlobal("console", new Console());
                interpreter.installTimerGlobals();
            }
            interpreter.setDebugController(controller);
            final GSInterpreter interp = interpreter;
            final List datas = new ArrayList(gclassDataList);
            Thread t = new Thread(new Runnable() {
                public void run() {
                    try {
                        for (int i = 0; i < datas.size(); i++) {
                            GSClassData data = (GSClassData) datas.get(i);
                            interp.eval(data.src, data.constantPool, data.sourceLines,
                                    data.sourcePath, data.sourceContent);
                        }
                        interp.runEventLoop();
                    } catch (DebugAbortException e) {
                        // 调试会话终止，正常退出
                    } catch (Throwable e) {
                        e.printStackTrace();
                    }
                    // 解释器结束，通知 DapServer 发送 terminated 事件
                    if (dapServer != null) {
                        dapServer.notifyInterpreterTerminated();
                    }
                }
            }, "gscript-interpreter");
            t.setDaemon(true);
            t.start();
        }
        // attachReady 模式：解释器已运行，无需启动
    }
}
