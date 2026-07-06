package org.gscript.vm.value;

import java.util.ArrayList;
import java.util.HashMap;

public class GSArray extends GSObject {

    // ===== 静态共享的原生方法实例 =====
    // 所有数组共用同一组函数对象（语义等价于 JS 的 Array.prototype.xxx）。
    // OP_INVOKE 调用时 args[0] 永远是 this（数组本身），故无需闭包捕获，直接操作 args[0]。

    /** push(...items): 尾部追加，返回新长度（JS 语义） */
    private static final GSNativeFunction PUSH = new GSNativeFunction("push") {
        public GSValue call(ArrayList args) {
            GSArray arr = (GSArray) args.get(0);
            int len = computeLength(arr.members);
            for (int i = 1; i < args.size(); i++) {
                arr.members.put(Integer.toString(len), (GSValue) args.get(i));
                len++;
            }
            return new GSInt(len);
        }
    };

    /** pop(): 删除并返回末尾元素；空数组返回 null（JS: undefined） */
    private static final GSNativeFunction POP = new GSNativeFunction("pop") {
        public GSValue call(ArrayList args) {
            GSArray arr = (GSArray) args.get(0);
            int len = computeLength(arr.members);
            if (len == 0) {
                return GSNull.NULL;
            }
            GSValue last = (GSValue) arr.members.get(Integer.toString(len - 1));
            arr.members.remove(Integer.toString(len - 1));
            return last;
        }
    };

    /** shift(): 删除并返回头部元素，剩余元素前移；空数组返回 null */
    private static final GSNativeFunction SHIFT = new GSNativeFunction("shift") {
        public GSValue call(ArrayList args) {
            GSArray arr = (GSArray) args.get(0);
            int len = computeLength(arr.members);
            if (len == 0) {
                return GSNull.NULL;
            }
            GSValue first = (GSValue) arr.members.get("0");
            // 元素前移一位（从前往后，避免覆盖）
            for (int i = 1; i < len; i++) {
                GSValue v = (GSValue) arr.members.get(Integer.toString(i));
                if (v != null) {
                    arr.members.put(Integer.toString(i - 1), v);
                } else {
                    arr.members.remove(Integer.toString(i - 1));
                }
            }
            arr.members.remove(Integer.toString(len - 1));
            return first;
        }
    };

    /** unshift(...items): 头部插入，原有元素后移，返回新长度 */
    private static final GSNativeFunction UNSHIFT = new GSNativeFunction("unshift") {
        public GSValue call(ArrayList args) {
            GSArray arr = (GSArray) args.get(0);
            int len = computeLength(arr.members);
            int addCount = args.size() - 1;
            if (addCount <= 0) {
                return new GSInt(len);
            }
            // 原有元素后移 addCount 位（从后往前，避免覆盖）
            for (int i = len - 1; i >= 0; i--) {
                GSValue v = (GSValue) arr.members.get(Integer.toString(i));
                if (v != null) {
                    arr.members.put(Integer.toString(i + addCount), v);
                }
                arr.members.remove(Integer.toString(i));
            }
            // 插入新元素到头部
            for (int i = 0; i < addCount; i++) {
                arr.members.put(Integer.toString(i), (GSValue) args.get(i + 1));
            }
            return new GSInt(len + addCount);
        }
    };

    /** indexOf(item[, fromIndex]): 返回首个严格相等元素的索引，未找到返回 -1 */
    private static final GSNativeFunction INDEX_OF = new GSNativeFunction("indexOf") {
        public GSValue call(ArrayList args) {
            GSArray arr = (GSArray) args.get(0);
            int len = computeLength(arr.members);
            if (args.size() < 2) {
                return new GSInt(-1);
            }
            GSValue target = (GSValue) args.get(1);
            int from = 0;
            if (args.size() >= 3) {
                from = ((GSValue) args.get(2)).toIntValue();
                if (from < 0) {
                    from += len;
                    if (from < 0) from = 0;
                }
            }
            for (int i = from; i < len; i++) {
                GSValue elem = (GSValue) arr.members.get(Integer.toString(i));
                if (elem != null && GSValue.seq(elem, target)) {
                    return new GSInt(i);
                }
            }
            return new GSInt(-1);
        }
    };

