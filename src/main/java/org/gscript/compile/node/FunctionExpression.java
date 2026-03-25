package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

import java.util.List;

public class FunctionExpression extends Node{
    // 函数名
    public Identifier identifier;

    // 形参列表
    public List<Identifier> params;

    // 方法体
    public BlockStatement body;

    public FunctionExpression(Identifier identifier, List<Identifier> params, BlockStatement body) {
        type = "FunctionExpression";
        this.identifier = identifier;
        this.params = params;
        this.body = body;
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
