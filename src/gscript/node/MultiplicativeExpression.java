package gscript.node;

import gscript.gen.Visitor;

public class MultiplicativeExpression extends OperatorNode{
    public MultiplicativeExpression(Node left, Operator operator, Node right) {
        super(left, operator, right);
        type = "MultiplicativeExpression";
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
