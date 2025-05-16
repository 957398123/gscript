package gscript.node;

public class OperatorNode extends Node {
    public Node left;
    public Operator operator;
    public Node right;

    public OperatorNode(Node left, Operator operator, Node right) {
        type = "OperatorNode";
        this.left = left;
        this.operator = operator;
        this.right = right;
    }
}
