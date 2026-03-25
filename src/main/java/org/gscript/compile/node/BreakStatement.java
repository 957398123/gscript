package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

public class BreakStatement extends Node {

    public BreakStatement() {
        type = "BreakStatement";
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
