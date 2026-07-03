package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

import java.util.List;

public class CatchClause extends Node {
    // 捕获的异常形参
    public Identifier identifier;

    // 方法体
    public List body;

    public CatchClause(Identifier identifier, List body) {
        type = "CatchClause";
        this.identifier = identifier;
        this.body = body;
    }

    public void accept(Visitor v) {
        v.visit(this);
    }
}
