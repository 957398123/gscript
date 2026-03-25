package org.gscript.vm.value;

/**
 * 非数
 */
public class GSNaN extends GSObject {

    public static final GSNaN NAN = new GSNaN();

    @Override
    public String toStringValue() {
        return "NaN";
    }

    private GSNaN() {
        this.type = 10;
    }
}
