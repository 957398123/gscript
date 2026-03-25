package org.gscript.vm;

/**
 * 异常监控
 */
public class GSExceptionMonitor {

    // try块开始
    public int tryStart = -1;

    // try块结束
    public int tryEnd = -1;

    // catch块开始
    public int catchStart = -1;

    // finally块开始
    public int finallyStart = -1;

    public GSExceptionMonitor(int tryStart, int tryEnd, int catchStart, int finallyStart) {
        this.tryStart = tryStart;
        this.tryEnd = tryEnd;
        this.catchStart = catchStart;
        this.finallyStart = finallyStart;
    }
}
