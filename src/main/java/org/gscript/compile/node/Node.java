package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

/**
 * 语法树节点
 */
public class Node {
    // 节点名称
    public String type = "Node";

    /**
     * 访问当前节点
     *
     * @param v
     */
    public void accept(Visitor v) {
    }
}
