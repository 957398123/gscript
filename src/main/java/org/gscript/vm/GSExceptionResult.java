package org.gscript.vm;

import org.gscript.vm.value.GSValue;

/**
 * 异常处理结果
 */
public class GSExceptionResult {

    public int ip;

    public GSValue e;

    public GSExceptionResult(int ip, GSValue e) {
        this.ip = ip;
        this.e = e;
    }
}
