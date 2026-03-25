package org.gscript.compile;

import org.gscript.compile.node.*;
import org.gscript.compile.node.Literal;
import org.gscript.compile.token.GSToken;
import org.gscript.compile.token.GSTokenType;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

/**
 * 读取token并使用递归下降解析器解析成抽象语法树
 */
@SuppressWarnings("unused")
public class Parser {

    /**
     * 当前token位置
     */
    private int pos;

    /**
     * 赋值操作符
     */
    private static final GSTokenType[] ASSIGN = {GSTokenType.EQ, GSTokenType.PLUS_EQUAL, GSTokenType.MINUS_EQUAL, GSTokenType.STAR_EQUAL, GSTokenType.SLASH_EQUAL, GSTokenType.PERCENT_EQUAL, GSTokenType.T_AND_ASSIGN, GSTokenType.T_OR_ASSIGN, GSTokenType.T_XOR_ASSIGN};

    /**
     * 比较操作符
     */
    private static final GSTokenType[] EQUALITY = {GSTokenType.T_EQ, GSTokenType.T_NEQ, GSTokenType.S_T_EQ, GSTokenType.S_T_NEQ};

    /**
     * 关系操作符
     */
    private static final GSTokenType[] RELATIONAL = {GSTokenType.GT, GSTokenType.LT, GSTokenType.T_GE, GSTokenType.T_LE};

    /**
     * 移位操作符
     */
    private static final GSTokenType[] SHIFT = {GSTokenType.T_LSHIFT, GSTokenType.T_RSHIFT};

    /**
     * 加减运算符
     */
    private static final GSTokenType[] ADDITIVE = {GSTokenType.PLUS, GSTokenType.MINUS};

    /**
     * 乘除运算符
     */
    private static final GSTokenType[] MULTIPLICATIVE = {GSTokenType.MUL, GSTokenType.DIV, GSTokenType.MODULO};

    /**
     * 一元操作符
     */
    private static final GSTokenType[] UNARY = {GSTokenType.PLUS, GSTokenType.MINUS, GSTokenType.NOT, GSTokenType.BIT_NOT};

    /**
     * 自增和自减
     */
    private static final GSTokenType[] POSTFIX = {GSTokenType.INCREMENT, GSTokenType.DECREMENT};

    /**
     * token流
     */
    private final List<GSToken> tokens;

    /**
     * 构造解析器
     */
    public Parser(List<GSToken> tokens) {
        this.pos = 0;
        this.tokens = tokens;
    }

    /**
     * 开始解析源文程序，这里不解析成(function(){xxx})();这种形式
     * 虽然本质上一个源文件里面的内容相当于一个匿名函数内容，但是其作用域是顶层作用域
     * 目前生成源文件字节码，就是载入即执行的。如果翻译成上面的形式，相当于额外有一层函数作用域了，顶层不是全局域
     *
     * @return 程序根节点
     */
    public Node parseProgram() {
        List<Node> stmt = new ArrayList<>();
        while (!isAtEnd()) {
            stmt.add(parseStatement());
        }
        return new ProgramNode(stmt);
    }

    /**
     * 解析语句，以下是语句类型<br/>
     * {@literal <BlockStatement> 块语句}<br/>
     * {@literal <VariableStatement> 变量声明语句}<br/>
     * {@literal <IfStatement> if条件语句}<br/>
     * {@literal <ForStatement> for循环语句}<br/>
     * {@literal <DoWhileStatement> do语句}<br/>
     * {@literal <WhileStatement> while语句}<br/>
     * {@literal <FunctionStatement> 函数定义语句}<br/>
     * {@literal <BreakStatement> break语句}<br/>
     * {@literal <ContinueStatement> continue语句}<br/>
     * {@literal <ReturnStatement> return语句}<br/>
     * {@literal <ThrowStatement> throw语句}<br/>
     * {@literal <ExceptionStatement> exception语句}<br/>
     * {@literal <ExpressionStatement> express语句}
     *
     * @return 语句节点
     */
    private Node parseStatement() {
        // 这里忽略空语句
        if (check(GSTokenType.SEMICOLON)) {
            advance();
        }
        if (check(GSTokenType.LBRACE)) return parseBlockStatement();         // 解析块语句
        if (check(GSTokenType.VAR)) return parseVariableStatement();         // 解析变量声明语句
        if (check(GSTokenType.IF)) return parseIfStatement();                // 解析if语句
        if (check(GSTokenType.FOR)) return parseForStatement();              // 解析for语句
        if (check(GSTokenType.DO)) return parseDoWhileStatement();           // 尝试解析do语句
        if (check(GSTokenType.WHILE)) return parseWhileStatement();          // 尝试解析while语句
        if (check(GSTokenType.FUNCTION)) return parseFunctionStatement();    // 尝试解析function语句
        if (check(GSTokenType.BREAK)) return parseBreakStatement();          // 尝试解析break语句
        if (check(GSTokenType.CONTINUE)) return parseContinueStatement();    // 尝试解析continue语句
        if (check(GSTokenType.RETURN)) return parseReturnStatement();        // 尝试解析return语句
        if (check(GSTokenType.THROW)) return parseThrowStatement();          // 尝试解析throw语句
        if (check(GSTokenType.TRY)) return parseExceptionStatement();        // 尝试解析exception语句
        return parseExpressionStatement();                                   // 默认解析express语句
    }

