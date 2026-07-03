package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

import java.util.List;

public class DoWhileStatement extends Node {

    // 循环体
    public List body;

    // 条件表达式
    public Expression condition;

    public DoWhileStatement(List body, Expression condition) {
        type = "DoWhileStatement";
        this.body = body;
        this.condition = condition;
    }

    public void accept(Visitor v) {
        v.visit(this);
    }
}
