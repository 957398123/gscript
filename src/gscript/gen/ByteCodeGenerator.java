package gscript.gen;

import gscript.node.*;
import gscript.token.GSToken;
import gscript.token.GSTokenType;

import java.util.*;

public class ByteCodeGenerator implements Visitor {

    /**
     * 字节码
     */
    private final ArrayList<String> bytecode = new ArrayList<>();

    /**
     * 获取生成的字节码
     *
     * @return
     */
    public ArrayList<String> getBytecode() {
        return bytecode;
    }

    /**
     * 打印字节码
     */
    public void print() {
        for (String s : bytecode) {
            System.out.println(s);
        }
    }

    public ArrayList<String> getByteCode() {
        return bytecode;
    }

    /**
     * 提交字节码
     *
     * @param byteCode
     */
    public void emit(String byteCode) {
        bytecode.add(byteCode);
    }

    /**
     * 替换指定位置字节码
     *
     * @param index
     * @param byteCode
     */
    public void emit(int index, String byteCode) {
        bytecode.set(index, byteCode);
    }

    /**
     * 获取指定位置字节码
     *
     * @param index
     * @return
     */
    public String get(int index) {
        return bytecode.get(index);
    }

    /**
     * 获取字节码大小
     *
     * @return
     */
    public int size() {
        return bytecode.size();
    }

    /**
     * 处理块语句中函数声明提升
     * @param stmt
     */
    private void handleFunctionDeclare(List<Node> stmt) {
        // 这里先把function语句提升到前面
        ListIterator<Node> iterator = stmt.listIterator();
        while (iterator.hasNext()) {
            Node node = iterator.next();
            if (node instanceof FunctionStatement) {
                iterator.remove();
                stmt.add(0, node);
                // 重置迭代器
                iterator = stmt.listIterator(iterator.nextIndex());
            }
        }
    }

    /**
     * 生成变量声明字节码
     *
     * @param statement
     */
    @Override
    public void visit(VariableStatement statement) {
        // var声明，需要把变量加载到本地域
        for (VariableDecl decl : statement.args.decls) {
            emit("declare " + decl.identifier.name);
            // 如果有值，求值后保存
            if (decl.value != null) {
                decl.value.accept(this);
                emit("store " + decl.identifier.name);
            }
        }
    }

    /**
     * function语句字节码生成
     *
     * @param expr
     */
    @Override
    public void visit(FunctionStatement expr) {
        // 获取函数名，和当前index
        String funName = expr.identifier.name;
        // 参数列表
        List<Identifier> params = expr.params;
        // 语句列表
        List<Node> body = expr.body.stmt;
        // 函数起始位置
        int start = size();
        // 这里先预填函数定义 fundef
        emit("");
        int index = 0;
        // 将实参加入到本地变量表
        for (Identifier param : params) {
            emit("fstore " + param.name + " " + index++);
        }
        // 解析函数体
        for (Node node : body) {
            node.accept(this);
        }
        // 回填函数定义（需要减去fundef）
        emit(start, "fundef %d %s".formatted((size() - start - 1), funName));
    }

    /**
     * 生成块语句字节码
     *
     * @param statement
     */
    @Override
    public void visit(BlockStatement statement) {
        emit("pushenv block");
        // 将函数声明提前
        handleFunctionDeclare(statement.stmt);
        for (Node node : statement.stmt) {
            node.accept(this);
        }
        emit("popenv block");
    }

    /**
     * 生成程序节点字节码
     *
     * @param program
     */
    @Override
    public void visit(ProgramNode program) {
        List<Node> stmt = program.stmt;
        // 这里先把function语句提升到前面
        handleFunctionDeclare(stmt);
        for (Node node : stmt) {
            node.accept(this);
        }
    }

