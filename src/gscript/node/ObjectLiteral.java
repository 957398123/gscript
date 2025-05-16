package gscript.node;

import gscript.gen.Visitor;

import java.util.Hashtable;

public class ObjectLiteral extends Node {
    public Hashtable<Node, Node> members;

    public ObjectLiteral(Hashtable<Node, Node> members) {
        type = "ObjectLiteral";
        this.members = members;
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
