package gscript.gen;

import gscript.node.*;

/**
 * 遍历所有子节点
 * 这里能正常生成字节码，肯定遍历了所有节点
 */
public interface Visitor {

    public void visit(VariableStatement node);

    void visit(FunctionStatement node);

    void visit(BlockStatement node);

    void visit(ProgramNode program);

    void visit(Expression node);

    void visit(ConditionalExpression node);

    void visit(Literal node);

    void visit(Identifier node);

    void visit(AdditiveExpression node);

    void visit(ExpressionStatement node);

    void visit(VariableDecl node);

    void visit(PropertyAccess node);

    void visit(ComputedMemberNode node);

    /**
     * 访问函数调用
     * @param node
     */
    void visit(FunctionCallNode node);

    void visit(ReturnStatement node);

    void visit(UnaryExpression node);

    void visit(IfStatement node);

    void visit(EqualityExpression node);

    void visit(DoWhileStatement node);

    void visit(ForStatement node);

    void visit(VariableDeclList node);

    void visit(RelationalExpression node);

    void visit(WhileStatement node);

    void visit(ContinueStatement node);

    void visit(BreakStatement node);

    void visit(PostfixExpression node);

    void visit(LogicalORExpression node);

    void visit(LogicalANDExpression node);

    void visit(BitwiseORExpression node);

    void visit(BitwiseXORExpression node);

    void visit(BitwiseANDExpression node);

    void visit(ObjectLiteral node);

    void visit(ArrayLiteral node);

    void visit(MultiplicativeExpression node);

    void visit(ShiftExpression node);

    void visit(MemberFunctionStatement expr);
}
