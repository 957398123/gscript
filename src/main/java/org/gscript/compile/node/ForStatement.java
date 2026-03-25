package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

import java.util.List;

public class ForStatement extends Node {

    // 初始化节点
    public Node init;

    // 条件表达式
    public Expression condition;

    // 更新表达式
    public Expression update;

    // 循环体
    public List<Node> body;

    public ForStatement(Node init, Expression condition, Expression update, List<Node> body) {
        type = "ForStatement";
        this.init = init;
        this.condition = condition;
        this.update = update;
        this.body = body;
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
