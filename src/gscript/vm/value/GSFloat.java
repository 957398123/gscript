package gscript.vm.value;

public class GSFloat extends GSValue {
    public float value;

    public GSFloat(float value) {
        type = 3;
        this.value = value;
    }

    @Override
    public String getStringValue() {
        return value + "";
    }

    @Override
    public int getIntValue() {
        return (int) value;
    }

    @Override
    public float getFloatValue() {
        return value;
    }

    @Override
    public boolean getBoolean() {
        return value != 0;
    }

    @Override
    public boolean eq(GSValue object) {
        return value == object.getFloatValue();
    }

    @Override
    public boolean neq(GSValue object){
        return value != object.getFloatValue();
    }

    @Override
    public boolean gt(GSValue object) {
        return value > object.getFloatValue();
    }

    @Override
    public boolean ge(GSValue object) {
        return value >= object.getFloatValue();
    }

    @Override
    public boolean lt(GSValue object) {
        return value < object.getFloatValue();
    }

    @Override
    public boolean le(GSValue object) {
        return value <= object.getFloatValue();
    }
}
