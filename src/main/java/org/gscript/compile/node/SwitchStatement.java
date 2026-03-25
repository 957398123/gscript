package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

import java.util.List;

public class SwitchStatement extends Node {

    // 条件表达式
    public Expression condition;

    // case条件
    public List<Expression> cases;

    // case对应的语句
    public List<BlockStatement> blocks;

    // case与block的映射位置
    public int[] offsetMap;

    public boolean hasDefault = false;

    public SwitchStatement(Expression condition, List<Expression> cases, List<BlockStatement> blocks, int[] offsetMap, boolean hasDefault) {
        this.condition = condition;
        this.cases = cases;
        this.blocks = blocks;
        this.offsetMap = offsetMap;
        this.hasDefault = hasDefault;
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
