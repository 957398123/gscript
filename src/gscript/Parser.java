package gscript;

import gscript.node.*;
import gscript.node.Literal;
import gscript.token.GSToken;
import gscript.token.GSTokenType;

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

public class Parser {

    private int pos;

    /**
     * 赋值操作符
     */
    private static final GSTokenType[] ASSIGN = {GSTokenType.EQ, GSTokenType.PLUS_EQUAL, GSTokenType.MINUS_EQUAL, GSTokenType.STAR_EQUAL, GSTokenType.SLASH_EQUAL, GSTokenType.PERCENT_EQUAL, GSTokenType.T_AND_ASSIGN, GSTokenType.T_OR_ASSIGN, GSTokenType.T_XOR_ASSIGN};

    /**
     * 比较操作符
     */
    private static final GSTokenType[] EQUALITY = {GSTokenType.T_EQ, GSTokenType.T_NEQ};

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

    private List<GSToken> tokens;

    public Parser(List<GSToken> tokens) {
        this.pos = 0;
        this.tokens = tokens;
    }

    /**
     * 解析程序
     * <p>
     * 程序由0条或者多条语句组成
     *
     * @return
     */
    public Node parseProgram() {
        List<Node> stmt = new ArrayList<>();
        while (!isAtEnd()) {
            stmt.add(parseStatement());
        }
        return new ProgramNode(stmt);
    }

    /**
     * 解析语句
     * 以下是语句类型
     * <BlockStatement>  块语句 {}
     * <EmptyStatement>  空语句 ; 忽略掉
     * <VariableStatement> 变量声明语句 var a = 9;
     * <IfStatement> if条件语句 if(a==c){}
     * <ForStatement> for循环语句 for(var i = 0; i < c.length; ++i){}
     * <DoWhileStatement> do语句 do{var a = 1;}while(a==c);
     * <WhileStatement> while语句 while(a==c){}
     * <FunctionStatement> 函数定义语句 function(a, c){}
     * <BreakStatement> break语句 break;
     * <ContinueStatement> continue语句 continue;
     * <ReturnStatement> return语句 return
     * <ExpressionStatement> 表达式语句
     *
     * @return
     */
    private Node parseStatement() {
        // 预看当前token类型
        if (check(GSTokenType.SEMICOLON)) {
            advance();
        }  // 忽略空语句
        if (check(GSTokenType.LBRACE)) return parseBlockStatement();         // 解析块语句
        if (check(GSTokenType.VAR)) return parseVariableStatement();         // 解析变量声明语句
        if (check(GSTokenType.IF)) return parseIfStatement();                // 解析if語句
        if (check(GSTokenType.FOR)) return parseForStatement();              // 解析for语句
        if (check(GSTokenType.DO)) return parseDoWhileStatement();           // 尝试解析do语句
        if (check(GSTokenType.WHILE)) return parseWhileStatement();          // 尝试解析while语句
        if (check(GSTokenType.FUNCTION)) return parseFunctionStatement();    // 尝试解析function语句
        if (check(GSTokenType.BREAK)) return parseBreakStatement();          // 尝试解析break语句
        if (check(GSTokenType.CONTINUE)) return parseContinueStatement();    // 尝试解析continue语句
        if (check(GSTokenType.RETURN)) return parseReturnStatement();        // 尝试解析return语句
        return parseExpressionStatement();                                   // 默认解析表达式语句
    }

    /**
     * 解析块语句
     * <BlockStatement>           ::= "{" {Statement} "}"
     *
     * @return
     */
    private BlockStatement parseBlockStatement() {
        consume(GSTokenType.LBRACE, "Expect '{' before block statement");
        List<Node> stmt = new ArrayList<>();
        // 这里要先判定空语句
        if (!check(GSTokenType.RBRACE)) {
            do {
                stmt.add(parseStatement());
            } while (!check(GSTokenType.RBRACE));
        }
        consume(GSTokenType.RBRACE, "Expect '}' after block statement");
        return new BlockStatement(stmt);
    }

