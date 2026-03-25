package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

public class ReturnStatement extends Node {

    // return的值(可能为空)
    public Expression expression;

    public ReturnStatement(Expression expression) {
        type = "ReturnStatement";
        this.expression = expression;
    }

    public void accept(Visitor v) {
        v.visit(this);
    }
}
