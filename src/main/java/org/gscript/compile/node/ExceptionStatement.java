package org.gscript.compile.node;

import org.gscript.compile.gen.Visitor;

public class ExceptionStatement extends Node{

    /**
     * try块
     */
    public TryClause tryClause;

    /**
     * 异常处理
     */
    public CatchClause catchClause;

    /**
     * 最终处理
     */
    public FinallyClause finallyBody;

    public ExceptionStatement(TryClause tryClause, CatchClause catchClause, FinallyClause finallyBody) {
        type = "ExceptionStatement";
        this.tryClause = tryClause;
        this.catchClause = catchClause;
        this.finallyBody = finallyBody;
    }

    @Override
    public void accept(Visitor v) {
        v.visit(this);
    }
}
