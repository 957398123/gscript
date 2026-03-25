package org.gscript.compile.gen;

import org.gscript.compile.node.*;
import org.gscript.compile.token.GSToken;
import org.gscript.compile.token.GSTokenType;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;

public class ByteCodeGenerator implements Visitor {

    /**
     * 字节码
     */
    private final ArrayList<String> bytecode = new ArrayList<>();

    /**
     * 获取生成的字节码
     */
    public ArrayList<String> getBytecode() {
        return bytecode;
    }

    /**
     * 打印字节码
     */
    public void print() {
        int index = 0;
        for (String s : bytecode) {
            System.out.println(index++ + ": " + s);
        }
    }

    /**
     * 获取字节码
     *
     * @return 字节码
     */
    public ArrayList<String> getByteCode() {
        return bytecode;
    }

    /**
     * 获取可视化化的字节码
     *
     * @return 可视化化的字节码
     */
    public ArrayList<String> getFormatByteCode() {
        ArrayList<String> format = new ArrayList<>();
        int index = 0;
        for (String s : bytecode) {
            format.add(index++ + ": " + s);
        }
        return format;
    }

    /**
     * 提交字节码
     */
    public void emit(String byteCode) {
        bytecode.add(byteCode);
    }

    /**
     * 替换指定位置字节码
     */
    public void emit(int index, String byteCode) {
        bytecode.set(index, byteCode);
    }

    /**
     * 获取指定位置字节码
     */
    public String get(int index) {
        return bytecode.get(index);
    }

    /**
     * 错误输出
     */
    public void error(String msg) {
        throw new RuntimeException("Uncaught ReferenceError: " + msg);
    }

    /**
     * 获取字节码大小
     */
    public int size() {
        return bytecode.size();
    }

    /**
     * 当前定义的函数位置
     * 整个字节码程序可以看作一个匿名函数所以这个值在开始的时候是0
     * 在函数语句或者函数表达式结束的时候，需要将其置为0
     */
    private int definedFunctionOffset = 0;

    /**
     * 生成程序字节码
     *
     * @param node 程序节点
     */
    @Override
    public void visit(ProgramNode node) {
        List<Node> stmts = node.stmts;
        // 将函数声明提前
        handleFunctionDeclare(stmts);
        // 生成字节码
        for (Node stmt : stmts) {
            stmt.accept(this);
        }
    }

    /**
     * 生成块语句字节码
     *
     * @param node 块语句节点
     */
    @Override
    public void visit(BlockStatement node) {
        List<Node> stmts = node.stmts;
        // 将函数声明提前
        handleFunctionDeclare(stmts);
        // 创建块域
        emit("pushenv block");
        // 解析块语句内容
        for (Node stmt : stmts) {
            stmt.accept(this);
        }
        // 销毁块域
        emit("popenv block");
    }

    /**
     * 生成变量声明字节码
     *
     * @param node 变量声明语句
     */
    @Override
    public void visit(VariableStatement node) {
        node.args.accept(this);
    }

    /**
     * 生成if语句字节码
     *
     * @param node if语句节点
     */
    @Override
    public void visit(IfStatement node) {
        // 处理If条件(if里面的条件表达式是不能为空的)
        node.condition.accept(this);
        // 如果有 else，这里先预填一个跳转
        int start = size();
        // 这里先预填跳转语句
        emit("");
        // 这里处理then语句
        node.thenBranch.accept(this);
        int thenEnd = size();
        // 如果存在else，跳转至结尾，这里预填
        if (node.elseBranch != null) {
            emit("");
        }
        // 回填跳转判断
        emit(start, String.format("false_jump %d", size() - start));
        if (node.elseBranch != null) {
            node.elseBranch.accept(this);
            // 这里是如果有else，才在if执行完了以后直接跳到else后面
            emit(thenEnd, String.format("jump %d", size() - thenEnd));
        }
    }

