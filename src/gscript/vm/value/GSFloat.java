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
}
