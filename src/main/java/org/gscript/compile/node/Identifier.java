package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

public class Identifier extends Node {

    // 标识符
    public String name;

    public Identifier(String name) {
        type = "Identifier";
        this.name = name;
    }

    public void accept(Visitor v) {
        v.visit(this);
    }
}