    /**
     * 生成for语句字节码
     *
     * @param node for语句字节码
     */
    @Override
    public void visit(ForStatement node) {
        Node init = node.init;
        Expression condition = node.condition;
        Expression update = node.update;
        // 首先解析init
        if (init != null) {
            init.accept(this);
            // 这里如果是Expression就需要出栈Expression的值
            if (init instanceof Expression) {
                emit("pop");
            }
        }
        // 条件表达式开始位置
        int cStart = size();
        if (condition != null) {
            condition.accept(this);
            // 这里预填结束循环跳转，false_jump会消耗表达式的值
            emit("");
        }
        // body开始位置
        int bodyStart = size();
        // 解析body
        handleLoopStatement(node.body);
        // body结束位置
        int bodyEnd = size();
        // 如果有update，执行update
        if (update != null) {
            update.accept(this);
            // update表达式的值需要出栈
            emit("pop");
        }
        // 不管update有没有实际内容，update后需要跳转至condition
        emit(String.format("jump %d", cStart - size()));
        // 回填条件表达式跳转结束地址
        if (condition != null) {
            emit(bodyStart - 1, String.format("false_jump %d", size() - bodyStart + 1));
        }
        // 这里对这段循环体里面的continue和break进行处理，替换成对应的跳转
        handleLoopJump(node.body, bodyStart, bodyEnd, bodyEnd, size());
    }

    /**
     * 生成 do-while字节码
     *
     * @param node do-while节点
     */
    @Override
    public void visit(DoWhileStatement node) {
        // 解析body
        // body开始位置
        int bodyStart = size();
        // 解析body
        handleLoopStatement(node.body);
        // body结束位置
        int bodyEnd = size();
        // 执行表达式
        node.condition.accept(this);
        // 对栈顶的值进行取反，方便false跳转
        emit("rela_op l_not");
        // 如果是表达式值为true，继续执行循环体
        emit(String.format("false_jump %d", bodyStart - size()));
        // 这里对这段循环体里面的continue和break进行处理，替换成对应的跳转
        handleLoopJump(node.body, bodyStart, bodyEnd, bodyEnd, size());
    }

    /**
     * 生成while语句字节码
     *
     * @param node while语句节点
     */
    @Override
    public void visit(WhileStatement node) {
        // 条件表达式开始位置
        int cStart = size();
        // 解析条件表达式
        node.condition.accept(this);
        // 预填条件表达式false跳转
        emit("");
        // body开始位置
        int bodyStart = size();
        // 解析body
        handleLoopStatement(node.body);
        // body结束位置
        int bodyEnd = size();
        // body结尾跳转到条件判断
        emit(String.format("jump %d", cStart - size()));
        // 回填条件判断的false跳转
        emit(bodyStart - 1, String.format("false_jump %d", size() - bodyStart + 1));
        // 这里对这段循环体里面的continue和break进行处理，替换成对应的跳转
        handleLoopJump(node.body, bodyStart, bodyEnd, cStart, size());
    }

    /**
     * 生成函数语句字节码
     *
     * @param node 函数语句字节码
     */
    @Override
    public void visit(FunctionStatement node) {
        String identifier = node.identifier.name;
        // 声明函数变量
        emit(String.format("declare %s", identifier));
        // 更新当前函数定义位置
        definedFunctionOffset = size() + 1;
        // 处理函数声明
        handleFunctionDeclaration(node);
        // 还原定义
        definedFunctionOffset = 0;
        // 赋值变量
        emit(String.format("store %s", identifier));
    }

    /**
     * 生成break语句字节码
     *
     * @param node
     */
    @Override
    public void visit(BreakStatement node) {
        // 这里直接提交break，让其上一级处理
        emit("break");
    }

    /**
     * 生成continue语句字节码
     *
     * @param node continue语句字节码
     */
    @Override
    public void visit(ContinueStatement node) {
        // 这里直接提交continue，让其上一级处理
        emit("continue");
    }

    /**
     * 生成return字节码
     *
     * @param node return字节码
     */
    @Override
    public void visit(ReturnStatement node) {
        // 如果有返回值
        if (node.expression != null) {
            node.expression.accept(this);
        } else {
            // 栈顶放上null
            emit("lda_null");
        }
        emit("return");
    }

    /**
     * 生成throw字节码
     *
     * @param node throw字节码
     */
    @Override
    public void visit(ThrowStatement node) {
        // 首先计算抛出异常值
        node.expression.accept(this);
        // 生成throw字节码
        emit("throw");
    }

