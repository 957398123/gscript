package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

public class ConditionalExpression extends Node {

    // 判定条件
    public Node condition;

    // 条件为真时返回值("?"后的表达式)
    public Expression thenExpr;

    // 条件为假时返回值(":"后的表达式)
    public Expression elseExpr;

    public ConditionalExpression(Node condition, Expression thenExpr, Expression elseExpr) {
        type = "ConditionalExpression";
        this.condition = condition;
        this.thenExpr = thenExpr;
        this.elseExpr = elseExpr;
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