    /**
     * 解析变量声明语句
     * <VariableStatement>        ::= <VariableDeclList> ";"
     *
     * @return
     */
    private VariableStatement parseVariableStatement() {
        consume(GSTokenType.VAR, "variable declaration must start with 'var'");
        VariableDeclList vars = parseVariableDeclList();
        consume(GSTokenType.SEMICOLON, "variable declaration must end with ';'");
        return new VariableStatement(vars);
    }

    /**
     * 解析if语句
     * <IfStatement>              ::= "if" "(" <Expression> ")"
     * (
     * ( <BlockStatement> [ "else" ( <BlockStatement> | <IfStatement> ) ] ) | ";"
     * )
     *
     * @return
     */
    private IfStatement parseIfStatement() {
        consume(GSTokenType.IF, "if statement must start with 'if'");
        consume(GSTokenType.LPAREN, "Expected '(' after 'if'.");
        // 解析条件表达式
        Expression condition = parseExpression();
        consume(GSTokenType.RPAREN, "Expected ')' after condition.");
        // then分支
        BlockStatement thenBranch = null;
        // else分支
        Node elseBranch = null;
        // 如果不是;结尾
        if (!match(GSTokenType.SEMICOLON)) {
            thenBranch = parseBlockStatement();
            // 如果匹配到了else
            if (match(GSTokenType.ELSE)) {
                if (check(GSTokenType.LBRACE)) {  // 如果是{
                    elseBranch = parseBlockStatement();
                } else if (check(GSTokenType.IF)) {  //如果是if
                    elseBranch = parseIfStatement();
                }
            }
        }
        return new IfStatement(condition, thenBranch, elseBranch);
    }

    /**
     * 解析for语句
     * <ForStatement>             ::= "for" "("
     * ( <VariableDeclList> | <Expression>? )
     * ";" [ <Expression> ]? ";" [ <Expression> ]? ")"
     * <BlockStatement>
     *
     * @return
     */
    private ForStatement parseForStatement() {
        consume(GSTokenType.FOR, "for statement must start with 'for'");
        consume(GSTokenType.LPAREN, "Expected '(' after 'for'.");
        Node init = null;
        Expression condition = null;
        Expression update = null;
        BlockStatement body;
        // 解析init
        if (check(GSTokenType.VAR)) {
            init = parseVariableStatement();
        } else if (!match(GSTokenType.SEMICOLON)) {  // 如果不匹配;就尝试解析表达式
            init = parseExpression();
            consume(GSTokenType.SEMICOLON, "Expected ';' after for statement 'init'.");
        }
        // 解析condition
        if (!match(GSTokenType.SEMICOLON)) {
            condition = parseExpression();
            consume(GSTokenType.SEMICOLON, "Expected ';' after for statement 'condition'.");
        }
        // 解析update
        if (!match(GSTokenType.RPAREN)) {
            update = parseExpression();
            consume(GSTokenType.RPAREN, "Expected ')' after for statement 'update'.");
        }
        // 解析body
        body = parseBlockStatement();
        return new ForStatement(init, condition, update, body);
    }

    /**
     * 解析do语句
     * <DoWhileStatement>         ::= "do" <BlockStatement> "while" "(" <Expression> ")" ";"
     *
     * @return
     */
    private DoWhileStatement parseDoWhileStatement() {
        consume(GSTokenType.DO, "Expected 'do' to start do-while statement");
        BlockStatement body = parseBlockStatement();
        consume(GSTokenType.WHILE, "Expected 'while' after do-while body");
        consume(GSTokenType.LPAREN, "Expected '(' after 'while'");
        Expression condition = parseExpression();
        consume(GSTokenType.RPAREN, "Expected ')' after while condition");
        consume(GSTokenType.SEMICOLON, "Expected ';' after do-while statement");
        return new DoWhileStatement(body, condition);
    }

    /**
     * 解析while语句
     * <WhileStatement>           ::= "while" "(" <Expression> ")" <BlockStatement>
     *
     * @return
     */
    private WhileStatement parseWhileStatement() {
        consume(GSTokenType.WHILE, "Expected 'while' to start while statement");
        consume(GSTokenType.LPAREN, "Expected '(' after 'while'");
        Expression condition = parseExpression();
        consume(GSTokenType.RPAREN, "Expected ')' after 'while'");
        BlockStatement body = parseBlockStatement();
        return new WhileStatement(condition, body);
    }