    /**
     * 生成异常处理语句字节码
     * 这里必须要注意，异常的处理是相当于当前定义的函数的offset
     *
     * @param node 异常处理语句字节码
     */
    @Override
    public void visit(ExceptionStatement node) {
        // try块起始位置
        int tStart = size();
        // 首先生成try字节码
        node.tryClause.accept(this);
        int tEnd = size() - 1;
        int cStart = -1;
        if (node.catchClause != null) {
            // 有catch的话，try_end以后跳转至catch后面
            emit("");
            cStart = size();
            node.catchClause.accept(this);
            emit(cStart - 1, String.format("jump %d", size() - cStart + 1));
        }
        int fStart = -1;
        if (node.finallyBody != null) {
            fStart = size();
            node.finallyBody.accept(this);
        }
        // 回填try块的try_start字节码 这里需要减去当前函数的offset
        cStart = cStart == -1 ? -1 : cStart - definedFunctionOffset;
        fStart = fStart == -1 ? -1 : fStart - definedFunctionOffset;
        emit(tStart, String.format("try_start %d %d %d %d", tStart - definedFunctionOffset, tEnd - definedFunctionOffset, cStart, fStart));
    }

    /**
     * 生成表达式语句字节码
     *
     * @param node 表达式语句字节码
     */
    @Override
    public void visit(ExpressionStatement node) {
        // 计算表达式
        node.expr.accept(this);
        // 纯表达式语句要出栈栈顶的表达式的值
        emit("pop");
    }

    /**
     * 生成表达式字节码
     *
     * @param expr 表达式字节码
     */
    @Override
    public void visit(Expression expr) {
        // "=" | "+=" | "-=" | "*=" | "/=" | "%=" | "&=" | "|=" | "^=" 必须要有效的左值
        if (expr.operator != null) {
            // 这里要进行判断是否是括号表达式，把括号表达式里面的东西取出来，因为(a)这种也是支持的
            Node left = expr.left;
            if (left instanceof ParenthesizedExpression) {
                left = ((ParenthesizedExpression) left).expression;
            }
            // 接下来判断是不是左值
            if (left instanceof Identifier) {
                Identifier node = (Identifier) left;
                GSTokenType type = expr.operator.symbol.type;
                if (type != GSTokenType.EQ) {
                    // +=这种运算符要先获取当前左值
                    node.accept(this);
                }
                expr.right.accept(this);
                emitAssignmentOperator(type);
                // 表达式计算完了以后有值
                emit("copy");
                emit("store " + node.name);
            } else if (left instanceof MemberAccess) {  // 成员访问表达式
                // 赋值对象
                MemberAccess node = (MemberAccess) left;
                // 赋值操作符
                GSTokenType type = expr.operator.symbol.type;
                // 获取左值引用
                node.object.accept(this);
                // 访问key表达式
                if (node.property instanceof Identifier) {
                    Identifier property = (Identifier) node.property;
                    emit(String.format("const s %s", property.name));
                } else {
                    node.property.accept(this);
                }
                // 如果不是=
                if (type != GSTokenType.EQ) {
                    // 计算值
                    emit("copy2");
                    emit("getfield");
                }
                // 计算右值
                expr.right.accept(this);
                // 赋值操作 这里假如是=话，就会什么都不做
                emitAssignmentOperator(type);
                // 赋值操作
                emit("putfield");
            } else {  // 异常
                error("Invalid left-hand side in assignment.");
            }
        } else {
            expr.left.accept(this);
        }
    }

    /**
     * 生成条件表达式字节码
     *
     * @param node 条件表达式字节码
     */
    @Override
    public void visit(ConditionalExpression node) {
        node.condition.accept(this);
        // 处理三目表达式
        if (node.thenExpr != null) {
            if (node.elseExpr == null) {
                error("Conditional expressions must have then else expression.");
            }
            int start = size();
            // 预填跳转else
            emit("");
            node.thenExpr.accept(this);
            int end = size();
            // 预填跳转end
            emit("");
            // 回填else跳转
            emit(start, String.format("false_jump %d", size() - start));
            node.elseExpr.accept(this);
            // 回填if的结束跳转
            emit(end, String.format("jump %d", size() - end));
        }
    }