    /**
     * 解析块语句<br/>
     * {@literal 语法定义：BlockStatement
     * = '{', { Statement }, '}';}
     *
     * @return 块语句
     */
    private BlockStatement parseBlockStatement() {
        // 块语句必须"{"开头
        consume(GSTokenType.LBRACE, "Expect '{' before block statement");
        List<Node> stmt = null;
        // 如果不是空语句
        if (!check(GSTokenType.RBRACE)) {
            stmt = new ArrayList<>();
            // 循环解析语句，直到遇到当前块语句结束符号
            do {
                stmt.add(parseStatement());
            } while (!check(GSTokenType.RBRACE));
        }
        // 块语句必须"}"结尾
        consume(GSTokenType.RBRACE, "Expect '}' after block statement");
        // 返回块语句节点
        return new BlockStatement(stmt);
    }

    /**
     * 解析变量声明语句<br/>
     * {@literal 语法定义：VariableStatement
     * = VariableDeclList, ";";}
     *
     * @return 变量声明语句
     */
    private VariableStatement parseVariableStatement() {
        // 变量声明语句必须使用"var"开头
        consume(GSTokenType.VAR, "variable declaration must start with 'var'");
        // 解析变量声明列表
        VariableDeclList vars = parseVariableDeclList();
        // 变量声明语句必须以";"结尾
        consume(GSTokenType.SEMICOLON, "variable declaration must end with ';'");
        // 返回变量声明语句节点
        return new VariableStatement(vars);
    }

    /**
     * 解析if语句<br/>
     * {@literal 语法定义：IfStatement
     * = "if", "(", Expression, ")", BlockStatement, [ "else", ( BlockStatement
     * | IfStatement ) ];}
     *
     * @return if语句
     */
    private IfStatement parseIfStatement() {
        // if语句必须if关键字开头
        consume(GSTokenType.IF, "if statement must start with 'if'");
        // if关键字后面必须跟"("
        consume(GSTokenType.LPAREN, "Expected '(' after 'if'.");
        // 解析条件表达式
        Expression condition = parseExpression();
        // 条件表达式后面必须是)
        consume(GSTokenType.RPAREN, "Expected ')' after condition.");
        // 初始化then分支
        BlockStatement thenBranch;
        // 初始化else分支
        Node elseBranch = null;
        // 匹配if为true语句
        thenBranch = parseBlockStatement();
        // 如果匹配到了else
        if (match(GSTokenType.ELSE)) {
            if (check(GSTokenType.LBRACE)) {  // 如果是if else这种
                elseBranch = parseBlockStatement();
            } else if (check(GSTokenType.IF)) {  //如果是if else if语句
                elseBranch = parseIfStatement();
            }
        }
        // 返回if语句节点
        return new IfStatement(condition, thenBranch, elseBranch);
    }

    /**
     * 解析for语句<br/>
     * {@literal 语法定义：ForStatement
     * = "for", "(", [ VariableDeclList
     * | Expression ], ";", [ Expression ], ";", [ Expression ], ")", [ ";" | '{', { Statement }, '}'];}
     *
     * @return for语句
     */
    private ForStatement parseForStatement() {
        // for语句必须是"for"关键字开头
        consume(GSTokenType.FOR, "for statement must start with 'for'");
        // 匹配"("
        consume(GSTokenType.LPAREN, "Expected '(' after 'for'.");
        // 声明初始化节点
        Node init = null;
        // 声明条件节点
        Expression condition = null;
        // 声明更新节点
        Expression update = null;
        // 循环体
        List<Node> body = null;
        // 解析初始化表达式(可能是变量声明语句或者表达式，也有可能为空)
        if (check(GSTokenType.VAR)) {
            init = parseVariableStatement();
        } else if (!match(GSTokenType.SEMICOLON)) {  // 如果不匹配;就尝试解析表达式
            init = parseExpression();
            consume(GSTokenType.SEMICOLON, "Expected ';' after for statement 'init'.");
        }
        // 如果不是;说明有condition
        if (!match(GSTokenType.SEMICOLON)) {
            condition = parseExpression();
            consume(GSTokenType.SEMICOLON, "Expected ';' after for statement 'condition'.");
        }
        // 如果不是)说明有update
        if (!match(GSTokenType.RPAREN)) {
            update = parseExpression();
            consume(GSTokenType.RPAREN, "Expected ')' after for statement 'update'.");
        }
        // 解析body，body可以为空
        if (!match(GSTokenType.SEMICOLON)) {
            // 初始化循环体
            body = new ArrayList<>();
            // 块语句必须"{"开头
            consume(GSTokenType.LBRACE, "Expect '{' before block statement");
            // 如果不是空语句
            if (!check(GSTokenType.RBRACE)) {
                // 循环解析语句，直到遇到当前块语句结束符号
                do {
                    body.add(parseStatement());
                } while (!check(GSTokenType.RBRACE));
            }
            // 块语句必须"}"结尾
            consume(GSTokenType.RBRACE, "Expect '}' after block statement");
        }
        // 返回for语句节点
        return new ForStatement(init, condition, update, body);
    }