    /**
     * 解析function语句
     * <FunctionStatement>        ::= "function" <Identifier>
     * "(" [ <ParameterList> ] ")"
     * "{" {Statement} "}"
     *
     * @return
     */
    private FunctionStatement parseFunctionStatement() {
        consume(GSTokenType.FUNCTION, "Expected 'function' keyword");
        String funName = consume(GSTokenType.IDENTIFIER, "Expected function name after 'function'");
        Identifier identifier = new Identifier(funName);
        consume(GSTokenType.LPAREN, "Expected '(' after function name.");
        List<Identifier> params = new ArrayList<>();
        if (!match(GSTokenType.RPAREN)) {  // 如果有参数
            do {
                String paramName = consume(GSTokenType.IDENTIFIER, "Expected parameter name");
                params.add(new Identifier(paramName));
            } while (match(GSTokenType.COMMA));
            consume(GSTokenType.RPAREN, "Expected ')' after parameters.");
        }
        consume(GSTokenType.LBRACE, "Expect '{' before function statement");
        List<Node> stmt = new ArrayList<>();
        // 这里要先判定空语句
        if (!check(GSTokenType.RBRACE)) {
            do {
                stmt.add(parseStatement());
            } while (!check(GSTokenType.RBRACE));
            // 在这里判断最后结束语句是否是return，不是显示加上空return
            if (!(stmt.get(stmt.size() - 1) instanceof ReturnStatement)) {
                stmt.add(new ReturnStatement(null));
            }
        }
        consume(GSTokenType.RBRACE, "Expect '}' after function statement");
        BlockStatement body = new BlockStatement(stmt);
        return new FunctionStatement(identifier, params, body);
    }

    /**
     * 解析function语句
     * <FunctionStatement>        ::= "function" {<Identifier>}
     * "(" [ <ParameterList> ] ")"
     * "{" {Statement} "}"
     *
     * @return
     */
    private MemberFunctionStatement parseMemberFunctionStatement() {
        consume(GSTokenType.FUNCTION, "Expected 'function' keyword");
        Identifier identifier = new Identifier("anonymous");
        if (peek().type == GSTokenType.IDENTIFIER) {
            String funName = consume(GSTokenType.IDENTIFIER, "Expected function name after 'function'");
            identifier = new Identifier(funName);
        }
        consume(GSTokenType.LPAREN, "Expected '(' after function name.");
        List<Identifier> params = new ArrayList<>();
        if (!match(GSTokenType.RPAREN)) {  // 如果有参数
            do {
                String paramName = consume(GSTokenType.IDENTIFIER, "Expected parameter name");
                params.add(new Identifier(paramName));
            } while (match(GSTokenType.COMMA));
            consume(GSTokenType.RPAREN, "Expected ')' after parameters.");
        }
        consume(GSTokenType.LBRACE, "Expect '{' before function statement");
        List<Node> stmt = new ArrayList<>();
        // 这里要先判定空语句
        if (!check(GSTokenType.RBRACE)) {
            do {
                stmt.add(parseStatement());
            } while (!check(GSTokenType.RBRACE));
            // 在这里判断最后结束语句是否是return，不是显示加上空return
            if (!(stmt.get(stmt.size() - 1) instanceof ReturnStatement)) {
                stmt.add(new ReturnStatement(null));
            }
        }
        consume(GSTokenType.RBRACE, "Expect '}' after function statement");
        BlockStatement body = new BlockStatement(stmt);
        return new MemberFunctionStatement(identifier, params, body);
    }

    /**
     * 解析break语句
     * <BreakStatement>           ::= "break;"
     *
     * @return
     */
    private BreakStatement parseBreakStatement() {
        consume(GSTokenType.BREAK, "Expected 'break' keyword");
        consume(GSTokenType.SEMICOLON, "Expected ';' after 'break'.");
        return new BreakStatement();
    }

