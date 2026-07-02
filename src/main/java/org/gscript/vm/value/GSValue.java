package org.gscript.vm.value;

import java.util.ArrayList;
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
     * 对当前GSValue进行自增操作
     *
     * @return 值
     */
    public GSValue incr() {
        if (type <= 3) {
            if (type == 3) {
                return new GSFloat(toFloatValue() + 1);
            } else {
                return new GSInt(toIntValue() + 1);
            }
        } else {
            return GSNaN.NAN;
        }
    }

    /**
     * 对当前GSValue进行自减操作
     *
     * @return 值
     */
    public GSValue decr() {
        if (type <= 3) {
            if (type == 3) {
                return new GSFloat(toFloatValue() - 1);
            } else {
                return new GSInt(toIntValue() - 1);
            }
        } else {
            return GSNaN.NAN;
        }
    }

    /**
     * 判断2个值是否相等
     *
     * @param v1 值1
     * @param v2 值2
     * @return 计算结果
     */
    public static final boolean eq(GSValue v1, GSValue v2) {
        if (v1.type <= 3 && v2.type <= 3) {  // 数值类型
            if (v1.type == 3 || v2.type == 3) {
                return v1.toFloatValue() == v2.toFloatValue();
            } else {
                return v1.toIntValue() == v2.toIntValue();
            }
        } else if (v1.type == 5 || v2.type == 5) {  // 任意一个是字符串
            String strValue1 = v1.toStringValue();
            String strValue2 = v2.toStringValue();
            return strValue1.equals(strValue2);
        } else {
            return v1 == v2;
        }
    }

    /**
     * 判断2个值是否严格相等
     *
     * @param v1 值1
     * @param v2 值2
     * @return 计算结果
     */
    public static final boolean seq(GSValue v1, GSValue v2) {
        if (v1.type <= 3 && v2.type <= 3) {
            if (v1.type != 1 && v2.type != 1) {
                if (v1.type == 3 || v2.type == 3) {
                    return v1.toFloatValue() == v2.toFloatValue();
                } else {
                    return v1.toIntValue() == v2.toIntValue();
                }
            } else {
                return v1.toBoolean() == v2.toBoolean();
            }
        } else if (v1.type == v2.type) {
            return v1 == v2;
        } else {
            return false;
        }
    }

    /**
     * 判断值1是否大于值2
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
        if (v1.type <= 3 && v2.type <= 3) {
            if (v1.type == 3 || v2.type == 3) {
                return v1.toFloatValue() > v2.toFloatValue();
            } else {
                return v1.toIntValue() > v2.toIntValue();
            }
        } else {
            return false;
        }
    }

    /**
     * 判断值1是否大于等于值2
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
        if (v1.type > 3 || v2.type > 3) {
            return false;
        } else {
            if (v1.type == 3 || v2.type == 3) {
                return v1.toFloatValue() >= v2.toFloatValue();
            } else {
                return v1.toIntValue() >= v2.toIntValue();
            }
        }
    }

    /**
     * 判断值1是否小于值2
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
        if (v1.type > 3 || v2.type > 3) {
            return false;
        } else {
            if (v1.type == 3 || v2.type == 3) {
                return v1.toFloatValue() < v2.toFloatValue();
            } else {
                return v1.toIntValue() < v2.toIntValue();
            }
        }
    }

    /**
     * 判断值1是否小于等于值2
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
        if (v1.type <= 3 && v2.type <= 3) {
            if (v1.type == 3 || v2.type == 3) {
                return v1.toFloatValue() <= v2.toFloatValue();
            } else {
                return v1.toIntValue() <= v2.toIntValue();
            }
        } else {
            return false;
        }
    }

    /**
     * 对值进行负号操作
     *
     * @param v1 值
     * @return 结果
     */
    public static final GSValue neg(GSValue v1) {
        if (v1.type == 3) {
            return new GSFloat(-v1.toFloatValue());
        } else if (v1.type < 3) {
            return new GSInt(-v1.toIntValue());
        } else {
            return GSNaN.NAN;
        }
    }

    /**
     * 对值进行加法运算
     *
     * @param v1 值1
     * @param v2 值2
     * @return 结果
     */
    public static final GSValue plus(GSValue v1, GSValue v2) {
        if (v1.type <= 3 && v2.type <= 3) {  // 数值类型计算
            // float类型提升
            if (v1.type == 3 || v2.type == 3) {
                return new GSFloat(v1.toFloatValue() + v2.toFloatValue());
            } else {
                return new GSInt(v1.toIntValue() + v2.toIntValue());
            }
        } else {
            return new GSString(v1.toStringValue() + v2.toStringValue());
        }
    }

    /**
     * 对值进行减法运算
     *
     * @param v1 值1
     * @param v2 值2
     * @return 结果
     */
    public static final GSValue minus(GSValue v1, GSValue v2) {
        if (v1.type <= 3 && v2.type <= 3) {  // 数值类型计算
            // float类型提升
            if (v1.type == 3 || v2.type == 3) {
                return new GSFloat(v1.toFloatValue() - v2.toFloatValue());
            } else {
                return new GSInt(v1.toIntValue() - v2.toIntValue());
            }
        } else {
            // 其中有一个不是数字，返回非数
            return GSNaN.NAN;
        }
    }

    /**
     * 对值进行乘法运算
     *
     * @param v1 值1
     * @param v2 值2
     * @return 结果
     */
    public static final GSValue mul(GSValue v1, GSValue v2) {
        if (v1.type <= 3 && v2.type <= 3) {  // 数值类型计算
            // float类型提升
            if (v1.type == 3 || v2.type == 3) {
                return new GSFloat(v1.toFloatValue() * v2.toFloatValue());
            } else {
                return new GSInt(v1.toIntValue() * v2.toIntValue());
            }
        } else {
            // 其中有一个不是数字，返回非数
            return GSNaN.NAN;
        }
    }

    /**
     * 对值进行除法运算
     *
     * @param v1 值1
     * @param v2 值2
     * @return 结果
     */
    public static final GSValue div(GSValue v1, GSValue v2) {
        if (v1.type <= 3 && v2.type <= 3) {  // 数值类型计算
            // JS 语义：/ 总是浮点除法（7/2=3.5 而非截断为 3）
            // 除零返回 NaN（gscript 无 Infinity 表示）
            float b = v2.toFloatValue();
            if (b == 0) {
                return GSNaN.NAN;
            }
            return new GSFloat(v1.toFloatValue() / b);
        } else {
            // 其中有一个不是数字，返回非数
            return GSNaN.NAN;
        }
    }

    /**
     * 对值进行取模运算（JS 的 % 语义：截断除法的余数，与 Java % 一致）
     *
     * @param v1 值1
     * @param v2 值2
     * @return 结果
     */
    public static final GSValue modulo(GSValue v1, GSValue v2) {
        if (v1.type <= 3 && v2.type <= 3) {  // 数值类型计算
            // float类型提升
            if (v1.type == 3 || v2.type == 3) {
                return new GSFloat(v1.toFloatValue() % v2.toFloatValue());
            } else {
                return new GSInt(v1.toIntValue() % v2.toIntValue());
            }
        } else {
            // 其中有一个不是数字，返回非数
            return GSNaN.NAN;
        }
    }

    /**
     * 对值进行左移位运算
     *
     * @param v1 值1
     * @param v2 值2
     * @return 结果
     */
    public static final GSValue ls(GSValue v1, GSValue v2) {
        if (v1.type <= 3 && v2.type <= 3) {  // 数值类型计算
            // float类型提升
            return new GSInt(v1.toIntValue() << v2.toIntValue());
        } else {
            // 其中有一个不是数字，返回非数
            return GSNaN.NAN;
        }
    }

    /**
     * 对值进行右移位运算
     *
     * @param v1 值1
     * @param v2 值2
     * @return 结果
     */
    public static final GSValue rs(GSValue v1, GSValue v2) {
        if (v1.type <= 3 && v2.type <= 3) {  // 数值类型计算
            // float类型提升
            return new GSInt(v1.toIntValue() >> v2.toIntValue());
        } else {
            // 其中有一个不是数字，返回非数
            return GSNaN.NAN;
        }
    }

    /**
     * 对值进行按位与运算
     *
     * @param v1 值1
     * @param v2 值2
     * @return 结果
     */
    public static final GSValue b_and(GSValue v1, GSValue v2) {
        return new GSInt(v1.toIntValue() & v2.toIntValue());
    }

    /**
     * 对值进行按位或运算
     *
     * @param v1 值1
     * @param v2 值2
     * @return 结果
     */
    public static final GSValue b_or(GSValue v1, GSValue v2) {
        return new GSInt(v1.toIntValue() | v2.toIntValue());
    }

    /**
     * 对值进行按位异或运算
     *
     * @param v1 值1
     * @param v2 值2
     * @return 结果
     */
    public static final GSValue b_xor(GSValue v1, GSValue v2) {
        return new GSInt(v1.toIntValue() ^ v2.toIntValue());
    }

    /**
     * 对值进行按位取反运算
     *
     * @param v1 值1
     * @return 结果
     */
    public static final GSValue b_not(GSValue v1) {
        return new GSInt(~v1.toIntValue());
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
                return ((GSBool) this).value;
            case 2:  // GSInt
                return ((GSInt) this).value;
            case 3:  // GSFloat
                return ((GSFloat) this).value;
            case 5:  // GSString（value 私有，用 toStringValue）
                return this.toStringValue();
            case 8:  // GSNull
                return null;
            case 10: // GSNaN
                return Float.NaN;
            case 4:  // GSObject
                Map<String, Object> map = new LinkedHashMap<>();
                for (Map.Entry<String, GSValue> e : ((GSObject) this).getMembers().entrySet()) {
                    map.put(e.getKey(), e.getValue().toJavaObject());
                }
                return map;
            case 7:  // GSArray（extends GSObject，元素键为 "0","1",...）
                List<Object> list = new ArrayList<>();
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
        if (obj instanceof Integer) return new GSInt((Integer) obj);
        if (obj instanceof Float) return new GSFloat((Float) obj);
        if (obj instanceof Double) return new GSFloat(((Double) obj).floatValue());
        if (obj instanceof String) return new GSString((String) obj);
        if (obj instanceof Boolean) return GSBool.getGSBool((Boolean) obj);
        if (obj instanceof Map) {
            GSObject gso = new GSObject();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) obj).entrySet()) {
                gso.setProperty(e.getKey().toString(), fromJavaObject(e.getValue()));
            }
            return gso;
        }
        if (obj instanceof List) {
            GSArray gsa = new GSArray();
            int i = 0;
            for (Object item : (List<?>) obj) {
                gsa.setProperty(String.valueOf(i++), fromJavaObject(item));
            }
            return gsa;
        }
        throw new IllegalArgumentException("Cannot convert Java type to GSValue: " + obj.getClass());
    }

}
