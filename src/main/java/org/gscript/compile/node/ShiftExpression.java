package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

public class ShiftExpression extends OperatorNode{

    public ShiftExpression(Node left, Operator operator, Node right) {
        super(left, operator, right);
        type = "ShiftExpression";
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
