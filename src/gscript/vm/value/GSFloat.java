package gscript.vm.value;

public class GSFloat extends GSValue {
    public float value;

    public GSFloat(float value) {
        type = 3;
        this.value = value;
    }

    @Override
    public String toValueString() {
        return value + "";
    }
}
