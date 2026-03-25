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
     * 构建执行帧
     *
     * @param function
     */
    public GSFrame(GSFunction function) {
        this.function = function;
    }

    /**
     * 返回当前要执行的字节码
     *
     * @return
     */
    public String[] getCode() {
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
     * 销毁当前frame
     */
    public void destroy() {
        this.function.returnSpecScope("function");
        this.exceptions.clear();
        this.throwException = null;
    }
}
