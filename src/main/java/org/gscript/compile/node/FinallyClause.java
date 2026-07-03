package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

public class FinallyClause extends Node{

    public BlockStatement finallyBody;

    public FinallyClause(BlockStatement finallyBody) {
        type = "FinallyClause";
        this.finallyBody = finallyBody;
    }

    public void accept(Visitor v) {
        v.visit(this);
    }
}
