package gscript.node;

import gscript.gen.Visitor;

public class ExpressionStatement extends Node {
    public Expression expr;

    public ExpressionStatement(Expression expr) {
        type = "ExpressionStatement";
        this.expr = expr;
    }

    public void accept(Visitor v) {
        v.visit(this);
    }
}
