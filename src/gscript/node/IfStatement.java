package gscript.node;

import gscript.gen.Visitor;

public class IfStatement extends Node {
    // 条件
    public Expression condition;
    // then分支
    public BlockStatement thenBranch;
    // else分支 可能是else或者是else if
    public Node elseBranch;

    public IfStatement(Expression condition, BlockStatement thenBranch, Node elseBranch) {
        type = "IfStatement";
        this.condition = condition;
        this.thenBranch = thenBranch;
        this.elseBranch = elseBranch;
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
