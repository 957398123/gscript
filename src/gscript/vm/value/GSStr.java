package gscript.vm.value;

public class GSStr extends GSValue {
    private String value;

    public GSStr(String value) {
        this.type = 5;
        this.value = value;
    }

    @Override
    public String getStringValue() {
        return value;
    }

    @Override
    public int getIntValue() {
        return Integer.parseInt(value);
    }

    @Override
    public float getFloatValue() {
        return 1;
    }

    @Override
    public boolean getBoolean() {
        return "true".equals(value);
    }

    @Override
    public boolean eq(GSValue object) {
        if(object.type == 5){
            return value.equals(object.getStringValue());
        }else {
            return false;
        }
    }

    @Override
    public boolean neq(GSValue object) {
        return !eq(object);
    }
}
