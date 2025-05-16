package gscript.node;

import gscript.gen.Visitor;

public class BitwiseXORExpression extends OperatorNode {
    public BitwiseXORExpression(Node left, Operator operator, Node right) {
        super(left, operator, right);
        type = "BitwiseXORExpression";
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
