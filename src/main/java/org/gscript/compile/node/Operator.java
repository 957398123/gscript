package org.gscript.compile.node;

import org.gscript.compile.token.GSToken;

public class Operator extends Node {

    public GSToken symbol;

    public Operator(GSToken symbol) {
        type = "Operator";
        this.symbol = symbol;
    }

}
