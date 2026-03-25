package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

import java.util.List;

public class ArrayLiteral extends Node {

    // 数组元素
    public List<Node> elements;

    public ArrayLiteral(List<Node> elements) {
        type = "ArrayLiteral";
        this.elements = elements;
    }

    public void accept(Visitor v) {
        v.visit(this);
    }
}
