package org.gscript.compile.node;

import java.util.Hashtable;
import org.gscript.compile.gen.Visitor;

public class ObjectLiteral extends Node {

    // 成员列表
    public Hashtable<Node, Node> members;

    public ObjectLiteral(Hashtable<Node, Node> members) {
        type = "ObjectLiteral";
        this.members = members;
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
