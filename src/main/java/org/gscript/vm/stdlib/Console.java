package org.gscript.vm.stdlib;

import org.gscript.vm.GSInterpreter;
import org.gscript.vm.value.GSNativeFunction;
import org.gscript.vm.value.GSObject;
import org.gscript.vm.value.GSValue;

import java.util.ArrayList;

/**
 * 控制台类
 */
public class Console extends GSObject {
    public Console() {
        // 注入打印方法
        members.put("log", new GSNativeFunction("log") {
            @Override
            public GSValue call(ArrayList<GSValue> args) {
                if (args.size() > 1) {
                    String str = args.get(1).toStringValue();
                    System.out.println(str);
                }
                return null;
            }
        });
    }
}
