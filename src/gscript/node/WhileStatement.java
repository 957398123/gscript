package gscript.node;

import gscript.gen.Visitor;

public class WhileStatement extends Node {

    public Expression condition;

    public BlockStatement body;

    public WhileStatement(Expression condition, BlockStatement body) {
        type = "WhileStatement";
        this.condition = condition;
        this.body = body;
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
