package org.gscript.vm.value;

import java.util.ArrayList;

public class GSString extends GSObject {

    private final String value;

    public GSString(String value) {
        this.type = 5;
        this.value = value;
    }

    @Override
    public String toStringValue() {
        return value;
    }

    @Override
    public boolean toBoolean() {
        // 非空字符串为 true（JS 语义）
        return !value.isEmpty();
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
    @Override
    public GSValue getProperty(String name) {
        switch (name) {
            case "length":
                return new GSInt(value.length());
            case "charAt":
                return new GSNativeFunction("charAt") {
                    @Override
                    public GSValue call(ArrayList<GSValue> args) {
                        // args[0]=this(字符串本身), args[1]=index
                        if (args.size() < 2) {
                            return new GSString("");
                        }
                        int index = args.get(1).toIntValue();
                        if (index < 0 || index >= value.length()) {
                            return new GSString("");  // JS: 越界返回空串
                        }
                        return new GSString(String.valueOf(value.charAt(index)));
                    }
                };
            case "indexOf":
                return new GSNativeFunction("indexOf") {
                    @Override
                    public GSValue call(ArrayList<GSValue> args) {
                        if (args.size() < 2) {
                            return new GSInt(-1);
                        }
                        String needle = args.get(1).toStringValue();
                        return new GSInt(value.indexOf(needle));
                    }
                };
            case "substring":
                return new GSNativeFunction("substring") {
                    @Override
                    public GSValue call(ArrayList<GSValue> args) {
                        int len = value.length();
                        int start = 0;
                        int end = len;
                        if (args.size() >= 2) {
                            start = args.get(1).toIntValue();
                            if (start < 0) start = 0;
                            if (start > len) start = len;
                        }
                        if (args.size() >= 3) {
                            end = args.get(2).toIntValue();
                            if (end < 0) end = 0;
                            if (end > len) end = len;
                        }
                        if (start > end) {
                            int tmp = start; start = end; end = tmp;
                        }
                        return new GSString(value.substring(start, end));
                    }
                };
            case "toUpperCase":
                return new GSNativeFunction("toUpperCase") {
                    @Override
                    public GSValue call(ArrayList<GSValue> args) {
                        return new GSString(value.toUpperCase());
                    }
                };
            case "toLowerCase":
                return new GSNativeFunction("toLowerCase") {
                    @Override
                    public GSValue call(ArrayList<GSValue> args) {
                        return new GSString(value.toLowerCase());
                    }
                };
            default:
                return super.getProperty(name);
        }
    }
}
