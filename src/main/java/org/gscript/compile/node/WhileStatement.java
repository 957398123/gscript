package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

import java.util.List;

/**
 * while语句节点
 * 语法定义：<WhileStatement> ::= "while" "(" <Expression> ")" <BlockStatement>
 * 示例：
 *   while(a > 5){
 *       ++i;
 *   }
 */
public class WhileStatement extends Node {

    // 判断条件
    public Expression condition;

    // 循环体
    public List<Node> body;

    public WhileStatement(Expression condition, List<Node> body) {
        type = "WhileStatement";
        this.condition = condition;
        this.body = body;
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
