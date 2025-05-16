package gscript.node;

import gscript.gen.Visitor;

public class LogicalORExpression extends OperatorNode{

    public LogicalORExpression(Node left, Operator operator, Node right) {
        super(left, operator, right);
        type = "LogicalORExpression";
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
