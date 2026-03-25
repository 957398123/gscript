package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

public class LogicalANDExpression extends OperatorNode{

    public LogicalANDExpression(Node left, Operator operator, Node right) {
        super(left, operator, right);
        type = "LogicalANDExpression";
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
