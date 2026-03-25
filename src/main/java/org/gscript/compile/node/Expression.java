package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

public class Expression extends Node {

    // 条件表达式
    public Node left;

    // 关联操作符
    public Operator operator;

    // 右表达式
    public Expression right;

    public Expression(Node left, Operator operator, Expression right) {
        type = "Expression";
        this.left = left;
        this.operator = operator;
        this.right = right;
    }

    public void accept(Visitor v) {
        v.visit(this);
    }
}
