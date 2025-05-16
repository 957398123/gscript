package gscript.node;

import gscript.gen.Visitor;

public class AdditiveExpression extends OperatorNode{
    public AdditiveExpression(Node left, Operator operator, Node right) {
        super(left, operator, right);
        type = "AdditiveExpression";
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
