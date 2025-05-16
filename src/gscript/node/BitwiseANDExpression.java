package gscript.node;

import gscript.gen.Visitor;

public class BitwiseANDExpression extends OperatorNode{

    public BitwiseANDExpression(Node left, Operator operator, Node right) {
        super(left, operator, right);
        type = "BitwiseANDExpression";
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
