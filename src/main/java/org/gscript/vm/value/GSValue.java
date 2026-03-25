package org.gscript.vm.value;

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
            // float类型提升
            if (v1.type == 3 || v2.type == 3) {
                return new GSFloat(v1.toFloatValue() / v2.toFloatValue());
            } else {
                return new GSInt(v1.toIntValue() / v2.toIntValue());
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

}
