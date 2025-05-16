package gscript.vm.value;

public class GSStr extends GSValue {
    private String value;

    public GSStr(String value) {
        this.type = 5;
        this.value = value;
    }

    @Override
    public String toValueString() {
        return value;
    }
}
