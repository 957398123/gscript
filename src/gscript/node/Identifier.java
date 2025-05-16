package gscript.node;

import gscript.gen.Visitor;

public class Identifier extends Node {

    public String name;

    public Identifier(String name) {
        type = "Identifier";
        this.name = name;
    }

    public void accept(Visitor v) {
        v.visit(this);
    }
}
