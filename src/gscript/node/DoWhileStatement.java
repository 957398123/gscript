package gscript.node;

import gscript.gen.Visitor;

public class DoWhileStatement extends Node {

    public BlockStatement body;

    public Expression condition;

    public DoWhileStatement(BlockStatement body, Expression condition) {
        type = "DoWhileStatement";
        this.body = body;
        this.condition = condition;
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
