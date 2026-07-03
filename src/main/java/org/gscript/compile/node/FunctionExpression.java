package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

import java.util.List;

public class FunctionExpression extends Node{
    // 函数名
    public Identifier identifier;

    // 形参列表
    public List params;

    // 方法体
    public BlockStatement body;

    public FunctionExpression(Identifier identifier, List params, BlockStatement body) {
        type = "FunctionExpression";
        this.identifier = identifier;
        this.params = params;
        this.body = body;
    }

    public void accept(Visitor v) {
        v.visit(this);
    }
}