    /**
     * 解析continue语句
     * <ContinueStatement>        ::= "continue;"
     *
     * @return
     */
    private ContinueStatement parseContinueStatement() {
        consume(GSTokenType.CONTINUE, "Expected 'continue' keyword");
        consume(GSTokenType.SEMICOLON, "Expected ';' after 'continue'.");
        return new ContinueStatement();
    }

    /**
     * 解析return语句
     * <ReturnStatement>          ::= "return" [ <Expression> ";"]
     *
     * @return
     */
    private ReturnStatement parseReturnStatement() {
        consume(GSTokenType.RETURN, "Expected 'return' keyword");
        Expression expr = null;
        if (!match(GSTokenType.SEMICOLON)) {
            expr = parseExpression();
            consume(GSTokenType.SEMICOLON, "Expected ';' after return expression");
        }
        return new ReturnStatement(expr);
    }

    /**
     * 解析表达式语句
     * <ExpressionStatement>      ::= <Expression> ";"
     *
     * @return
     */
    private ExpressionStatement parseExpressionStatement() {
        Expression expr = parseExpression();
        consume(GSTokenType.SEMICOLON, "Expected ';' after expression");
        return new ExpressionStatement(expr);
    }

    /**
     * 解析变量声明列表
     * <VariableDeclList>         :=  "var" <VariableDecl> { "," <VariableDecl> }
     *
     * @return
     */
    private VariableDeclList parseVariableDeclList() {
        List<VariableDecl> decls = new ArrayList<>();
        do {
            decls.add(parseVariableDecl());
        } while (match(GSTokenType.COMMA));
        return new VariableDeclList(decls);
    }

    /**
     * 解析标识符
     * <VariableDecl>             ::= <Identifier> [ "=" <Expression> ]
     *
     * @return
     */
    private VariableDecl parseVariableDecl() {
        // 获取变量名
        String name = consume(GSTokenType.IDENTIFIER, "Expect variable name");
        // 获取值
        Expression value = null;
        // 检测是否有关联语句
        if (match(GSTokenType.EQ)) {
            value = parseExpression();
        }
        return new VariableDecl(new Identifier(name), value);
    }

    /**
     * 解析表达式
     * <Expression>               ::= <ConditionalExpression> ( <AssignmentOperator> <Expression> )?
     *
     * @return
     */
    private Expression parseExpression() {
        ConditionalExpression left = parseConditionalExpression();
        Operator operator = null;
        Expression right = null;
        // 如果存在关联操作符
        if (isAssignmentOperator(peek())) {
            operator = new Operator(advance());
            right = parseExpression();
        }
        return new Expression(left, operator, right);
    }

    /**
     * 解析条件表达式
     * <ConditionalExpression>    ::= <LogicalORExpression> "?" <Expression> ":" <Expression> | <LogicalORExpression>
     *
     * @return
     */
    private ConditionalExpression parseConditionalExpression() {
        Node condition = parseLogicalORExpression();
        Expression thenExpr = null;
        Expression elseExpr = null;
        // 如果匹配到了?号，进入三目表达式
        if (match(GSTokenType.QUESTION)) {
            thenExpr = parseExpression();
            consume(GSTokenType.COLON, "Expected ':' after then expression");
            elseExpr = parseExpression();
        }
        return new ConditionalExpression(condition, thenExpr, elseExpr);
    }

    /**
     * 解析逻辑or表达式
     * <LogicalORExpression>      ::= <LogicalANDExpression> { "||" <LogicalANDExpression> }
     *
     * @return
     */
    private Node parseLogicalORExpression() {
        Node left = parseLogicalANDExpression();
        while (check(GSTokenType.T_OR)) {
            Operator operator = new Operator(advance());
            Node right = parseLogicalANDExpression();
            left = new LogicalORExpression(left, operator, right);
        }
        return left;
    }

    /**
     * 解析逻辑与表达式
     * <LogicalANDExpression>     ::= <BitwiseORExpression> { "&&" <BitwiseORExpression> }
     *
     * @return
     */
    private Node parseLogicalANDExpression() {
        Node left = parseBitwiseORExpression();
        while (check(GSTokenType.T_AND)) {
            Operator operator = new Operator(advance());
            Node right = parseBitwiseORExpression();
            left = new LogicalANDExpression(left, operator, right);
        }
        return left;
    }