    /** join([separator]): 用分隔符连接所有元素（默认 ","），null 元素输出空串 */
    private static final GSNativeFunction JOIN = new GSNativeFunction("join") {
        public GSValue call(ArrayList args) {
            GSArray arr = (GSArray) args.get(0);
            int len = computeLength(arr.members);
            String sep = ",";
            if (args.size() >= 2) {
                sep = ((GSValue) args.get(1)).toStringValue();
            }
            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < len; i++) {
                if (i > 0) {
                    sb.append(sep);
                }
                GSValue elem = (GSValue) arr.members.get(Integer.toString(i));
                if (elem != null && elem.type != 8) {  // null(type=8) 输出空串
                    sb.append(elem.toStringValue());
                }
            }
            return new GSString(sb.toString());
        }
    };

    /** slice([start[, end]]): 返回区间浅拷贝（新数组），支持负索引 */
    private static final GSNativeFunction SLICE = new GSNativeFunction("slice") {
        public GSValue call(ArrayList args) {
            GSArray arr = (GSArray) args.get(0);
            int len = computeLength(arr.members);
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
            GSArray result = new GSArray();
            int idx = 0;
            for (int i = start; i < end; i++) {
                GSValue elem = (GSValue) arr.members.get(Integer.toString(i));
                if (elem != null) {
                    result.members.put(Integer.toString(idx), elem);
                }
                idx++;
            }
            return result;
        }
    };

    /**
     * splice(start, deleteCount, ...items): 删除/插入/替换，返回被删元素数组（JS 语义）。
     * <ul>
     *   <li>{@code splice(start)} 不传 deleteCount：删到末尾</li>
     *   <li>{@code splice(start, 0, ...items)}：纯插入</li>
     *   <li>{@code splice(start, n, ...items)}：替换（n 个删除，items 插入）</li>
     *   <li>start 支持负索引（+= len）</li>
     * </ul>
     */
    private static final GSNativeFunction SPLICE = new GSNativeFunction("splice") {
        public GSValue call(ArrayList args) {
            GSArray arr = (GSArray) args.get(0);
            int len = computeLength(arr.members);

            // start 归一化
            int start = 0;
            if (args.size() >= 2) {
                start = ((GSValue) args.get(1)).toIntValue();
                if (start < 0) { start += len; if (start < 0) start = 0; }
                if (start > len) start = len;
            }

            // deleteCount（ES5 Array.prototype.splice 规范）：
            //   0 实参（args.size()==1）：actualDeleteCount=0，无操作
            //   1 实参（args.size()==2，只传 start）：删到末尾
            //   2+ 实参（args.size()>=3，传了 deleteCount）：删除 deleteCount 个
            int deleteCount;
            if (args.size() == 1) {
                deleteCount = 0;
            } else if (args.size() == 2) {
                deleteCount = len - start;
            } else {
                deleteCount = ((GSValue) args.get(2)).toIntValue();
                if (deleteCount < 0) deleteCount = 0;
                if (deleteCount > len - start) deleteCount = len - start;
            }

            // args[3..] 为插入项；args.size() < 3 时无插入项，clamp 到 0 避免负数导致尾部写回 key 偏移
            int insertCount = args.size() > 3 ? args.size() - 3 : 0;

            // 1. 收集被删元素 → removed
            GSArray removed = new GSArray();
            for (int i = 0; i < deleteCount; i++) {
                GSValue v = (GSValue) arr.members.get(Integer.toString(start + i));
                if (v != null) removed.members.put(Integer.toString(i), v);
            }

            // 2. 读出尾部元素（start+deleteCount 之后的部分）
            int tailCount = len - start - deleteCount;
            GSValue[] tail = new GSValue[tailCount];
            for (int i = 0; i < tailCount; i++) {
                tail[i] = (GSValue) arr.members.get(Integer.toString(start + deleteCount + i));
            }

            // 3. 删除从 start 到原末尾的所有 key
            for (int i = start; i < len; i++) {
                arr.members.remove(Integer.toString(i));
            }

            // 4. 写入新插入项
            for (int i = 0; i < insertCount; i++) {
                arr.members.put(Integer.toString(start + i), (GSValue) args.get(3 + i));
            }

            // 5. 写回尾部
            for (int i = 0; i < tailCount; i++) {
                if (tail[i] != null) {
                    arr.members.put(Integer.toString(start + insertCount + i), tail[i]);
                }
            }
            return removed;
        }
    };

    /**
     * 计算数组长度（从索引 0 开始的最大连续索引 + 1）。
     * 遇到空洞（key 不存在）即停止，与现有 length 语义一致。
     */
    private static int computeLength(HashMap members) {
        int len = 0;
        while (members.get(Integer.toString(len)) != null) {
            ++len;
        }
        return len;
    }

    public GSArray() {
        this.type = 7;
    }

    /**
     * 数组转字符串：等价于 join(",")（JS 语义）。
     * null 元素输出为空字符串。
     */
    public String toStringValue() {
        if (members.isEmpty()) {
            return "";
        }
        StringBuffer sb = new StringBuffer();
        int len = computeLength(members);
        for (int i = 0; i < len; i++) {
            if (i > 0) {
                sb.append(",");
            }
            GSValue value = (GSValue) members.get(Integer.toString(i));
            if (value != null && value.type != 8) {  // 非 null 输出值，null 输出空串
                sb.append(value.toStringValue());
            }
        }
        return sb.toString();
    }

    /**
     * 获取数组属性：
     * <ul>
     *   <li>{@code length} 返回元素个数（连续索引数）</li>
     *   <li>{@code push/pop/shift/unshift/indexOf/join/slice/splice} 返回共享的静态原生方法</li>
     * </ul>
     * 方法以共享的静态 {@link GSNativeFunction} 形式返回（所有数组共用同一组函数对象，
     * 语义等价于 JS 的 Array.prototype.xxx）。由 OP_INVOKE 的 type==9 分支直接调用，
     * {@code args[0]} 为数组本身（this）。其余属性委托父类。
     */
    public GSValue getProperty(String name) {
        if ("length".equals(name)) {
            return new GSInt(computeLength(members));
        }
        if ("push".equals(name)) return PUSH;
        if ("pop".equals(name)) return POP;
        if ("shift".equals(name)) return SHIFT;
        if ("unshift".equals(name)) return UNSHIFT;
        if ("indexOf".equals(name)) return INDEX_OF;
        if ("join".equals(name)) return JOIN;
        if ("slice".equals(name)) return SLICE;
        if ("splice".equals(name)) return SPLICE;
        return super.getProperty(name);
    }

    /**
     * 重写 setProperty：拦截 {@code "length"} 赋值，按 JS 语义截断或扩容数组。
     * <ul>
     *   <li>{@code arr.length = n}（n &lt; 当前长度）：截断，删除 n..len-1 的元素</li>
     *   <li>{@code arr.length = n}（n &gt; 当前长度）：扩容，补 GSNull（gscript 无 hole 概念，用 null 近似）</li>
     *   <li>{@code arr.length = 0}：清空数组</li>
     * </ul>
     * 其余属性（数字索引等）走父类 {@code members.put(name, value)}，与 JS 数组下标赋值一致。
     */
    public void setProperty(String name, GSValue value) {
        if ("length".equals(name)) {
            int newLen = value.toIntValue();
            if (newLen < 0) newLen = 0;
            int curLen = computeLength(members);
            if (newLen < curLen) {
                // 截断：删除 newLen ~ curLen-1 的元素
                for (int i = newLen; i < curLen; i++) {
                    members.remove(Integer.toString(i));
                }
            } else if (newLen > curLen) {
                // 扩容：补 GSNull（JS 语义为 empty slot/hole，gscript 无 hole 概念，用 GSNull 近似）
                for (int i = curLen; i < newLen; i++) {
                    members.put(Integer.toString(i), GSNull.NULL);
                }
            }
            return;
        }
        super.setProperty(name, value);
    }
}
