package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

public class ExpressionStatement extends Node {

    // 表达式
    public Expression expr;

    public ExpressionStatement(Expression expr) {
        type = "ExpressionStatement";
        this.expr = expr;
    }

    public void accept(Visitor v) {
        v.visit(this);
    }
}
