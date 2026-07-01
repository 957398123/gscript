package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

/**
 * 语法树节点
 */
public class Node {
    // 节点名称
    public String type = "Node";

    /**
     * 节点对应源代码行号（1-based，0 表示未设置）。
     * 调试器用于生成"字节码索引 ↔ 源码行"映射，不影响语法和字节码结构。
     */
    public int line = 0;

    /**
     * 访问当前节点
     *
     * @param v
     */
    public void accept(Visitor v) {
    }
}
