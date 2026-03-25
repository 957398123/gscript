package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

public class UnaryExpression extends Node{

    // 操作符
    public Operator operator;

    // 操作数
    public Node operand;

    public UnaryExpression(Operator operator, Node operand) {
        type = "UnaryExpression";
        this.operator = operator;
        this.operand = operand;
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
