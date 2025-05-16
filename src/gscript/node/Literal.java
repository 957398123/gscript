package gscript.node;

import gscript.token.GSToken;
import gscript.gen.Visitor;

public class Literal extends Node {
    public GSToken token;

    public Literal(GSToken token) {
        type = "Literal";
        this.token = token;
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
