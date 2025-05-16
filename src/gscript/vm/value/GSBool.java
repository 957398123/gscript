package gscript.vm.value;

public class GSBool extends GSValue {

    public boolean value;

    public GSBool(boolean value) {
        type = 1;
        this.value = value;
    }

    @Override
    public String toValueString() {
        return value + "";
    }
}
