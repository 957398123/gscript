package gscript.node;

import gscript.gen.Visitor;

import java.util.List;

public class VariableDeclList extends Node {

    public List<VariableDecl> decls;

    public VariableDeclList(List<VariableDecl> decl) {
        type = "VariableDeclList";
        this.decls = decl;
    }

    public void accept(Visitor v) {
        v.visit(this);
    }
}
