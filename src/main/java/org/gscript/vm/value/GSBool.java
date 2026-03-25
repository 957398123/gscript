package org.gscript.vm.value;

public class GSBool extends GSValue {

    public boolean value;

    public static final GSBool TRUE = new GSBool(true);

    public static final GSBool FALSE = new GSBool(false);

    private GSBool(boolean value) {
        type = 1;
        this.value = value;
    }

    public static GSBool getGSBool(boolean value) {
        if (value) {
            return TRUE;
        } else {
            return FALSE;
        }
    }

    public static GSBool getGSBool(String value) {
        if ("true".equals(value)) {
            return TRUE;
        } else {
            return FALSE;
        }
    }

    public String toStringValue() {
        return value + "";
    }

    public int toIntValue() {
        if (value) {
            return 1;
        } else {
            return 0;
        }
    }

    public float toFloatValue() {
        if (value) {
            return 1;
        } else {
            return 0;
        }
    }

    public boolean toBoolean() {
        return value;
    }
}