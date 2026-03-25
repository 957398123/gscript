package org.gscript.compile.node;

import java.util.List;

import org.gscript.compile.gen.Visitor;

public class FunctionCallNode extends Node {

    // 函数节点
    public Node callee;

    // 参数列表
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
