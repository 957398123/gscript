package gscript.token;

public enum GSTokenType {

    PLUS("+"),

    MINUS("-"),

    MUL("*"),

    DIV("/"),

    MODULO("%"),

    EQ("="),

    NOT("!"),

    GT(">"),

    LT("<"),

    BIT_AND("&"),

    BIT_OR("|"),

    BIT_XOR("^"),

    BIT_NOT("~"),

    COMMA(","),

    LBRACKET("["),

    RBRACKET("]"),

    LPAREN("("),

    RPAREN(")"),

    LBRACE("{"),

    RBRACE("}"),

    SEMICOLON(";"),

    QUESTION("?"),

    COLON(":"),

    DOT("."),

    INCREMENT("++"),

    PLUS_EQUAL("+="),

    DECREMENT("--"),

    MINUS_EQUAL("-="),

    STAR_EQUAL("*="),

    SLASH_EQUAL("/="),

    PERCENT_EQUAL("%="),

    T_EQ("=="),

    T_NEQ("!="),

    T_RSHIFT(">>"),

    T_GE(">="),

    T_LSHIFT("<<"),

    T_LE("<="),

    T_AND("&&"),

    T_AND_ASSIGN("&="),

    T_OR("||"),

    T_OR_ASSIGN("|="),

    T_XOR_ASSIGN("^="),

    FUNCTION("function"),

    DO("do"),

    VAR("var"),

    IF("if"),

    ELSE("else"),

    BREAK("break"),

    CONTINUE("continue"),

    FOR("for"),

    WHILE("while"),

    TRUE("true"),

    FALSE("false"),

    NULL("null"),

    SWITCH("switch"),

    CASE("case"),

    RETURN("return"),

    // 标识符 变量名或者函数名
    IDENTIFIER(""),

    INTEGER_DECIMAL(""),

    INTEGER_HEX(""),

    FLOAT(""),

    STRING(""),

    EOF("eof");

    private final String value;

    // 枚举构造函数
    GSTokenType(String value) {
        this.value = value;
    }

    // 获取枚举值的字符串表示
    public String getValue() {
        return value;
    }
}