    /**
     * 生成逻辑或表达式字节码
     *
     * @param node 逻辑或表达式字节码
     */
    @Override
    public void visit(LogicalORExpression node) {
        // 先计算左边的值
        node.left.accept(this);
        // 看左边值是不是true，是的话进行跳转（短路效果）
        emit("rela_op l_not");
        int start = size();
        // 第一个true跳转
        emit("");
        node.right.accept(this);
        int start2 = size();
        // false跳转
        emit("");
        // 表达式计算为true
        emit("const b true");
        // 跳转至行尾
        emit(String.format("jump %d", size() + 2));
        // 表达式计算为false
        emit("const b false");
        // 回填第一个true跳转（必须到const b true）
        emit(start, String.format("false_jump %d", size() - start - 3));
        // 回填第二个false跳转（必须到const b false）
        emit(start2, String.format("false_jump %d", size() - start - 1));
    }

    /**
     * 生成逻辑与表达式字节码
     *
     * @param node 逻辑与表达式字节码
     */
    @Override
    public void visit(LogicalANDExpression node) {
        // 先计算左边的值
        node.left.accept(this);
        // 看左边值是不是false，是的话进行跳转（短路效果）
        int start = size();
        // 第一个false跳转
        emit("");
        node.right.accept(this);
        int start2 = size();
        // 第二个false跳转
        emit("");
        // 表达式计算为true
        emit("const b true");
        // 跳转至行尾
        emit(String.format("jump %d", size() + 2));
        // 表达式计算为false
        emit("const b false");
        // 回填第一个false跳转（必须到const b false）
        emit(start, String.format("false_jump %d", size() - start - 1));
        // 回填第二个false跳转（必须到const b false）
        emit(start2, String.format("false_jump %d", size() - start - 1));
    }

    /**
     * 生成按位或表达式字节码
     *
     * @param node 按位或表达式字节码
     */
    @Override
    public void visit(BitwiseORExpression node) {
        node.left.accept(this);
        node.right.accept(this);
        emit("rela_op b_or");
    }

    /**
     * 生成按位异或表达式字节码
     *
     * @param node 按位异或表达式字节码
     */
    @Override
    public void visit(BitwiseXORExpression node) {
        node.left.accept(this);
        node.right.accept(this);
        emit("rela_op b_xor");
    }

    /**
     * 生成按位与表达式
     *
     * @param node 按位与表达式
     */
    @Override
    public void visit(BitwiseANDExpression node) {
        node.left.accept(this);
        node.right.accept(this);
        emit("rela_op b_and");
    }

    /**
     * 生成比较表达式字节码
     *
     * @param node 比较表达式字节码
     */
    @Override
    public void visit(EqualityExpression node) {
        node.left.accept(this);
        if (node.operator != null) {
            node.right.accept(this);
            GSTokenType type = node.operator.symbol.type;
            switch (type) {
                case T_EQ: {  // == 等于
                    emit(String.format("comp eq"));
                    break;
                }
                case T_NEQ: {  // != 不等于
                    emit(String.format("comp neq"));
                    break;
                }
                case S_T_EQ: {  // === 严格等于
                    emit(String.format("comp seq"));
                    break;
                }
                case S_T_NEQ: {  // !== 严格不等于
                    emit(String.format("comp sneq"));
                    break;
                }
            }
        }
    }

    /**
     * 生成关系表达式字节码
     *
     * @param node 关系表达式字节码
     */
    @Override
    public void visit(RelationalExpression node) {
        node.left.accept(this);
        if (node.operator != null) {
            node.right.accept(this);
            GSTokenType type = node.operator.symbol.type;
            switch (type) {
                case LT: {
                    emit("comp lt");
                    break;
                }
                case GT: {
                    emit("comp gt");
                    break;
                }
                case T_LE: {
                    emit("comp le");
                    break;
                }
                case T_GE: {
                    emit("comp ge");
                    break;
                }
                default: {
                    error("Unsupported relational operator: '" + type + "'.");
                }
            }
        }
    }

