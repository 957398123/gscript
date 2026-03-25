package org.gscript.vm.value;

public class GSNull extends GSObject {

    public static final GSNull NULL = new GSNull();

    private GSNull() {
        this.type = 8;
    }

    @Override
    public String toStringValue() {
        return "null";
    }

    @Override
    public int toIntValue() {
        return 0;
    }

    @Override
    public float toFloatValue() {
        return 0;
    }

    @Override
    public boolean toBoolean() {
        return false;
    }
}
