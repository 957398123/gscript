package gscript.node;

import gscript.gen.Visitor;

public class BreakStatement extends Node {
    public BreakStatement() {
        type = "BreakStatement";
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
