package org.gscript.vm;

import org.gscript.compile.Lexer;
import org.gscript.compile.Parser;
import org.gscript.compile.gen.ByteCodeGenerator;
import org.gscript.compile.gclass.BytecodeEncoder;
import org.gscript.compile.gclass.EncodedBytecode;
import org.gscript.compile.gclass.GSClassConstants;
import org.gscript.compile.gclass.GSClassData;
import org.gscript.compile.gclass.GSClassReader;
import org.gscript.compile.node.Node;
import org.gscript.vm.debug.DebugAbortException;
import org.gscript.vm.debug.DebugAgent;
import org.gscript.vm.debug.DebugController;
import org.gscript.vm.stdlib.TimerLib;
import org.gscript.vm.stdlib.TypeLib;
import org.gscript.vm.value.*;
import org.gscript.util.AtomicCounter;
import org.gscript.util.SimpleBlockingQueue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

public class GSInterpreter implements TimerScheduler.TaskDispatcher {

    /**
     * 顶级域
     */
    public GSEnv global = new GSEnv("global", null);

    /**
     * 当前运行栈
     */
    public LinkedList stack = new LinkedList();

    /**
     * 调试器调用栈（栈顶为当前执行帧，栈底为顶级匿名帧）。
     *
     * <p>解释器每进入一次 {@code eval(GSFrame, args)} 入栈一帧，方法退出（含异常传播）时出栈。
     * 调试器挂起期间，DAP 线程通过 {@link #getCallStack()} 读取调用栈生成 stackTrace 响应。
     * 非调试模式下该栈仍会被维护（push/pop 开销极小），但不会被读取。
     */
    public LinkedList callStack = new LinkedList();

    /**
     * 调试控制器（null 表示非调试模式，解释器全速运行不做挂起检查）。
     *
     * <p>volatile：attachReady 模式下，DAP 线程设置 controller、解释器线程读取，
     * 必须保证跨线程可见性。setDebugController(null) 可清除（disconnect 分离调试器）。
     */
    private volatile DebugController debugController;

    /**
     * 定时器调度器（构造时默认初始化，定时器是解释器核心机制）。
     *
     * <p>worker 模型：守护线程只计时，到期任务通过 {@link #dispatch} 投递到 {@link #taskQueue}，
     * 由 {@link #workerThread} 串行执行回调。shutdown 后置 null。
     */
    private TimerScheduler timerScheduler;

    /**
     * worker 线程（单线程，常驻守护）：独占解释器执行权。
     *
     * <p>消费 {@link #taskQueue}：外部线程提交的 EvalTask（eval/callFunction 包装）
     * + 定时器到期任务（{@link #dispatch} 投递的 TimerEvalTask）。串行执行，与调试器
     * （THREAD_ID=1）兼容。构造时启动，{@link #shutdown()} 时终止。
     */
    private Thread workerThread;

    /**
     * 统一任务队列：外部 eval/callFunction 提交 + 定时器到期任务都进此队列。
     */
    private final SimpleBlockingQueue taskQueue = new SimpleBlockingQueue();

    /**
     * worker 是否正在执行任务（volatile 供 {@link #runEventLoop} awaitIdle 观察，
     * 避免"队列空但 worker 正在跑任务"误判 idle）。
     */
    private volatile boolean workerBusy = false;

    /**
     * worker 停止标志（volatile）。shutdown 或定时器回调抛 DebugAbortException 后置 true。
     * 后续 eval/callFunction 调用会抛 RuntimeException。
     */
    private volatile boolean workerStopped = false;

    /**
     * 调试模式标志：true=愿意被调试（eval 入口注册 source + 请求 entry stop）。
     * 与 debugController 解耦：debugMode=true 但 controller=null 时 eval 正常运行
     * （JS "DevTools 未连接" 语义）。
     * volatile：宿主线程设置，解释器线程读取。
     */
    private volatile boolean debugMode = false;

    /**
     * 调试代理反向引用（供 eval 入口注册 source）。
     * 由 {@link DebugAgent#setInterpreter} 回调 {@link #setDebugAgent} 设置。
     * null 表示未关联 agent。
     */
    private DebugAgent debugAgent = null;

    /** eval 入口 sourcePath 计数器（debug 模式下 evalScript/evalExpression 生成唯一路径） */
    private final AtomicCounter evalPathCounter = new AtomicCounter(0);

    public GSInterpreter() {
        ensureTimerScheduler();      // 构造时就绪 TimerScheduler（核心机制默认初始化，this 作为 TaskDispatcher）
        installTimerGlobals();       // 默认注册 setTimeout/setInterval 等入口函数
        installTypeGlobals();        // 默认注册 parseInt/parseFloat/isNaN/String/Number/Boolean
        startWorker();               // 启动 worker 线程（独占执行权）
    }

    /**
     * 设置调试控制器，进入调试模式。
     *
     * @param debugController 调试控制器
     */
    public void setDebugController(DebugController debugController) {
        this.debugController = debugController;
    }

    /**
     * 获取调试控制器。
     *
     * @return 调试控制器，非调试模式返回 null
     */
    public DebugController getDebugController() {
        return debugController;
    }

    /**
     * 启用/关闭调试模式。
     *
     * <p>启用后，所有顶层 eval 入口（{@link #eval(byte[][], Object[], int[], String, String)} /
     * {@link #evalScript(String)} / {@link #evalExpression(String)} / {@link #evalScriptFile(String)} 等）
     * 会自动注册 source 并请求 entry stop（若 controller 已就位）。
     * 未连接 VSCode 时 eval 正常执行（JS "DevTools 未连接" 语义）。
     *
     * @param mode true=启用调试模式
     */
    public void setDebugMode(boolean mode) {
        this.debugMode = mode;
    }

    /** 查询调试模式是否启用。 */
    public boolean isDebugMode() {
        return debugMode;
    }

    /**
     * 设置调试代理（反向引用）。
     *
     * <p>通常由 {@link DebugAgent#setInterpreter} 回调调用，宿主无需直接调用。
     * eval 入口通过此引用调用 {@link DebugAgent#registerSource} 注册源码。
     *
     * @param agent 调试代理
     */
    public void setDebugAgent(DebugAgent agent) {
        this.debugAgent = agent;
    }

    /** 查询调试代理。 */
    public DebugAgent getDebugAgent() {
        return debugAgent;
    }

    /**
     * 便捷主入口：一步启用调试模式（setInterpreter + setDebugMode(true)）。
     *
     * <p>调用后可选择连接策略：
     * <ul>
     *   <li>{@link DebugAgent#startAttachListener()} - 后台监听，立即返回（VSCode 随时 attach）</li>
     *   <li>{@link DebugAgent#waitForDebuggerAndAttach()} - 阻塞等连接（宿主手动等待）</li>
     *   <li>{@link DebugAgent#addGclass} + {@link DebugAgent#waitForDebuggerAndRun()} - launch 模式</li>
     * </ul>
     *
     * @param agent 调试代理
     */
    public void enableDebugMode(DebugAgent agent) {
        agent.setInterpreter(this);  // 内部回调 setDebugAgent
        this.debugMode = true;
    }

    /**
     * 调试模式 eval 入口预处理：注册 source + 请求 entry stop。
     *
     * <p>在创建 frame 之前调用，确保首次 suspendCheck 能命中 entry stop。
     * <ul>
     *   <li>debugAgent==null 或 sourcePath/content 为 null 时跳过注册</li>
     *   <li>debugController==null 时跳过 requestEntryStop（VSCode 未连接，正常运行）</li>
     * </ul>
     */
    private void prepareDebugEntry(String sourcePath, String sourceContent) {
        if (debugAgent != null && sourcePath != null && sourceContent != null) {
            debugAgent.registerSource(sourcePath, sourceContent);
        }
        DebugController dc = this.debugController;
        if (dc != null) {
            dc.requestEntryStop();
        }
    }

