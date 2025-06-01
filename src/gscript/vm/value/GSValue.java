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
     * @return
     */
    public abstract String getStringValue();

    /**
     * 转为int
     * @return
     */
    public abstract int getIntValue();

    /**
     * 转为float
     * @return
     */
    public abstract float getFloatValue();

    /**
     * 转为布尔
     * @return
     */
    public abstract boolean getBoolean();
}
