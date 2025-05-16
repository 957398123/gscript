package gscript.vm.value;

import java.util.List;

/**
 * 本地函数实现
 */
public abstract class GSNativeFunction extends GSValue {

    private String name;

    public GSNativeFunction(String name) {
        this.name = "ƒ %s() { [native code] }".formatted(name);
        type = 9;
    }

    public abstract GSValue call(List<GSValue> args);

    /**
     * 默认调用无this
     * @param object
     * @param args
     * @return
     */
    public GSValue call(GSObject object, List<GSValue> args){
        return call(args);
    }

    @Override
    public String toValueString() {
        return name;
    }
}
