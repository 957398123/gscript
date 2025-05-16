package gscript.node;

import gscript.gen.Visitor;

public class ComputedMemberNode extends Node {
    public Node object;
    public Expression expression;

    public ComputedMemberNode(Node object, Expression expression) {
        type = "ComputedMemberNode";
        this.object = object;
        this.expression = expression;
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
