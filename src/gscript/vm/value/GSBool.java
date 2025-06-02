package gscript.vm.value;

public class GSBool extends GSValue {

    private boolean value;

    public static final GSBool TRUE = new GSBool(true);

    public static final GSBool FALSE = new GSBool(false);

    private GSBool(boolean value) {
        type = 1;
        this.value = value;
    }

    public static GSBool getGSBool(boolean value) {
        if (value) return TRUE;
        else return FALSE;
    }

    @Override
    public String getStringValue() {
        return value + "";
    }

    @Override
    public int getIntValue() {
        if (value) {
            return 1;
        } else {
            return 0;
        }
    }

    @Override
    public float getFloatValue() {
        if (value) {
            return 1;
        } else {
            return 0;
        }
    }

    @Override
    public boolean getBoolean() {
        return value;
    }

    @Override
    public boolean eq(GSValue object) {
        return value = object.getBoolean();
    }

    @Override
    public boolean neq(GSValue object) {
        return value != object.getBoolean();
    }
}