    /**
     * 生成移位表达式字节码
     *
     * @param node 移位表达式字节码
     */
    @Override
    public void visit(ShiftExpression node) {
        node.left.accept(this);
        Operator operator = node.operator;
        if (operator != null) {
            GSTokenType type = operator.symbol.type;
            node.right.accept(this);
            switch (type) {
                case T_LSHIFT: {
                    emit("arith_op ls");
                    break;
                }
                case T_RSHIFT: {
                    emit("arith_op rs");
                    break;
                }
                default: {
                    error("Unsupported operator: '" + operator + "'.");
                }
            }
        }
    }

    /**
     * 生成加法表达式字节码
     *
     * @param node 加法表达式字节码
     */
    @Override
    public void visit(AdditiveExpression node) {
        node.left.accept(this);
        Operator operator = node.operator;
        if (operator != null) {
            GSTokenType type = operator.symbol.type;
            node.right.accept(this);
            switch (type) {
                case PLUS: {
                    emit("arith_op plus");
                    break;
                }
                case MINUS: {
                    emit("arith_op minus");
                    break;
                }
                default: {
                    error("Unsupported additive operator: '" + type + "'. Expected '+' or '-'.");
                }
            }
        }
    }

    /**
     * 生成乘法表达式字节码
     *
     * @param node 乘法表达式字节码
     */
    @Override
    public void visit(MultiplicativeExpression node) {
        node.left.accept(this);
        Operator operator = node.operator;
        if (operator != null) {
            GSTokenType type = operator.symbol.type;
            node.right.accept(this);
            switch (type) {
                case MUL: {
                    emit("arith_op mul");
                    break;
                }
                case DIV: {
                    emit("arith_op div");
                    break;
                }
                case MODULO: {
                    emit("arith_op modulo");
                    break;
                }
                default: {
                    error("Unsupported operator: '" + operator + "'.");
                }
            }
        }
    }

    /**
     * 生成一元表达式字节码
     *
     * @param node 一元表达式字节码
     */
    @Override
    public void visit(UnaryExpression node) {
        // 运算节点
        Node operand = node.operand;
        // 运算符
        Operator operator = node.operator;
        // 处理运算符
        if (operator != null) {
            GSTokenType type = operator.symbol.type;
            // 如果是前缀++或者--
            if (type == GSTokenType.INCREMENT || type == GSTokenType.DECREMENT) {
                // 先取左值引用
                if (operand instanceof Identifier) {
                    Identifier variable = (Identifier) operand;
                    // 获取当前计算值
                    variable.accept(this);
                    if (type == GSTokenType.INCREMENT) {
                        emit("incr");
                    } else {
                        emit("decr");
                    }
                    // 表达式计算完了以后有值
                    emit("copy");
                    emit("store " + variable.name);
                } else if (operand instanceof MemberAccess) {  // 成员访问表达式
                    // 赋值对象
                    MemberAccess member = (MemberAccess) operand;
                    // 获取左值引用
                    member.object.accept(this);
                    // 计算赋值Key
                    member.property.accept(this);
                    // 计算左值
                    member.accept(this);
                    // 对值进行处理
                    if (type == GSTokenType.INCREMENT) {
                        emit("incr");
                    } else {
                        emit("decr");
                    }
                    // 赋值操作
                    emit("putfield");
                } else {  // 异常
                    error("Invalid left-hand side in assignment.");
                }
            } else {
                // 直接返回表达式
                operand.accept(this);
                // 再执行一元运算
                switch (type) {
                    case PLUS: {  // + 什么都不做
                        break;
                    }
                    case MINUS: { // -
                        emit("arith_op neg");
                        break;
                    }
                    case NOT: {  // !
                        emit("rela_op l_not");
                        break;
                    }
                    case BIT_NOT: { // ~
                        emit("rela_op b_not");
                        break;
                    }
                    default: {
                        error("Unsupported operator: '" + operator + "'.");
                    }
                }
            }
        } else {
            // 直接返回表达式
            operand.accept(this);
        }
    }

