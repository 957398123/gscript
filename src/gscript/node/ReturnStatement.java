package gscript.node;

import gscript.gen.Visitor;

public class ReturnStatement extends Node {

    public Expression expression;

    public ReturnStatement(Expression expression) {
        type = "ReturnStatement";
        this.expression = expression;
    }

    public void accept(Visitor v) {
        v.visit(this);
    }
}
