package org.gscript.vm.value;

import java.util.ArrayList;

public class GSString extends GSObject {

    private final String value;

    public GSString(String value) {
        this.type = 5;
        this.value = value;
    }

    public String toStringValue() {
        return value;
    }

    public boolean toBoolean() {
        // 非空字符串为 true（JS 语义）
        return value.length() != 0;
    }

    /**
     * 字符串属性访问：
     * <ul>
     *   <li>{@code length} 返回字符串长度（数值属性）</li>
     *   <li>{@code charAt(index)} 返回指定位置字符（越界返回空串，JS 语义）</li>
     *   <li>{@code indexOf(str)} 返回子串首次出现的索引，未找到返回 -1</li>
     *   <li>{@code substring(start[, end])} 返回子串</li>
     *   <li>{@code toUpperCase} / {@code toLowerCase} 大小写转换</li>
     * </ul>
     * 方法以 {@link GSNativeFunction} 形式返回，捕获当前字符串作为 {@code this}，
     * 由 OP_INVOKE 的 type==9 分支直接调用。其余属性委托父类（返回 null）。
     */
    public GSValue getProperty(String name) {
        if ("length".equals(name)) {
            return new GSInt(value.length());
        } else if ("charAt".equals(name)) {
            return new GSNativeFunction("charAt") {
                public GSValue call(ArrayList args) {
                    // args[0]=this(字符串本身), args[1]=index
                    if (args.size() < 2) {
                        return new GSString("");
                    }
                    int index = ((GSValue) args.get(1)).toIntValue();
                    if (index < 0 || index >= value.length()) {
                        return new GSString("");  // JS: 越界返回空串
                    }
                    return new GSString(String.valueOf(value.charAt(index)));
                }
            };
        } else if ("indexOf".equals(name)) {
            return new GSNativeFunction("indexOf") {
                public GSValue call(ArrayList args) {
                    if (args.size() < 2) {
                        return new GSInt(-1);
                    }
                    String needle = ((GSValue) args.get(1)).toStringValue();
                    return new GSInt(value.indexOf(needle));
                }
            };
        } else if ("substring".equals(name)) {
            return new GSNativeFunction("substring") {
                public GSValue call(ArrayList args) {
                    int len = value.length();
                    int start = 0;
                    int end = len;
                    if (args.size() >= 2) {
                        start = ((GSValue) args.get(1)).toIntValue();
                        if (start < 0) start = 0;
                        if (start > len) start = len;
                    }
                    if (args.size() >= 3) {
                        end = ((GSValue) args.get(2)).toIntValue();
                        if (end < 0) end = 0;
                        if (end > len) end = len;
                    }
                    if (start > end) {
                        int tmp = start; start = end; end = tmp;
                    }
                    return new GSString(value.substring(start, end));
                }
            };
        } else if ("toUpperCase".equals(name)) {
            return new GSNativeFunction("toUpperCase") {
                public GSValue call(ArrayList args) {
                    return new GSString(value.toUpperCase());
                }
            };
        } else if ("toLowerCase".equals(name)) {
            return new GSNativeFunction("toLowerCase") {
                public GSValue call(ArrayList args) {
                    return new GSString(value.toLowerCase());
                }
            };
        } else {
            return super.getProperty(name);
        }
    }
}
