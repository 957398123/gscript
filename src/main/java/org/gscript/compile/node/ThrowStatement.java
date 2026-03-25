package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

public class ThrowStatement extends Node {
    // throw的值(不为空)
    public Expression expression;

    public ThrowStatement(Expression expression) {
        type = "ThrowStatement";
        this.expression = expression;
    }

    public void accept(Visitor v) {
        v.visit(this);
    }

}