    /**
     * 解析do语句<br/>
     * {@literal 语法定义：DoWhileStatement
     * = "do", BlockStatement, "while", "(", Expression, ")", ";";}
     *
     * @return do-while语句
     */
    private DoWhileStatement parseDoWhileStatement() {
        // do语句必须"do"关键字开头
        consume(GSTokenType.DO, "Expected 'do' to start do-while statement");
        // 解析循环体
        List<Node> body = null;
        consume(GSTokenType.LBRACE, "Expect '{' before 'do' keyword");
        // 如果不是空语句
        if (!check(GSTokenType.RBRACE)) {
            body = new ArrayList<>();
            // 循环解析语句，直到遇到当前块语句结束符号
            do {
                body.add(parseStatement());
            } while (!check(GSTokenType.RBRACE));
        }
        consume(GSTokenType.RBRACE, "Expect '}' after block statement");
        // 匹配while关键字
        consume(GSTokenType.WHILE, "Expected 'while' after do-while body");
        // 匹配符号"("
        consume(GSTokenType.LPAREN, "Expected '(' after 'while'");
        // 解析条件表达式
        Expression condition = parseExpression();
        // 匹配符号")"
        consume(GSTokenType.RPAREN, "Expected ')' after while condition");
        // 匹配符号";"
        consume(GSTokenType.SEMICOLON, "Expected ';' after do-while statement");
        // 返回do-while语句节点
        return new DoWhileStatement(body, condition);
    }

    /**
     * 解析while语句<br/>
     * {@literal 语法定义：WhileStatement
     * = "while", "(", Expression, ")", BlockStatement;}
     *
     * @return while语句
     */
    private WhileStatement parseWhileStatement() {
        // while语句必须"while"关键字开头
        consume(GSTokenType.WHILE, "Expected 'while' to start while statement");
        // 匹配符号"("
        consume(GSTokenType.LPAREN, "Expected '(' after 'while'");
        // 解析条件表达式
        Expression condition = parseExpression();
        // 匹配符号")"
        consume(GSTokenType.RPAREN, "Expected ')' after 'while'");
        // 解析循环体
        List<Node> body = null;
        // 空循环
        if (!match(GSTokenType.SEMICOLON)) {
            if (match(GSTokenType.LBRACE)) {
                // 如果不是空语句
                if (!check(GSTokenType.RBRACE)) {
                    body = new ArrayList<>();
                    // 循环解析语句，直到遇到当前块语句结束符号
                    do {
                        body.add(parseStatement());
                    } while (!check(GSTokenType.RBRACE));
                }
                consume(GSTokenType.RBRACE, "Expect '}' after block statement");
            } else {
                error(peek(), "Expected ';' or '{' after 'while'");
            }
        }
        // 返回while语句节点
        return new WhileStatement(condition, body);
    }

    /**
     * 解析function语句<br/>
     * {@literal 语法定义：FunctionStatement
     * = "function", Identifier, "(", [ ParameterList ], ")", BlockStatement;}
     *
     * @return function语句
     */
    private FunctionStatement parseFunctionStatement() {
        // function语句必须function关键字开头
        consume(GSTokenType.FUNCTION, "Expected 'function' keyword");
        // 获取函数名称
        String funName = consume(GSTokenType.IDENTIFIER, "Expected function name after 'function'");
        if ("null".equals(funName)) {
            error("Function name cannot be null");
        }
        // 创建函数名称标识符节点
        Identifier identifier = new Identifier(funName);
        // 匹配符号"("
        consume(GSTokenType.LPAREN, "Expected '(' after function name.");
        // 初始化参数列表节点
        List<Identifier> params = null;
        // 如果不是紧跟)，说明有参数
        if (!match(GSTokenType.RPAREN)) {
            // 初始化参数节点
            params = new ArrayList<>();
            // 循环解析多个参数
            do {
                String paramName = consume(GSTokenType.IDENTIFIER, "Expected parameter name");
                params.add(new Identifier(paramName));
            } while (match(GSTokenType.COMMA));
            // 匹配符号)
            consume(GSTokenType.RPAREN, "Expected ')' after parameters.");
        }
        // 解析块语句
        BlockStatement body = parseBlockStatement();
        // 这里需要判断，如果函数最后一行不是return，需要显式加上return null;
        if (!body.havingReturn()) {
            if (body.stmts == null) {
                body.stmts = new ArrayList<>();
            }
            body.stmts.add(new ReturnStatement(null));
        }
        // 返回函数定义语句节点
        return new FunctionStatement(identifier, params, body);
    }

    /**
     * 解析break语句<br/>
     * {@literal 语法定义：BreakStatement
     * = "break", ";";}
     *
     * @return break语句
     */
    private BreakStatement parseBreakStatement() {
        // 匹配"break"关键字
        consume(GSTokenType.BREAK, "Expected 'break' keyword");
        // 匹配";"符号
        consume(GSTokenType.SEMICOLON, "Expected ';' after 'break'.");
        // 返回break语句节点
        return new BreakStatement();
    }

    /**
     * 解析continue语句<br/>
     * {@literal 语法定义：ContinueStatement
     * = "continue", ";";}
     *
     * @return 返回continue语句
     */
    private ContinueStatement parseContinueStatement() {
        // 匹配"continue"关键字
        consume(GSTokenType.CONTINUE, "Expected 'continue' keyword");
        // 匹配";"符号
        consume(GSTokenType.SEMICOLON, "Expected ';' after 'continue'.");
        // 返回continue语句节点
        return new ContinueStatement();
    }

    /**
     * 解析return语句<br/>
     * {@literal 语法定义：ReturnStatement
     * = "return", [ Expression ], ";";}
     *
     * @return 返回return语句
     */
    private ReturnStatement parseReturnStatement() {
        // 匹配"return"关键字
        consume(GSTokenType.RETURN, "Expected 'return' keyword");
        // 初始化返回值表达式
        Expression expr = null;
        // 如果存在返回值，解析返回值表达式
        if (!match(GSTokenType.SEMICOLON)) {
            expr = parseExpression();
            consume(GSTokenType.SEMICOLON, "Expected ';' after return expression");
        }
        // 返回return语句节点
        return new ReturnStatement(expr);
    }

