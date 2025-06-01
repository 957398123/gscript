package gscript.vm.value;

public class GSWarpper extends GSValue{

    private Object value;

    public GSWarpper(Object value) {
        this.type = 10;
        this.value = value;
    }

    public Object getValue(){
        return value;
    }

    @Override
    public String getStringValue() {
        return "";
    }

    @Override
    public int getIntValue() {
        return 0;
    }

    @Override
    public float getFloatValue() {
        return 0;
    }

    @Override
    public boolean getBoolean() {
        return false;
    }
}
