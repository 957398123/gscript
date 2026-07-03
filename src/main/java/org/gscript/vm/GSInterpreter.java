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
import org.gscript.compile.token.GSToken;
import org.gscript.vm.debug.DebugAbortException;
import org.gscript.vm.debug.DebugAgent;
import org.gscript.vm.debug.DebugController;
import org.gscript.vm.stdlib.Console;
import org.gscript.vm.stdlib.TimerLib;
import org.gscript.vm.value.*;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

public class GSInterpreter {

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
     * 定时器调度器（lazy 初始化，首次 schedule 时创建）。
     *
     * <p>方案 B：守护线程只计时，到期任务入 readyQueue，由 {@link #runEventLoop()}
     * 在主线程串行执行回调。null 表示无定时器任务、或调度器已 shutdown。
     */
    private TimerScheduler timerScheduler;

    public GSInterpreter() {
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
     * 其他自定义全局变量注入。本方法不返回直到调试会话结束。
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
                                throw new GSException(frame.function.name, frame.getIP() - 1, new GSString("TypeError: Cannot read properties of null"));
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
                                    throw new GSException(frame.function.name, frame.getIP() - 1, new GSString("TypeError: Cannot set properties of null."));
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
                                    throw ex;
                                }
                            } else if (methodRef.type == 9) {  // 本地函数（本地函数需要手动放值）
                                GSNativeFunction nativeFunction = (GSNativeFunction) methodRef;
                                GSValue r = nativeFunction.eval(callArgs);
                                stack.push(r);
                            } else {  // 函数引用为空
                                throw new GSException(frame.function.name, frame.getIP() - 1, new GSString("TypeError: function not exist."));
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
                                throw new GSException(frame.function.name, frame.getIP() - 1, new GSString("TypeError: constructor function not exist."));
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
                            throw new GSException(frame.function.name, frame.getIP() - 1, origin);
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
                    GSException exception = new GSException(frame.function.name, ip, origin);
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
    public void eval(byte[][] codes, Object[] constantPool, int[] sourceLines, String sourcePath, String sourceContent) {
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
            System.out.println("Uncaught Error: " + e.origin.toStringValue() + " at <anonymous>:" + e.getIp());
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
        return new BytecodeEncoder().encode(Arrays.asList(src));
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
    public void evalScript(String code) {
        EncodedBytecode encoded = compile(code);
        eval(encoded.instructions, encoded.constantPool, null, null);
    }

    /**
     * 求值一个 gscript 表达式并返回结果。
     *
     * <p>实现：将表达式包装为 {@code return (expr);} 执行，OP_RETURN 会把结果留在
     * {@link #stack} 上，执行后弹出返回。表达式出错（语法/运行时）时返回 {@link GSNull#NULL}。
     *
     * <p>注意：不能复用 {@link #evalScript}（它内部 eval 会吞掉 GSException），
     * 故直接调用 {@link #eval(GSFrame, ArrayList)} 以检测异常。
     *
     * @param expr gscript 表达式（如 "a + b"、"add(1, 2)"、"{x: 1, y: 2}"）
     * @return 求值结果，出错返回 GSNull.NULL
     */
    public GSValue evalExpression(String expr) {
        stack.clear();  // 清空栈上残留值，确保返回值是本次表达式的结果
        EncodedBytecode encoded = compile("return (" + expr + ");");
        GSFunction anonymous = new GSFunction("null", encoded.instructions, encoded.constantPool, global);
        GSFrame frame = new GSFrame(anonymous);
        try {
            eval(frame, null);
        } catch (GSException e) {
            System.out.println("Uncaught Error: " + e.origin.toStringValue() + " at <anonymous>:" + e.getIp());
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
            try { in.close(); } catch (Exception e) {}
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

    /** 读取文件全部字节（1.4 兼容，替代 Java 9 Files.readAllBytes）。 */
    private static byte[] readFileBytes(File f) throws IOException {
        long len = f.length();
        int capacity = (int) Math.min(len, 8192);
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(capacity);
        FileInputStream in = new FileInputStream(f);
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

    // ===== 定时器与事件循环（方案 B：单线程 + 守护定时器线程）=====

    /**
     * 调用 gscript 函数（native 回调 gscript 的唯一入口）。
     *
     * <p>供 {@link TimerLib} 在主线程执行定时器回调使用：new GSFrame → eval。
     * eval 内部走 {@code callStack.push/pop} + 每条指令 {@code suspendCheck}，
     * 故回调里的断点/单步/变量查看与普通调用完全一致。
     *
     * <p>异常处理：传播 {@link GSException} 和 {@link DebugAbortException} 给调用方
     * （{@link #runEventLoop()} 决定如何处理）。回调无 return 时栈不增长，返回 GSNull.NULL。
     *
     * @param fn   目标函数（已在 setTimeout/setInterval 时捕获）
     * @param args 参数列表（OP_INVOKE 约定：args[0]=this，args[1..]=实际参数）
     * @return 函数返回值（无 return 返回 GSNull.NULL）
     */
    public GSValue callFunction(GSFunction fn, ArrayList args) {
        GSFrame frame = new GSFrame(fn);
        int stackMark = stack.size();
        eval(frame, args);
        return stack.size() > stackMark ? (GSValue) stack.pop() : GSNull.NULL;
    }

    /** 调度一次性定时器（setTimeout），返回 timer id。 */
    public int scheduleTimeout(GSFunction cb, long delay, ArrayList args) {
        ensureTimerScheduler();
        return timerScheduler.schedule(cb, delay, args);
    }

    /** 调度周期性定时器（setInterval），返回 timer id。 */
    public int scheduleInterval(GSFunction cb, long period, ArrayList args) {
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
            timerScheduler = new TimerScheduler();
        }
    }

    /**
     * 事件循环：在主脚本 eval 返回后，pump 定时器任务队列直到排空。
     *
     * <p>循环退出条件：{@link TimerScheduler#hasPending()} 为 false
     * （timerQueue + readyQueue 都空，即所有 setTimeout 已执行、所有 setInterval 已 cancel）。
     * 纯 setInterval 脚本永不退出——attach 模式 disconnect 仅分离调试器（程序继续运行，
     * 如同 node --inspect），需宿主 kill 进程或调用 terminate 请求终止；
     * terminate 触发的 {@link DebugAbortException} 会传播出本循环。
     *
     * <p>异常策略（类 JS）：
     * <ul>
     *   <li>{@link DebugAbortException}（terminate 请求 / launch 模式 disconnect）：传播出循环，终止事件循环</li>
     *   <li>{@link GSException}（gscript 未捕获异常）：打印 stderr，继续下一个任务</li>
     *   <li>其他 Throwable：打印栈，继续下一个任务</li>
     * </ul>
     */
    public void runEventLoop() {
        if (timerScheduler == null) {
            return;  // 无定时器任务，直接返回
        }
        try {
            while (timerScheduler.hasPending()) {
                long timeout = timerScheduler.nextDelayMs();
                if (timeout < 0) {
                    break;  // 队列空（不应发生，hasPending 已检查）
                }
                TimerScheduler.TimerTask task;
                try {
                    task = timerScheduler.pollReady(timeout);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (task == null) {
                    continue;  // 超时，重新检查 hasPending
                }
                try {
                    callFunction(task.callback, task.args);
                } catch (DebugAbortException e) {
                    throw e;  // 调试会话终止，传播
                } catch (GSException e) {
                    System.err.println("Uncaught Error: " + (e.origin != null ? e.origin.toStringValue() : "?"));
                } catch (Throwable e) {
                    e.printStackTrace();
                }
            }
        } finally {
            timerScheduler.shutdown();
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
}
