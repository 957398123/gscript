package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

public class MemberAccess extends Node {

    // 访问对象
    public Node object;

    // 访问成员(可能是一个标识符或者是表达式)
    public Node property;

    public MemberAccess(Node object, Node property) {
        type = "MemberAccess";
        this.object = object;
        this.property = property;
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
