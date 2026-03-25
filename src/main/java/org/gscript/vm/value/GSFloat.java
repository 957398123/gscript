package org.gscript.vm.value;

public class GSFloat extends GSValue {

    public float value;

    public GSFloat(float value) {
        type = 3;
        this.value = value;
    }

    public GSFloat(String value) {
        type = 3;
        this.value = Float.parseFloat(value);
    }

    public String toStringValue() {
        return value + "";
    }

    public int toIntValue() {
        return (int) value;
    }

    public float toFloatValue() {
        return value;
    }

    public boolean toBoolean() {
        return value != 0;
    }

}