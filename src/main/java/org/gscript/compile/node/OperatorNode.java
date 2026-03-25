package org.gscript.compile.node;

public class OperatorNode extends Node {
    // 左节点
    public Node left;
    // 操作符
    public Operator operator;
    // 右节点
    public Node right;

    public OperatorNode(Node left, Operator operator, Node right) {
        type = "OperatorNode";
        this.left = left;
        this.operator = operator;
        this.right = right;
    }
}
