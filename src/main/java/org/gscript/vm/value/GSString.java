package org.gscript.vm.value;

import org.gscript.vm.GSException;

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
     * 字符串转 int（JS ToInt32 语义：先 ToNumber 再取整，NaN → 0）。
     * <p>对标 JS {@code "23" | 0 === 23}、{@code "abc" | 0 === 0}。
     * 用于位运算、原生方法索引参数（如 charAt("1")）的隐式转换。
     *
     * @return 解析后的 int；非数字字符串返回 0
     */
    public int toIntValue() {
        GSValue n = toNumber(this);
        if (n.type == 10) {
            return 0;  // NaN → 0（JS ToInt32 语义）
        }
        return n.toIntValue();
    }

    /**
     * 字符串转 float（JS ToNumber 语义：解析数字，非数字 → NaN）。
     * <p>对标 JS {@code Number("3.14") === 3.14}、{@code Number("abc") === NaN}。
     *
     * @return 解析后的 float；非数字字符串返回 Float.NaN
     */
    public float toFloatValue() {
        GSValue n = toNumber(this);
        if (n.type == 10) {
            return Float.NaN;  // 非数字 → NaN
        }
        return n.toFloatValue();
    }

    // ===== 静态共享的原生方法实例 =====
    // 所有字符串共用同一组函数对象（语义等价于 JS 的 String.prototype.xxx）。
    // OP_INVOKE 调用时 args[0] 永远是 this（字符串本身），故无需闭包捕获。
    // 静态方法与实例同属一类，可直接读取 private final value，无需修改可见性。

    /** charAt(index): 返回指定位置字符（越界返回空串，JS 语义） */
    private static final GSNativeFunction CHAR_AT = new GSNativeFunction("charAt") {
        public GSValue call(ArrayList args) {
            String str = ((GSString) args.get(0)).value;
            if (args.size() < 2) {
                return new GSString("");
            }
            int index = ((GSValue) args.get(1)).toIntValue();
            if (index < 0 || index >= str.length()) {
                return new GSString("");  // JS: 越界返回空串
            }
            return new GSString(String.valueOf(str.charAt(index)));
        }
    };

    /** charCodeAt(index): 返回指定位置字符的 Unicode 编码（GSInt），越界返回 NaN（JS 语义） */
    private static final GSNativeFunction CHAR_CODE_AT = new GSNativeFunction("charCodeAt") {
        public GSValue call(ArrayList args) {
            String str = ((GSString) args.get(0)).value;
            if (args.size() < 2) {
                return GSNaN.NAN;
            }
            int index = ((GSValue) args.get(1)).toIntValue();
            if (index < 0 || index >= str.length()) {
                return GSNaN.NAN;  // JS: 越界返回 NaN
            }
            return new GSInt((int) str.charAt(index));
        }
    };

    /** indexOf(str[, fromIndex]): 返回子串首次出现的索引，未找到返回 -1 */
    private static final GSNativeFunction INDEX_OF = new GSNativeFunction("indexOf") {
        public GSValue call(ArrayList args) {
            String str = ((GSString) args.get(0)).value;
            if (args.size() < 2) {
                return new GSInt(-1);
            }
            String needle = ((GSValue) args.get(1)).toStringValue();
            int fromIndex = 0;
            if (args.size() >= 3) {
                fromIndex = ((GSValue) args.get(2)).toIntValue();
                if (fromIndex < 0) {
                    fromIndex = 0;  // JS: 负 fromIndex 视为 0
                }
            }
            return new GSInt(str.indexOf(needle, fromIndex));
        }
    };

    /** lastIndexOf(str): 返回子串最后一次出现的索引，未找到返回 -1 */
    private static final GSNativeFunction LAST_INDEX_OF = new GSNativeFunction("lastIndexOf") {
        public GSValue call(ArrayList args) {
            String str = ((GSString) args.get(0)).value;
            if (args.size() < 2) {
                return new GSInt(-1);
            }
            String needle = ((GSValue) args.get(1)).toStringValue();
            return new GSInt(str.lastIndexOf(needle));
        }
    };

    /** substring(start[, end]): 返回子串（不支持负索引，start>end 交换，JS 语义） */
    private static final GSNativeFunction SUBSTRING = new GSNativeFunction("substring") {
        public GSValue call(ArrayList args) {
            String str = ((GSString) args.get(0)).value;
            int len = str.length();
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
            return new GSString(str.substring(start, end));
        }
    };

    /** slice(start[, end]): 区间子串，支持负索引（与 substring 区别） */
    private static final GSNativeFunction SLICE = new GSNativeFunction("slice") {
        public GSValue call(ArrayList args) {
            String str = ((GSString) args.get(0)).value;
            int len = str.length();
            int start = 0;
            int end = len;
            if (args.size() >= 2) {
                start = ((GSValue) args.get(1)).toIntValue();
                if (start < 0) {
                    start += len;
                    if (start < 0) start = 0;
                }
                if (start > len) start = len;
            }
            if (args.size() >= 3) {
                end = ((GSValue) args.get(2)).toIntValue();
                if (end < 0) {
                    end += len;
                    if (end < 0) end = 0;
                }
                if (end > len) end = len;
            }
            if (start >= end) {
                return new GSString("");  // start>=end 返回空串
            }
            return new GSString(str.substring(start, end));
        }
    };

    /** substr(start[, length]): 从 start 取 length 个字符（start 支持负索引） */
    private static final GSNativeFunction SUBSTR = new GSNativeFunction("substr") {
        public GSValue call(ArrayList args) {
            String str = ((GSString) args.get(0)).value;
            int len = str.length();
            int start = 0;
            if (args.size() >= 2) {
                start = ((GSValue) args.get(1)).toIntValue();
                if (start < 0) {
                    start += len;
                    if (start < 0) start = 0;
                }
                if (start > len) start = len;
            }
            int length = len - start;  // 缺省取到末尾
            if (args.size() >= 3) {
                length = ((GSValue) args.get(2)).toIntValue();
                if (length < 0) length = 0;
            }
            int end = start + length;
            if (end > len) end = len;
            if (start >= end) {
                return new GSString("");
            }
            return new GSString(str.substring(start, end));
        }
    };

    /** split([separator]): 分割成 GSArray。无参返回 [原串]；separator 为空串按字符拆 */
    private static final GSNativeFunction SPLIT = new GSNativeFunction("split") {
        public GSValue call(ArrayList args) {
            String str = ((GSString) args.get(0)).value;
            GSArray result = new GSArray();
            // 无参：返回单元素数组 [原串]（JS 语义）
            if (args.size() < 2) {
                result.setProperty("0", new GSString(str));
                return result;
            }
            String sep = ((GSValue) args.get(1)).toStringValue();
            // separator 为空串：按字符拆分
            if (sep.length() == 0) {
                for (int i = 0; i < str.length(); i++) {
                    result.setProperty(Integer.toString(i),
                            new GSString(String.valueOf(str.charAt(i))));
                }
                return result;
            }
            // 逐个查找分隔符切分
            int idx = 0;
            int start = 0;
            int pos;
            while ((pos = str.indexOf(sep, start)) != -1) {
                result.setProperty(Integer.toString(idx),
                        new GSString(str.substring(start, pos)));
                idx++;
                start = pos + sep.length();
            }
            // 追加最后一段
            result.setProperty(Integer.toString(idx), new GSString(str.substring(start)));
            return result;
        }
    };

    /** replace(search, replacement): 替换首次出现（JS 字符串不可变，返回新串） */
    private static final GSNativeFunction REPLACE = new GSNativeFunction("replace") {
        public GSValue call(ArrayList args) {
            String str = ((GSString) args.get(0)).value;
            if (args.size() < 3) {
                return new GSString(str);
            }
            String search = ((GSValue) args.get(1)).toStringValue();
            String replacement = ((GSValue) args.get(2)).toStringValue();
            // search 为空串：插在开头（JS 语义）
            if (search.length() == 0) {
                return new GSString(replacement + str);
            }
            int pos = str.indexOf(search);
            if (pos == -1) {
                return new GSString(str);  // 找不到返回原串
            }
            return new GSString(
                    str.substring(0, pos) + replacement + str.substring(pos + search.length()));
        }
    };

    /** trim(): 去两端空白（直接用 String.trim()，Java 1.0+ 自带） */
    private static final GSNativeFunction TRIM = new GSNativeFunction("trim") {
        public GSValue call(ArrayList args) {
            String str = ((GSString) args.get(0)).value;
            return new GSString(str.trim());
        }
    };

    /** startsWith(prefix[, position]): 判断前缀。position 缺省=0 */
    private static final GSNativeFunction STARTS_WITH = new GSNativeFunction("startsWith") {
        public GSValue call(ArrayList args) {
            String str = ((GSString) args.get(0)).value;
            if (args.size() < 2) {
                return GSBool.FALSE;
            }
            String prefix = ((GSValue) args.get(1)).toStringValue();
            int position = 0;
            if (args.size() >= 3) {
                position = ((GSValue) args.get(2)).toIntValue();
                if (position < 0) position = 0;
                if (position > str.length()) position = str.length();
            }
            if (position + prefix.length() > str.length()) {
                return GSBool.FALSE;
            }
            return GSBool.getGSBool(str.regionMatches(position, prefix, 0, prefix.length()));
        }
    };

    /** endsWith(suffix[, endPosition]): 判断后缀。endPosition 缺省=length */
    private static final GSNativeFunction ENDS_WITH = new GSNativeFunction("endsWith") {
        public GSValue call(ArrayList args) {
            String str = ((GSString) args.get(0)).value;
            if (args.size() < 2) {
                return GSBool.FALSE;
            }
            String suffix = ((GSValue) args.get(1)).toStringValue();
            int endPosition = str.length();
            if (args.size() >= 3) {
                endPosition = ((GSValue) args.get(2)).toIntValue();
                if (endPosition < 0) endPosition = 0;
                if (endPosition > str.length()) endPosition = str.length();
            }
            if (suffix.length() > endPosition) {
                return GSBool.FALSE;
            }
            int start = endPosition - suffix.length();
            return GSBool.getGSBool(str.regionMatches(start, suffix, 0, suffix.length()));
        }
    };

    /** includes(substr[, position]): 是否包含子串。position 缺省=0 */
    private static final GSNativeFunction INCLUDES = new GSNativeFunction("includes") {
        public GSValue call(ArrayList args) {
            String str = ((GSString) args.get(0)).value;
            if (args.size() < 2) {
                return GSBool.FALSE;
            }
            String needle = ((GSValue) args.get(1)).toStringValue();
            int position = 0;
            if (args.size() >= 3) {
                position = ((GSValue) args.get(2)).toIntValue();
                if (position < 0) position = 0;
                if (position > str.length()) position = str.length();
            }
            if (needle.length() == 0) {
                return GSBool.TRUE;  // 空串总是被包含
            }
            return GSBool.getGSBool(str.indexOf(needle, position) != -1);
        }
    };

    /** repeat(n): 重复 n 次。n<0 抛 RangeError；n=0 返回空串 */
    private static final GSNativeFunction REPEAT = new GSNativeFunction("repeat") {
        public GSValue call(ArrayList args) {
            String str = ((GSString) args.get(0)).value;
            if (args.size() < 2) {
                return new GSString("");
            }
            int n = ((GSValue) args.get(1)).toIntValue();
            if (n < 0) {
                // JS 抛 RangeError；GSException 用 "<native>" 作 function 名，
                // OP_INVOKE type==9 分支会 rebase ip 到调用者位置以匹配 try-catch 监视
                throw new GSException("<native>", 0,
                        new GSString("RangeError: Invalid count value"));
            }
            if (n == 0 || str.length() == 0) {
                return new GSString("");
            }
            StringBuffer sb = new StringBuffer(str.length() * n);
            for (int i = 0; i < n; i++) {
                sb.append(str);
            }
            return new GSString(sb.toString());
        }
    };

    /** concat(...strs): 拼接多个字符串 */
    private static final GSNativeFunction CONCAT = new GSNativeFunction("concat") {
        public GSValue call(ArrayList args) {
            String str = ((GSString) args.get(0)).value;
            if (args.size() == 1) {
                return new GSString(str);
            }
            StringBuffer sb = new StringBuffer(str);
            for (int i = 1; i < args.size(); i++) {
                sb.append(((GSValue) args.get(i)).toStringValue());
            }
            return new GSString(sb.toString());
        }
    };

    /** toUpperCase(): 转大写 */
    private static final GSNativeFunction TO_UPPER_CASE = new GSNativeFunction("toUpperCase") {
        public GSValue call(ArrayList args) {
            String str = ((GSString) args.get(0)).value;
            return new GSString(str.toUpperCase());
        }
    };

    /** toLowerCase(): 转小写 */
    private static final GSNativeFunction TO_LOWER_CASE = new GSNativeFunction("toLowerCase") {
        public GSValue call(ArrayList args) {
            String str = ((GSString) args.get(0)).value;
            return new GSString(str.toLowerCase());
        }
    };

    /**
     * 字符串属性访问：
     * <ul>
     *   <li>{@code length} 返回字符串长度（数值属性）</li>
     *   <li>所有方法以共享的静态 {@link GSNativeFunction} 形式返回（所有字符串共用同一组函数对象，
     *       语义等价于 JS 的 String.prototype.xxx）。由 OP_INVOKE 的 type==9 分支直接调用，
     *       {@code args[0]} 为字符串本身（this）。</li>
     * </ul>
     * 其余属性委托父类（返回 null）。
     */
    public GSValue getProperty(String name) {
        if ("length".equals(name)) {
            return new GSInt(value.length());
        }
        if ("charAt".equals(name)) return CHAR_AT;
        if ("charCodeAt".equals(name)) return CHAR_CODE_AT;
        if ("indexOf".equals(name)) return INDEX_OF;
        if ("lastIndexOf".equals(name)) return LAST_INDEX_OF;
        if ("substring".equals(name)) return SUBSTRING;
        if ("slice".equals(name)) return SLICE;
        if ("substr".equals(name)) return SUBSTR;
        if ("split".equals(name)) return SPLIT;
        if ("replace".equals(name)) return REPLACE;
        if ("trim".equals(name)) return TRIM;
        if ("startsWith".equals(name)) return STARTS_WITH;
        if ("endsWith".equals(name)) return ENDS_WITH;
        if ("includes".equals(name)) return INCLUDES;
        if ("repeat".equals(name)) return REPEAT;
        if ("concat".equals(name)) return CONCAT;
        if ("toUpperCase".equals(name)) return TO_UPPER_CASE;
        if ("toLowerCase".equals(name)) return TO_LOWER_CASE;
        return super.getProperty(name);
    }
}
