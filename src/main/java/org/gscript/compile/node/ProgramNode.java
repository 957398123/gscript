package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

import java.util.List;

public class ProgramNode extends Node {

    // 语句列表
    public List<Node> stmts;

    public  ProgramNode(List<Node> stmts) {
        type = "Program";
        this.stmts = stmts;
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
