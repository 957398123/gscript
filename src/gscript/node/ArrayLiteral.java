package gscript.node;

import gscript.gen.Visitor;

import java.util.List;

public class ArrayLiteral extends Node{
    public List<Node> elements;

    public ArrayLiteral(List<Node> elements) {
        type = "ArrayLiteral";
        this.elements = elements;
    }

    public void accept(Visitor v) {
        v.visit(this);
    }
}
