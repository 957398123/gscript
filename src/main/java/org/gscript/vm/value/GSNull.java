package org.gscript.vm.value;

public class GSNull extends GSObject {

    public static final GSNull NULL = new GSNull();

    private GSNull() {
        this.type = 8;
    }

    public String toStringValue() {
        return "null";
    }

    public int toIntValue() {
        return 0;
    }

    public float toFloatValue() {
        return 0;
    }

    public boolean toBoolean() {
        return false;
    }
}
