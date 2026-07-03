package org.gscript.compile.node;

import java.util.Hashtable;
import org.gscript.compile.gen.Visitor;

public class ObjectLiteral extends Node {

    // 成员列表
    public Hashtable members;

    public ObjectLiteral(Hashtable members) {
        type = "ObjectLiteral";
        this.members = members;
    }

    public void accept(Visitor v) {
        v.visit(this);
    }
}
