package gscript.node;

import gscript.gen.Visitor;

public class ConditionalExpression extends Node {
    public Node condition;  // 条件部分（LogicalORExpression）
    public Expression thenExpr;   // "?"后的表达式
    public Expression elseExpr;   // ":"后的表达式

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
