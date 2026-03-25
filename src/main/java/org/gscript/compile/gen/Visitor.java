package org.gscript.compile.gen;

import org.gscript.compile.node.*;

/**
 * 遍历节点接口定义
 * 这里是使用的访问者模式
 */
public interface Visitor {

    void visit(ProgramNode node);

    void visit(BlockStatement node);

    void visit(VariableStatement node);

    void visit(IfStatement node);

    void visit(ForStatement node);

    void visit(DoWhileStatement node);

    void visit(WhileStatement node);

    void visit(FunctionStatement node);

    void visit(BreakStatement node);

    void visit(ContinueStatement node);

    void visit(ReturnStatement node);

    void visit(ThrowStatement throwStatement);

    void visit(ExceptionStatement exceptionStatement);

    void visit(ExpressionStatement node);

    void visit(Expression node);

    void visit(ConditionalExpression node);

    void visit(LogicalORExpression node);

    void visit(LogicalANDExpression node);

    void visit(BitwiseORExpression node);

    void visit(BitwiseXORExpression node);

    void visit(BitwiseANDExpression node);

    void visit(EqualityExpression node);

    void visit(RelationalExpression node);

    void visit(ShiftExpression node);

    void visit(AdditiveExpression node);

    void visit(MultiplicativeExpression node);

    void visit(UnaryExpression node);

    void visit(PostfixExpression node);

    void visit(Literal node);

    void visit(ObjectLiteral node);

    void visit(FunctionExpression node);

    void visit(NewExpression node);

    void visit(Identifier node);

    void visit(ArrayLiteral node);

    void visit(MemberAccess memberAccess);

    void visit(FunctionCallNode node);

    void visit(ParenthesizedExpression parenthesizedExpression);

    void visit(VariableDecl node);

    void visit(VariableDeclList node);

    void visit(TryClause node);

    void visit(CatchClause node);

    void visit(FinallyClause node);

}
