package gscript.vm.value;

import gscript.Interpreter;
import gscript.vm.GSException;
import org.ietf.jgss.GSSException;

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
    public void callFunction(Interpreter context
            , GSFunction name, List<GSValue> args) {
        throw new GSException("Cannot read properties of null (setting '%s')".formatted(name));
    }

    @Override
    public String toValueString() {
        return "null";
    }
}
