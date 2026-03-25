package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

/**
 * 变量声明语句节点
 * 语法定义：<VariableStatement>        ::= <VariableDeclList> ";"
 */
public class VariableStatement extends Node {

    public VariableDeclList args;

    public VariableStatement(VariableDeclList args){
        type = "VariableStatement";
        this.args = args;
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