    /**
     * 解析throw语句<br/>
     * {@literal 语法定义：ThrowStatement
     * = "throw", Expression, ";";}
     *
     * @return 返回throw语句
     */
    private ThrowStatement parseThrowStatement() {
        // 匹配throw关键字
        consume(GSTokenType.THROW, "Expected 'throw' keyword");
        // 初始化throw值表达式
        Expression expr = parseExpression();
        // 消费;
        consume(GSTokenType.SEMICOLON, "Expected ';' after throw expression");
        // 返回return语句节点
        return new ThrowStatement(expr);
    }

    /**
     * 解析exception语句<br/>
     * {@literal 语法定义：ExceptionStatement
     * = "try", BlockStatement, [ CatchClause, FinallyClause
     * | FinallyClause ];}
     *
     * @return 返回exception语句
     */
    private ExceptionStatement parseExceptionStatement() {
        // 匹配try关键字
        consume(GSTokenType.TRY, "Expected 'try' keyword");
        // 解析try块语句
        BlockStatement tryBody = parseBlockStatement();
        // try子句
        TryClause tryClause = new TryClause(tryBody);
        // catch子句
        CatchClause catchClause = null;
        // finally子句
        FinallyClause finallyClause = null;
        // try块语句后面必须接catch或者finally
        if (check(GSTokenType.CATCH) || check(GSTokenType.FINALLY)) {
            // 解析异常处理语句
            if (match(GSTokenType.CATCH)) {
                // 匹配(
                consume(GSTokenType.LPAREN, "Expected '(' after catch.");
                // 获取函数名称
                String catchName = consume(GSTokenType.IDENTIFIER, "Expected catch identifier after '('");
                // 创建函数名称标识符节点
                Identifier identifier = new Identifier(catchName);
                // 匹配)
                consume(GSTokenType.RPAREN, "Expected ')' after catch identifier.");
                // 匹配捕获语句块
                BlockStatement catchBody = parseBlockStatement();
                // 创建异常处理节点
                catchClause = new CatchClause(identifier, catchBody.stmts);
            }
            if (match(GSTokenType.FINALLY)) {
                // 解析最终执行语句块
                BlockStatement finallyBody = parseBlockStatement();
                // 创建最终执行节点
                finallyClause = new FinallyClause(finallyBody);
            }
        } else {
            error(peek(), "Uncaught SyntaxError: Missing catch or finally after try");
        }
        // 返回异常处理语句
        return new ExceptionStatement(tryClause, catchClause, finallyClause);
    }

    /**
     * 解析表达式节点<br/>
     * {@literal 语法定义：<ExpressionStatement> ::= <Expression> ";"}
     *
     * @return 表达式节点
     */
    private ExpressionStatement parseExpressionStatement() {
        // 解析表达式
        Expression expr = parseExpression();
        // 匹配";"符号
        consume(GSTokenType.SEMICOLON, "Expected ';' after expression");
        // 返回表达式节点
        return new ExpressionStatement(expr);
    }

    /**
     * 解析变量声明列表节点<br/>
     * {@literal 语法定义：VariableDeclList
     * = "var", VariableDecl, { ",", VariableDecl };}
     *
     * @return 变量列表声明节点
     */
    private VariableDeclList parseVariableDeclList() {
        // 初始化变量声明列表
        List<VariableDecl> vars = new ArrayList<>();
        // 这里是只要匹配到,就执行变量声明解析。因为最少会有一个变量声明，所以使用do while
        do {
            vars.add(parseVariableDecl());
        } while (match(GSTokenType.COMMA));
        // 返回变量列表声明节点
        return new VariableDeclList(vars);
    }

    /**
     * 解析变量声明<br/>
     * {@literal 语法定义：VariableDecl
     * = Identifier, [ "=", Expression ];}
     *
     * @return 变量声明节点
     */
    private VariableDecl parseVariableDecl() {
        // 获取变量名
        String name = consume(GSTokenType.IDENTIFIER, "Expect variable name");
        // 初始化关联值
        Expression value = null;
        // 检测是否有关联赋值
        if (match(GSTokenType.EQ)) {
            // 解析表达式
            value = parseExpression();
        }
        // 返回变量声明节点
        return new VariableDecl(new Identifier(name), value);
    }

    /**
     * 解析表达式节点<br/>
     * {@literal 语法定义：Expression
     * = ConditionalExpression
     * | ( LeftHandSideExpression, AssignmentOperator, Expression );}
     *
     * @return 返回解析表达式节点
     */
    private Expression parseExpression() {
        // 首先解析表达式
        Node left = parseConditionalExpression();
        // 初始化操作符
        Operator operator = null;
        // 初始化右表达式
        Expression right = null;
        // 如果存在关联操作符
        if (isAssignmentOperator(peek())) {
            // 检测当前是否是左值(左值必须是一个可赋值对象)
            if (isLeftHandSideExpression(left)) {
                // 赋值操作符
                operator = new Operator(advance());
                // 解析右值
                right = parseExpression();
            } else {
                // 抛出无效左值异常
                error("Uncaught SyntaxError: Invalid left-hand side in assignment");
            }
        }
        // 返回解析表达式节点
        return new Expression(left, operator, right);
    }

