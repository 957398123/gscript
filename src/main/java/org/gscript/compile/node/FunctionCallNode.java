package org.gscript.compile.node;

import java.util.List;

import org.gscript.compile.gen.Visitor;

public class FunctionCallNode extends Node {

    // 函数节点
    public Node callee;

    // 参数列表
    public List args;

    public FunctionCallNode(Node callee, List args) {
        type = "FunctionCallNode";
        this.callee = callee;
        this.args = args;
    }

    public void accept(Visitor v) {
        v.visit(this);
    }
}
