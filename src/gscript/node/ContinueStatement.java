package gscript.node;

import gscript.gen.Visitor;

public class ContinueStatement extends Node {
    public ContinueStatement() {
        type = "ContinueStatement";
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
