package gscript.node;

import gscript.gen.Visitor;

public class PostfixExpression extends Node {
    public Node operand;
    public Operator operator;

    public PostfixExpression(Node operand, Operator operator) {
        type = "PostfixExpression";
        this.operand = operand;
        this.operator = operator;
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
