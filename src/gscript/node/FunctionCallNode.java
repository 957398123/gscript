package gscript.node;

import gscript.gen.Visitor;

import java.util.List;

public class FunctionCallNode extends Node {
    public Node callee;
    public List<Expression> args;

    public FunctionCallNode(Node callee, List<Expression> args) {
        type = "FunctionCallNode";
        this.callee = callee;
        this.args = args;
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
