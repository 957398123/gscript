package gscript.vm.value;

import gscript.vm.Env;

/**
 * 函数实现，暂时不实现闭包
 */
public class GSFunction extends GSObject {

    /**
     * 函数名称
     */
    public String name;

    /**
     * 函数指令
     */
    public String[] codes;

    public GSFunction(String name, String[] codes) {
        this.type = 6;
        this.name = name;
        this.codes = codes;
    }

    @Override
    public String toValueString() {
        return "ƒ %s(){}".formatted(name);
    }
}