    /**
     * 生成后缀表达式字节码
     *
     * @param node 后缀表达式字节码
     */
    @Override
    public void visit(PostfixExpression node) {
        // 运算节点
        Node operand = node.operand;
        // 运算符
        Operator operator = node.operator;
        if (operator != null) {
            GSTokenType type = operator.symbol.type;
            // 如果是后缀++或者--
            if (type == GSTokenType.INCREMENT || type == GSTokenType.DECREMENT) {
                // 这里如果是括号表达式，把括号表达式里面的东西取出来
                if (operand instanceof ParenthesizedExpression) {
                    operand = ((ParenthesizedExpression) operand).expression;
                }
                // 先取左值引用
                if (operand instanceof Identifier) {  // 先计算再返回之前的值
                    Identifier variable = (Identifier) operand;
                    // 获取当前计算值
                    variable.accept(this);
                    // 复制当前值
                    emit("copy");
                    if (type == GSTokenType.INCREMENT) {
                        emit("incr");
                    } else {
                        emit("decr");
                    }
                    emit("store " + variable.name);
                } else if (operand instanceof MemberAccess) {  // 成员访问表达式
                    // 赋值对象
                    MemberAccess member = (MemberAccess) operand;
                    // 计算左值
                    member.accept(this);
                    // 复制当前值
                    emit("copy");
                    // 获取左值引用
                    member.object.accept(this);
                    // 交换位置
                    emit("swap");
                    // 计算赋值Key
                    member.property.accept(this);
                    // 交换位置
                    emit("swap");
                    // 对值进行处理
                    if (type == GSTokenType.INCREMENT) {
                        emit("incr");
                    } else {
                        emit("decr");
                    }
                    // 赋值操作
                    emit("putfield");
                    // 把最新值出栈
                    emit("pop");
                } else {  // 异常
                    error("Invalid left-hand side in assignment.");
                }
            } else {
                error("Unsupported operator: '" + operator + "'.");
            }
        } else {
            // 直接返回表达式
            operand.accept(this);
        }
    }

    /**
     * 生成字面量字节码
     *
     * @param node 字面量字节码
     */
    @Override
    public void visit(Literal node) {
        GSToken token = node.token;
        switch (token.type) {
            case INTEGER_DECIMAL: {
                emit(String.format("const i %s", token.value));
                break;
            }
            case FLOAT: {
                emit(String.format("const f %s", token.value));
                break;
            }
            case STRING: {
                emit(String.format("const s %s", token.value));
                break;
            }
            case TRUE:
            case FALSE: {
                emit(String.format("const b %s", token.value));
                break;
            }
            case NULL: {
                emit("lda_null");
                break;
            }
            case NaN: {
                emit("lda_nan");
                break;
            }
            default: {
                error("Unsupported Literal: '" + token.value + "'.");
            }
        }
    }

    /**
     * 生成对象声明表达式字节码
     *
     * @param node 对象声明表达式字节码
     */
    @Override
    public void visit(ObjectLiteral node) {
        // 构建对象
        emit("new Object");
        Hashtable<Node, Node> members = node.members;
        if (members != null) {
            for (Map.Entry<Node, Node> member : members.entrySet()) {
                // 复制对象引用
                emit("copy");
                // 将key计算结果放栈顶
                member.getKey().accept(this);
                // 将值计算结果放栈顶
                member.getValue().accept(this);
                // 进行赋值操作
                emit("putfield");
                // 清除当前栈顶的赋值
                emit("pop");
            }
        }
    }

    /**
     * 生成函数表达式字节码
     *
     * @param node 函数表达式字节码
     */
    @Override
    public void visit(FunctionExpression node) {
        // 更新当前函数定义位置
        definedFunctionOffset = size() + 1;
        // 函数表达式就需要留函数引用在栈顶
        handleFunctionDeclaration(node);
        // 还原定义
        definedFunctionOffset = 0;
    }

    /**
     * 生成标识符字节码
     *
     * @param node 标识符字节码
     */
    @Override
    public void visit(Identifier node) {
        emit(String.format("const a %s", node.name));
    }

    /**
     * 生成括号表达式字节码
     *
     * @param node 括号表达式字节码
     */
    @Override
    public void visit(ParenthesizedExpression node) {
        node.expression.accept(this);
    }

    /**
     * 生成new关键字字节码
     *
     * @param newExpression new关键字字节码
     */
    @Override
    public void visit(NewExpression newExpression) {
        Expression constructor = newExpression.constructor;
        // 首先获取构造函数
        constructor.accept(this);
        // 如果是函数调用
        if (constructor.left instanceof FunctionCallNode) {
            // 将invoke修改为constructor
            emit(size() - 1, get(size() - 1).replace("invoke", "constructor"));
        } else {
            emit("constructor 0");
        }
    }

