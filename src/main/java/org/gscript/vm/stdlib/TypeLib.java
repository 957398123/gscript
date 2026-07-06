package org.gscript.vm.stdlib;

import org.gscript.vm.value.GSBool;
import org.gscript.vm.value.GSFloat;
import org.gscript.vm.value.GSInt;
import org.gscript.vm.value.GSNativeFunction;
import org.gscript.vm.value.GSNaN;
import org.gscript.vm.value.GSString;
import org.gscript.vm.value.GSValue;

import java.util.ArrayList;

/**
 * 类型转换库：向 gscript 暴露 parseInt/parseFloat/isNaN/String/Number/Boolean 全局函数。
 *
 * <p>与 {@link TimerLib} 不同，本库的 6 个函数都是**无状态纯函数**，不依赖 {@link org.gscript.vm.GSInterpreter}
 * 实例，故全部声明为 {@code static final} 共享实例（语义等价于 JS 的全局函数对象）。
 * 所有 interpreter 实例共用同一组函数对象，{@link org.gscript.vm.GSInterpreter#installTypeGlobals()}
 * 仅将引用注册到 global 域，不创建新对象。
 *
 * <p>调用约定（沿用 {@code OP_INVOKE}）：
 * {@code args[0]} = this（全局函数调用时为 null，本库忽略），
 * {@code args[1..]} = 实际参数。
 *
 * <p>语义要点：
 * <ul>
 *   <li>{@code parseInt(str[, radix])}：JS 容错语义，提取前导数字部分（"123abc"→123），
 *       支持 2-36 进制、"0x" 前缀检测，数值类型短路（int/float 直接截断），无效返回 NaN</li>
 *   <li>{@code parseFloat(str)}：提取前导浮点部分（"3.14abc"→3.14），数值类型短路，无效返回 NaN</li>
 *   <li>{@code Number(value)}：严格语义，整体必须合法数字（"123abc"→NaN），
 *       bool→0/1，null→0，其他→NaN</li>
 *   <li>{@code isNaN(value)}：判断 value 是否 NaN（type==10），或字符串转 Number 后是否 NaN</li>
 * </ul>
 */
public class TypeLib {

    /** parseInt(str[, radix]): 解析整数，支持进制，JS 容错语义（提取前导数字） */
    public static final GSNativeFunction PARSE_INT = new GSNativeFunction("parseInt") {
        public GSValue call(ArrayList args) {
            if (args.size() < 2) {
                return GSNaN.NAN;
            }
            GSValue v = (GSValue) args.get(1);
            int radix = 10;
            boolean radixSpecified = (args.size() >= 3);
            if (radixSpecified) {
                radix = ((GSValue) args.get(2)).toIntValue();
                if (radix < 2 || radix > 36) {
                    return GSNaN.NAN;
                }
            }
            // 数值类型短路（radix==10 时）：避免 toString→再解析 的开销
            // JS 语义：parseInt(123)=123, parseInt(3.14)=3（截断小数）, parseInt(NaN)=NaN
            // 注意：bool/null 不能短路——parseInt(true)=NaN（ToString="true"），不是 toIntValue 的 1
            if (radix == 10) {
                if (v.type == 2) {  // int
                    return new GSInt(v.toIntValue());
                }
                if (v.type == 3) {  // float → 截断为 int（JS: parseInt(3.14)=3）
                    return new GSInt((int) v.toFloatValue());
                }
                if (v.type == 10) {  // NaN
                    return GSNaN.NAN;
                }
            }
            // 字符串路径：toStringValue 后提取前导数字
            String raw = v.toStringValue();
            // trim 前导空白（JS 语义）
            int i = 0;
            int len = raw.length();
            while (i < len && raw.charAt(i) <= ' ') {
                i++;
            }
            if (i >= len) {
                return GSNaN.NAN;
            }
            // 可选符号
            boolean negative = false;
            if (raw.charAt(i) == '+' || raw.charAt(i) == '-') {
                negative = (raw.charAt(i) == '-');
                i++;
            }
            if (i >= len) {
                return GSNaN.NAN;
            }
            // "0x"/"0X" 前缀处理（JS 语义）：
            // - radix 未指定 + "0x" 前缀 → 自动切换 radix=16，跳过前缀
            // - radix==16 + "0x" 前缀 → 跳过前缀（允许可选）
            // - radix==10（显式）+ "0x" 前缀 → 不特殊处理（'0' 解析后 'x' 停止，结果 0）
            if (i + 1 < len && raw.charAt(i) == '0'
                    && (raw.charAt(i + 1) == 'x' || raw.charAt(i + 1) == 'X')) {
                if (!radixSpecified) {
                    radix = 16;  // 自动切换十六进制
                }
                if (radix == 16) {
                    i += 2;  // 跳过 "0x" 前缀
                }
            }
            if (i >= len) {
                return GSNaN.NAN;  // "0x" 后无数字
            }
            // 提取连续有效数字（按 radix 判断字符范围）
            int digitStart = i;
            while (i < len) {
                int digit = Character.digit(raw.charAt(i), radix);
                if (digit < 0) {
                    break;
                }
                i++;
            }
            if (i == digitStart) {
                return GSNaN.NAN;  // 无有效数字
            }
            try {
                int result = Integer.parseInt(raw.substring(digitStart, i), radix);
                if (negative) {
                    result = -result;
                }
                return new GSInt(result);
            } catch (NumberFormatException e) {
                return GSNaN.NAN;
            }
        }
    };

