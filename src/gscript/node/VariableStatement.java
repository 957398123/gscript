package gscript.node;

import gscript.gen.Visitor;

public class VariableStatement extends Node {

    public VariableDeclList args;

    public VariableStatement(VariableDeclList args){
        type = "VariableStatement";
        this.args = args;
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