    /**
     * 生成数组字面量字节码
     *
     * @param node 数组字面量字节码
     */
    @Override
    public void visit(ArrayLiteral node) {
        emit("new Array");
        List<Node> elements = node.elements;
        if (elements != null) {
            int index = 0;
            for (Node element : elements) {
                // 复制数组引用
                emit("copy");
                // 往栈顶放key
                emit(String.format("const i %d", index++));
                // 获取数组赋值
                element.accept(this);
                // 进行赋值操作
                emit("putfield");
                // 清除当前栈顶的赋值
                emit("pop");
            }
        }
    }

    /**
     * 生成成员访问字节码
     *
     * @param node 成员访问字节码
     */
    @Override
    public void visit(MemberAccess node) {
        // 获取访问对象
        node.object.accept(this);
        // 访问key表达式
        if (node.property instanceof Identifier) {
            Identifier property = (Identifier) node.property;
            emit(String.format("const s %s", property.name));
        } else {
            node.property.accept(this);
        }
        // 获取值
        emit("getfield");
    }

    /**
     * 生成函数调用字节码
     *
     * @param node 函数调用字节码
     */
    @Override
    public void visit(FunctionCallNode node) {
        Node callee = node.callee;
        // 放函数调用者objectref(只有属性访问的才有，其余的都是null)
        if (callee instanceof MemberAccess) {
            MemberAccess master = (MemberAccess) callee;
            master.object.accept(this);
            // 获取函数调用引用methodref
            emit("copy");
            // 访问key表达式
            if (master.property instanceof Identifier) {
                Identifier property = (Identifier) master.property;
                emit(String.format("const s %s", property.name));
            } else {
                master.property.accept(this);
            }
            // 获取值
            emit("getfield");
        } else {
            emit("lda_null");
            // 获取函数调用引用methodref
            node.callee.accept(this);
        }
        List<Expression> args = node.args;
        // 往栈顶倒序放置参数，从最后一个元素开始，倒序遍历到第一个
        for (int i = args.size() - 1; i >= 0; i--) {
            Expression arg = args.get(i);
            arg.accept(this);
        }
        // 调用函数，注意这里是函数调用的时候传入的实参个数，虚拟机需要处理函数默认0是this传参
        emit(String.format("invoke %d", node.args.size()));
    }

    /**
     * 生成变量定义字节码
     *
     * @param node 变量定义字节码
     */
    @Override
    public void visit(VariableDecl node) {
        Expression value = node.value;
        // 这里需要判断有没有值
        if (value != null) {
            value.accept(this);
        } else {
            emit("lda_null");
        }
        // 复制栈顶值
        emit("copy");
        // 存储当前值
        emit("store " + node.identifier.name);
    }

    /**
     * 生成变量声明列表字节码
     *
     * @param node 变量声明列表字节码
     */
    @Override
    public void visit(VariableDeclList node) {
        // var声明，需要把变量加载到本地域
        for (VariableDecl decl : node.decls) {
            // 声明变量
            emit("declare " + decl.identifier.name);
            // 访问变量定义节点
            decl.accept(this);
            // 变量定义栈顶会有变量的值，出栈
            emit("pop");
        }
    }

    /**
     * 生成try子句字节码
     *
     * @param node
     */
    @Override
    public void visit(TryClause node) {
        // try_start
        emit("try_start");
        // try子句
        node.tryBody.accept(this);
        // try_end
        emit("try_end");
    }

    /**
     * 生成catch子句字节码
     *
     * @param node catch子句字节码
     */
    @Override
    public void visit(CatchClause node) {
        // 创建块级作用域
        emit("pushenv block");
        // 异常名称
        String name = node.identifier.name;
        // 先声明变量
        emit(String.format("declare %s", name));
        // 从栈顶取异常对象
        emit(String.format("store %s", name));
        // 解析catch子句内容
        for (Node stmt : node.body) {
            stmt.accept(this);
        }
        // 销毁块级作用域
        emit("popenv block");
    }