    /**
     * 解析条件表达式<br/>
     * {@literal 语法定义：ConditionalExpression
     * = LogicalORExpression [ "?", Expression, ":", Expression ];}
     *
     * @return 返回条件表达式节点
     */
    private Node parseConditionalExpression() {
        // 解析逻辑or表达式
        Node condition = parseLogicalORExpression();
        // 初始化then表达式
        Expression thenExpr;
        // 初始化else表达式
        Expression elseExpr;
        // 如果匹配到了?号，进入三目表达式
        if (match(GSTokenType.QUESTION)) {
            // 解析then表达式
            thenExpr = parseExpression();
            // 匹配":"符号
            consume(GSTokenType.COLON, "Expected ':' after then expression");
            // 解析else表达式
            elseExpr = parseExpression();
            // 返回条件表达式节点
            return new ConditionalExpression(condition, thenExpr, elseExpr);
        }
        // 返回条件表达式节点（这里有可能返回原始值）
        return condition;
    }

    /**
     * 解析逻辑or表达式<br/>
     * {@literal 语法定义：LogicalORExpression
     * = LogicalANDExpression, { "||", LogicalANDExpression };}
     *
     * @return 返回逻辑or表达式节点
     */
    private Node parseLogicalORExpression() {
        // 解析逻辑与表达式节点
        Node expr = parseLogicalANDExpression();
        // 如果匹配到||
        while (check(GSTokenType.T_OR)) {
            // 创建操作符节点
            Operator operator = new Operator(advance());
            // 解析逻辑与表达式节点
            Node right = parseLogicalANDExpression();
            // 构建新的逻辑or表达式
            expr = new LogicalORExpression(expr, operator, right);
        }
        // 返回逻辑or表达式节点
        return expr;
    }

    /**
     * 解析逻辑and表达式<br/>
     * {@literal 语法定义：LogicalANDExpression
     * = BitwiseORExpression, { "&&", BitwiseORExpression };}
     *
     * @return 返回逻辑and表达式节点
     */
    private Node parseLogicalANDExpression() {
        // 解析按位or表达式节点
        Node expr = parseBitwiseORExpression();
        // 如果匹配到&&
        while (check(GSTokenType.T_AND)) {
            // 创建操作符节点
            Operator operator = new Operator(advance());
            // 解析按位or表达式节点
            Node right = parseBitwiseORExpression();
            // 构建新的逻辑and表达式
            expr = new LogicalANDExpression(expr, operator, right);
        }
        // 返回逻辑and表达式节点
        return expr;
    }

    /**
     * 解析按位或表达式<br/>
     * {@literal 语法定义：BitwiseORExpression
     * = BitwiseXORExpression, { "|", BitwiseXORExpression };}
     *
     * @return 返回按位或表达式节点
     */
    private Node parseBitwiseORExpression() {
        // 解析按位xor表达式节点
        Node expr = parseBitwiseXORExpression();
        // 如果匹配到|
        while (check(GSTokenType.BIT_OR)) {
            // 创建操作符节点
            Operator operator = new Operator(advance());
            // 解析按位xor表达式节点
            Node right = parseBitwiseXORExpression();
            // 构建新的按位or表达式
            expr = new BitwiseORExpression(expr, operator, right);
        }
        // 返回按位或表达式节点
        return expr;
    }

    /**
     * 解析按位xor表达式<br/>
     * {@literal 语法定义：BitwiseXORExpression
     * = BitwiseANDExpression, { "^", BitwiseANDExpression };}
     *
     * @return 返回按位xor表达式节点
     */
    private Node parseBitwiseXORExpression() {
        // 解析按位and表达式节点
        Node expr = parseBitwiseANDExpression();
        // 如果匹配到^
        while (check(GSTokenType.BIT_XOR)) {
            // 创建操作符节点
            Operator operator = new Operator(advance());
            // 解析按位and表达式节点
            Node right = parseBitwiseANDExpression();
            // 构建新的按位xor表达式
            expr = new BitwiseXORExpression(expr, operator, right);
        }
        // 返回按位xor表达式节点
        return expr;
    }

    /**
     * 解析按位and表达式<br/>
     * {@literal 语法定义：BitwiseANDExpression
     * = EqualityExpression, { "&", EqualityExpression };}
     *
     * @return 返回按位and表达式节点
     */
    private Node parseBitwiseANDExpression() {
        // 解析比较表达式节点
        Node expr = parseEqualityExpression();
        // 如果匹配到&
        while (check(GSTokenType.BIT_AND)) {
            // 创建操作符节点
            Operator operator = new Operator(advance());
            // 解析比较表达式节点
            Node right = parseEqualityExpression();
            // 构建新的按位and表达式
            expr = new BitwiseANDExpression(expr, operator, right);
        }
        // 返回按位and表达式节点
        return expr;
    }

    /**
     * 解析比较表达式<br/>
     * {@literal 语法定义：EqualityExpression
     * = RelationalExpression, { ( "=="
     * | "!="
     * | "==="
     * | "!==" ), RelationalExpression };}
     *
     * @return 返回比较表达式节点
     */
    private Node parseEqualityExpression() {
        // 解析关系表达式节点
        Node expr = parseRelationalExpression();
        // 如果匹配到操作符
        while (isEqualityOperator(peek())) {
            // 创建操作符节点
            Operator operator = new Operator(advance());
            // 解析关系表达式节点
            Node right = parseRelationalExpression();
            // 构建新的比较表达式
            expr = new EqualityExpression(expr, operator, right);
        }
        // 返回比较表达式节点
        return expr;
    }

