package org.gscript.compile.node;

import java.util.List;
import org.gscript.compile.gen.Visitor;

/**
 * 变量声明列表节点
 * 语法定义：<VariableStatement> ::= <VariableDeclList> ";"
 * 示例：var a, b = 3;
 */
public class VariableDeclList extends Node {

    // 变量声明列表
    public List<VariableDecl> decls;

    public VariableDeclList(List<VariableDecl> decl) {
        type = "VariableDeclList";
        this.decls = decl;
    }

    public void accept(Visitor v) {
        v.visit(this);
    }
}
