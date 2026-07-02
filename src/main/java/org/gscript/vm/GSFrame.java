package org.gscript.vm;

import org.gscript.vm.value.GSFunction;
import org.gscript.vm.value.GSValue;

import java.util.ArrayDeque;

public class GSFrame {

    /**
     * 程序计数器
     */
    private int ip = 0;

    /**
     * 执行函数
     */
    public GSFunction function;

    /**
     * 异常监视表
     */
    public ArrayDeque<GSExceptionMonitor> exceptions = new ArrayDeque<>();

    /**
     * 要抛出的异常
     */
    private GSException throwException = null;

    /**
     * 待处理的返回值（用于 try/catch 内 return 时跳过 finally 的情况）。
     *
     * <p>当 return 指令位于带 finally 的 try/catch 块内时，不能直接 destroy 帧，
     * 而是先把返回值暂存到此字段，跳转到 finally 块执行；finally 执行完毕
     * （{@code finally_check}）时再取出此值完成真正的返回。
     */
    public GSValue pendingReturnValue = null;

    /**
     * 构建执行帧
     *
     * @param function
     */
    public GSFrame(GSFunction function) {
        this.function = function;
    }

    /**
     * 返回当前要执行的字节码（二进制格式：byte[]，code[0]=opcode）
     *
     * @return 二进制指令数组
     */
    public byte[] getCode() {
        return this.function.src[ip];
    }

    /**
     * 自增程序计数器
     */
    public void incrIP() {
        ++this.ip;
    }

    /**
     * 更新程序计数器
     *
     * @param ip
     */
    public void setIp(int ip) {
        this.ip = ip;
    }

    /**
     * 获取当前程序计数器位置
     *
     * @return 位置
     */
    public int getIP() {
        return this.ip;
    }

    /**
     * 当前帧是否结束执行
     *
     * @return
     */
    public boolean isEvalComplete() {
        return ip >= this.function.src.length;
    }

    /**
     * 往当前监视器增加异常表
     *
     * @param tryStart     try起始位置
     * @param tryEnd       try结束位置
     * @param catchStart   catch起始位置
     * @param finallyStart finally起始位置
     */
    public void addGSExceptionMonitor(int tryStart, int tryEnd, int catchStart, int finallyStart) {
        GSExceptionMonitor monitor = new GSExceptionMonitor(tryStart, tryEnd, catchStart, finallyStart);
        this.exceptions.push(monitor);
    }

    /**
     * 处理异常
     *
     * @param ip
     * @param e
     * @return
     * @throws GSException
     */
    public GSValue handleException(int ip, GSException e) throws GSException {
        // 首先查找当前异常ip对应的异常表，如果没有就需要向上抛异常
        GSExceptionMonitor monitor = exceptions.peek();
        if (monitor != null) {
            // 跳转需要清除当前块域
            function.freeToSpecScope("block");
            if (ip >= monitor.tryStart && ip <= monitor.tryEnd) {  // 如果是try块出了异常，try块肯定没有执行完毕
                if (monitor.catchStart != -1) {  // 如果有catch
                    setIp(monitor.catchStart);
                    return e.origin;
                } else if (monitor.finallyStart != -1) {  // try块异常只有finally
                    // 设置finally执行完成以后需要抛出的异常
                    throwException = e;
                    setIp(monitor.finallyStart);
                    return null;
                } else {
                    // TODO 这里不可能进来
                    System.out.println("无效的异常！");
                    throw e;
                }
            } else {
                if (monitor.finallyStart != -1 && ip >= monitor.finallyStart) {  // 异常在finally块
                    // 清除当前异常监视
                    exceptions.pop();
                    throwException = null;
                    return handleException(ip, e);
                } else if (monitor.catchStart != -1 && ip >= monitor.catchStart) {  // 异常在catch块
                    // catch里面出异常了，说明try块也是异常了的，所以不需要清理异常监视，但是要看有没有final，没有finally直接向上抛出异常，有的话设置需要抛出异常，然后跳转到finally
                    // 看看有没有finally
                    if (monitor.finallyStart == -1) {  // 没有finally，查看上层异常监视
                        // 清除当前异常监视
                        exceptions.pop();
                        // 置空throwException
                        throwException = null;
                        // 继续找异常表中的异常监视
                        return handleException(ip, e);
                    } else {  // 跳转至finally
                        throwException = e;
                        setIp(monitor.finallyStart);
                        return null;
                    }
                } else {
                    // TODO 这里不可能进来
                    System.out.println("无效的异常！");
                    throw e;
                }
            }
        }
        // 没有异常监听，抛出异常到上层frame
        // 清理frame
        destroy();
        // 没有找到异常处理，继续向上抛出异常
        throw e;
    }

    /**
     * finally块结束以后，检测是否要向上抛出异常
     */
    public void finallyCheck() throws GSException {
        // 说明try和finally块都正常执行完成
        exceptions.pop();
        // 如果catch块有异常，那么继续抛出catch块产生的异常
        if (throwException != null) {
            throw throwException;
        }
    }

    /**
     * try块结束以后的检测
     */
    public void tryEndCheck() {
        // 如果try块执行完成以后，没有finally，那么需要移除当前监视
        GSExceptionMonitor monitor = exceptions.peek();
        if (monitor.finallyStart == -1) {
            exceptions.pop();
        }
    }

    /**
     * 查找包含指定 ip 的、带 finally 的最近异常监视器（用于 return 跳转 finally）。
     *
     * <p>从栈顶（最内层）向下查找，返回第一个 finallyStart != -1 且 ip 落在其
     * try 块或 catch 块范围内的监视器。用于实现 "try/catch 内 return 时先执行 finally"。
     *
     * @param ip return 指令的位置
     * @return 目标监视器，没有则返回 null
     */
    public GSExceptionMonitor findReturnFinallyTarget(int ip) {
        for (GSExceptionMonitor m : exceptions) {
            if (m.finallyStart == -1) {
                continue;  // 无 finally 的监视器不需要跳转
            }
            // try 块内
            if (ip >= m.tryStart && ip <= m.tryEnd) {
                return m;
            }
            // catch 块内（catchStart <= ip < finallyStart）
            if (m.catchStart != -1 && ip >= m.catchStart && ip < m.finallyStart) {
                return m;
            }
        }
        return null;
    }

    /**
     * 销毁当前frame
     *
     * <p>必须把 {@code function.env} 完全恢复到调用前的静态作用域（function env 的 parent），
     * 而不是只回退到 function env 本身。原因：{@code GSFunction.env} 是共享字段，
     * 递归调用同一个函数时 {@code pushenv function} 会覆盖它。若 return 后只回退到
     * function env，外层调用再访问局部变量会从内层调用的 function env 取值，
     * 导致递归结果错误（如 {@code fibonacci(10) = -80} 的根因）。
     */
    public void destroy() {
        // 先回退到 function env（清理函数体内遗留的 block/loop 嵌套域）
        this.function.returnSpecScope("function");
        // 再回退一层到 function env 的 parent，恢复调用前的静态作用域
        GSEnv env = this.function.getEnv();
        if (env != null && "function".equals(env.name)) {
            this.function.setEnv(env.parent);
        }
        this.exceptions.clear();
        this.throwException = null;
        this.pendingReturnValue = null;
    }
}
