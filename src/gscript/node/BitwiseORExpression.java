package gscript.node;

import gscript.gen.Visitor;

public class BitwiseORExpression extends OperatorNode{
    public BitwiseORExpression(Node left, Operator operator, Node right) {
        super(left, operator, right);
        type = "BitwiseORExpression";
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