    /**
     * 解析关系表达式<br/>
     * {@literal 语法定义：RelationalExpression
     * = ShiftExpression, { ( "<"
     * | ">"
     * | "<="
     * | ">=" ), ShiftExpression };}
     *
     * @return 返回关系表达式节点
     */
    private Node parseRelationalExpression() {
        // 解析移位表达式节点
        Node expr = parseShiftExpression();
        // 如果匹配到关系操作符
        while (isRelationalOperator(peek())) {
            // 创建操作符节点
            Operator operator = new Operator(advance());
            // 解析移位表达式节点
            Node right = parseShiftExpression();
            // 构建新的比较表达式
            expr = new RelationalExpression(expr, operator, right);
        }
        // 返回关系表达式节点
        return expr;
    }

    /**
     * 解析移位表达式<br/>
     * {@literal 语法定义：ShiftExpression
     * = AdditiveExpression, { ( "<<"
     * | ">>" ), AdditiveExpression };}
     *
     * @return 返回移位表达式节点
     */
    private Node parseShiftExpression() {
        // 解析加法表达式节点
        Node expr = parseAdditiveExpression();
        // 如果匹配到移位操作符
        while (isShiftOperator(peek())) {
            // 创建操作符节点
            Operator operator = new Operator(advance());
            // 解析加法表达式节点
            Node right = parseAdditiveExpression();
            // 构建新的移位表达式
            expr = new ShiftExpression(expr, operator, right);
        }
        // 返回移位表达式节点
        return expr;
    }

    /**
     * 解析加减法运算<br/>
     * {@literal 语法定义：AdditiveExpression
     * = MultiplicativeExpression, { ( "+"
     * | "-" ), MultiplicativeExpression };}
     *
     * @return 返回加法表达式节点
     */
    private Node parseAdditiveExpression() {
        // 解析乘法表达式节点
        Node expr = parseMultiplicativeExpression();
        // 如果匹配到加法操作符
        while (isAdditiveOperator(peek())) {
            // 创建操作符节点
            Operator operator = new Operator(advance());
            // 解析乘法表达式节点
            Node right = parseMultiplicativeExpression();
            // 构建新的移位表达式
            expr = new AdditiveExpression(expr, operator, right);
        }
        // 返回加法表达式节点
        return expr;
    }

    /**
     * 解析乘法表达式<br/>
     * {@literal 语法定义：MultiplicativeExpression
     * = UnaryExpression, { ( "*"
     * | "/"
     * | "%" ), UnaryExpression };}
     *
     * @return 返回乘法表达式节点
     */
    private Node parseMultiplicativeExpression() {
        // 解析一元表达式节点
        Node expr = parseUnaryExpression();
        // 如果匹配到乘法操作符
        while (isMultiplicativeOperator(peek())) {
            // 创建操作符节点
            Operator operator = new Operator(advance());
            // 解析一元表达式节点
            Node right = parseUnaryExpression();
            // 构建新的移位表达式
            expr = new MultiplicativeExpression(expr, operator, right);
        }
        // 返回乘法表达式节点
        return expr;
    }

    /**
     * 解析一元表达式<br/>
     * {@literal 语法定义：UnaryExpression
     * = ( "+"
     * | "-"
     * | "!"
     * | "~" ), PostfixExpression
     * | ( "++"
     * | "--" ), LeftHandSideExpression
     * | PostfixExpression;}
     *
     * @return 返回一元表达式节点
     */
    private Node parseUnaryExpression() {
        Operator operator;
        // 如果是一元操作符
        if (isUnaryOperator(peek())) {
            operator = new Operator(advance());
            // 解析后缀表达式
            Node operand = parsePostfixExpression();
            return new UnaryExpression(operator, operand);
        } else if (isPosfixOperator(peek())) {  // 如果匹配到了前缀++/--
            operator = new Operator(advance());
            // 解析左值表达式
            Node operand = parsePostfixExpression();
            // 这里要判断这个Node是LeftHandSideExpression
            if (isLeftHandSideExpression(operand)) {
                return new UnaryExpression(operator, operand);
            } else {
                error("Uncaught SyntaxError: Invalid left-hand side in assignment");
            }
        }
        // 解析后缀表达式
        return parsePostfixExpression();
    }

    /**
     * 解析后缀表达式
     * {@literal 语法定义：PostfixExpression
     * = LeftHandSideExpression, ( "++"
     * | "--" )
     * | PrimaryExpression;}
     */
    private Node parsePostfixExpression() {
        Node expr = parsePrimaryExpression();
        // 如果匹配到了后缀++/--
        if (isPosfixOperator(peek())) {
            Operator operator = new Operator(advance());
            // 必须是一个左值
            if (isLeftHandSideExpression(expr)) {
                return new PostfixExpression(expr, operator);
            } else {
                error(peek(), "Invalid left-hand side expression in postfix operation");
            }
        }
        return expr;
    }

