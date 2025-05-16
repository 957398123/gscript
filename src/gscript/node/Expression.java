package gscript.node;

import gscript.gen.Visitor;

public class Expression extends Node {
    public ConditionalExpression left;
    public Operator operator;
    public Expression right;

    public Expression(ConditionalExpression left, Operator operator, Expression right) {
        type = "Expression";
        this.left = left;
        this.operator = operator;
        this.right = right;
    }

    public void accept(Visitor v) {
        v.visit(this);
    }
}
