package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

public class TryClause extends Node{

    public BlockStatement tryBody;

    public TryClause(BlockStatement tryBody) {
        type = "TryClause";
        this.tryBody = tryBody;
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
