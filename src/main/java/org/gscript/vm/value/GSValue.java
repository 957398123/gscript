package org.gscript.vm.value;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public abstract class GSValue {

    /**
     * 值类型
     * 1 bool
     * 2 int
     * 3 float
     * 4 object
     * 5 str
     * 6 function
     * 7 array
     * 8 null
     * 9 native function
     * 10 nan
     * 设计这个是快速类型运算
     */
    public int type = 0;

    /**
     * 转为string
     *
     * @return
     */
    public abstract String toStringValue();

    /**
     * 转为int
     *
     * @return
     */
    public abstract int toIntValue();

    /**
     * 转为float
     *
     * @return
     */
    public abstract float toFloatValue();

    /**
     * 转为布尔
     *
     * @return
     */
    public abstract boolean toBoolean();

    /**
     * JS ToNumber 抽象操作：将任意 GSValue 转为数值类型 (GSInt/GSFloat/GSNaN)。
     * <ul>
     *   <li>bool → 0/1 (GSInt)</li>
     *   <li>int/float → 自身</li>
     *   <li>null → 0 (GSInt)</li>
     *   <li>NaN → 自身</li>
     *   <li>string → 严格解析（trim 后整体合法）：int/float/NaN</li>
     *   <li>array → 先 toString 再按 string 规则解析（[] → "" → 0，[5] → "5" → 5，[1,2] → "1,2" → NaN）</li>
     *   <li>object/function → NaN</li>
     * </ul>
     * 算术/比较/位运算的隐式类型转换统一经此入口（对齐 JS 语义）。
     *
     * @param v 输入值
     * @return 数值类型的 GSValue（GSInt/GSFloat/GSNaN）
     */
    public static GSValue toNumber(GSValue v) {
        switch (v.type) {
            case 1:  // bool → 0/1
                return new GSInt(v.toIntValue());
            case 2:  // int
            case 3:  // float
                return v;
            case 7: {  // array → toString → 再按 string 规则解析（JS 语义）
                // [] → "" → 0, [5] → "5" → 5, [1,2] → "1,2" → NaN
                return toNumber(new GSString(v.toStringValue()));
            }
            case 8:  // null → 0
                return new GSInt(0);
            case 10: // NaN
                return GSNaN.NAN;
            case 5: {  // string：严格解析（trim 后整体合法），对标 JS Number(string)
                String s = v.toStringValue().trim();
                if (s.length() == 0) {
                    return new GSInt(0);  // 空串 → 0（JS 语义）
                }
                try {
                    return new GSInt(Integer.parseInt(s));
                } catch (NumberFormatException e1) {
                    try {
                        return new GSFloat(Float.parseFloat(s));
                    } catch (NumberFormatException e2) {
                        return GSNaN.NAN;
                    }
                }
            }
            default:  // object/function → NaN
                return GSNaN.NAN;
        }
    }

    /**
     * JS ToInt32 抽象操作（位运算用）：toNumber 后取整，NaN/null→0，float 截断。
     *
     * @param v 输入值
     * @return 32 位整数
     */
    private static int toInt32(GSValue v) {
        GSValue n = toNumber(v);
        if (n.type == 10) {
            return 0;  // NaN → 0
        }
        return n.toIntValue();
    }

    /**
     * 对当前GSValue进行自增操作（JS 语义：先 ToNumber 再 +1）
     *
     * @return 值
     */
    public GSValue incr() {
        GSValue n = toNumber(this);
        if (n.type == 10) {
            return GSNaN.NAN;
        }
        if (n.type == 3) {
            return new GSFloat(n.toFloatValue() + 1);
        }
        return new GSInt(n.toIntValue() + 1);
    }

    /**
     * 对当前GSValue进行自减操作（JS 语义：先 ToNumber 再 -1）
     *
     * @return 值
     */
    public GSValue decr() {
        GSValue n = toNumber(this);
        if (n.type == 10) {
            return GSNaN.NAN;
        }
        if (n.type == 3) {
            return new GSFloat(n.toFloatValue() - 1);
        }
        return new GSInt(n.toIntValue() - 1);
    }

    /**
     * 判断2个值是否宽松相等（==，JS Abstract Equality Comparison 算法）
     * <ul>
     *   <li>两边 null → true；仅一边 null → false（gscript 无 undefined）</li>
     *   <li>任一 NaN → false</li>
     *   <li>两边数值类型（含 bool）→ 数值比较</li>
     *   <li>一边数值(含 bool) / 一边字符串 → 字符串 toNumber 后比较</li>
     *   <li>两边字符串 → 字符串比较</li>
     *   <li>其余（对象/数组/函数）→ 引用比较</li>
     * </ul>
     *
     * @param v1 值1
     * @param v2 值2
     * @return 计算结果
     */
    public static final boolean eq(GSValue v1, GSValue v2) {
        // null 仅与 null 宽松相等（gscript 无 undefined）
        if (v1.type == 8 || v2.type == 8) {
            return v1.type == 8 && v2.type == 8;
        }
        // NaN 与任何值都不相等（IEEE 754 规范）
        if (v1.type == 10 || v2.type == 10) {
            return false;
        }
        // 两边数值类型（含 bool）→ 数值比较
        if (v1.type <= 3 && v2.type <= 3) {
            if (v1.type == 3 || v2.type == 3) {
                return v1.toFloatValue() == v2.toFloatValue();
            }
            return v1.toIntValue() == v2.toIntValue();
        }
        // 一边数值(含 bool) / 一边字符串 → 字符串 toNumber 后比较
        if ((v1.type <= 3 && v2.type == 5) || (v1.type == 5 && v2.type <= 3)) {
            GSValue n1 = toNumber(v1);
            GSValue n2 = toNumber(v2);
            if (n1.type == 10 || n2.type == 10) {
                return false;  // 字符串非数字 → NaN → false
            }
            if (n1.type == 3 || n2.type == 3) {
                return n1.toFloatValue() == n2.toFloatValue();
            }
            return n1.toIntValue() == n2.toIntValue();
        }
        // 两边字符串 → 字符串比较
        if (v1.type == 5 && v2.type == 5) {
            return v1.toStringValue().equals(v2.toStringValue());
        }
        // 其余（对象/数组/函数）→ 引用比较
        return v1 == v2;
    }

    /**
     * 判断2个值是否严格相等
     *
     * @param v1 值1
     * @param v2 值2
     * @return 计算结果
     */
    public static final boolean seq(GSValue v1, GSValue v2) {
        // NaN 与任何值都不严格相等
        if (v1.type == 10 || v2.type == 10) {
            return false;
        }
        // 类型不同则严格不相等（int/float 同属数值可互比）
        if (v1.type != v2.type) {
            if ((v1.type == 2 || v1.type == 3) && (v2.type == 2 || v2.type == 3)) {
                return v1.toFloatValue() == v2.toFloatValue();
            }
            return false;
        }
        // 类型相同
        if (v1.type == 3) {  // float
            return v1.toFloatValue() == v2.toFloatValue();
        } else if (v1.type == 2) {  // int
            return v1.toIntValue() == v2.toIntValue();
        } else if (v1.type == 1) {  // bool
            return v1.toBoolean() == v2.toBoolean();
        } else if (v1.type == 5) {  // 字符串：值比较（非引用比较）
            return v1.toStringValue().equals(v2.toStringValue());
        } else {
            return v1 == v2;  // object/array/function/null：引用比较
        }
    }

    /**
     * 判断值1是否大于值2（JS Abstract Relational Comparison 算法）
     * <p>两边都是字符串 → 字典序比较；否则两边 toNumber 后数值比较，任一 NaN → false。
     *
     * @param v1 值1
     * @param v2 值2
     * @return 计算结果
     */
    public static final boolean gt(GSValue v1, GSValue v2) {
        // 两个字符串按字典序比较
        if (v1.type == 5 && v2.type == 5) {
            return v1.toStringValue().compareTo(v2.toStringValue()) > 0;
        }
        // 否则 toNumber 后数值比较
        GSValue n1 = toNumber(v1);
        GSValue n2 = toNumber(v2);
        if (n1.type == 10 || n2.type == 10) {
            return false;  // NaN 比较始终返回 false
        }
        if (n1.type == 3 || n2.type == 3) {
            return n1.toFloatValue() > n2.toFloatValue();
        }
        return n1.toIntValue() > n2.toIntValue();
    }

    /**
     * 判断值1是否大于等于值2（JS Abstract Relational Comparison 算法）
     * <p>两边都是字符串 → 字典序比较；否则两边 toNumber 后数值比较，任一 NaN → false。
     *
     * @param v1 值1
     * @param v2 值2
     * @return 计算结果
     */
    public static final boolean ge(GSValue v1, GSValue v2) {
        // 两个字符串按字典序比较
        if (v1.type == 5 && v2.type == 5) {
            return v1.toStringValue().compareTo(v2.toStringValue()) >= 0;
        }
        // 否则 toNumber 后数值比较
        GSValue n1 = toNumber(v1);
        GSValue n2 = toNumber(v2);
        if (n1.type == 10 || n2.type == 10) {
            return false;  // NaN 比较始终返回 false
        }
        if (n1.type == 3 || n2.type == 3) {
            return n1.toFloatValue() >= n2.toFloatValue();
        }
        return n1.toIntValue() >= n2.toIntValue();
    }

    /**
     * 判断值1是否小于值2（JS Abstract Relational Comparison 算法）
     * <p>两边都是字符串 → 字典序比较；否则两边 toNumber 后数值比较，任一 NaN → false。
     *
     * @param v1 值1
     * @param v2 值2
     * @return 计算结果
     */
    public static final boolean lt(GSValue v1, GSValue v2) {
        // 两个字符串按字典序比较
        if (v1.type == 5 && v2.type == 5) {
            return v1.toStringValue().compareTo(v2.toStringValue()) < 0;
        }
        // 否则 toNumber 后数值比较
        GSValue n1 = toNumber(v1);
        GSValue n2 = toNumber(v2);
        if (n1.type == 10 || n2.type == 10) {
            return false;  // NaN 比较始终返回 false
        }
        if (n1.type == 3 || n2.type == 3) {
            return n1.toFloatValue() < n2.toFloatValue();
        }
        return n1.toIntValue() < n2.toIntValue();
    }

    /**
     * 判断值1是否小于等于值2（JS Abstract Relational Comparison 算法）
     * <p>两边都是字符串 → 字典序比较；否则两边 toNumber 后数值比较，任一 NaN → false。
     *
     * @param v1 值1
     * @param v2 值2
     * @return 计算结果
     */
    public static final boolean le(GSValue v1, GSValue v2) {
        // 两个字符串按字典序比较
        if (v1.type == 5 && v2.type == 5) {
            return v1.toStringValue().compareTo(v2.toStringValue()) <= 0;
        }
        // 否则 toNumber 后数值比较
        GSValue n1 = toNumber(v1);
        GSValue n2 = toNumber(v2);
        if (n1.type == 10 || n2.type == 10) {
            return false;  // NaN 比较始终返回 false
        }
        if (n1.type == 3 || n2.type == 3) {
            return n1.toFloatValue() <= n2.toFloatValue();
        }
        return n1.toIntValue() <= n2.toIntValue();
    }

    /**
     * 对值进行负号操作（JS 语义：先 ToNumber 再取负）
     *
     * @param v1 值
     * @return 结果
     */
    public static final GSValue neg(GSValue v1) {
        GSValue n = toNumber(v1);
        if (n.type == 10) {
            return GSNaN.NAN;
        }
        if (n.type == 3) {
            return new GSFloat(-n.toFloatValue());
        }
        return new GSInt(-n.toIntValue());
    }

    /**
     * 对值进行加法运算（JS 语义）
     * <p>任一为字符串/对象/数组/函数 → 字符串拼接（ToPrimitive 默认 hint=string）；
     * 否则（bool/int/float/null/NaN）→ toNumber 后数值加法。
     *
     * @param v1 值1
     * @param v2 值2
     * @return 结果
     */
    public static final GSValue plus(GSValue v1, GSValue v2) {
        // 对象类类型（str/object/array/function/native）→ 字符串拼接
        // JS + 运算符：ToPrimitive(default hint) 对这些类型走 toString
        // null/NaN/bool/int/float → toNumber 后数值加法
        boolean v1Concat = (v1.type > 3 && v1.type != 8 && v1.type != 10);
        boolean v2Concat = (v2.type > 3 && v2.type != 8 && v2.type != 10);
        if (v1Concat || v2Concat) {
            return new GSString(v1.toStringValue() + v2.toStringValue());
        }
        // 数值加法（bool/int/float/null/NaN）
        GSValue n1 = toNumber(v1);
        GSValue n2 = toNumber(v2);
        if (n1.type == 10 || n2.type == 10) {
            return GSNaN.NAN;
        }
        if (n1.type == 3 || n2.type == 3) {
            return new GSFloat(n1.toFloatValue() + n2.toFloatValue());
        }
        return new GSInt(n1.toIntValue() + n2.toIntValue());
    }

    /**
     * 对值进行减法运算（JS 语义：两边 toNumber 后计算）
     *
     * @param v1 值1
     * @param v2 值2
     * @return 结果
     */
    public static final GSValue minus(GSValue v1, GSValue v2) {
        GSValue n1 = toNumber(v1);
        GSValue n2 = toNumber(v2);
        if (n1.type == 10 || n2.type == 10) {
            return GSNaN.NAN;
        }
        if (n1.type == 3 || n2.type == 3) {
            return new GSFloat(n1.toFloatValue() - n2.toFloatValue());
        }
        return new GSInt(n1.toIntValue() - n2.toIntValue());
    }

    /**
     * 对值进行乘法运算（JS 语义：两边 toNumber 后计算）
     *
     * @param v1 值1
     * @param v2 值2
     * @return 结果
     */
    public static final GSValue mul(GSValue v1, GSValue v2) {
        GSValue n1 = toNumber(v1);
        GSValue n2 = toNumber(v2);
        if (n1.type == 10 || n2.type == 10) {
            return GSNaN.NAN;
        }
        if (n1.type == 3 || n2.type == 3) {
            return new GSFloat(n1.toFloatValue() * n2.toFloatValue());
        }
        return new GSInt(n1.toIntValue() * n2.toIntValue());
    }

    /**
     * 对值进行除法运算（JS 语义：两边 toNumber 后计算）
     * <p>JS 语义：/ 总是浮点除法（7/2=3.5 而非截断为 3）；
     * 除零返回 NaN（gscript 无 Infinity 表示）。
     *
     * @param v1 值1
     * @param v2 值2
     * @return 结果
     */
    public static final GSValue div(GSValue v1, GSValue v2) {
        GSValue n1 = toNumber(v1);
        GSValue n2 = toNumber(v2);
        if (n1.type == 10 || n2.type == 10) {
            return GSNaN.NAN;
        }
        float b = n2.toFloatValue();
        if (b == 0) {
            return GSNaN.NAN;
        }
        return new GSFloat(n1.toFloatValue() / b);
    }

    /**
     * 对值进行取模运算（JS 语义：两边 toNumber 后计算；JS % 截断除法的余数）
     *
     * @param v1 值1
     * @param v2 值2
     * @return 结果
     */
    public static final GSValue modulo(GSValue v1, GSValue v2) {
        GSValue n1 = toNumber(v1);
        GSValue n2 = toNumber(v2);
        if (n1.type == 10 || n2.type == 10) {
            return GSNaN.NAN;
        }
        if (n1.type == 3 || n2.type == 3) {
            // float 模 0 返回 NaN（JS 语义），Java float % 0 不抛异常
            float f2 = n2.toFloatValue();
            if (f2 == 0) {
                return GSNaN.NAN;
            }
            return new GSFloat(n1.toFloatValue() % f2);
        }
        // int 模 0 返回 NaN（JS 语义），避免 Java ArithmeticException: / by zero
        int i2 = n2.toIntValue();
        if (i2 == 0) {
            return GSNaN.NAN;
        }
        return new GSInt(n1.toIntValue() % i2);
    }

    /**
     * 对值进行左移位运算（JS 语义：两边 toInt32 后移位）
     *
     * @param v1 值1
     * @param v2 值2
     * @return 结果
     */
    public static final GSValue ls(GSValue v1, GSValue v2) {
        return new GSInt(toInt32(v1) << toInt32(v2));
    }

    /**
     * 对值进行右移位运算（JS 语义：两边 toInt32 后移位）
     *
     * @param v1 值1
     * @param v2 值2
     * @return 结果
     */
    public static final GSValue rs(GSValue v1, GSValue v2) {
        return new GSInt(toInt32(v1) >> toInt32(v2));
    }

    /**
     * 对值进行按位与运算（JS 语义：两边 toInt32）
     *
     * @param v1 值1
     * @param v2 值2
     * @return 结果
     */
    public static final GSValue b_and(GSValue v1, GSValue v2) {
        return new GSInt(toInt32(v1) & toInt32(v2));
    }

    /**
     * 对值进行按位或运算（JS 语义：两边 toInt32）
     *
     * @param v1 值1
     * @param v2 值2
     * @return 结果
     */
    public static final GSValue b_or(GSValue v1, GSValue v2) {
        return new GSInt(toInt32(v1) | toInt32(v2));
    }

    /**
     * 对值进行按位异或运算（JS 语义：两边 toInt32）
     *
     * @param v1 值1
     * @param v2 值2
     * @return 结果
     */
    public static final GSValue b_xor(GSValue v1, GSValue v2) {
        return new GSInt(toInt32(v1) ^ toInt32(v2));
    }

    /**
     * 对值进行按位取反运算（JS 语义：toInt32 后取反）
     *
     * @param v1 值1
     * @return 结果
     */
    public static final GSValue b_not(GSValue v1) {
        return new GSInt(~toInt32(v1));
    }

    /**
     * 对值进行逻辑非运算
     *
     * @param v1 值1
     * @return 结果
     */
    public static final GSValue l_not(GSValue v1) {
        return GSBool.getGSBool(!v1.toBoolean());
    }

    /**
     * 将 GSValue 转换为 Java 原生对象（递归转换对象/数组）。
     *
     * <p>类型映射：
     * <ul>
     *   <li>bool/int/float/str → Boolean/Integer/Float/String</li>
     *   <li>null → null；nan → Float.NaN</li>
     *   <li>object → LinkedHashMap（递归转换每个成员）</li>
     *   <li>array → ArrayList（按索引顺序递归转换）</li>
     *   <li>function/native → 原样返回 GSValue 引用</li>
     * </ul>
     *
     * @return Java 原生对象
     */
    public Object toJavaObject() {
        switch (this.type) {
            case 1:  // GSBool
                return new Boolean(((GSBool) this).value);
            case 2:  // GSInt
                return new Integer(((GSInt) this).value);
            case 3:  // GSFloat
                return new Float(((GSFloat) this).value);
            case 5:  // GSString（value 私有，用 toStringValue）
                return this.toStringValue();
            case 8:  // GSNull
                return null;
            case 10: // GSNaN
                return new Float(Float.NaN);
            case 4:  // GSObject
                Map map = new LinkedHashMap();
                Iterator it = ((GSObject) this).getMembers().entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry e = (Map.Entry) it.next();
                    map.put((String) e.getKey(), ((GSValue) e.getValue()).toJavaObject());
                }
                return map;
            case 7:  // GSArray（extends GSObject，元素键为 "0","1",...）
                List list = new ArrayList();
                GSObject arr = (GSObject) this;
                for (int i = 0; arr.getMembers().containsKey(String.valueOf(i)); i++) {
                    list.add(arr.getProperty(String.valueOf(i)).toJavaObject());
                }
                return list;
            default:  // GSFunction(6) / GSNativeFunction(9) 原样返回
                return this;
        }
    }

    /**
     * 将 Java 对象转换为 GSValue（静态工厂，递归转换 Map/List）。
     *
     * <p>支持类型：Integer/Float/Double/String/Boolean/Map/List/null/GSValue。
     * Double 会收窄为 float（gscript 浮点统一用 GSFloat）。
     *
     * @param obj Java 对象
     * @return 对应的 GSValue
     * @throws IllegalArgumentException 不支持的 Java 类型
     */
    public static GSValue fromJavaObject(Object obj) {
        if (obj == null) return GSNull.NULL;
        if (obj instanceof GSValue) return (GSValue) obj;
        if (obj instanceof Integer) return new GSInt(((Integer) obj).intValue());
        if (obj instanceof Float) return new GSFloat(((Float) obj).floatValue());
        if (obj instanceof Double) return new GSFloat(((Double) obj).floatValue());
        if (obj instanceof String) return new GSString((String) obj);
        if (obj instanceof Boolean) return GSBool.getGSBool(((Boolean) obj).booleanValue());
        if (obj instanceof Map) {
            GSObject gso = new GSObject();
            Iterator it = ((Map) obj).entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry e = (Map.Entry) it.next();
                gso.setProperty(e.getKey().toString(), fromJavaObject(e.getValue()));
            }
            return gso;
        }
        if (obj instanceof List) {
            GSArray gsa = new GSArray();
            List list = (List) obj;
            for (int i = 0; i < list.size(); i++) {
                Object item = list.get(i);
                gsa.setProperty(String.valueOf(i), fromJavaObject(item));
            }
            return gsa;
        }
        throw new IllegalArgumentException("Cannot convert Java type to GSValue: " + obj.getClass());
    }

}
