package org.gscript.compile.token;

/**
 * token类型常量（Java 1.4 兼容：int 常量替代 enum）。
 *
 * <p>原 enum 已转为 int 常量类，switch(token.type) 天然兼容（Java 1.4 switch 支持 int）。
 * getValue(int) 静态方法替代原实例方法 getValue()。
 */
public class GSTokenType {

    public static final int PLUS = 1;
    public static final int MINUS = 2;
    public static final int MUL = 3;
    public static final int DIV = 4;
    public static final int MODULO = 5;
    public static final int EQ = 6;
    public static final int NOT = 7;
    public static final int GT = 8;
    public static final int LT = 9;
    public static final int BIT_AND = 10;
    public static final int BIT_OR = 11;
    public static final int BIT_XOR = 12;
    public static final int BIT_NOT = 13;
    public static final int COMMA = 14;
    public static final int LBRACKET = 15;
    public static final int RBRACKET = 16;
    public static final int LPAREN = 17;
    public static final int RPAREN = 18;
    public static final int LBRACE = 19;
    public static final int RBRACE = 20;
    public static final int SEMICOLON = 21;
    public static final int QUESTION = 22;
    public static final int COLON = 23;
    public static final int DOT = 24;
    public static final int INCREMENT = 25;
    public static final int PLUS_EQUAL = 26;
    public static final int DECREMENT = 27;
    public static final int MINUS_EQUAL = 28;
    public static final int STAR_EQUAL = 29;
    public static final int SLASH_EQUAL = 30;
    public static final int PERCENT_EQUAL = 31;
    public static final int T_EQ = 32;
    public static final int T_NEQ = 33;
    public static final int S_T_EQ = 34;
    public static final int S_T_NEQ = 35;
    public static final int T_RSHIFT = 36;
    public static final int T_GE = 37;
    public static final int T_LSHIFT = 38;
    public static final int T_LE = 39;
    public static final int T_AND = 40;
    public static final int T_AND_ASSIGN = 41;
    public static final int T_OR = 42;
    public static final int T_OR_ASSIGN = 43;
    public static final int T_XOR_ASSIGN = 44;
    public static final int FUNCTION = 45;
    public static final int DO = 46;
    public static final int VAR = 47;
    public static final int IF = 48;
    public static final int ELSE = 49;
    public static final int BREAK = 50;
    public static final int CONTINUE = 51;
    public static final int FOR = 52;
    public static final int WHILE = 53;
    public static final int TRUE = 54;
    public static final int FALSE = 55;
    public static final int NULL = 56;
    public static final int SWITCH = 57;
    public static final int CASE = 58;
    public static final int DEFAULT = 59;
    public static final int RETURN = 60;
    public static final int TRY = 61;
    public static final int CATCH = 62;
    public static final int FINALLY = 63;
    public static final int THROW = 64;
    public static final int NEW = 65;
    public static final int NaN = 66;
    public static final int IDENTIFIER = 67;
    public static final int INTEGER_DECIMAL = 68;
    public static final int INTEGER_HEX = 69;
    public static final int FLOAT = 70;
    public static final int STRING = 71;
    public static final int EOF = 72;
    public static final int TYPEOF = 73;

    /** 常量总数（含 0 号占位）。 */
    private static final int COUNT = 74;

    /** 各类型对应的字符串值（索引 = 常量值，0 号占位为 null）。 */
    private static final String[] VALUES = new String[COUNT];
    static {
        VALUES[PLUS] = "+";
        VALUES[MINUS] = "-";
        VALUES[MUL] = "*";
        VALUES[DIV] = "/";
        VALUES[MODULO] = "%";
        VALUES[EQ] = "=";
        VALUES[NOT] = "!";
        VALUES[GT] = ">";
        VALUES[LT] = "<";
        VALUES[BIT_AND] = "&";
        VALUES[BIT_OR] = "|";
        VALUES[BIT_XOR] = "^";
        VALUES[BIT_NOT] = "~";
        VALUES[COMMA] = ",";
        VALUES[LBRACKET] = "[";
        VALUES[RBRACKET] = "]";
        VALUES[LPAREN] = "(";
        VALUES[RPAREN] = ")";
        VALUES[LBRACE] = "{";
        VALUES[RBRACE] = "}";
        VALUES[SEMICOLON] = ";";
        VALUES[QUESTION] = "?";
        VALUES[COLON] = ":";
        VALUES[DOT] = ".";
        VALUES[INCREMENT] = "++";
        VALUES[PLUS_EQUAL] = "+=";
        VALUES[DECREMENT] = "--";
        VALUES[MINUS_EQUAL] = "-=";
        VALUES[STAR_EQUAL] = "*=";
        VALUES[SLASH_EQUAL] = "/=";
        VALUES[PERCENT_EQUAL] = "%=";
        VALUES[T_EQ] = "==";
        VALUES[T_NEQ] = "!=";
        VALUES[S_T_EQ] = "===";
        VALUES[S_T_NEQ] = "!==";
        VALUES[T_RSHIFT] = ">>";
        VALUES[T_GE] = ">=";
        VALUES[T_LSHIFT] = "<<";
        VALUES[T_LE] = "<=";
        VALUES[T_AND] = "&&";
        VALUES[T_AND_ASSIGN] = "&=";
        VALUES[T_OR] = "||";
        VALUES[T_OR_ASSIGN] = "|=";
        VALUES[T_XOR_ASSIGN] = "^=";
        VALUES[FUNCTION] = "function";
        VALUES[DO] = "do";
        VALUES[VAR] = "var";
        VALUES[IF] = "if";
        VALUES[ELSE] = "else";
        VALUES[BREAK] = "break";
        VALUES[CONTINUE] = "continue";
        VALUES[FOR] = "for";
        VALUES[WHILE] = "while";
        VALUES[TRUE] = "true";
        VALUES[FALSE] = "false";
        VALUES[NULL] = "null";
        VALUES[SWITCH] = "switch";
        VALUES[CASE] = "case";
        VALUES[DEFAULT] = "default";
        VALUES[RETURN] = "return";
        VALUES[TRY] = "try";
        VALUES[CATCH] = "catch";
        VALUES[FINALLY] = "finally";
        VALUES[THROW] = "throw";
        VALUES[NEW] = "new";
        VALUES[NaN] = "NaN";
        VALUES[IDENTIFIER] = "";
        VALUES[INTEGER_DECIMAL] = "";
        VALUES[INTEGER_HEX] = "";
        VALUES[FLOAT] = "";
        VALUES[STRING] = "";
        VALUES[EOF] = "eof";
        VALUES[TYPEOF] = "typeof";
    }