    /**
     * 解析主表达式
     * {@literal 语法定义：PrimaryExpression
     * = Literal
     * | ObjectLiteral
     * | FunctionExpression
     * | NewExpression
     * | AccessProperty
     * | CallExpression
     * | MemberExpression;}
     */
    private Node parsePrimaryExpression() {
        // 获取当前token
        GSToken token = peek();
        switch (token.type) {
            case NULL:
            case TRUE:
            case STRING:
            case FALSE:
            case FLOAT:
            case NaN:
            case INTEGER_HEX:
            case INTEGER_DECIMAL: {  // Literal
                return new Literal(advance());
            }
            case LBRACE: {  // <ObjectLiteral>
                advance();
                Hashtable<Node, Node> members = null;
                if (!match(GSTokenType.RBRACE)) {
                    members = new Hashtable<>();
                    do {
                        Node key;
                        switch (peek().type) {
                            case IDENTIFIER: {
                                key = new Identifier(advance().value);
                                break;
                            }
                            case STRING:
                            case INTEGER_HEX:
                            case INTEGER_DECIMAL: {
                                key = new Literal(advance());
                                break;
                            }
                            case LBRACKET: {  // 如果是表达式
                                advance();
                                key = parseExpression();
                                consume(GSTokenType.RBRACKET, "Expected ']' to close computed property name");
                                break;
                            }
                            default: {
                                throw new RuntimeException(String.format("[Line %d:%d] Error:Unexpected token '%s' while parsing PropertyName.", token.line, token.column, token.value));
                            }
                        }
                        consume(GSTokenType.COLON, "Expected ':' after property name.");
                        Node value = parseExpression();
                        members.put(key, value);
                    } while (match(GSTokenType.COMMA));
                    consume(GSTokenType.RBRACE, "Expected '}' to close object literal.");
                }
                return new ObjectLiteral(members);
            }
            case FUNCTION: {  // FunctionExpression
                return parseFunctionExpression();
            }
            case NEW: {  // NewExpression
                advance();
                // new后面可以跟一个表达式
                Expression constructor = parseExpression();
                return new NewExpression(constructor);
            }
            default: {
                return parseAccessPropertyOrMemberExpressionOrCallExpression();
            }
        }
    }

    /**
     * 尝试解析AccessProperty或者MemberExpression或者CallExpression，这是一个递进关系</br>
     * <p>
     * {@literal 语法定义：AccessProperty
     * = Identifier
     * | "(", Expression, ")"
     * | ArrayLiteral;}
     * </br>
     * {@literal 语法定义：MemberExpression
     * = AccessProperty, { MemberAccess
     * | CallSuffix }, MemberAccess;}
     * </br>
     * {@literal 语法定义：CallExpression
     * = AccessProperty, { MemberAccess
     * | CallSuffix }, CallSuffix;}
     */
    private Node parseAccessPropertyOrMemberExpressionOrCallExpression() {
        Node expr = null;
        // 获取当前Token
        GSToken token = peek();
        switch (token.type) {
            case IDENTIFIER: {  // Identifier
                expr = new Identifier(advance().value);
                break;
            }
            case LPAREN: {  // "(", Expression, ")"
                advance();
                expr = new ParenthesizedExpression(parseExpression());
                consume(GSTokenType.RPAREN, "Expected ')' after expression");
                break;
            }
            case LBRACKET: {  // <ArrayLiteral>
                advance();
                List<Node> element = null;
                // 如果有数组定义
                if (!match(GSTokenType.RBRACKET)) {
                    element = new ArrayList<>();
                    do {
                        element.add(parseExpression());
                    } while (match(GSTokenType.COMMA));
                    consume(GSTokenType.RBRACKET, "Expected ']' after array element.");
                }
                expr = new ArrayLiteral(element);
                break;
            }
            default: {
                throw new RuntimeException(String.format("[Line %d:%d] Error:Unexpected token '%s' while parsing primary expression", token.line, token.column, token.value));
            }
        }
        // 连续匹配MemberAccess或者CallSuffix
        while (true) {
            if (match(GSTokenType.DOT)) {  // 匹配到了对象成员访问
                String name = consume(GSTokenType.IDENTIFIER, "");
                Identifier identifier = new Identifier(name);
                expr = new MemberAccess(expr, identifier);
            } else if (match(GSTokenType.LBRACKET)) {  // 匹配到了数组访问
                Expression expression = parseExpression();
                consume(GSTokenType.RBRACKET, "");
                expr = new MemberAccess(expr, expression);
            } else if (match(GSTokenType.LPAREN)) {  // 匹配到了函数调用
                ArrayList<Expression> args = new ArrayList<>();
                // 这里先判断是否为空参数
                if (!check(GSTokenType.RPAREN)) {
                    do {
                        args.add(parseExpression());
                    } while (match(GSTokenType.COMMA));
                }
                consume(GSTokenType.RPAREN, "Expected ')' to close function argument list after arguments.");
                expr = new FunctionCallNode(expr, args);
            } else {
                break;
            }
        }
        return expr;
    }

