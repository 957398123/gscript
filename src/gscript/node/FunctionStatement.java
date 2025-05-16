package gscript.node;

import gscript.gen.Visitor;

import java.util.List;

public class FunctionStatement extends Node {

    public Identifier identifier;

    public List<Identifier> params;

    public List<Node> body;

    public FunctionStatement(Identifier identifier, List<Identifier> params, List<Node> body) {
        type = "FunctionStatement";
        this.identifier = identifier;
        this.params = params;
        this.body = body;
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