    /** 各类型对应的常量名（调试用，索引 = 常量值）。 */
    private static final String[] NAMES = new String[COUNT];
    static {
        NAMES[PLUS] = "PLUS";
        NAMES[MINUS] = "MINUS";
        NAMES[MUL] = "MUL";
        NAMES[DIV] = "DIV";
        NAMES[MODULO] = "MODULO";
        NAMES[EQ] = "EQ";
        NAMES[NOT] = "NOT";
        NAMES[GT] = "GT";
        NAMES[LT] = "LT";
        NAMES[BIT_AND] = "BIT_AND";
        NAMES[BIT_OR] = "BIT_OR";
        NAMES[BIT_XOR] = "BIT_XOR";
        NAMES[BIT_NOT] = "BIT_NOT";
        NAMES[COMMA] = "COMMA";
        NAMES[LBRACKET] = "LBRACKET";
        NAMES[RBRACKET] = "RBRACKET";
        NAMES[LPAREN] = "LPAREN";
        NAMES[RPAREN] = "RPAREN";
        NAMES[LBRACE] = "LBRACE";
        NAMES[RBRACE] = "RBRACE";
        NAMES[SEMICOLON] = "SEMICOLON";
        NAMES[QUESTION] = "QUESTION";
        NAMES[COLON] = "COLON";
        NAMES[DOT] = "DOT";
        NAMES[INCREMENT] = "INCREMENT";
        NAMES[PLUS_EQUAL] = "PLUS_EQUAL";
        NAMES[DECREMENT] = "DECREMENT";
        NAMES[MINUS_EQUAL] = "MINUS_EQUAL";
        NAMES[STAR_EQUAL] = "STAR_EQUAL";
        NAMES[SLASH_EQUAL] = "SLASH_EQUAL";
        NAMES[PERCENT_EQUAL] = "PERCENT_EQUAL";
        NAMES[T_EQ] = "T_EQ";
        NAMES[T_NEQ] = "T_NEQ";
        NAMES[S_T_EQ] = "S_T_EQ";
        NAMES[S_T_NEQ] = "S_T_NEQ";
        NAMES[T_RSHIFT] = "T_RSHIFT";
        NAMES[T_GE] = "T_GE";
        NAMES[T_LSHIFT] = "T_LSHIFT";
        NAMES[T_LE] = "T_LE";
        NAMES[T_AND] = "T_AND";
        NAMES[T_AND_ASSIGN] = "T_AND_ASSIGN";
        NAMES[T_OR] = "T_OR";
        NAMES[T_OR_ASSIGN] = "T_OR_ASSIGN";
        NAMES[T_XOR_ASSIGN] = "T_XOR_ASSIGN";
        NAMES[FUNCTION] = "FUNCTION";
        NAMES[DO] = "DO";
        NAMES[VAR] = "VAR";
        NAMES[IF] = "IF";
        NAMES[ELSE] = "ELSE";
        NAMES[BREAK] = "BREAK";
        NAMES[CONTINUE] = "CONTINUE";
        NAMES[FOR] = "FOR";
        NAMES[WHILE] = "WHILE";
        NAMES[TRUE] = "TRUE";
        NAMES[FALSE] = "FALSE";
        NAMES[NULL] = "NULL";
        NAMES[SWITCH] = "SWITCH";
        NAMES[CASE] = "CASE";
        NAMES[DEFAULT] = "DEFAULT";
        NAMES[RETURN] = "RETURN";
        NAMES[TRY] = "TRY";
        NAMES[CATCH] = "CATCH";
        NAMES[FINALLY] = "FINALLY";
        NAMES[THROW] = "THROW";
        NAMES[NEW] = "NEW";
        NAMES[NaN] = "NaN";
        NAMES[IDENTIFIER] = "IDENTIFIER";
        NAMES[INTEGER_DECIMAL] = "INTEGER_DECIMAL";
        NAMES[INTEGER_HEX] = "INTEGER_HEX";
        NAMES[FLOAT] = "FLOAT";
        NAMES[STRING] = "STRING";
        NAMES[EOF] = "EOF";
        NAMES[TYPEOF] = "TYPEOF";
    }

    /** 获取类型对应的字符串值（替代原枚举实例方法 getValue()）。 */
    public static String getValue(int type) {
        if (type < 0 || type >= COUNT) {
            return "";
        }
        String v = VALUES[type];
        return (v == null) ? "" : v;
    }

    /** 获取类型对应的常量名（调试用）。 */
    public static String getName(int type) {
        if (type < 0 || type >= COUNT) {
            return "UNKNOWN";
        }
        String n = NAMES[type];
        return (n == null) ? "UNKNOWN" : n;
    }

    private GSTokenType() {
        // 不可实例化
    }
}
