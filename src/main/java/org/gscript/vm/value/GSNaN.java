package org.gscript.vm.value;

/**
 * 非数
 */
public class GSNaN extends GSObject {

    public static final GSNaN NAN = new GSNaN();

    public String toStringValue() {
        return "NaN";
    }

    private GSNaN() {
        this.type = 10;
    }

    public boolean toBoolean() {
        // NaN 是 falsy（JS 语义）
        return false;
    }

    /**
     * NaN 转 int（JS ToInt32 语义：NaN → 0）。
     * <p>对标 JS {@code NaN | 0 === 0}、{@code NaN >> 0 === 0}。
     *
     * @return 0
     */
    public int toIntValue() {
        return 0;  // JS ToInt32(NaN) === 0
    }

    /**
     * NaN 转 float（保持 NaN 语义，而非继承 GSObject 的 0）。
     * <p>对标 JS {@code Number(NaN) === NaN}、{@code NaN + 1 === NaN}。
     * 注意：GSObject 默认返回 0 会导致 {@code NaN == 0} 在 float 比较时误判为 true。
     *
     * @return Float.NaN
     */
    public float toFloatValue() {
        return Float.NaN;
    }
}