    /**
     * 解析function表达式<br/>
     * {@literal 语法定义：FunctionExpression
     * = "function", [ Identifier ], "(", [ ParameterList ], ")", BlockStatement;}
     *
     * @return function表达式
     */
    private FunctionExpression parseFunctionExpression() {
        // function语句必须function关键字开头
        consume(GSTokenType.FUNCTION, "Expected 'function' keyword");
        // 函数名称
        Identifier identifier = new Identifier("null");
        if (check(GSTokenType.IDENTIFIER)) {
            // 获取函数名称
            String funName = consume(GSTokenType.IDENTIFIER, "Expected function name after 'function'");
            if ("null".equals(funName)) {
                error("Function name cannot be null");
            }
            identifier = new Identifier(funName);
        }
        // 匹配符号"("
        consume(GSTokenType.LPAREN, "Expected '(' after function name.");
        // 初始化参数列表节点
        List<Identifier> params = null;
        // 如果不是紧跟)，说明有参数
        if (!match(GSTokenType.RPAREN)) {
            // 初始化参数节点
            params = new ArrayList<>();
            // 循环解析多个参数
            do {
                String paramName = consume(GSTokenType.IDENTIFIER, "Expected parameter name");
                params.add(new Identifier(paramName));
            } while (match(GSTokenType.COMMA));
            // 匹配符号)
            consume(GSTokenType.RPAREN, "Expected ')' after parameters.");
        }
        // 解析块语句
        BlockStatement body = parseBlockStatement();
        // 这里需要判断，如果函数最后一行不是return，需要显式加上return null;
        if (!body.havingReturn()) {
            body.stmts.add(new ReturnStatement(null));
        }
        // 返回函数定义语句节点
        return new FunctionExpression(identifier, params, body);
    }

    /**
     * 判断当前表达式是否是左值表达式
     *
     * @param expr 传入表达式
     * @return
     */
    private boolean isLeftHandSideExpression(Node expr) {
        return expr instanceof Identifier || expr instanceof MemberAccess;
    }

    /**
     * 判断当前toke是否是关联操作符
     *
     * @param token 传入token
     * @return
     */
    private boolean isAssignmentOperator(GSToken token) {
        for (GSTokenType gsTokenType : ASSIGN) {
            if (token.type == gsTokenType) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断当前token是否是比较操作符
     *
     * @param token 传入token
     * @return
     */
    private boolean isEqualityOperator(GSToken token) {
        for (GSTokenType gsTokenType : EQUALITY) {
            if (token.type == gsTokenType) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断当前token是否是关系操作符
     *
     * @param token 传入token
     * @return
     */
    private boolean isRelationalOperator(GSToken token) {
        for (GSTokenType gsTokenType : RELATIONAL) {
            if (token.type == gsTokenType) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断当前token是否为移位操作符
     *
     * @param token 传入token
     * @return
     */
    private boolean isShiftOperator(GSToken token) {
        for (GSTokenType gsTokenType : SHIFT) {
            if (token.type == gsTokenType) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断当前token是否是加减法
     *
     * @param token 传入token
     * @return
     */
    private boolean isAdditiveOperator(GSToken token) {
        for (GSTokenType gsTokenType : ADDITIVE) {
            if (token.type == gsTokenType) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断当前token是否是乘除法
     *
     * @param token 传入token
     * @return
     */
    private boolean isMultiplicativeOperator(GSToken token) {
        for (GSTokenType gsTokenType : MULTIPLICATIVE) {
            if (token.type == gsTokenType) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断当前token是否是一元操作符
     *
     * @param token 传入token
     * @return
     */
    private boolean isUnaryOperator(GSToken token) {
        for (GSTokenType gsTokenType : UNARY) {
            if (token.type == gsTokenType) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断当前token是否是自增和自减
     *
     * @param token 传入token
     * @return
     */
    private boolean isPosfixOperator(GSToken token) {
        for (GSTokenType postfix : POSTFIX) {
            if (token.type == postfix) {
                return true;
            }
        }
        return false;
    }

    /**
     * 报错信息
     *
     * @param message 报错信息
     */
    private void error(String message) {
        throw new RuntimeException(String.format("Uncaught SyntaxError: %s", message));
    }

    /**
     * 报错信息
     *
     * @param token   当前token
     * @param message 报错信息
     */
    private void error(GSToken token, String message) {
        throw new RuntimeException(String.format("[Line %d:%d] Uncaught SyntaxError: %s", token.line, token.column, message));
    }

    /**
     * 消费指定token，如果是返回true，如果当前不是返回false
     *
     * @param type 要预消费的token类型
     * @return 是否消费成功
     */
    private boolean match(GSTokenType type) {
        if (tokens.get(pos).type == type) {
            advance();
            return true;
        }
        return false;
    }

    /**
     * 消费指定类型token，与match不同的是这个会直接抛出异常并终止执行
     *
     * @param type    token类型
     * @param message 报错提示
     * @return token值
     */
    private String consume(GSTokenType type, String message) {
        String result;
        if (check(type)) {
            result = advance().value;
        } else {
            GSToken token = peek();
            throw new RuntimeException(String.format("[Line %d:%d] Error: %s", token.line, token.column, message));
        }
        return result;
    }

    /**
     * 检查当前token类型是否匹配
     *
     * @param type
     * @return
     */
    private boolean check(GSTokenType type) {
        return !isAtEnd() && peek().type == type;
    }

    /**
     * 获取当前token并移动指针
     *
     * @return
     */
    private GSToken advance() {
        if (isAtEnd()) {
            GSToken lastToken = tokens.get(tokens.size() - 1);
            throw new RuntimeException(String.format("[Line %d:%d] SyntaxError: Unexpected end of input after '%s'", lastToken.line, lastToken.column, lastToken.value));
        }
        ++pos;
        return previous();
    }

    /**
     * 获取上一个token
     *
     * @return
     */
    private GSToken previous() {
        return tokens.get(pos - 1);
    }

    /**
     * 获取当前token
     *
     * @return
     */
    private GSToken peek() {
        return tokens.get(pos);
    }

    /**
     * token是否结束
     *
     * @return
     */
    private boolean isAtEnd() {
        return peek().type == GSTokenType.EOF;
    }

}
