package org.gscript.vm.value;

public class GSInt extends GSValue {

    public int value;

    public GSInt(int value) {
        this.type = 2;
        this.value = value;
    }

    public GSInt(String value) {
        this.type = 2;
        this.value = Integer.parseInt(value);
    }

    public String toStringValue() {
        return value + "";
    }

    public int toIntValue() {
        return value;
    }

    public float toFloatValue() {
        return (float) value;
    }

    public boolean toBoolean() {
        return value != 0;
    }

}
