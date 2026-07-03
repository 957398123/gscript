package org.gscript.compile.node;

import org.gscript.compile.token.GSToken;
import org.gscript.compile.gen.Visitor;

public class Literal extends Node {

    // token
    public GSToken token;

    public Literal(GSToken token) {
        type = "Literal";
        this.token = token;
    }

    public void accept(Visitor v) {
        v.visit(this);
    }
}
