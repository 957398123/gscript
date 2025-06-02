package gscript.vm.value;

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
     * 10 wrapper object
     * 设计这个是快速类型运算
     */
    public int type = 0;

    /**
     * 转为string
     *
     * @return
     */
    public abstract String getStringValue();

    /**
     * 转为int
     *
     * @return
     */
    public abstract int getIntValue();

    /**
     * 转为float
     *
     * @return
     */
    public abstract float getFloatValue();

    /**
     * 转为布尔
     *
     * @return
     */
    public abstract boolean getBoolean();

    /**
     * 比较函数
     *
     * @param object
     * @return
     */
    public boolean eq(GSValue object) {
        return this == object;
    }

    /**
     * 是否不等于
     * @param object
     * @return
     */
    public boolean neq(GSValue object) {
        return this != object;
    }

    /**
     * 是否大于
     *
     * @param object
     * @return
     */
    public boolean gt(GSValue object) {
        return false;
    }

    /**
     * 是否大于等于
     *
     * @param object
     * @return
     */
    public boolean ge(GSValue object) {
        return false;
    }

    /**
     * 是否小于
     * @param object
     * @return
     */
    public boolean lt(GSValue object) {
        return false;
    }

    /**
     * 是否小于等于
     * @param object
     * @return
     */
    public boolean le(GSValue object) {
        return false;
    }

}
