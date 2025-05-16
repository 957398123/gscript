package gscript.node;

import gscript.gen.Visitor;

public class PropertyAccess extends Node {
    public Node object;
    public Identifier property;

    public PropertyAccess(Node object, Identifier property) {
        type = "PropertyAccess";
        this.object = object;
        this.property = property;
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
