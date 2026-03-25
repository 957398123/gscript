package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

public class PostfixExpression extends Node {

    // 操作数
    public Node operand;

    // 操作符
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
