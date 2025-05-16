package gscript.node;

import gscript.gen.Visitor;

import java.util.List;

public class BlockStatement extends Node {
    // 语句列表
    public List<Node> stmt;

    public BlockStatement(List<Node> stmt) {
        type = "BlockStatement";
        this.stmt = stmt;
    }

    public void accept(Visitor v) {
        v.visit(this);
    }
}
