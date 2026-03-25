package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

public class ParenthesizedExpression extends Node{
    // 表达式
    public Expression expression;

    public ParenthesizedExpression(Expression expression) {
        type = "ParenthesizedExpression";
        this.expression = expression;
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
