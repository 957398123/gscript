package gscript.vm.value;

public class GSBool extends GSValue {

    public boolean value;

    public GSBool(boolean value) {
        type = 1;
        this.value = value;
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
    public boolean neq(GSValue object){
        return value != object.getBoolean();
    }
}
