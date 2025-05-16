package gscript.node;

import gscript.gen.Visitor;

public class RelationalExpression extends OperatorNode{

    public RelationalExpression(Node left, Operator operator, Node right) {
        super(left, operator, right);
        type = "RelationalExpression";
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