    /**
     * 生成表达式字节码
     *
     * @param expr
     */
    @Override
    public void visit(Expression expr) {
        // "=" | "+=" | "-=" | "*=" | "/=" | "%=" | "&=" | "|=" | "^=" 必须要有效的左值
        if (expr.operator != null) {
            if (expr.left.condition instanceof Identifier) {  // a = 3
                Identifier identifier = (Identifier) expr.left.condition;
                GSTokenType type = expr.operator.symbol.type;
                if (type != GSTokenType.EQ) {
                    // +=这种运算符要先获取当前左值
                    identifier.accept(this);
                }
                expr.right.accept(this);
                emitAssignmentOperator(type);
                emit("store " + identifier.name);
            } else if (expr.left.condition instanceof PropertyAccess) {  //属性访问表达式 a.b = 3
                PropertyAccess node = (PropertyAccess) expr.left.condition;
                node.object.accept(this);
                GSTokenType type = expr.operator.symbol.type;
                if (type != GSTokenType.EQ) {
                    // +=这种运算符要先获取当前左值
                    emit("copy");
                    emit("getfield " + node.property.name);
                }
                expr.right.accept(this);
                emitAssignmentOperator(type);
                emit("putfield " + node.property.name);
            } else if (expr.left.condition instanceof ComputedMemberNode) {  // a[0] = 1这种
                ComputedMemberNode node = (ComputedMemberNode) expr.left.condition;
                node.object.accept(this);
                GSTokenType type = expr.operator.symbol.type;
                // += 需要处理
                if (type != GSTokenType.EQ) {
                    // +=这种运算符要先获取当前左值
                    emit("copy");
                    node.expression.accept(this);
                    emit("aaload");
                } else {
                    node.expression.accept(this);
                }
                expr.right.accept(this);
                emitAssignmentOperator(type);
                // += 需要处理
                if (type != GSTokenType.EQ) {
                    node.expression.accept(this);
                    emit("swap");
                }
                emit("aastore");
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
     * @param expr
     */
    @Override
    public void visit(ConditionalExpression expr) {
        expr.condition.accept(this);
        // 处理三目表达式
        if (expr.thenExpr != null) {
            if (expr.elseExpr == null) {
                error("Conditional expressions must have then else expression.");
            }
            int start = size();
            // 预填跳转else
            emit("");
            expr.thenExpr.accept(this);
            int end = size();
            // 预填跳转end
            emit("");
            emit(start, "false_jump %d".formatted(size() - start));
            expr.elseExpr.accept(this);
            emit(end, "jump %d".formatted(size() - end));
        }
    }

    /**
     * 生成字面量字节码
     *
     * @param node
     */
    @Override
    public void visit(Literal node) {
        GSToken token = node.token;
        switch (token.type) {
            case INTEGER_DECIMAL: {
                emit("const %s %s".formatted("i", token.value));
                break;
            }
            case FLOAT: {
                emit("const %s %s".formatted("f", token.value));
                break;
            }
            case STRING: {
                emit("const %s %s".formatted("s", token.value));
                break;
            }
            case TRUE:
            case FALSE: {
                emit("const %s %s".formatted("b", token.value));
            }
        }
    }

    /**
     * 生成标识符字节码
     *
     * @param node
     */
    @Override
    public void visit(Identifier node) {
        emit("const %s %s".formatted("a", node.name));
    }

    /**
     * AdditiveExpression字节码生成
     *
     * @param expr
     */
    @Override
    public void visit(AdditiveExpression expr) {
        if (expr.operator != null) {
            expr.left.accept(this);
            expr.right.accept(this);
            GSTokenType type = expr.operator.symbol.type;
            switch (type) {
                case PLUS: {
                    emit("arith_op plus");
                    break;
                }
                case MINUS: {
                    emit("arith_op minus");
                }
                default: {
                    error("Unsupported additive operator: '" + type + "'. Expected '+' or '-'.");
                }
            }
        } else {
            expr.left.accept(this);
        }
    }

    /**
     * 生成表达式语句字节码
     *
     * @param statement
     */
    @Override
    public void visit(ExpressionStatement statement) {
        Expression expr = statement.expr;
        expr.accept(this);
        // 如果这里没有赋值操作，弹出栈顶值
        Operator operator = expr.operator;
        // 这里只会是<AssignmentOperator>
        if (operator == null) {
            emit("pop");
        }
    }

    /**
     * 生成变量定义字节码
     *
     * @param node
     */
    @Override
    public void visit(VariableDecl node) {
        // a = 5
        node.value.accept(this);
        emit("store " + node.identifier.name);
    }

    /**
     * 生成属性访问字节码
     *
     * @param node
     */
    @Override
    public void visit(PropertyAccess node) {
        // 获取对象引用
        if (node.object instanceof Identifier) {
            Identifier object = (Identifier) node.object;
            emit("const %s %s".formatted("a", object.name));
        } else {
            node.object.accept(this);
        }
        emit("getfield %s".formatted(node.property.name));
    }

    /**
     * 生成数组访问字节码
     *
     * @param node
     */
    @Override
    public void visit(ComputedMemberNode node) {  // 访问数组
        // 获取引用
        node.object.accept(this);
        // 获取index
        node.expression.accept(this);
        // 加载数据
        emit("aaload");
    }

    /**
     * 生成函数调用字节码
     *
     * @param node
     */
    @Override
    public void visit(FunctionCallNode node) {
        // 获取函数引用
        Node callee = node.callee;
        if (node.callee instanceof PropertyAccess) {
            PropertyAccess access = (PropertyAccess) node.callee;
            access.object.accept(this);
            emit("copy");
            emit("getfield %s".formatted(access.property.name));
        } else {
            callee.accept(this);
        }
        // 往栈顶放置参数
        for (Expression arg : node.args) {
            arg.accept(this);
        }
        if (node.callee instanceof PropertyAccess) {
            emit("invokeMember %d".formatted(node.args.size()));
        } else {
            emit("invoke %d".formatted(node.args.size()));
        }
    }

    /**
     * 生成return语句字节码
     *
     * @param expr
     */
    @Override
    public void visit(ReturnStatement expr) {
        // 如果有返回值
        if (expr.expression != null) {
            expr.expression.accept(this);
        } else {
            // 栈顶放上null
            emit("lda_null");
        }
        emit("return");
    }

    /**
     * 生成一元表达式字节码
     *
     * @param expr
     */
    @Override
    public void visit(UnaryExpression expr) {
        // 先访问节点
        Node operand = expr.operand;
        GSTokenType type = expr.operator.symbol.type;
        // ++和--需要原来的值参与计算
        if (type == GSTokenType.INCREMENT || type == GSTokenType.DECREMENT) {
            if (operand instanceof Identifier) {
                Identifier id = (Identifier) operand;
                id.accept(this);
            } else if (operand instanceof PropertyAccess) {
                PropertyAccess node = (PropertyAccess) operand;
                node.object.accept(this);
                emit("copy");
                emit("copy");
                emit("getfield %s".formatted(node.property.name));
            } else if (operand instanceof ComputedMemberNode) {
                ComputedMemberNode node = (ComputedMemberNode) operand;
                node.object.accept(this);
                emit("copy");
                node.expression.accept(this);
                emit("aaload");
            } else {
                error("Unsupported unary operator: '" + type + "'.");
            }
        } else {
            expr.operand.accept(this);
        }

        // 再执行一元运算
        switch (type) {
            case MINUS: { // -
                emit("arith_op neg");
                break;
            }
            case NOT: {  // 逻辑非
                emit("rela_op l_not");
                break;
            }
            case BIT_NOT: {
                emit("rela_op b_not");
                break;
            }
            case INCREMENT: {
                emit("incr");
                break;
            }
            case DECREMENT: {
                emit("decr");
                break;
            }
        }
        // 设置值以后，还要把最新值放到栈顶
        if (type == GSTokenType.INCREMENT || type == GSTokenType.DECREMENT) {
            if (operand instanceof Identifier) {
                Identifier id = (Identifier) operand;
                emit("store " + id.name);
                id.accept(this);
            } else if (operand instanceof PropertyAccess) {
                PropertyAccess node = (PropertyAccess) operand;
                emit("putfield " + node.property.name);
                emit("getfield " + node.property.name);
            } else if (operand instanceof ComputedMemberNode) {
                ComputedMemberNode node = (ComputedMemberNode) operand;
                node.expression.accept(this);
                emit("swap");
                emit("avstore");
            }
        }
    }

    /**
     * if语句字节码生成
     *
     * @param node
     */
    @Override
    public void visit(IfStatement node) {
        // 处理If条件
        node.condition.accept(this);
        // 如果有 else，这里先预填一个跳转
        int start = size();
        // 这里先预填跳转语句
        emit("");
        // 这里处理then语句
        node.thenBranch.accept(this);
        // then跳转至结尾，这里预填
        int thenEnd = size();
        emit("");
        // 回填if为false时的跳转语句
        emit(start, "false_jump %d".formatted((size() - start)));
        // 如果存在解析else分支
        if (node.elseBranch != null) {
            node.elseBranch.accept(this);
        }
        emit(thenEnd, "jump %d".formatted((size() - thenEnd)));
    }

    /**
     * 比较表达式 值是boolean
     *
     * @param node
     */
    @Override
    public void visit(EqualityExpression node) {
        node.left.accept(this);
        if (node.operator != null) {
            node.right.accept(this);
            GSTokenType type = node.operator.symbol.type;
            switch (type) {
                case T_EQ: {  // ==
                    emit("comp %s".formatted("eq"));
                    break;
                }
                case T_NEQ: {  // != 不等于
                    emit("comp %s".formatted("neq"));
                    break;
                }
            }
        }
    }

    /**
     * do语句字节码生成
     *
     * @param node
     */
    @Override
    public void visit(DoWhileStatement node) {
        int start = size();
        // 首先执行一次语句
        node.body.accept(this);
        // 执行表达式
        node.condition.accept(this);
        // 如果是值表达式，才进行跳转
        if (node.condition.operator == null) {
            // 预填false跳转至后续代码
            emit("false_jump %d".formatted(2));
        }
        emit("jump %d".formatted((start - size())));
    }

    /**
     * 生成for语句字节码
     *
     * @param node
     */
    @Override
    public void visit(ForStatement node) {
        Node init = node.init;
        Expression condition = node.condition;
        Expression update = node.update;
        // 首先执行init
        if (init != null) {
            init.accept(this);
        }
        // 条件表达式开始位置
        int constart = size();
        // 条件表达式结束位置
        int conend = 0;
        // 是否有结束跳转
        boolean isNeedEndJump = false;
        if (condition != null) {
            condition.accept(this);
            // 如果是赋值表达式
            if (condition instanceof Expression) {
                // 如果这里是条件表达式，才进行跳转
                Operator operator = condition.operator;
                // 值表达式才有跳转
                if (operator == null) {
                    isNeedEndJump = true;
                    conend = size();
                    emit("");
                }
            }
        }
        // 执行body
        node.body.accept(this);
        int updatestart = size();
        // 如果有update，执行update
        if (update != null) {
            update.accept(this);
            // 这需要判断update表达式是否有往栈顶放值
            Operator operator = update.operator;
            // 如果不是关联表达式，需要去掉生成的值
            if (operator == null) {
                emit("pop");
            }
        }
        // update执行完后跳转至condition
        emit("jump %d".formatted((constart - size())));
        // 跳转至结尾
        if (isNeedEndJump) {
            emit(conend, "false_jump %d".formatted((size() - conend)));
        }
        // 这里对这段for循环里面的continue和break进行处理，替换成对应的跳转
        for (int i = constart; i < size(); i++) {
            if ("continue".equals(get(i))) {
                // 跳转至更新体
                emit(i, "loop_jump %d".formatted((updatestart - i)));
            } else if ("break".equals(get(i))) {
                // 跳转至结束
                emit(i, "loop_jump %d".formatted((size() - i)));
            }
        }
    }

    /**
     * 生成变量声明列表字节码
     *
     * @param node
     */
    @Override
    public void visit(VariableDeclList node) {
        for (VariableDecl decl : node.decls) {
            decl.accept(this);
        }
    }

    /**
     * 生成关系表达式字节码
     *
     * @param node
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
     * 生成while语句字节码
     *
     * @param node
     */
    @Override
    public void visit(WhileStatement node) {
        int constart = size();
        node.condition.accept(this);
        int conend = 0;
        boolean isNeedEndJump = false;
        // 如果是值表达式，才进行跳转
        if (node.condition.operator == null) {
            conend = size();
            isNeedEndJump = true;
            // 预填，如果表达式为false，结束
            emit("");
        }
        // 创建循环域
        emit("pushenv loop");
        node.body.accept(this);
        // 销毁循环域
        emit("popenv loop");
        // 跳转到表达式
        emit("jump %d".formatted((constart - size())));
        if (isNeedEndJump) {
            emit(conend, "false_jump %d".formatted((size() - conend)));
        }
        // 这里对这段while循环里面的continue和break进行处理
        for (int i = constart; i < size(); ++i) {
            if ("continue".equals(get(i))) {
                emit(i, "loop_jump %d".formatted((constart - i)));
            } else if ("break".equals(get(i))) {
                emit(i, "loop_jump %d".formatted((size() - i)));
            }
        }
    }

    /**
     * 生成continue字节码
     *
     * @param node
     */
    @Override
    public void visit(ContinueStatement node) {
        // 这里直接提交continue，让其上一级处理
        emit("continue");
    }

    /**
     * 生成break字节码
     *
     * @param node
     */
    @Override
    public void visit(BreakStatement node) {
        // 这里直接提交break，让其上一级处理
        emit("break");
    }

    /**
     * 生成PostfixExpression字节码
     *
     * @param node
     */
    @Override
    public void visit(PostfixExpression node) {
        Node operand = node.operand;
        if (operand instanceof Identifier) {
            operand.accept(this);
            emit("copy");
        } else if (operand instanceof PropertyAccess) {
            PropertyAccess access = (PropertyAccess) operand;
            String property = access.property.name;
            access.object.accept(this);
            emit("copy");
            emit("getfield %s".formatted(property));
            emit("swap");
            emit("copy");
            emit("getfield %s".formatted(property));
        } else if (operand instanceof ComputedMemberNode) {
            ComputedMemberNode member = (ComputedMemberNode) operand;
            member.object.accept(this);
            emit("copy");
            member.expression.accept(this);
            emit("aaload");
            emit("swap");
            emit("copy");
            member.expression.accept(this);
            emit("aaload");
        }
        // 获取当前后缀类型
        GSTokenType type = node.operator.symbol.type;
        // 处理a++和a--
        switch (type) {
            case INCREMENT: {
                emit("incr");
                break;
            }
            case DECREMENT: {
                emit("decr");
                break;
            }
            default: {
                error("Unsupported operator: '" + type + "'.");
            }
        }
        if (operand instanceof Identifier) {
            Identifier id = (Identifier) operand;
            emit("store %s".formatted(id.name));
        } else if (operand instanceof PropertyAccess) {
            PropertyAccess access = (PropertyAccess) operand;
            emit("putfield %s".formatted(access.property.name));
        } else if (operand instanceof ComputedMemberNode) {
            ComputedMemberNode member = (ComputedMemberNode) operand;
            member.expression.accept(this);
            emit("swap");
            emit("aastore");
        }
    }

    /**
     * 生成逻辑或表达式字节码
     *
     * @param node
     */
    @Override
    public void visit(LogicalORExpression node) {
        node.left.accept(this);
        node.right.accept(this);
        emit("rela_op l_or");
    }

    /**
     * 生成逻辑与表达式字节码
     *
     * @param node
     */
    @Override
    public void visit(LogicalANDExpression node) {
        node.left.accept(this);
        node.right.accept(this);
        emit("rela_op l_and");
    }

    /**
     * 生成按位或表达式字节码
     *
     * @param node
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
     * @param node
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
     * @param node
     */
    @Override
    public void visit(BitwiseANDExpression node) {
        node.left.accept(this);
        node.right.accept(this);
        emit("rela_op b_and");
    }

    /**
     * 生成对象声明表达式字节码
     *
     * @param node
     */
    @Override
    public void visit(ObjectLiteral node) {
        emit("new Object");
        for (Map.Entry<Node, Node> member : node.members.entrySet()) {
            emit("copy");
            Node value = member.getValue();
            // 获取值
            value.accept(this);
            // 获取key
            Node key = member.getKey();
            if (key instanceof Identifier) {  // 如果是标识符
                Identifier id = (Identifier) key;
                emit("putfield %s".formatted(id.name));
            } else if (key instanceof Literal) {  // 如果是字面量
                Literal literal = (Literal) key;
                emit("putfield %s".formatted(literal.token.value));
            } else {  //如果是表达式，使用表达式计算结果为Key
                key.accept(this);
                emit("putfieldExpr");
            }
        }
    }

    /**
     * 生成数组表达式字节码
     *
     * @param node
     */
    @Override
    public void visit(ArrayLiteral node) {
        emit("new Array");
        int index = 0;
        for (Node element : node.elements) {
            emit("copy");
            emit("const i %d".formatted(index++));
            element.accept(this);
            emit("aastore");
        }
    }

    /**
     * 生成MultiplicativeExpression字节码
     *
     * @param node
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
     * 生成ShiftExpression字节码
     *
     * @param node
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
     * 成员函数字节码生成
     *
     * @param expr
     */
    @Override
    public void visit(MemberFunctionStatement expr) {
        // 获取函数名，和当前index
        String funName = expr.identifier.name;
        // 参数列表
        List<Identifier> params = expr.params;
        // 语句列表
        BlockStatement body = expr.body;
        // 函数起始位置
        int start = size();
        // 这里先预填函数定义 fundefload
        emit("");
        int index = 0;
        // 将实参加入到本地变量表
        for (Identifier param : params) {
            emit("fstore " + param.name + " " + index++);
        }
        // 解析函数体
        body.accept(this);
        // 回填函数定义（需要减去fundefload）
        emit(start, "fundefload %d %s".formatted((size() - start - 1), funName));
    }

    private void emitAssignmentOperator(GSTokenType type) {
        switch (type) {
            case EQ: {  // =
                break;
            }
            case PLUS_EQUAL: {  // +=
                emit("plus");
                break;
            }
            case MINUS_EQUAL: {  // -=
                emit("minus");
                break;
            }
            case STAR_EQUAL: {  // *=
                emit("mul");
                break;
            }
            case SLASH_EQUAL: {  // /=
                emit("div");
                break;
            }
            case PERCENT_EQUAL: {  // %=
                emit("modulo");
                break;
            }
            case T_AND_ASSIGN: {  // &=
                emit("bitand");
                break;
            }
            case T_OR_ASSIGN: {   // |=
                emit("bitor");
                break;
            }
            case T_XOR_ASSIGN: {  // ^=
                emit("bitxor");
                break;
            }
        }
    }

    public void error(String msg) {
        throw new RuntimeException("Uncaught ReferenceError: " + msg);
    }

}