    /**
     * 生成finally子句字节码
     *
     * @param node finally子句字节码
     */
    @Override
    public void visit(FinallyClause node) {
        node.finallyBody.accept(this);
        // 最后的异常监测
        emit("finally_check");
    }

    /**
     * 生成关联符号字节码
     *
     * @param type 关联符号字节码
     */
    private void emitAssignmentOperator(GSTokenType type) {
        switch (type) {
            case EQ: {  // =
                break;
            }
            case PLUS_EQUAL: {  // +=
                emit("arith_op plus");
                break;
            }
            case MINUS_EQUAL: {  // -=
                emit("arith_op minus");
                break;
            }
            case STAR_EQUAL: {  // *=
                emit("arith_op mul");
                break;
            }
            case SLASH_EQUAL: {  // /=
                emit("arith_op div");
                break;
            }
            case PERCENT_EQUAL: {  // %=
                emit("arith_op modulo");
                break;
            }
            case T_AND_ASSIGN: {  // &=
                emit("rela_op b_and");
                break;
            }
            case T_OR_ASSIGN: {   // |=
                emit("rela_op b_or");
                break;
            }
            case T_XOR_ASSIGN: {  // ^=
                emit("rela_op b_xor");
                break;
            }
        }
    }

    private void handleFunctionDeclaration(FunctionExpression node) {
        // 获取函数名字
        Identifier identifier = node.identifier;
        // 获取函数名(null是匿名函数)
        String funName = identifier.name;
        // 参数不可能为空
        List<Identifier> params = node.params;
        // 语句列表
        List<Node> stmts = node.body.stmts;
        // 函数起始位置
        int start = size();
        // 这里先预填函数定义 fundef
        emit("");
        // 首先创建函数作用域
        emit("pushenv function");
        int index = 0;
        // 将实参加入到本地变量表 实参变量索引从1开始，0是隐式this
        if (params != null) {
            for (Identifier param : params) {
                emit("fstore " + param.name + " " + ++index);
            }
        }
        // 解析函数体
        for (Node stmt : stmts) {
            stmt.accept(this);
        }
        // 回填函数定义总长度（不包括fundef本身）
        emit(start, String.format("fundef %s %d", funName, (size() - start - 1)));
    }

    /**
     * 处理循环体里面的跳转
     *
     * @param body          循环体
     * @param bodyStart     循环体开始位置
     * @param bodyEnd       循环体结束位置
     * @param continueStart continue跳转位置
     * @param breakStart    break跳转位置
     */
    private void handleLoopJump(List<Node> body, int bodyStart, int bodyEnd, int continueStart, int breakStart) {
        if (body != null) {
            for (int i = bodyStart; i < bodyEnd; ++i) {
                if ("continue".equals(get(i))) {
                    // 跳转至更新体
                    emit(i, String.format("loop_jump %d", continueStart - i));
                } else if ("break".equals(get(i))) {
                    // 跳转至结束
                    emit(i, String.format("loop_jump %d", breakStart - i));
                }
            }
        }
    }

    /**
     * 处理块语句中函数声明提升
     */
    private void handleFunctionDeclare(List<Node> stmt) {
        // 这里先把function语句提升到前面
        List<Node> functionStatements = new ArrayList<>();
        // 临时存储其他类型的元素
        List<Node> otherStatements = new ArrayList<>();
        // 遍历列表并分类存储
        for (Node node : stmt) {
            if (node instanceof FunctionStatement) {
                functionStatements.add(node);
            } else {
                otherStatements.add(node);
            }
        }
        // 清空原列表并将FunctionStatement元素添加到最前面
        stmt.clear();
        stmt.addAll(functionStatements);
        stmt.addAll(otherStatements);
    }

    /**
     * 生成循环体字节码
     *
     * @param body 循环体
     */
    private void handleLoopStatement(List<Node> body) {
        if (body != null) {
            // 将循环体里面的函数声明提前
            handleFunctionDeclare(body);
            // 创建循环域
            emit("pushenv loop");
            // 解析循环体内容
            for (Node stmt : body) {
                stmt.accept(this);
            }
            // 销毁循环域
            emit("popenv loop");
        }
    }
}
