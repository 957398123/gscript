package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

public class ContinueStatement extends Node {

    public ContinueStatement() {
        type = "ContinueStatement";
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
