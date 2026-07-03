package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

import java.util.List;

public class ArrayLiteral extends Node {

    // 数组元素
    public List elements;

    public ArrayLiteral(List elements) {
        type = "ArrayLiteral";
        this.elements = elements;
    }

    public void accept(Visitor v) {
        v.visit(this);
    }
}
