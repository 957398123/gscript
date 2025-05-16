package gscript.node;

import gscript.gen.Visitor;

public class ForStatement extends Node {
    public Node init;       // 初始化部分（可能是变量声明或表达式）
    public Expression condition;  // 条件表达式（可能为null）
    public Expression update;     // 更新表达式（可能为null）
    public BlockStatement body; // 循环体（必须为块语句）

    public ForStatement(Node init, Expression condition, Expression update, BlockStatement body) {
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