    /** parseFloat(str): 解析浮点，提取前导浮点部分，无效返回 NaN */
    public static final GSNativeFunction PARSE_FLOAT = new GSNativeFunction("parseFloat") {
        public GSValue call(ArrayList args) {
            if (args.size() < 2) {
                return GSNaN.NAN;
            }
            GSValue v = (GSValue) args.get(1);
            // 数值类型短路：避免 toString→再解析 的开销
            // JS 语义：parseFloat(123)=123, parseFloat(3.14)=3.14, parseFloat(NaN)=NaN
            // 注意：bool/null 不能短路——parseFloat(true)=NaN（ToString="true"）
            if (v.type == 2) {  // int → float
                return new GSFloat(v.toFloatValue());
            }
            if (v.type == 3) {  // float
                return new GSFloat(v.toFloatValue());
            }
            if (v.type == 10) {  // NaN
                return GSNaN.NAN;
            }
            // 字符串路径
            String raw = v.toStringValue();
            // trim 前导空白
            int i = 0;
            int len = raw.length();
            while (i < len && raw.charAt(i) <= ' ') {
                i++;
            }
            if (i >= len) {
                return GSNaN.NAN;
            }
            int start = i;
            // 可选符号
            if (i < len && (raw.charAt(i) == '+' || raw.charAt(i) == '-')) {
                i++;
            }
            // 整数部分
            while (i < len && Character.isDigit(raw.charAt(i))) {
                i++;
            }
            // 小数部分
            if (i < len && raw.charAt(i) == '.') {
                i++;
                while (i < len && Character.isDigit(raw.charAt(i))) {
                    i++;
                }
            }
            // 指数部分
            if (i < len && (raw.charAt(i) == 'e' || raw.charAt(i) == 'E')) {
                int saved = i;
                i++;
                if (i < len && (raw.charAt(i) == '+' || raw.charAt(i) == '-')) {
                    i++;
                }
                if (i < len && Character.isDigit(raw.charAt(i))) {
                    while (i < len && Character.isDigit(raw.charAt(i))) {
                        i++;
                    }
                } else {
                    i = saved;  // 指数无有效数字，回退
                }
            }
            if (i == start) {
                return GSNaN.NAN;
            }
            try {
                return new GSFloat(Float.parseFloat(raw.substring(start, i)));
            } catch (NumberFormatException e) {
                return GSNaN.NAN;
            }
        }
    };

    /** isNaN(value): 判断是否 NaN */
    public static final GSNativeFunction IS_NAN = new GSNativeFunction("isNaN") {
        public GSValue call(ArrayList args) {
            if (args.size() < 2) {
                return GSBool.TRUE;
            }
            GSValue v = (GSValue) args.get(1);
            // NaN 直接判断
            if (v.type == 10) {
                return GSBool.TRUE;
            }
            // 数值类型（int/float/bool）都不是 NaN
            if (v.type == 2 || v.type == 3 || v.type == 1) {
                return GSBool.FALSE;
            }
            // null 的 Number()=0，不是 NaN
            if (v.type == 8) {
                return GSBool.FALSE;
            }
            // 字符串走 Number 转换后判断（JS 语义：isNaN(x) 等价于 isNaN(Number(x))）
            if (v.type == 5) {
                String s = v.toStringValue().trim();
                if (s.length() == 0) {
                    return GSBool.TRUE;  // 空串：Number("")=0 但 isNaN("")=true（JS 特例）
                }
                try {
                    Integer.parseInt(s);
                    return GSBool.FALSE;
                } catch (NumberFormatException e1) {
                    try {
                        Float.parseFloat(s);
                        return GSBool.FALSE;
                    } catch (NumberFormatException e2) {
                        return GSBool.TRUE;
                    }
                }
            }
            // object/array/function：Number()=NaN，故 isNaN=true
            return GSBool.TRUE;
        }
    };

    /** String(value): 转 GSString */
    public static final GSNativeFunction STRING = new GSNativeFunction("String") {
        public GSValue call(ArrayList args) {
            if (args.size() < 2) {
                return new GSString("");
            }
            return new GSString(((GSValue) args.get(1)).toStringValue());
        }
    };

    /** Number(value): 严格转数字，bool→0/1，null→0，纯数字串→数值，其他→NaN
     *  <p>内部委托 {@link GSValue#toNumber(GSValue)}（JS ToNumber 抽象操作），
     *  保证 Number(x) 与算术/比较运算的隐式转换语义一致。 */
    public static final GSNativeFunction NUMBER = new GSNativeFunction("Number") {
        public GSValue call(ArrayList args) {
            if (args.size() < 2) {
                return GSNaN.NAN;
            }
            return GSValue.toNumber((GSValue) args.get(1));
        }
    };

    /** Boolean(value): 转 GSBool（等价 toBoolean） */
    public static final GSNativeFunction BOOLEAN = new GSNativeFunction("Boolean") {
        public GSValue call(ArrayList args) {
            if (args.size() < 2) {
                return GSBool.FALSE;
            }
            return GSBool.getGSBool(((GSValue) args.get(1)).toBoolean());
        }
    };
}
