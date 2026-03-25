package org.gscript.compile.node;

import java.util.List;

import org.gscript.compile.gen.Visitor;

public class BlockStatement extends Node {

    // 语句列表
    public List<Node> stmts;

    public BlockStatement(List<Node> stmts) {
        type = "BlockStatement";
        this.stmts = stmts;
    }

    public void accept(Visitor v) {
        v.visit(this);
    }

    /**
     * 返回块语句最后一行是不是return
     *
     * @return 是否有return语句
     */
    public boolean havingReturn() {
        return stmts != null && !stmts.isEmpty() && stmts.get(stmts.size() - 1) instanceof ReturnStatement;
    }
}
