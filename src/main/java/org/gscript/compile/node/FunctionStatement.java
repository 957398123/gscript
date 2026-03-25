package org.gscript.compile.node;

import java.util.List;

import org.gscript.compile.gen.Visitor;

public class FunctionStatement extends FunctionExpression {

    public FunctionStatement(Identifier identifier, List<Identifier> params, BlockStatement body) {
        super(identifier, params, body);
        type = "FunctionStatement";
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
