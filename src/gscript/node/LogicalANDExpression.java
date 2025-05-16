package gscript.node;

import gscript.gen.Visitor;

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