    /**
     * 生成唯一 eval sourcePath（debug 模式下 evalScript/evalExpression 用）。
     * 格式："eval-&lt;counter&gt;.script"，counter 自增。
     */
    private String generateEvalPath() {
        return "eval-" + evalPathCounter.incrementAndGet() + ".script";
    }

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
     * <p>调用前应完成：addVariableToGlobal("console", new Console())、
     * 其他自定义全局变量注入（定时器机制构造时已默认初始化）。本方法不返回直到调试会话结束。
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
        enableDebugMode(agent);  // setInterpreter + setDebugMode(true)（保留 caller 的 console/timer/globals 设置）
        agent.addGclass(data);
        agent.waitForDebuggerAndRun();
    }

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
     * <p>调用前应完成：addVariableToGlobal("console", new Console())
     * （定时器机制构造时已默认初始化）。
     * 本方法阻塞直到脚本执行完毕（VSCode disconnect 仅分离调试器，不终止脚本——
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
        enableDebugMode(agent);  // setInterpreter + setDebugMode(true)
        agent.addGclass(data);
        agent.startAttachListener();  // 后台监听，立即返回
        try {
            eval(data.src, data.constantPool, data.sourceLines, data.sourcePath, data.sourceContent);
            runEventLoop();  // 保留：现有便捷封装语义，含定时器回调；宿主若不需要可用 enableDebugMode + evalScriptFile 自行控制
        } finally {
            agent.stop();
        }
    }

    /**
     * 获取调用栈（栈顶在前）。
     *
     * @return 调用栈双端队列，迭代顺序为栈顶到栈底
     */
    public LinkedList getCallStack() {
        return callStack;
    }

    /**
     * 获取调用栈的线程安全快照（栈顶在前）。
     *
     * <p>调试器挂起期间，DAP 线程通过此方法读取调用栈生成 stackTrace 响应。
     * 解释器线程在挂起期间（{@code lock.wait()}）不会修改 callStack，故快照本身安全；
     * {@code synchronized} 提供内存可见性的防御性保证（LinkedList 非线程安全）。
     *
     * @return 调用栈快照（ArrayList，栈顶在前），非调试模式返回空列表
     */
    public synchronized ArrayList getCallStackSnapshot() {
        return new ArrayList(callStack);
    }

    /**
     * 读 u2（无符号 2 字节，Big-Endian），用于读取 CP 索引和计数值。
     */
    private static int readU2(byte[] code, int off) {
        return ((code[off] & 0xFF) << 8) | (code[off + 1] & 0xFF);
    }

    /**
     * 读 s2（有符号 2 字节，Big-Endian），用于读取跳转偏移量。
     */
    private static short readS2(byte[] code, int off) {
        return (short) ((code[off] << 8) | (code[off + 1] & 0xFF));
    }

    /**
     * 执行js函数
     *
     * @param frame 当前函数帧
     * @param args  函数入参
     */
    private void eval(GSFrame frame, ArrayList args) {
        // 调试器调用栈追踪：当前帧入栈，方法退出时（含异常传播）出栈
        callStack.push(frame);
        try {
            while (!frame.isEvalComplete()) {
                // 获取当前字节码（二进制格式：byte[]，code[0]=opcode）
                byte[] codes = frame.getCode();
                // 自增程序计数器
                frame.incrIP();
                // 操作码
                byte opcode = codes[0];
                // 调试器挂起检查（命中断点/单步/暂停请求时阻塞，直至 DAP 线程唤醒）
                // 本地变量捕获避免 check-then-act 竞态（attach disconnect 时 DAP 线程可能置 null）
                DebugController dc = this.debugController;
                if (dc != null) {
                    dc.suspendCheck(frame, callStack.size());
                }
                try {
                    switch (opcode) {
                        // ===== const_* =====
                        case GSClassConstants.OP_CONST_A: {  // const a <name>  从域中加载变量到栈顶
                            int cpIdx = readU2(codes, 1);
                            String name = (String) frame.function.constantPool[cpIdx];
                            stack.push(frame.function.getVariableFromScope(name));
                            break;
                        }
                        case GSClassConstants.OP_CONST_I: {  // const i <value> 加载整数到栈顶
                            int cpIdx = readU2(codes, 1);
                            stack.push(new GSInt(((Integer) frame.function.constantPool[cpIdx]).intValue()));
                            break;
                        }
                        case GSClassConstants.OP_CONST_F: {  // const f <value> 加载浮点数到栈顶
                            int cpIdx = readU2(codes, 1);
                            stack.push(new GSFloat(((Float) frame.function.constantPool[cpIdx]).floatValue()));
                            break;
                        }
                        case GSClassConstants.OP_CONST_S: {  // const s <value> 加载字符串到栈顶
                            int cpIdx = readU2(codes, 1);
                            stack.push(new GSString((String) frame.function.constantPool[cpIdx]));
                            break;
                        }
                        case GSClassConstants.OP_CONST_B: {  // const b <value> 加载布尔型到栈顶
                            int cpIdx = readU2(codes, 1);
                            stack.push(GSBool.getGSBool(((Boolean) frame.function.constantPool[cpIdx]).booleanValue()));
                            break;
                        }
                        // ===== 算术运算 =====
                        case GSClassConstants.OP_ARITH_OP: {
                            byte sub = codes[1];
                            GSValue v2 = (GSValue) stack.pop();
                            if (sub == GSClassConstants.ARITH_NEG) {
                                stack.push(GSValue.neg(v2));
                            } else {
                                GSValue v1 = (GSValue) stack.pop();
                                switch (sub) {
                                    case GSClassConstants.ARITH_PLUS:
                                        stack.push(GSValue.plus(v1, v2));
                                        break;
                                    case GSClassConstants.ARITH_MINUS:
                                        stack.push(GSValue.minus(v1, v2));
                                        break;
                                    case GSClassConstants.ARITH_MUL:
                                        stack.push(GSValue.mul(v1, v2));
                                        break;
                                    case GSClassConstants.ARITH_DIV:
                                        stack.push(GSValue.div(v1, v2));
                                        break;
                                    case GSClassConstants.ARITH_MODULO:
                                        stack.push(GSValue.modulo(v1, v2));
                                        break;
                                    case GSClassConstants.ARITH_LS:
                                        stack.push(GSValue.ls(v1, v2));
                                        break;
                                    case GSClassConstants.ARITH_RS:
                                        stack.push(GSValue.rs(v1, v2));
                                        break;
                                    default:
                                        throw new Error("VMError: the virtual machine does not support this bytecode");
                                }
                            }
                            break;
                        }
                        // ===== 字段访问 =====
                        case GSClassConstants.OP_GETFIELD: {
                            String name = ((GSValue) stack.pop()).toStringValue();
                            GSValue objRef = (GSValue) stack.pop();
                            if (objRef.type <= 3) {
                                stack.push(GSNull.NULL);
                            } else if (objRef.type == 8) {
                                throw new GSException(frame, frame.getIP() - 1, new GSString("TypeError: Cannot read properties of null"));
                            } else {
                                GSObject object = (GSObject) objRef;
                                stack.push(object.getProperty(name));
                            }
                            break;
                        }
                        case GSClassConstants.OP_PUTFIELD: {
                            GSValue objValue = (GSValue) stack.pop();
                            String name = ((GSValue) stack.pop()).toStringValue();
                            GSValue objRef = (GSValue) stack.pop();
                            if (objRef.type > 3) {
                                if (objRef.type != 8) {
                                    GSObject object = (GSObject) objRef;
                                    object.setProperty(name, objValue);
                                } else {
                                    throw new GSException(frame, frame.getIP() - 1, new GSString("TypeError: Cannot set properties of null."));
                                }
                            }
                            stack.push(objValue);
                            break;
                        }
                        // ===== 栈操作 =====
                        case GSClassConstants.OP_COPY: {
                            stack.push((GSValue) stack.peek());
                            break;
                        }
                        case GSClassConstants.OP_COPY2: {
                            GSValue value2 = (GSValue) stack.pop();
                            GSValue value1 = (GSValue) stack.pop();
                            stack.push(value1);
                            stack.push(value2);
                            stack.push(value1);
                            stack.push(value2);
                            break;
                        }
                        case GSClassConstants.OP_SWAP: {
                            GSValue v1 = (GSValue) stack.pop();
                            GSValue v2 = (GSValue) stack.pop();
                            stack.push(v1);
                            stack.push(v2);
                            break;
                        }
                        case GSClassConstants.OP_POP: {
                            stack.pop();
                            break;
                        }
                        // ===== 比较运算 =====
                        case GSClassConstants.OP_COMP: {
                            byte sub = codes[1];
                            GSValue v2 = (GSValue) stack.pop();
                            GSValue v1 = (GSValue) stack.pop();
                            switch (sub) {
                                case GSClassConstants.COMP_EQ:
                                    stack.push(GSBool.getGSBool(GSValue.eq(v1, v2)));
                                    break;
                                case GSClassConstants.COMP_NEQ:
                                    stack.push(GSBool.getGSBool(!GSValue.eq(v1, v2)));
                                    break;
                                case GSClassConstants.COMP_SEQ:
                                    stack.push(GSBool.getGSBool(GSValue.seq(v1, v2)));
                                    break;
                                case GSClassConstants.COMP_SNEQ:
                                    stack.push(GSBool.getGSBool(!GSValue.seq(v1, v2)));
                                    break;
                                case GSClassConstants.COMP_GT:
                                    stack.push(GSBool.getGSBool(GSValue.gt(v1, v2)));
                                    break;
                                case GSClassConstants.COMP_GE:
                                    stack.push(GSBool.getGSBool(GSValue.ge(v1, v2)));
                                    break;
                                case GSClassConstants.COMP_LT:
                                    stack.push(GSBool.getGSBool(GSValue.lt(v1, v2)));
                                    break;
                                case GSClassConstants.COMP_LE:
                                    stack.push(GSBool.getGSBool(GSValue.le(v1, v2)));
                                    break;
                                default:
                                    throw new Error("VMError: the virtual machine does not support this bytecode");
                            }
                            break;
                        }
                        // ===== 变量声明与赋值 =====
                        case GSClassConstants.OP_DECLARE: {
                            int cpIdx = readU2(codes, 1);
                            String name = (String) frame.function.constantPool[cpIdx];
                            frame.function.declareVariableToScope(name);
                            break;
                        }
                        case GSClassConstants.OP_STORE: {
                            int cpIdx = readU2(codes, 1);
                            String name = (String) frame.function.constantPool[cpIdx];
                            GSValue value = (GSValue) stack.pop();
                            frame.function.setVariableToScope(name, value);
                            break;
                        }
                        // ===== 自增自减 =====
                        case GSClassConstants.OP_INCR: {
                            GSValue v1 = (GSValue) stack.pop();
                            stack.push(v1.incr());
                            break;
                        }
                        case GSClassConstants.OP_DECR: {
                            GSValue v1 = (GSValue) stack.pop();
                            stack.push(v1.decr());
                            break;
                        }
                        // ===== 跳转指令 =====
                        case GSClassConstants.OP_JUMP: {
                            short offset = readS2(codes, 1);
                            int ip = frame.getIP() + offset - 1;
                            frame.setIp(ip);
                            break;
                        }
                        case GSClassConstants.OP_FALSE_JUMP: {
                            GSValue value = (GSValue) stack.pop();
                            short offset = readS2(codes, 1);
                            if (!value.toBoolean()) {
                                int ip = frame.getIP() + offset - 1;
                                frame.setIp(ip);
                            }
                            break;
                        }
                        case GSClassConstants.OP_LOOP_JUMP: {
                            frame.function.freeToSpecScope("loop");
                            short offset = readS2(codes, 1);
                            int ip = frame.getIP() + offset - 1;
                            frame.setIp(ip);
                            break;
                        }
                        case GSClassConstants.OP_BLOCK_JUMP: {
                            frame.function.freeToSpecScope("block");
                            short offset = readS2(codes, 1);
                            int ip = frame.getIP() + offset - 1;
                            frame.setIp(ip);
                            break;
                        }
                        // ===== 特殊常量 =====
                        case GSClassConstants.OP_LDA_NULL: {
                            stack.push(GSNull.NULL);
                            break;
                        }
                        case GSClassConstants.OP_LDA_NAN: {
                            stack.push(GSNaN.NAN);
                            break;
                        }
                        // ===== 域操作 =====
                        case GSClassConstants.OP_PUSHENV: {
                            byte sub = codes[1];
                            String type = (String) GSClassConstants.PUSHENV_SUB_REV.get(new Byte(sub));
                            String name = frame.function.name;
                            GSEnv env = frame.function.addEnv(type);
                            // 创建函数域的时候，如果不是匿名函数，把函数本身加入到域里面
                            if (sub == GSClassConstants.PUSHENV_FUNCTION) {
                                // 隐式入参this
                                env.addVariableValue("this", (GSValue) args.get(0));
                                // 入参函数名指向函数本身
                                if (!"null".equals(name)) {
                                    env.addVariableValue(name, frame.function);
                                }
                            }
                            frame.function.setEnv(env);
                            break;
                        }
                        case GSClassConstants.OP_POPENV: {
                            byte sub = codes[1];
                            String name = (String) GSClassConstants.POPENV_SUB_REV.get(new Byte(sub));
                            frame.function.freeToSpecScope(name);
                            break;
                        }
                        // ===== 关系运算 =====
                        case GSClassConstants.OP_RELA_OP: {
                            byte sub = codes[1];
                            GSValue v2 = (GSValue) stack.pop();
                            switch (sub) {
                                case GSClassConstants.RELA_B_AND: {
                                    GSValue v1 = (GSValue) stack.pop();
                                    stack.push(GSValue.b_and(v1, v2));
                                    break;
                                }
                                case GSClassConstants.RELA_B_OR: {
                                    GSValue v1 = (GSValue) stack.pop();
                                    stack.push(GSValue.b_or(v1, v2));
                                    break;
                                }
                                case GSClassConstants.RELA_B_XOR: {
                                    GSValue v1 = (GSValue) stack.pop();
                                    stack.push(GSValue.b_xor(v1, v2));
                                    break;
                                }
                                case GSClassConstants.RELA_B_NOT: {
                                    stack.push(GSValue.b_not(v2));
                                    break;
                                }
                                case GSClassConstants.RELA_L_NOT: {
                                    stack.push(GSValue.l_not(v2));
                                    break;
                                }
                                default:
                                    throw new Error("VMError: the virtual machine does not support this bytecode");
                            }
                            break;
                        }
                        // ===== 函数定义与调用 =====
                        case GSClassConstants.OP_FUNDEF: {
                            int cpIdx = readU2(codes, 1);
                            String name = (String) frame.function.constantPool[cpIdx];
                            int len = readU2(codes, 3);
                            int ip = frame.getIP();
                            GSEnv env = frame.function.getEnv();
                            byte[][] src = new byte[len][];
                            System.arraycopy(frame.function.src, ip, src, 0, len);
                            // 子函数共享父函数的 constantPool 引用（同文件函数共享 CP）
                            GSFunction function = new GSFunction(name, src, frame.function.constantPool, env);
                            // 调试器源码映射：子函数共享顶级 sourceLines 数组（不切片），
                            // baseOffset = 父函数.baseOffset + 切片起始IP，使任意帧 IP 可映射回源码行
                            function.sourceLines = frame.function.sourceLines;
                            function.sourcePath = frame.function.sourcePath;
                            function.sourceContent = frame.function.sourceContent;
                            function.baseOffset = frame.function.baseOffset + ip;
                            stack.push(function);
                            // 加载定义函数后要移动程序计数器
                            frame.setIp(ip + len);
                            break;
                        }
                        case GSClassConstants.OP_FSTORE: {
                            int cpIdx = readU2(codes, 1);
                            String name = (String) frame.function.constantPool[cpIdx];
                            int argIdx = readU2(codes, 3);
                            GSValue value;
                            // 传来的参数可能为空，也就是调用的时候少传
                            if (argIdx < args.size()) {
                                value = (GSValue) args.get(argIdx);
                            } else {
                                value = GSNull.NULL;
                            }
                            // 关联变量值到当前域
                            frame.function.assignmentVariableToScope(name, value);
                            break;
                        }
                        case GSClassConstants.OP_INVOKE: {
                            int argCount = readU2(codes, 1);
                            ArrayList callArgs = new ArrayList();
                            // 预占位this
                            callArgs.add(null);
                            for (int i = 0; i < argCount; i++) {
                                callArgs.add(stack.pop());
                            }
                            GSValue methodRef = (GSValue) stack.pop();
                            GSValue objectRef = (GSValue) stack.pop();
                            // 设置this传参数
                            callArgs.set(0, objectRef);
                            if (methodRef.type == 6) {  // 普通函数（普通函数执行后会往栈顶放值）
                                GSFunction method = (GSFunction) methodRef;
                                GSFrame newFrame = new GSFrame(method);
                                try {
                                    eval(newFrame, callArgs);
                                } catch (GSException ex) {
                                    // 异常从被调用函数传播上来：必须用调用者 invoke 指令位置重定位 ip，
                                    // 否则调用者 handleException 会用 callee 的 ip 空间比对 caller 的 monitor 范围，
                                    // 导致跨函数抛出的异常无法被调用者的 try/catch 捕获
                                    ex.setIp(frame.getIP() - 1);
                                    ex.appendCaller(frame);  // 追加 caller 帧到调用栈快照（供顶层打印）
                                    throw ex;
                                }
                            } else if (methodRef.type == 9) {  // 本地函数（本地函数需要手动放值）
                                GSNativeFunction nativeFunction = (GSNativeFunction) methodRef;
                                GSValue r;
                                try {
                                    r = nativeFunction.eval(callArgs);
                                } catch (GSException ex) {
                                    // 本地函数抛出异常（如 "x".repeat(-1) 抛 RangeError）：
                                    // 必须 rebase ip 到调用者 invoke 指令位置，否则 handleException
                                    // 会用异常自带的 ip（<native>,0）比对 caller 的 monitor 范围，
                                    // 导致 try-catch 无法捕获本地函数抛出的异常（与 type==6 分支同构）
                                    ex.setIp(frame.getIP() - 1);
                                    ex.appendCaller(frame);
                                    throw ex;
                                }
                                stack.push(r);
                            } else {  // 函数引用为空
                                throw new GSException(frame, frame.getIP() - 1, new GSString("TypeError: function not exist."));
                            }
                            break;
                        }
                        case GSClassConstants.OP_CONSTRUCTOR: {
                            // 默认对象
                            GSObject object = new GSObject();
                            int argCount = readU2(codes, 1);
                            ArrayList callArgs = new ArrayList();
                            // 预占位this
                            callArgs.add(null);
                            for (int i = 0; i < argCount; i++) {
                                callArgs.add(stack.pop());
                            }
                            GSValue methodRef = (GSValue) stack.pop();
                            // 出栈未使用的默认this
                            stack.pop();
                            // 设置this传参数
                            callArgs.set(0, object);
                            if (methodRef.type == 6) {  // 普通函数（普通函数执行后会往栈顶放值）
                                GSFunction method = (GSFunction) methodRef;
                                GSFrame newFrame = new GSFrame(method);
                                try {
                                    eval(newFrame, callArgs);
                                } catch (GSException ex) {
                                    // 异常从被调用函数传播上来：用调用者指令位置重定位 ip（同 invoke）
                                    ex.setIp(frame.getIP() - 1);
                                    ex.appendCaller(frame);  // 追加 caller 帧到调用栈快照（供顶层打印）
                                    throw ex;
                                }
                                // 这里需要取栈顶的数据，看看函数执行完成以后是不是一个对象，如果是，返回函数返回的对象
                                GSValue r = (GSValue) stack.pop();
                                if (r.type >= 4 && r.type != 8) {
                                    object = (GSObject) r;
                                }
                                // 否则返回默认对象
                                stack.push(object);
                            } else if (methodRef.type == 9) {  // 本地函数（本地函数需要手动放值）
                                GSNativeFunction nativeFunction = (GSNativeFunction) methodRef;
                                GSValue r = nativeFunction.eval(callArgs);
                                // 看构造函数是不是返回了对象
                                if (r.type >= 4) {
                                    object = (GSObject) r;
                                }
                                // 否则返回默认对象
                                stack.push(object);
                            } else {
                                throw new GSException(frame, frame.getIP() - 1, new GSString("TypeError: constructor function not exist."));
                            }
                            break;
                        }
                        case GSClassConstants.OP_RETURN: {
                            int retIp = frame.getIP() - 1;
                            GSExceptionMonitor finMonitor = frame.findReturnFinallyTarget(retIp);
                            if (finMonitor != null) {
                                // return 位于带 finally 的 try/catch 块内：暂存返回值，跳到 finally 执行，
                                // 待 finally_check 时再完成真正的返回（JS 语义：finally 必须执行）
                                frame.pendingReturnValue = (GSValue) stack.pop();
                                frame.function.returnSpecScope("function");
                                frame.setIp(finMonitor.finallyStart);
                                break;
                            }
                            // 正常返回：返回值留在共享栈上由调用者读取
                            frame.destroy();
                            return;
                        }
                        // ===== 对象创建 =====
                        case GSClassConstants.OP_NEW: {
                            byte sub = codes[1];
                            if (sub == GSClassConstants.NEW_OBJECT) {
                                stack.push(new GSObject());
                            } else if (sub == GSClassConstants.NEW_ARRAY) {
                                stack.push(new GSArray());
                            } else {
                                // TODO 抛出异常
                            }
                            break;
                        }
                        // ===== 异常处理 =====
                        case GSClassConstants.OP_THROW: {
                            GSValue origin = (GSValue) stack.pop();
                            throw new GSException(frame, frame.getIP() - 1, origin);
                        }
                        case GSClassConstants.OP_TRY_START: {
                            // 往当前frame的异常监视表里面增加监视
                            int tryStart = readS2(codes, 1);
                            int tryEnd = readS2(codes, 3);
                            int catchStart = readS2(codes, 5);
                            int finallyStart = readS2(codes, 7);
                            frame.addGSExceptionMonitor(tryStart, tryEnd, catchStart, finallyStart);
                            break;
                        }
                        case GSClassConstants.OP_TRY_END: {
                            // 销毁当前监视表的当前异常监视
                            frame.tryEndCheck();
                            break;
                        }
                        case GSClassConstants.OP_FINALLY_CHECK: {
                            int checkIp = frame.getIP() - 1;
                            // 检测是否向上抛出异常（finallyCheck 内部 pop monitor，若 throwException != null 则抛出）
                            frame.finallyCheck();
                            // 若有待处理的 return（try/catch 内 return 跳 finally 的情况），完成返回
                            if (frame.pendingReturnValue != null) {
                                // 看是否还有外层 finally 需要执行（嵌套 finally 链）
                                GSExceptionMonitor outer = frame.findReturnFinallyTarget(checkIp);
                                if (outer != null) {
                                    // 跳到外层 finally 继续执行（保留 pendingReturnValue）
                                    frame.function.returnSpecScope("function");
                                    frame.setIp(outer.finallyStart);
                                    break;
                                }
                                // 没有外层 finally，真正返回：把返回值压回共享栈
                                GSValue ret = frame.pendingReturnValue;
                                frame.pendingReturnValue = null;
                                stack.push(ret);
                                frame.destroy();
                                return;
                            }
                            break;
                        }
                        case GSClassConstants.OP_NOP:
                            break;
                        default: {  // 不支持的字节码：抛出虚拟机错误，避免静默忽略导致后续状态错乱
                            throw new Error("VMError: unknown opcode 0x" + Integer.toHexString(opcode & 0xFF)
                                    + " in function " + frame.function.name + " at ip " + (frame.getIP() - 1));
                        }
                    }
                } catch (GSException e) {  // 如果是包装好的异常
                    // 异常断点：若开启 pauseOnException，在异常抛出时挂起（S7 接线）
                    // checkException 内部会阻塞等待 DAP 线程唤醒，唤醒后继续正常异常处理
                    if (debugController != null) {
                        debugController.checkException(frame, callStack.size(), e);
                    }
                    // 进行异常处理，异常可能抛到上一个frame
                    GSValue value = frame.handleException(e.getIp(), e);
                    // 这里判断null的原因是如果是try或者catch转finally的话，是不需要往栈顶放异常对象
                    if (value != null) {
                        // 设置当前异常对象到栈顶
                        stack.push(value);
                    }
                } catch (Exception e) {  // 如果是运行时异常（虚拟机异常）
                    // 调试器中止异常向上传播，不当作虚拟机异常处理
                    if (e instanceof DebugAbortException) {
                        throw (DebugAbortException) e;
                    }
                    e.printStackTrace();
                    // 包装异常对象并再处理异常对象
                    GSValue origin = new GSString(e.getMessage());
                    int ip = frame.getIP() - 1;
                    GSException exception = new GSException(frame, ip, origin);
                    // 进行异常处理，异常可能抛到上一个frame
                    GSValue value = frame.handleException(ip, exception);
                    if (value != null) {
                        // 设置当前异常对象到栈顶
                        stack.push(value);
                    }
                }
            }
        } finally {
            // 调试器调用栈追踪：当前帧出栈（无论正常返回还是异常传播）
            callStack.pop();
        }
    }

    /**
     * 执行字节码（二进制格式，带源码映射与文件路径，供调试器使用）。
     *
     * <p>创建顶级匿名函数并执行。源码映射数组 {@code sourceLines} 与 {@code src} 平行，
     * 存入匿名函数的 {@link org.gscript.vm.value.GSFunction#sourceLines} 字段；
     * {@code sourcePath} 存入 {@link org.gscript.vm.value.GSFunction#sourcePath}，
     * 供 {@link DebugController} 区分断点所属文件与 stackTrace 报告源文件。
     *
     * <p>多文件调试时，对同一 {@code GSInterpreter} 实例多次调用本方法，每次传入不同文件，
     * 共享 global env——后加载文件定义同名函数会覆盖先加载的（"后面覆盖前面"语义）。
     *
     * @param codes         二进制字节码（byte[][]，每条指令为 byte[]，code[0]=opcode）
     * @param constantPool  常量池（Object[]，索引从 1 开始，0 不用）
     * @param sourceLines   字节码索引对应的源码行号数组（与 codes 平行，1-based，0=未设置），可为 null
     * @param sourcePath    源文件路径（调试用，区分多文件），可为 null
     */
    public void eval(byte[][] codes, Object[] constantPool, int[] sourceLines, String sourcePath) {
        eval(codes, constantPool, sourceLines, sourcePath, null);
    }

    /**
     * 执行字节码（二进制格式，带源码映射、文件路径与源码内容）。
     *
     * <p>attach 调试模式下，sourceContent 随函数继承，供 DAP source 请求返回。
     * 详见 {@link #eval(byte[][], Object[], int[], String)} 的多文件说明。
     *
     * @param codes         二进制字节码（byte[][]，每条指令为 byte[]，code[0]=opcode）
     * @param constantPool  常量池（Object[]，索引从 1 开始，0 不用）
     * @param sourceLines   字节码索引对应的源码行号数组（与 codes 平行，1-based，0=未设置），可为 null
     * @param sourcePath    源文件路径（调试用，区分多文件），可为 null
     * @param sourceContent 完整源码文本（attach 调试模式用，可为 null）
     */
    public void eval(final byte[][] codes, final Object[] constantPool, final int[] sourceLines,
                     final String sourcePath, final String sourceContent) {
        if (Thread.currentThread() == workerThread) {
            evalDirect(codes, constantPool, sourceLines, sourcePath, sourceContent);
            return;
        }
        try {
            submitAndAwait(new RunnableEvalTask(new TaskRunnable() {
                public void run() throws Throwable {
                    evalDirect(codes, constantPool, sourceLines, sourcePath, sourceContent);
                }
            }));
        } catch (RuntimeException e) { throw e; }
        catch (Throwable e) { throw new RuntimeException(e); }
    }

    /**
     * 直接执行字节码（worker 线程内调用，无队列包装）。
     *
     * <p>调试模式入口预处理 + 构造匿名 GSFunction + eval(frame, null)。
     * DebugAbortException/GSException 在内部 catch（同原实现），不传播给调用方。
     */
    private void evalDirect(byte[][] codes, Object[] constantPool, int[] sourceLines,
                            String sourcePath, String sourceContent) {
        // 调试模式入口预处理：注册 source + 请求 entry stop（controller 已就位时）
        if (debugMode) {
            prepareDebugEntry(sourcePath, sourceContent);
        }
        GSFunction anonymous = new GSFunction("null", codes, constantPool, global);
        anonymous.sourceLines = sourceLines;
        anonymous.sourcePath = sourcePath;
        anonymous.sourceContent = sourceContent;
        anonymous.baseOffset = 0;
        GSFrame frame = new GSFrame(anonymous);
        try {
            eval(frame, null);
        } catch (DebugAbortException e) {
            // 调试会话被终止，正常退出，不输出未捕获异常信息
        } catch (GSException e) {
            System.out.println(e.formatMessage());
        }
    }

    /**
     * 执行字节码（文本 1D 格式，带源码映射与文件路径）。
     *
     * <p>内部用 {@link BytecodeEncoder} 将文本字节码编码为二进制内存表示后，
     * 委托给 {@link #eval(byte[][], Object[], int[], String)} 执行。
     *
     * @param src         文本字节码（如 "const s hello world"、"arith_op plus"）
     * @param sourceLines 字节码索引对应的源码行号数组（与 src 平行，1-based，0=未设置），可为 null
     * @param sourcePath  源文件路径（调试用，区分多文件），可为 null
     */
    public void eval(String[] src, int[] sourceLines, String sourcePath) {
        BytecodeEncoder encoder = new BytecodeEncoder();
        EncodedBytecode encoded = encoder.encode(Arrays.asList(src));
        eval(encoded.instructions, encoded.constantPool, sourceLines, sourcePath);
    }

    /**
     * 执行字节码（带源码映射，文件路径为 null，兼容单文件/非多文件调试场景）。
     *
     * @param src         字节码
     * @param sourceLines 字节码索引对应的源码行号数组，可为 null
     */
    public void eval(String[] src, int[] sourceLines) {
        eval(src, sourceLines, null);
    }

    /**
     * 执行字节码（无源码映射，非调试模式入口）。
     *
     * @param src 字节码
     */
    public void eval(String[] src) {
        eval(src, null, null);
    }

    /**
     * 增加变量到全局域
     */
    public void addVariableToGlobal(String name, GSValue value) {
        global.addVariableValue(name, value);
    }

    /**
     * 编译 gscript 源码为二进制字节码（供 evalScript/evalExpression 共用）。
     *
     * @param code gscript 源码
     * @return 编码后的二进制字节码 + 常量池
     */
    private EncodedBytecode compile(String code) {
        Lexer lexer = new Lexer();
        List tokens = lexer.tokenize(code);
        Parser parser = new Parser(tokens);
        Node program = parser.parseProgram();
        ByteCodeGenerator gen = new ByteCodeGenerator();
        program.accept(gen);
        String[] src = (String[]) gen.getByteCode().toArray(new String[0]);
        BytecodeEncoder encoder = new BytecodeEncoder();
        EncodedBytecode encoded = encoder.encode(Arrays.asList(src));
        // 保留 sourceLines 供异常映射（非调试模式也能输出源码行号）
        ArrayList srcLineList = gen.getSourceLines();
        if (srcLineList != null && !srcLineList.isEmpty()) {
            int[] sourceLines = new int[srcLineList.size()];
            for (int i = 0; i < srcLineList.size(); i++) {
                sourceLines[i] = ((Integer) srcLineList.get(i)).intValue();
            }
            encoded.sourceLines = sourceLines;
        }
        return encoded;
    }

    /**
     * 在当前解释器全局上下文中执行一段 gscript 源码（语句序列）。
     *
     * <p>共享 {@link #global} 环境：执行的 var 声明、function 定义会落入 global，
     * 后续 {@link #getVariable} / {@link #evalExpression} 可访问。
     * 脚本未捕获异常会被捕获并打印（与 {@link #eval(byte[][], Object[], int[], String)} 行为一致）。
     *
     * @param code gscript 源码
     */
    public void evalScript(final String code) {
        if (Thread.currentThread() == workerThread) {
            evalScriptDirect(code);
            return;
        }
        try {
            submitAndAwait(new RunnableEvalTask(new TaskRunnable() {
                public void run() throws Throwable { evalScriptDirect(code); }
            }));
        } catch (RuntimeException e) { throw e; }
        catch (Throwable e) { throw new RuntimeException(e); }
    }

    /**
     * 直接执行脚本（worker 线程内调用，无队列包装）。
     *
     * <p>调试模式用 compileScriptContent 生成 sourcePath + sourceLines + sourceContent，
     * 5-arg eval 内部会调 prepareDebugEntry 注册 source + 请求 entry stop。
     * 非调试模式 compile 保留 sourceLines 供异常映射（无 sourcePath，显示 &lt;anonymous&gt;）。
     */
    private void evalScriptDirect(String code) {
        if (debugMode) {
            // 调试模式：用 compileScriptContent 生成 sourcePath + sourceLines + sourceContent
            // 5-arg eval 内部会调 prepareDebugEntry 注册 source + 请求 entry stop
            String path = generateEvalPath();
            GSClassData data = compileScriptContent(code, path);
            eval(data.src, data.constantPool, data.sourceLines,
                 data.sourcePath, data.sourceContent);
        } else {
            // 非调试模式：compile 保留 sourceLines 供异常映射（无 sourcePath，显示 <anonymous>）
            EncodedBytecode encoded = compile(code);
            eval(encoded.instructions, encoded.constantPool, encoded.sourceLines, null);
        }
    }

    /**
     * 求值一个 gscript 表达式并返回结果（双路径：worker 内直接执行，外部线程提交任务）。
     *
     * <p>worker 线程内调用（如定时器回调内、eval 内部 OP_INVOKE 等）：直接执行
     * {@link #evalExpressionDirect}，无队列开销。
     *
     * <p>外部线程调用（宿主业务线程）：包装为 CallableEvalTask 提交到 {@link #taskQueue}，
     * 阻塞 await 等 worker 执行完成，返回结果。
     *
     * <p>实现：将表达式包装为 {@code return (expr);} 执行，OP_RETURN 会把结果留在
     * {@link #stack} 上，执行后弹出返回。表达式出错（语法/运行时）时返回 {@link GSNull#NULL}
     * （类 JS eval 语义，{@link #evalExpressionDirect} 内 catch 吞掉异常不透传，
     * 与 {@link #callFunction} 透传异常给宿主的语义不同）。
     *
     * <p>注意：不能复用 {@link #evalScript}（它内部 eval 会吞掉 GSException），
     * 故直接调用 {@link #eval(GSFrame, ArrayList)} 以检测异常。
     *
     * @param expr gscript 表达式（如 "a + b"、"add(1, 2)"、"{x: 1, y: 2}"）
     * @return 求值结果，出错返回 GSNull.NULL
     */
    public GSValue evalExpression(final String expr) {
        if (Thread.currentThread() == workerThread) {
            return evalExpressionDirect(expr);
        }
        try {
            CallableEvalTask task = new CallableEvalTask(new TaskCallable() {
                public GSValue call() throws Throwable { return evalExpressionDirect(expr); }
            });
            submitAndAwait(task);
            return task.awaitResult();
        } catch (DebugAbortException e) {
            return GSNull.NULL;  // 防御性：direct 内已 catch，正常不触发
        } catch (GSException e) {
            System.out.println(e.formatMessage());  // 防御性：direct 内已 catch，正常不触发
            return GSNull.NULL;
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 直接求值表达式（worker 线程内调用，无队列包装）。
     *
     * <p>非调试模式：compile 保留 sourceLines 供异常映射（无 sourcePath，显示 &lt;anonymous&gt;）。
     * 调试模式：用 compileScriptContent 生成 sourcePath + sourceLines + sourceContent，
     * prepareDebugEntry 注册 source + 请求 entry stop。
     *
     * <p>异常策略：GSException/DebugAbortException 在内部 catch 后返回 GSNull.NULL
     * （类 JS eval「求值失败返回 null」语义），不向上抛。
     */
    private GSValue evalExpressionDirect(String expr) {
        stack.clear();  // 清空栈上残留值，确保返回值是本次表达式的结果
        if (!debugMode) {
            // 非调试模式：compile 保留 sourceLines 供异常映射
            EncodedBytecode encoded = compile("return (" + expr + ");");
            GSFunction anonymous = new GSFunction("null", encoded.instructions, encoded.constantPool, global);
            anonymous.sourceLines = encoded.sourceLines;
            GSFrame frame = new GSFrame(anonymous);
            try {
                eval(frame, null);
            } catch (GSException e) {
                System.out.println(e.formatMessage());
                stack.clear();
                return GSNull.NULL;
            } catch (DebugAbortException e) {
                return GSNull.NULL;
            }
            if (stack.isEmpty()) return GSNull.NULL;
            return (GSValue) stack.pop();
        }
        // 调试模式：用 compileScriptContent 生成 sourcePath + sourceLines + sourceContent
        String path = generateEvalPath();
        GSClassData data = compileScriptContent("return (" + expr + ");", path);
        GSFunction anonymous = new GSFunction("null", data.src, data.constantPool, global);
        anonymous.sourceLines = data.sourceLines;
        anonymous.sourcePath = data.sourcePath;
        anonymous.sourceContent = data.sourceContent;
        anonymous.baseOffset = 0;
        GSFrame frame = new GSFrame(anonymous);
        prepareDebugEntry(data.sourcePath, data.sourceContent);  // 注册 source + 请求 entry stop
        try {
            eval(frame, null);
        } catch (GSException e) {
            System.out.println(e.formatMessage());
            stack.clear();
            return GSNull.NULL;
        } catch (DebugAbortException e) {
            return GSNull.NULL;
        }
        if (stack.isEmpty()) return GSNull.NULL;
        return (GSValue) stack.pop();
    }

    // ===== 文件/流加载入口（带源码映射，供调试器使用）=====

    /**
     * 从文件系统读取 gscript 源码文件，编译并执行。
     *
     * <p>与 {@link #evalScript(String)} 的区别：本方法保留源码行号映射和源码内容，
     * 供调试器断点/单步/源码查看使用。sourcePath 取文件名（非绝对路径），
     * 与 {@link #eval(byte[][], Object[], int[], String, String)} 的多文件约定一致。
     *
     * <p>定时器机制（setTimeout/setInterval 等）在构造器中默认初始化，无需宿主显式调用
     * installTimerGlobals()。若脚本使用了定时器且希望同步等待回调完成，调用方需在 eval
     * 返回后调用 {@link #runEventLoop()}；否则可不调用（如宿主自行管理定时器线程）。
     * 调试模式下本方法会自动触发 entry stop（断在第一行）。
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

    /**
     * 从输入流读取 gscript 源码，编译并执行。
     *
     * <p>适用于 classpath 资源、网络流、zip 条目等非文件系统场景。
     * sourcePath 由调用方指定（如 "myscript.script"），仅供调试器标识用。
     *
     * @param in         输入流（方法内会读取但不关闭，由调用方负责）
     * @param sourcePath 源码标识路径（调试用，可为 null）
     * @throws IOException 流读取失败
     * @throws Exception   编译失败
     */
    public void evalScriptStream(InputStream in, String sourcePath) throws Exception {
        byte[] raw = readStreamBytes(in);
        String content = new String(raw, "UTF-8");
        evalScriptContent(content, sourcePath);
    }

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
            try { in.close(); } catch (Exception e) { /* close 失败忽略 */ }
        }
    }

    /**
     * 从输入流读取 .gclass 二进制数据，反序列化并执行。
     *
     * @param in 输入流（方法内会读取但不关闭，由调用方负责）
     * @throws IOException 流读取失败
     * @throws Exception   反序列化或 CRC 校验失败
     */
    public void evalGclassStream(InputStream in) throws Exception {
        GSClassReader reader = new GSClassReader();
        GSClassData data = reader.deserialize(in);
        eval(data.src, data.constantPool, data.sourceLines, data.sourcePath, data.sourceContent);
    }

    // ===== 源码 → GSClassData 编译入口（不执行，供宿主预编译多文件用）=====

    /**
     * 从输入流编译 gscript 源码为 {@link GSClassData}（不执行）。
     *
     * <p>供宿主程序预先编译多个脚本，再决定执行/调试模式（launch/attach）。
     * 生成 GSClassData 含完整 sourceLines + sourceContent + sourcePath，可直接传给
     * {@link org.gscript.vm.debug.DebugAgent#addGclass(GSClassData)} 或
     * {@link #eval(byte[][], Object[], int[], String, String)}。
     *
     * <p>多文件调试场景典型用法：
     * <pre>
     * DebugAgent agent = new DebugAgent(4711);
     * agent.setInterpreter(interp);
     * for (int i = 0; i < streams.length; i++) {
     *     GSClassData data = GSInterpreter.compileScriptStream(streams[i], paths[i]);
     *     agent.addGclass(data);
     * }
     * agent.startAttachListener();
     * </pre>
     *
     * @param in         输入流（不关闭，调用方负责）
     * @param sourcePath 源码标识路径（调试用，多文件场景必须唯一，可为 null）
     * @return 编译后的 GSClassData（含 sourceContent）
     * @throws IOException 流读取失败
     * @throws Exception   编译失败（词法/语法错误）
     */
    public static GSClassData compileScriptStream(InputStream in, String sourcePath) throws Exception {
        byte[] raw = readStreamBytes(in);
        String content = new String(raw, "UTF-8");
        return compileScriptContent(content, sourcePath);
    }

    /**
     * 从源码字符串编译为 {@link GSClassData}（不执行）。
     *
     * <p>与 {@link #compile(String)} 的区别：本方法走完整 ByteCodeGenerator 流水线，
     * 保留 sourceLines + sourceContent，供调试器断点/source 请求使用；
     * {@link #compile(String)} 是 REPL 用的简化版本，不返回 sourceLines。
     *
     * @param code       gscript 源码
     * @param sourcePath 源码标识路径（调试用，可为 null）
     * @return 编译后的 GSClassData（含 sourceContent）
     * @throws RuntimeException 编译失败（词法/语法错误）
     */
    public static GSClassData compileScriptContent(String code, String sourcePath) {
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
        return new GSClassData(encoded.instructions, encoded.constantPool,
                sourceLines, sourcePath, null, code);
    }

    /**
     * 编译 gscript 源码并执行（带完整源码映射，供调试器使用）。
     *
     * <p>与 {@link #evalScript(String)} 的区别：保留 sourceLines + sourceContent + sourcePath，
     * 使调试器能正确映射断点和 source 请求。内部委托 {@link #compileScriptContent(String, String)}
     * 编译后调用 {@link #eval(byte[][], Object[], int[], String, String)} 执行。
     *
     * @param code       gscript 源码
     * @param sourcePath 源码标识路径（调试用，可为 null）
     */
    private void evalScriptContent(String code, String sourcePath) {
        GSClassData data = compileScriptContent(code, sourcePath);
        eval(data.src, data.constantPool, data.sourceLines, data.sourcePath, data.sourceContent);
    }

    /** 读取文件全部字节（1.4 兼容，替代 Java 9 Files.readAllBytes）。 */
    private static byte[] readFileBytes(File f) throws IOException {
        long len = f.length();
        int capacity = (int) Math.min(len, 8192);
        ByteArrayOutputStream bos = new ByteArrayOutputStream(capacity);
        FileInputStream in = new FileInputStream(f);
        try {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) { bos.write(buf, 0, n); }
        } finally {
            try { in.close(); } catch (Exception e) { /* close 失败忽略 */ }
        }
        return bos.toByteArray();
    }

    /** 读取流全部字节直到 EOF（1.4 兼容，替代 Java 9 InputStream.readAllBytes）。
     *  不关闭流（由调用方负责）。 */
    private static byte[] readStreamBytes(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) { bos.write(buf, 0, n); }
        return bos.toByteArray();
    }

    /**
     * 从全局作用域获取变量。
     *
     * @param name 变量名
     * @return 变量值，未定义返回 {@link GSNull#NULL}
     */
    public GSValue getVariable(String name) {
        GSValue value = global.getVariableValue(name);
        return value != null ? value : GSNull.NULL;
    }

    /**
     * 设置全局变量（自动包装 Java 对象为 GSValue）。
     *
     * @param name  变量名
     * @param value Java 对象（Integer/Float/Double/String/Boolean/Map/List/null/GSValue）
     */
    public void setVariable(String name, Object value) {
        addVariableToGlobal(name, GSValue.fromJavaObject(value));
    }

    // ===== 定时器与事件循环（worker 模型：单 worker 线程 + 守护定时器线程）=====

    /**
     * 调用 gscript 函数（双路径：worker 内直接执行，外部线程提交任务）。
     *
     * <p>worker 线程内调用（定时器回调、native 回调 gscript、eval 内部 OP_INVOKE 等）：
     * 直接执行 {@link #callFunctionDirect}，无队列开销。
     *
     * <p>外部线程调用（宿主业务线程）：包装为 CallableEvalTask 提交到 {@link #taskQueue}，
     * 阻塞 await 等 worker 执行完成，返回结果。GSException/DebugAbortException 透传给调用方。
     *
     * <p>异常处理：worker 内执行时 GSException/DebugAbortException 经 EvalTask.error 透传；
     * eval 内部走 callStack.push/pop + suspendCheck，回调里的断点/单步/变量查看与普通调用完全一致。
     *
     * @param fn   目标函数
     * @param args 参数列表（OP_INVOKE 约定：args[0]=this，args[1..]=实际参数）
     * @return 函数返回值（无 return 返回 GSNull.NULL）
     */
    public GSValue callFunction(final GSFunction fn, final ArrayList args) {
        if (Thread.currentThread() == workerThread) {
            return callFunctionDirect(fn, args);
        }
        try {
            CallableEvalTask task = new CallableEvalTask(new TaskCallable() {
                public GSValue call() throws Throwable { return callFunctionDirect(fn, args); }
            });
            submitAndAwait(task);
            return task.awaitResult();
        } catch (DebugAbortException e) {
            throw e;  // 调试终止：透传给调用方
        } catch (GSException e) {
            throw e;  // gscript 异常：透传
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 直接执行 gscript 函数（worker 线程内调用，无队列包装）。
     *
     * <p>new GSFrame → eval(frame, args)。eval 内部 callStack.push/pop + suspendCheck，
     * 断点/单步在回调里天然工作。回调无 return 时栈不增长，返回 GSNull.NULL。
     */
    private GSValue callFunctionDirect(GSFunction fn, ArrayList args) {
        GSFrame frame = new GSFrame(fn);
        int stackMark = stack.size();
        eval(frame, args);
        return stack.size() > stackMark ? (GSValue) stack.pop() : GSNull.NULL;
    }

    /** 调度一次性定时器（setTimeout），返回 timer id。worker 停止后返回 -1。 */
    public int scheduleTimeout(GSFunction cb, long delay, ArrayList args) {
        if (workerStopped || timerScheduler == null) return -1;
        ensureTimerScheduler();
        return timerScheduler.schedule(cb, delay, args);
    }

    /** 调度周期性定时器（setInterval），返回 timer id。worker 停止后返回 -1。 */
    public int scheduleInterval(GSFunction cb, long period, ArrayList args) {
        if (workerStopped || timerScheduler == null) return -1;
        ensureTimerScheduler();
        return timerScheduler.scheduleAtFixedRate(cb, period, args);
    }

    /** 取消定时器（clearTimeout/clearInterval 共用）。 */
    public void cancelTimer(int id) {
        if (timerScheduler != null) {
            timerScheduler.cancel(id);
        }
    }

    private void ensureTimerScheduler() {
        if (timerScheduler == null) {
            timerScheduler = new TimerScheduler(this);  // this 作为 TaskDispatcher
        }
    }

    /**
     * TaskDispatcher 实现：TimerScheduler 到期任务投递到 worker 的 taskQueue。
     *
     * <p>TimerScheduler 守护线程在 synchronized(lock) 内调用本方法（保留原 readyQueue.offer
     * 的窗口期修复语义）。本方法把 TimerTask 包装成 TimerEvalTask offer 到 taskQueue，
     * worker 主循环取出后执行 callFunctionDirect。
     */
    public void dispatch(TimerScheduler.TimerTask task) {
        taskQueue.offer(new TimerEvalTask(task));
    }

    /**
     * 启动 worker 线程（构造器调用）。
     *
     * <p>worker 是守护线程，JVM 退出时自动终止。常驻，独占解释器执行权。
     * 启动后立即 taskQueue.take 阻塞（构造后队列必空），等待外部提交或定时器到期。
     */
    private void startWorker() {
        workerThread = new Thread(new Runnable() {
            public void run() {
                runWorkerLoop();
            }
        }, "gscript-worker");
        workerThread.setDaemon(true);
        workerThread.start();
    }

    /**
     * worker 主循环：消费 taskQueue，串行执行 EvalTask。
     *
     * <p>等待策略：
     * <ul>
     *   <li>无定时器（nextDelayMs==-1）：taskQueue.take 无限阻塞等外部任务</li>
     *   <li>有定时器（nextDelayMs&gt;=0）：taskQueue.poll(delay) 等 delay 毫秒，
     *       超时表示定时器到点（TimerScheduler 已 dispatch TimerEvalTask 到 taskQueue），
     *       下一轮立即取到</li>
     * </ul>
     *
     * <p>异常策略：
     * <ul>
     *   <li>任务内异常：EvalTask.execute catch 存 error，外部 await 重抛；worker 循环不退出</li>
     *   <li>定时器任务的 DebugAbortException：worker 停止 + scheduler.shutdown + drain（对齐原 runEventLoop）</li>
     *   <li>InterruptedException：workerStopped 则退出，否则 continue</li>
     * </ul>
     */
    private void runWorkerLoop() {
        while (!workerStopped) {
            long delay = (timerScheduler != null) ? timerScheduler.nextDelayMs() : -1L;
            EvalTask task = null;
            try {
                if (delay < 0) {
                    task = (EvalTask) taskQueue.take();
                } else {
                    task = (EvalTask) taskQueue.poll(delay);
                }
            } catch (InterruptedException e) {
                if (workerStopped) break;
                continue;  // spurious wake
            }
            if (task == null) {
                // poll 超时：定时器到点，TimerScheduler 应已 dispatch 到 taskQueue，下一轮立即取到
                continue;
            }
            workerBusy = true;
            try {
                task.execute();
            } finally {
                workerBusy = false;
            }
            // 定时器任务的 DebugAbortException：停止 worker（对齐原 runEventLoop 行为）
            if (task instanceof TimerEvalTask) {
                Throwable err = task.getError();
                if (err instanceof DebugAbortException) {
                    workerStopped = true;
                    if (timerScheduler != null) timerScheduler.shutdown();
                    drainTaskQueue();
                    break;
                }
            }
        }
    }

    /**
     * 停止时排空 taskQueue：剩余 EvalTask 全部 signalError "worker stopped"，
     * 避免外部线程永久阻塞在 await。
     */
    private void drainTaskQueue() {
        RuntimeException stoppedErr = new RuntimeException("gscript-worker stopped");
        while (true) {
            EvalTask t = (EvalTask) taskQueue.poll();
            if (t == null) break;
            t.signalError(stoppedErr);
        }
    }

    /**
     * 外部线程入口：提交任务到 taskQueue，阻塞等结果。
     * worker 停止后抛 RuntimeException。
     */
    private void submitAndAwait(EvalTask task) throws Throwable {
        if (workerStopped) {
            throw new RuntimeException("gscript-worker stopped");
        }
        taskQueue.offer(task);
        task.await();
    }

    /**
     * 事件循环新语义（awaitIdle）：外部线程等待 worker 排空 taskQueue + 无 pending 定时器。
     *
     * <p>等待条件：!workerBusy && taskQueue.isEmpty() && !timerScheduler.hasPending()
     * <p>worker 自己调用直接返回（死锁保护——脚本内部不能 pump 自己）。
     *
     * <p>10ms 轮询（避免 worker 内部状态锁竞争），精度对 CLI/调试场景足够。
     *
     * <p>退出场景：
     * <ul>
     *   <li>纯 setTimeout 脚本：所有回调执行完后 taskQueue 空 + hasPending=false → 返回</li>
     *   <li>纯 setInterval 脚本：hasPending 永远 true → 永不返回（需 DAP terminate 触发
     *       DebugAbortException → worker 停止 → workerStopped=true → 本方法返回）</li>
     *   <li>workerStopped（shutdown/terminate）：直接返回</li>
     * </ul>
     *
     * <p>调用点零改动：TestScript.gen / debugAttach / DebugAgent launch / DapServer launch
     * 的 runEventLoop 调用语义从"主线程 pump readyQueue"升级为"阻塞等 worker 排空"。
     * 定时器回调由 worker 后台执行，主线程不直接 pump；本方法仅用于宿主需同步等待
     * 回调完成的场景（如 CLI 工具、launch 模式发 terminated 前）。
     */
    public void runEventLoop() {
        if (Thread.currentThread() == workerThread) {
            return;  // 死锁保护：worker 内部不应调用
        }
        while (!workerStopped) {
            if (!workerBusy
                    && taskQueue.isEmpty()
                    && (timerScheduler == null || !timerScheduler.hasPending())) {
                return;
            }
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        // workerStopped：直接返回（调试终止或 shutdown）
    }

    /**
     * 显式关闭解释器：停止 worker 线程 + 关闭 TimerScheduler + 排空 taskQueue。
     *
     * <p>shutdown 后调用 eval/callFunction 会抛 RuntimeException。
     * 幂等。非阻塞（worker 是守护线程，JVM 退出时自动结束）。
     *
     * <p>典型用法：长运行宿主释放解释器资源时调用。TestScript 等 CLI 场景无需调用
     * （main 退出 JVM 终止，worker 守护线程自动结束）。
     */
    public void shutdown() {
        workerStopped = true;
        if (timerScheduler != null) {
            timerScheduler.shutdown();
        }
        if (workerThread != null) {
            workerThread.interrupt();
        }
        drainTaskQueue();
    }

    // ===== EvalTask 任务体系（worker 线程消费的任务对象）=====

    /** 1.4 兼容的函数式接口：无返回值任务（替代 lambda Runnable）。 */
    private static interface TaskRunnable {
        void run() throws Throwable;
    }

    /** 1.4 兼容的函数式接口：返回 GSValue 的任务（替代 lambda Callable）。 */
    private static interface TaskCallable {
        GSValue call() throws Throwable;
    }

    /**
     * 解释器执行任务抽象基类（非静态内部类，访问 workerStopped）。
     *
     * <p>外部线程 submit 后阻塞 {@link #await()}，worker 线程 {@link #execute()} 后通知。
     * 子类：{@link RunnableEvalTask}（void）/ {@link CallableEvalTask}（GSValue）/
     * {@link TimerEvalTask}（定时器回调）。
     */
    private abstract class EvalTask {
        private final Object lock = new Object();
        private boolean done = false;
        private Throwable error = null;

        /** 子类实现：在 worker 线程执行（可抛任意 Throwable）。 */
        abstract void run() throws Throwable;

        /**
         * worker 调用：执行任务，任何异常存入 error，不向外传播。
         * 完成后（done=true）notifyAll 唤醒外部 await。
         */
        final void execute() {
            try {
                run();
            } catch (Throwable e) {
                synchronized (lock) { error = e; }
            } finally {
                synchronized (lock) {
                    done = true;
                    lock.notifyAll();
                }
            }
        }

        /**
         * 外部线程调用：阻塞等待完成。若执行抛异常则重抛。
         * worker 停止时抛 RuntimeException（避免永久阻塞）。
         */
        final void await() throws Throwable {
            synchronized (lock) {
                while (!done) {
                    if (workerStopped) {
                        throw new RuntimeException("gscript-worker stopped");
                    }
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("await interrupted", e);
                    }
                }
                if (error != null) throw error;
            }
        }

        /** worker 排空 taskQueue 时调用：标记任务为失败（避免外部永久阻塞）。 */
        final void signalError(Throwable err) {
            synchronized (lock) {
                if (!done) {
                    error = err;
                    done = true;
                    lock.notifyAll();
                }
            }
        }

        /** worker 检查任务执行结果（用于 DebugAbortException 检测）。 */
        final Throwable getError() {
            synchronized (lock) { return error; }
        }
    }

    /** void 任务的 EvalTask（包装 eval 全家桶）。 */
    private class RunnableEvalTask extends EvalTask {
        private final TaskRunnable runnable;
        RunnableEvalTask(TaskRunnable r) { this.runnable = r; }
        void run() throws Throwable { runnable.run(); }
    }

    /** 返回 GSValue 的任务的 EvalTask（包装 callFunction/evalExpression）。 */
    private class CallableEvalTask extends EvalTask {
        private final TaskCallable callable;
        private GSValue result = GSNull.NULL;
        CallableEvalTask(TaskCallable c) { this.callable = c; }
        void run() throws Throwable { result = callable.call(); }
        GSValue awaitResult() throws Throwable {
            await();
            return result;
        }
    }

    /**
     * 定时器到期回调任务。
     *
     * <p>TimerScheduler.dispatch 把 TimerTask 包装成本类对象 offer 到 taskQueue，
     * worker 取出后 run() 调 callFunctionDirect 执行回调。
     * DebugAbortException 透传到 execute 存 error，worker 主循环检测后停止。
     * GSException（未捕获异常）打印 stderr 后吞掉（类 JS，继续下一个任务）。
     */
    private class TimerEvalTask extends EvalTask {
        private final TimerScheduler.TimerTask task;
        TimerEvalTask(TimerScheduler.TimerTask t) { this.task = t; }
        void run() throws Throwable {
            try {
                callFunctionDirect(task.callback, task.args);
            } catch (DebugAbortException e) {
                throw e;  // 调试终止：透传给 worker 主循环检测
            } catch (GSException e) {
                System.err.println(e.formatMessage());  // 未捕获异常：打印后吞掉，继续下一个任务
            }
        }
    }

    /**
     * 注册定时器全局函数（setTimeout/setInterval/clearTimeout/clearInterval）到 global 域。
     *
     * <p>在各入口（TestScript.gen/runGclass/debugAgent、DebugAgent 解释器创建处）
     * 创建 interpreter 后、eval 之前调用，与 {@code addVariableToGlobal("console", ...)} 并列。
     */
    public void installTimerGlobals() {
        TimerLib lib = new TimerLib(this);
        addVariableToGlobal("setTimeout", lib.setTimeout());
        addVariableToGlobal("setInterval", lib.setInterval());
        addVariableToGlobal("clearTimeout", lib.clearTimeout());
        addVariableToGlobal("clearInterval", lib.clearInterval());
    }

    /**
     * 注册类型转换全局函数（parseInt/parseFloat/isNaN/String/Number/Boolean）到 global 域。
     *
     * <p>6 个函数都是无状态纯函数，声明为 {@link org.gscript.vm.stdlib.TypeLib} 的 static final
     * 共享实例（所有 interpreter 共用同一组函数对象，语义等价于 JS 的全局函数对象）。
     * 本方法仅将引用注册到 global 域，不创建新对象，与 {@link #installTimerGlobals()} 的 per-interpreter
     * 创建模式不同（定时器函数依赖 interpreter 实例，类型转换函数不需要）。
     *
     * <p>语义要点：
     * <ul>
     *   <li>{@code parseInt(str[, radix])}：JS 容错语义，提取前导数字部分（"123abc"→123），
     *       支持 2-36 进制、"0x" 前缀检测，数值类型短路（int/float 直接截断），无效返回 NaN</li>
     *   <li>{@code parseFloat(str)}：提取前导浮点部分（"3.14abc"→3.14），数值类型短路，无效返回 NaN</li>
     *   <li>{@code Number(value)}：严格语义，整体必须合法数字（"123abc"→NaN），
     *       bool→0/1，null→0，其他→NaN</li>
     *   <li>{@code isNaN(value)}：判断 value 是否 NaN（type==10），或字符串转 Number 后是否 NaN</li>
     * </ul>
     */
    public void installTypeGlobals() {
        addVariableToGlobal("parseInt", TypeLib.PARSE_INT);
        addVariableToGlobal("parseFloat", TypeLib.PARSE_FLOAT);
        addVariableToGlobal("isNaN", TypeLib.IS_NAN);
        addVariableToGlobal("String", TypeLib.STRING);
        addVariableToGlobal("Number", TypeLib.NUMBER);
        addVariableToGlobal("Boolean", TypeLib.BOOLEAN);
    }
}
