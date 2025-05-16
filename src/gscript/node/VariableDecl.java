package gscript.node;

import gscript.gen.Visitor;

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
