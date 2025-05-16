package gscript.vm.stdlib;

import gscript.vm.value.*;

import java.util.List;

public final class PrintFunction extends GSNativeFunction {

    public PrintFunction() {
        super("println");
    }

    @Override
    public GSValue call(List<GSValue> args) {
        GSValue value = args.get(0);
        System.out.println(value.toValueString());
        return GSNull.NULL;
    }
}
