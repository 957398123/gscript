package gscript.node;

import gscript.gen.Visitor;

public class EqualityExpression extends OperatorNode {
    public EqualityExpression(Node left, Operator operator, Node right) {
        super(left, operator, right);
        type = "EqualityExpression";
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
