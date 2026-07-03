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
                // args[0] 为 this（console 对象），实际参数从 index 1 开始
                // JS 语义：多个参数用空格分隔，逐个输出
                if (args.size() > 1) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 1; i < args.size(); i++) {
                        if (i > 1) {
                            sb.append(" ");
                        }
                        sb.append(args.get(i).toStringValue());
                    }
                    System.out.println(sb.toString());
                }
                return null;
            }
        });
    }
}
