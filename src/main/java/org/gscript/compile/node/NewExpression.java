package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

public class NewExpression extends Node{

    public Expression constructor;

    public NewExpression(Expression constructor) {
        type = "NewExpression";
        this.constructor = constructor;
    }

    public void accept(Visitor v) {
        v.visit(this);
    }
}
