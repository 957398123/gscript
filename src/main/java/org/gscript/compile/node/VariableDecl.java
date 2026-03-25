package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

/**
 * 变量声明节点
 * 语法定义：<VariableDecl> ::= <Identifier> [ "=" <Expression> ]
 * 示例：var a = 5;
 */
public class VariableDecl extends Node {

    // 标识符
    public Identifier identifier;

    // 值
    public Expression value;

    public VariableDecl(Identifier identifier, Expression value) {
        type = "VariableDecl";
        this.identifier = identifier;
        this.value = value;
    }

    public void accept(Visitor v) {
        v.visit(this);
    }

}
