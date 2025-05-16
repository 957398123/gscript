package gscript.node;

import gscript.gen.Visitor;

public class Node {
    // 节点名称
    public String type = "Node";

    // 接收访问者
    public void accept(Visitor v) {
    }
}
