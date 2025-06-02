package gscript.vm.value;

import gscript.vm.Interpreter;
import gscript.vm.GSException;

import java.util.List;

public class GSNull extends GSObject {
    private GSNull() {
        this.type = 8;
    }

    public static final GSNull NULL = new GSNull();

    @Override
    public void setProperty(String name, GSValue value) {
        throw new GSException("Cannot set properties of null (setting '%s')".formatted(name));
    }

    @Override
    public GSValue getProperty(String name) {
        throw new GSException("Cannot read properties of null (setting '%s')".formatted(name));
    }

    @Override
    public String getStringValue() {
        return "null";
    }

    @Override
    public boolean getBoolean() {
        return false;
    }

    @Override
    public boolean eq(GSValue object) {
        return object.type == 8;
    }

    @Override
    public boolean neq(GSValue object) {
        return object.type != 8;
    }
}
