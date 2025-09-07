package gscript.vm.value;

public abstract class GSWrapper extends GSObject{

    public GSWrapper() {
        this.type = 10;
    }

    public abstract Object getValue();
}
