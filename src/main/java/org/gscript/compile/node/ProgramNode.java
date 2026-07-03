package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

import java.util.List;

public class ProgramNode extends Node {

    // 语句列表
    public List stmts;

    public  ProgramNode(List stmts) {
        type = "Program";
        this.stmts = stmts;
    }

    public void accept(Visitor v) {
        v.visit(this);
    }
}
