package gscript.vm.value;

public class GSInt extends GSValue {

    public int value;

    public GSInt(int value) {
        this.type = 2;
        this.value = value;
    }

    @Override
    public String getStringValue() {
        return value + "";
    }

    @Override
    public int getIntValue() {
        return value;
    }

    @Override
    public float getFloatValue() {
        return (float) value;
    }

    @Override
    public boolean getBoolean() {
        return value != 0;
    }

    @Override
    public boolean eq(GSValue object) {
        return value == object.getIntValue();
    }

    @Override
    public boolean neq(GSValue object) {
        return value != object.getIntValue();
    }

    @Override
    public boolean gt(GSValue object) {
        return value > object.getIntValue();
    }

    @Override
    public boolean ge(GSValue object) {
        return value >= object.getIntValue();
    }

    @Override
    public boolean lt(GSValue object) {
        return value < object.getIntValue();
    }

    @Override
    public boolean le(GSValue object) {
        return value <= object.getIntValue();
    }
}
