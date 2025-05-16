package gscript.vm.value;

public class GSInt extends GSValue {

    public int value;

    public GSInt(int value) {
        this.type = 2;
        this.value = value;
    }

    @Override
    public String toValueString() {
        return value + "";
    }
}