    /**
     * 解析按位或表达式
     * <BitwiseORExpression>      ::= <BitwiseXORExpression> { "|" <BitwiseXORExpression> }
     *
     * @return
     */
    private Node parseBitwiseORExpression() {
        Node left = BitwiseXORExpression();
        while (check(GSTokenType.BIT_OR)) {
            Operator operator = new Operator(advance());
            Node right = BitwiseXORExpression();
            left = new BitwiseORExpression(left, operator, right);
        }
        return left;
    }

    /**
     * 解析按位异或表达式
     * <BitwiseXORExpression>     ::= <BitwiseANDExpression> { "^" <BitwiseANDExpression> }
     *
     * @return
     */
    private Node BitwiseXORExpression() {
        Node left = parseBitwiseANDExpression();
        while (check(GSTokenType.BIT_XOR)) {
            Operator operator = new Operator(advance());
            Node right = parseBitwiseANDExpression();
            left = new BitwiseXORExpression(left, operator, right);
        }
        return left;
    }

    /**
     * 解析按位与表达式
     * <BitwiseANDExpression>     ::= <EqualityExpression> { "&" <EqualityExpression> }
     *
     * @return
     */
    private Node parseBitwiseANDExpression() {
        Node left = parseEqualityExpression();
        while (check(GSTokenType.BIT_AND)) {
            Operator operator = new Operator(advance());
            Node right = parseEqualityExpression();
            left = new BitwiseANDExpression(left, operator, right);
        }
        return left;
    }

    /**
     * 解析比较表达式
     * <EqualityExpression>       ::= <RelationalExpression> { ("==" | "!=" | "===" | "!==") <RelationalExpression> }
     *
     * @return
     */
    private Node parseEqualityExpression() {
        Node left = parseRelationalExpression();
        while (isEqualityOperator(peek())) {
            Operator operator = new Operator(advance());
            Node right = parseRelationalExpression();
            left = new EqualityExpression(left, operator, right);
        }
        return left;
    }

    /**
     * 解析关系表达式
     * <RelationalExpression>     ::= <ShiftExpression> { ("<" | ">" | "<=" | ">=") <ShiftExpression> }
     *
     * @return
     */
    private Node parseRelationalExpression() {
        Node left = parseShiftExpression();
        while (isRelationalOperator(peek())) {
            Operator operator = new Operator(advance());
            Node right = parseShiftExpression();
            left = new RelationalExpression(left, operator, right);
        }
        return left;
    }

    /**
     * 解析移位表达式
     * <ShiftExpression>          ::= <AdditiveExpression> { ("<<" | ">>") <AdditiveExpression> }
     *
     * @return
     */
    private Node parseShiftExpression() {
        Node left = parseAdditiveExpression();
        while (isShiftOperator(peek())) {
            Operator operator = new Operator(advance());
            Node right = parseAdditiveExpression();
            left = new ShiftExpression(left, operator, right);
        }
        return left;
    }

    /**
     * 解析加减法运算
     * <AdditiveExpression>       ::= <MultiplicativeExpression> { ("+" | "-") <MultiplicativeExpression> }
     *
     * @return
     */
    private Node parseAdditiveExpression() {
        Node left = parseMultiplicativeExpression();
        while (isAdditiveOperator(peek())) {
            Operator operator = new Operator(advance());
            Node right = parseMultiplicativeExpression();
            left = new AdditiveExpression(left, operator, right);
        }
        return left;
    }

    /**
     * 解析乘法运算
     * <MultiplicativeExpression> ::= <UnaryExpression> { ("*" | "/" | "%") <UnaryExpression> }
     *
     * @return
     */
    private Node parseMultiplicativeExpression() {
        Node left = parseUnaryExpression();
        while (isMultiplicativeOperator(peek())) {
            Operator operator = new Operator(advance());
            Node right = parseUnaryExpression();
            left = new MultiplicativeExpression(left, operator, right);
        }
        return left;
    }

