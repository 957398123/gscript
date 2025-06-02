package gscript.vm.value;

public abstract class GSWarpper extends GSObject{

    public GSWarpper() {
        this.type = 10;
    }

    public abstract Object getValue();
}
