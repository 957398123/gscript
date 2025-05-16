package gscript.node;

import gscript.gen.Visitor;

/**
 * 一元表达式
 */
public class UnaryExpression extends Node{

    public Operator operator;  // "+", "-", "!", "~", "++", "--"

    public Node operand;

    public UnaryExpression(Operator operator, Node operand) {
        this.operator = operator;
        this.operand = operand;
        type = "UnaryExpression";
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
