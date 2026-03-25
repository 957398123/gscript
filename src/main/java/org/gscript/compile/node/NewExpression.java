package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

import java.util.List;

public class NewExpression extends Node{

    public Expression constructor;

    public NewExpression(Expression constructor) {
        this.constructor = constructor;
    }

    public void accept(Visitor v) {
        v.visit(this);
    }
}