    /**
     * 解析一元表达式
     * <UnaryExpression>          ::= ("+" | "-" | "!" | "~" ) <UnaryExpression>
     * | ("++" | "--") <LValueExpression>
     * | <PostfixExpression>
     *
     * @return
     */
    private Node parseUnaryExpression() {
        // 如果是一元操作符
        if (isUnaryOperator(peek())) {
            Operator operator = new Operator(advance());
            Node operand = parseUnaryExpression();
            return new UnaryExpression(operator, operand);
        } else if (isPosfixOperator(peek())) {
            Operator operator = new Operator(advance());
            Node operand = parseLValueExpression();
            return new UnaryExpression(operator, operand);
        }
        return parsePostfixExpression();
    }

    /**
     * <LValueExpression>         ::= <PrimaryExpression> { <MemberAccess> }
     *
     * @return
     */
    private Node parseLValueExpression() {
        Node expr = parsePrimaryExpression();
        // 解析PrimaryExpression
        while (true) {
            if (match(GSTokenType.DOT)) {  // 匹配到了对象成员访问
                String name = consume(GSTokenType.IDENTIFIER, "");
                Identifier identifier = new Identifier(name);
                expr = new PropertyAccess(expr, identifier);
            } else if (match(GSTokenType.LBRACKET)) {  // 匹配到了数组访问
                Expression expression = parseExpression();
                consume(GSTokenType.RBRACKET, "");
                expr = new ComputedMemberNode(expr, expression);
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
        // 左值表达式不能以函数调用结尾
        if (expr instanceof FunctionCallNode) {
            error("Invalid left-hand side expression in prefix operation");
        }
        return expr;
    }

    /**
     * 解析后缀表达式
     * <PostfixExpression>        ::= (<PrimaryExpression> [ <PostfixOperator> ] | { "(" [ <ArgumentList> ] ")" })
     * | (<CallExpression> { "(" [ <ArgumentList> ] ")" })
     *
     * @return
     */
    private Node parsePostfixExpression() {
        Node expr = parsePrimaryExpression();
        // 解析PrimaryExpression
        while (true) {
            if (match(GSTokenType.DOT)) {  // 匹配到了对象成员访问
                String name = consume(GSTokenType.IDENTIFIER, "");
                Identifier identifier = new Identifier(name);
                expr = new PropertyAccess(expr, identifier);
            } else if (match(GSTokenType.LBRACKET)) {  // 匹配到了数组访问
                Expression expression = parseExpression();
                consume(GSTokenType.RBRACKET, "");
                expr = new ComputedMemberNode(expr, expression);
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
        if (isPosfixOperator(peek())) {
            if (expr instanceof FunctionCallNode) {
                error(peek(), "Invalid left-hand side expression in postfix operation");
            } else {
                Operator operator = new Operator(advance());
                return new PostfixExpression(expr, operator);
            }
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
        }
        return expr;
    }

    /**
     * 解析主表达式
     * <PrimaryExpression>        ::= <Literal>
     * | <Identifier>
     * | "(" <Expression> ")"
     * | <ArrayLiteral>
     * | <ObjectLiteral>
     * | <FunctionStatement>
     * @return
     */
    private Node parsePrimaryExpression() {
        Node expr = null;
        GSToken token = peek();
        switch (token.type) {
            case NULL:
            case TRUE:
            case STRING:
            case FALSE:
            case FLOAT:
            case INTEGER_HEX:
            case INTEGER_DECIMAL: {
                expr = new Literal(advance());
                break;
            }
            case IDENTIFIER: {
                expr = new Identifier(advance().value);
                break;
            }
            case LPAREN: {   // "(" <Expression> ")"
                advance();
                expr = parseExpression();
                consume(GSTokenType.RPAREN, "Expected ')' after expression");
                break;
            }
            case LBRACKET: {  // <ArrayLiteral>
                advance();
                List<Node> element = new ArrayList<>();
                if (!match(GSTokenType.RBRACKET)) {
                    do {
                        element.add(parseExpression());
                    } while (match(GSTokenType.COMMA));
                    consume(GSTokenType.RBRACKET, "Expected ']' after array element.");
                }
                expr = new ArrayLiteral(element);
                break;
            }
            case LBRACE: {    // <ObjectLiteral>
                advance();
                Hashtable<Node, Node> members = new Hashtable<>();
                if (!match(GSTokenType.RBRACE)) {
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
                expr = new ObjectLiteral(members);
                break;
            }
            case FUNCTION: {
                expr = parseMemberFunctionStatement();
                break;
            }
            default: {
                throw new RuntimeException(String.format("[Line %d:%d] Error:Unexpected token '%s' while parsing primary expression", token.line, token.column, token.value));
            }
        }
        return expr;
    }

    // 是否是关联操作符
    private boolean isAssignmentOperator(GSToken token) {
        for (int i = 0; i < ASSIGN.length; ++i) {
            if (token.type == ASSIGN[i]) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否是比较操作符
     *
     * @param token
     * @return
     */
    private boolean isEqualityOperator(GSToken token) {
        for (int i = 0; i < EQUALITY.length; ++i) {
            if (token.type == EQUALITY[i]) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否是关系操作符
     *
     * @param token
     * @return
     */
    private boolean isRelationalOperator(GSToken token) {
        for (int i = 0; i < RELATIONAL.length; ++i) {
            if (token.type == RELATIONAL[i]) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否为移位操作符
     *
     * @param token
     * @return
     */
    private boolean isShiftOperator(GSToken token) {
        for (int i = 0; i < SHIFT.length; ++i) {
            if (token.type == SHIFT[i]) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否是加减法
     *
     * @param token
     * @return
     */
    private boolean isAdditiveOperator(GSToken token) {
        for (int i = 0; i < ADDITIVE.length; ++i) {
            if (token.type == ADDITIVE[i]) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否是乘除法
     *
     * @param token
     * @return
     */
    private boolean isMultiplicativeOperator(GSToken token) {
        for (int i = 0; i < MULTIPLICATIVE.length; ++i) {
            if (token.type == MULTIPLICATIVE[i]) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否是一元操作符
     *
     * @param token
     * @return
     */
    private boolean isUnaryOperator(GSToken token) {
        for (int i = 0; i < UNARY.length; ++i) {
            if (token.type == UNARY[i]) {
                return true;
            }
        }
        return false;
    }

    /**
     * 是否是自增和自减
     *
     * @param token
     * @return
     */
    private boolean isPosfixOperator(GSToken token) {
        for (int i = 0; i < POSTFIX.length; ++i) {
            if (token.type == POSTFIX[i]) {
                return true;
            }
        }
        return false;
    }

    /**
     * 消费指定类型token
     *
     * @param type
     * @param message
     * @return
     */
    private String consume(GSTokenType type, String message) {
        if (check(type)) {
            return advance().value;
        }
        GSToken token = peek();
        throw new RuntimeException(String.format("[Line %d:%d] Error: %s", token.line, token.column, message));
    }

    private void error(String message) {
        throw new RuntimeException(String.format("Uncaught SyntaxError: %s", message));
    }

    private void error(GSToken token, String message) {
        throw new RuntimeException(String.format("[Line %d:%d] Uncaught SyntaxError: %s", token.line, token.column, message));
    }

    // 当前token类型
    private boolean match(GSTokenType type) {
        if (tokens.get(pos).type == type) {
            advance();
            return true;
        }
        return false;
    }

    // 检查token类型是否匹配
    private boolean check(GSTokenType type) {
        return !isAtEnd() && peek().type == type;
    }

    // 获取当前token并移动指针
    private GSToken advance() {
        if (isAtEnd()) {
            GSToken lastToken = tokens.get(tokens.size() - 1);
            throw new RuntimeException(String.format("[Line %d:%d] SyntaxError: Unexpected end of input after '%s'", lastToken.line, lastToken.column, lastToken.value));
        }
        ++pos;
        return previous();
    }

    // 获取上一个token
    private GSToken previous() {
        return tokens.get(pos - 1);
    }

    // 获取当前token
    private GSToken peek() {
        return tokens.get(pos);
    }

    // token是否结束
    private boolean isAtEnd() {
        return peek().type == GSTokenType.EOF;
    }

}
