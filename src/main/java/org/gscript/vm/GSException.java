package org.gscript.vm;

import org.gscript.vm.value.GSValue;

/**
 * 异常对象
 */
public class GSException extends RuntimeException {

    /**
     * 抛出异常时所在函数
     */
    private String function;

    /**
     * 抛出异常时的ip
     */
    private int ip;

    /**
     * 异常对象
     */
    public GSValue origin;

    public GSException(String function, int ip, GSValue origin) {
        this.function = function;
        this.ip = ip;
        this.origin = origin;
    }

    public void setIp(int ip) {
        this.ip = ip;
    }

    public int getIp() {
        return ip;
    }
}
