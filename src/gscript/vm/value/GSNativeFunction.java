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

    @Override
    public String toValueString() {
        return name;
    }
}
