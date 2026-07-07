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
 * <p>三种模式：
 * <ol>
 *   <li><b>waitForDebuggerAndRun</b>（launch 模式）：宿主启动时调用，阻塞等待 VSCode 连接 +
 *       configurationDone，然后在子线程启动解释器执行已注册的 gclass。适用于"agent 负责执行脚本"。</li>
 *   <li><b>startAttachListener</b>（运行时 attach）：宿主程序运行中调用，后台线程监听 VSCode 连接，
 *       连接后创建 controller 并设置到已运行的解释器（volatile 字段），解释器在下次 suspendCheck 时挂起。
 *       适用于"程序已运行，VSCode 随后附加"。</li>
 *   <li><b>waitForDebuggerAndAttach</b>（主线程驱动 attach）：阻塞当前线程等待 VSCode 连接 +
 *       configurationDone，连接后 controller 已注入并请求暂停，方法返回让主线程继续执行脚本。
 *       适用于"主线程是脚本执行驱动者，需先连接调试器再开始执行"——如宿主 static 块加载脚本场景。</li>
 * </ol>
 *
 * <p>典型用法：
 * <pre>
 * // 模式 1：launch（agent 执行脚本）
 * DebugAgent agent = new DebugAgent(4711);
 * agent.addGclass(data);
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
 *
 * // 模式 3：主线程驱动 attach（阻塞等连接后放行主线程）
 * DebugAgent agent = new DebugAgent(4711);
 * agent.setInterpreter(interp);
 * agent.setPauseOnAttach(true);
 * for (...) { agent.addGclass(data); }  // 注册 sourceContent
 * agent.waitForDebuggerAndAttach();      // 阻塞等 VSCode 连接，连接后返回
 * for (...) { interp.eval(data.src, ...); }  // 主线程执行，首次 eval 即挂起
 * </pre>
 *
 * <p>线程模型：
 * <ul>
 *   <li>模式 1：调用线程阻塞在 {@link #waitForDebuggerAndRun}（accept + DapServer.run）；
 *       解释器在 DapServer 触发的 "gscript-interpreter" 守护线程中运行。</li>
 *   <li>模式 2：{@link #startAttachListener} 启动 "gscript-debug-accept" 守护线程（accept + DapServer.run）；
 *       解释器在调用线程运行，controller 通过 volatile 字段附加。</li>
 *   <li>模式 3：{@link #waitForDebuggerAndAttach} 内部调 {@link #startAttachListener} 启动后台 accept 线程，
 *       主线程阻塞在 attachLock 等待 configurationDone；唤醒后主线程继续执行脚本，
 *       DapServer 留在后台线程处理后续 DAP 消息。</li>
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

    /**
     * waitForDebuggerAndAttach 模式：主线程阻塞等待 configurationDone 信号的同步锁。
     *
     * <p>仅 {@link #waitForDebuggerAndAttach()} 使用：主线程 wait，
     * {@link #onConfigurationDone()} 在 attach 分支 notify 唤醒。纯 attach 模式
     * （{@link #startAttachListener()}）不 wait，notify 被忽略，无副作用。
     */
    private final Object attachLock = new Object();

    /** waitForDebuggerAndAttach 模式：configurationDone 是否已触发（主线程可继续） */
    private boolean attachReady = false;

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
        if (interpreter != null) {
            interpreter.setDebugAgent(this);  // 反向引用，供 eval 入口注册 source
        }
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

    /**
     * 注册源码内容到 sourceContents map（供 DAP source 请求返回）。
     *
     * <p>与 {@link #addGclass} 的 source 注册逻辑一致，但不动 gclassDataList（不触发执行）。
     * 由解释器在 eval 入口（debugMode=true）调用，重复注册同 path 幂等（覆盖）。
     *
     * @param path    源码标识路径（DAP source 请求的 key）
     * @param content 源码文本内容
     */
    public void registerSource(String path, String content) {
        if (path != null && content != null) {
            sourceContents.put(path, content);
        }
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
                    // 循环 accept：disconnect 后不退出，等待 VSCode 重新 attach。
                    // 修复"断开后无法重新附加"问题：原实现 dapServer.run() 返回后线程即结束，
                    // serverSocket 虽在监听但无人 accept，新连接无人处理。
                    while (true) {
                        Socket socket = serverSocket.accept();
                        try {
                            System.err.println("[DebugAgent] VSCode 已附加");
                            // 注意：controller 不在此创建，由 DapServer.handleLaunchAttach 创建并通过
                            // setController 共享。setController 内部会根据 interpreterAlreadyRunning
                            // 立即设置到已运行的解释器（volatile 字段）并按需 pause()
                            // 每次 accept 创建新 DapServer 实例，状态（sourceRefs/varRefs/breakpoints）自然重置
                            dapServer = new DapServer(socket.getInputStream(),
                                    new PrintStream(socket.getOutputStream(), true), DebugAgent.this);
                            dapServer.run();  // 阻塞直到 disconnect
                            System.err.println("[DebugAgent] VSCode 已断开，解释器继续运行，等待重新附加...");
                        } finally {
                            try {
                                socket.close();
                            } catch (Exception e) {
                            }
                        }
                    }
                } catch (java.net.SocketException e) {
                    // serverSocket.close()（stop() 调用）导致 accept() 抛 SocketException，正常退出
                    System.err.println("[DebugAgent] accept 线程退出: " + e.getMessage());
                } catch (Exception e) {
                    System.err.println("[DebugAgent] accept 异常: " + e);
                }
            }
        }, "gscript-debug-accept");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    /**
     * 模式 3：阻塞当前线程等待 VSCode 连接 + configurationDone，连接后返回让主线程继续执行。
     *
     * <p>与 {@link #waitForDebuggerAndRun} 的区别：后者连接后由 agent 在子线程执行注册的 gclass，
     * 调用线程阻塞到 disconnect；本方法连接后**不执行任何脚本**，仅注入 controller 并请求暂停，
     * 然后唤醒调用线程返回——脚本执行由调用方（主线程）自行驱动。
     *
     * <p>与 {@link #startAttachListener} 的区别：后者立即返回，调用方需自行确保解释器已运行；
     * 本方法阻塞到 VSCode 连接，保证调用方返回时 controller 已就位、断点已设置，首次 eval 即可挂起。
     *
     * <p>典型场景：宿主 static 块加载脚本——主线程需先等调试器连接，再依次执行脚本。
     * 调用前应：
     * <ol>
     *   <li>{@link #setInterpreter(GSInterpreter)} 设置已初始化的解释器</li>
     *   <li>{@link #addGclass(GSClassData)} 注册所有脚本的 sourceContent（供 VSCode source 请求）</li>
     *   <li>{@link #setPauseOnAttach(boolean)} 设置连接后是否暂停（默认 true，首次 eval 即挂起）</li>
     * </ol>
     *
     * <p>返回后，调用方在主线程执行 {@link GSInterpreter#eval(byte[][], Object[], int[], String, String)}
     * 等方法，controller 已通过 volatile 字段注入，首次 suspendCheck 命中 pauseRequested 即挂起。
     * DapServer 在后台 "gscript-debug-accept" 线程继续处理后续 DAP 消息（step/continue/breakpoint）。
     *
     * @throws Exception 网络/调试协议异常
     * @throws IllegalStateException 未先 {@link #setInterpreter(GSInterpreter)}
     */
    public void waitForDebuggerAndAttach() throws Exception {
        if (interpreter == null) {
            throw new IllegalStateException("waitForDebuggerAndAttach 必须先 setInterpreter");
        }
        interpreterAlreadyRunning = true;  // attach 语义：setController 立即注入 controller + pause
        startAttachListener();              // 后台 "gscript-debug-accept" 线程 accept + DapServer.run

        // 阻塞等待 configurationDone 信号（由 onConfigurationDone 在 attach 分支 notify）
        synchronized (attachLock) {
            while (!attachReady) {
                try {
                    attachLock.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("等待调试器连接被中断", e);
                }
            }
        }
        System.err.println("[DebugAgent] waitForDebuggerAndAttach: 调试器已就绪，主线程继续执行");
    }

    /**
     * 通知 DapServer 脚本执行完毕，发送 terminated 事件。
     *
     * <p>waitForDebuggerAndAttach 模式下，主线程脚本跑完后调用此方法，
     * 让 DapServer 发送 terminated 事件，VSCode 据此正常结束调试会话。
     *
     * <p>launch 模式（{@link #waitForDebuggerAndRun}）无需调用——gscript-interpreter
     * 线程结束时自动调用 {@link DapServer#notifyInterpreterTerminated()}。
     * 纯 attach 模式（{@link #startAttachListener}）按 JS 语义 disconnect 不终止脚本，
     * 脚本结束由宿主自行处理。
     */
    public void notifyScriptCompleted() {
        if (dapServer != null) {
            dapServer.notifyInterpreterTerminated();
        }
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
     * <p>attachReady 模式：解释器已运行（模式 2）或主线程在等待（模式 3），无需启动解释器。
     *   模式 3（waitForDebuggerAndAttach）时唤醒主线程继续执行。
     */
    public void onConfigurationDone() {
        if (!interpreterAlreadyRunning) {
            // 模式 1：启动解释器线程
            if (interpreter == null) {
                interpreter = new GSInterpreter();  // 构造器已自动初始化 TimerScheduler + installTimerGlobals
                interpreter.addVariableToGlobal("console", new Console());
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
        } else {
            // 模式 2/3（attachReady）：解释器已运行或主线程等待中，无需启动解释器线程。
            // 模式 3（waitForDebuggerAndAttach）：唤醒阻塞等待的主线程继续执行。
            // 模式 2（startAttachListener）：无人 wait，notify 被忽略，无副作用。
            synchronized (attachLock) {
                attachReady = true;
                attachLock.notifyAll();
            }
        }
    }
}
