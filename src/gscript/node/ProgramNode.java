package gscript.node;

import gscript.gen.Visitor;

import java.util.List;

public class ProgramNode extends Node {

    // 语句列表
    public List<Node> stmt;

    // 构造程序节点
    public  ProgramNode(List<Node> stmt) {
        type = "Program";
        this.stmt = stmt;
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
