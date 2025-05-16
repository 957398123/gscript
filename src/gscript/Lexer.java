package gscript;

import gscript.token.GSToken;
import gscript.token.GSTokenType;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class Lexer {
    private String src;
    private int pos;
    private int line;
    private int column;

    // 关键字列表
    private static final Set<String> keywords = Set.of("function", "do", "var", "if", "else", "break", "for", "while", "true", "false", "null", "switch", "case", "return", "continue");

    public Lexer() {
        this.pos = 0;
        this.line = 1;
        this.column = 1;
    }

    private boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private boolean isSymbol(char c) {
        return "+-*/%=!><&|^~,[](){}?.:\";".indexOf(c) >= 0;
    }

    // 是否是字母
    private boolean isLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    // 是否是标识符开头 _或$或者字母开头
    private boolean isIdentifierStart(char c) {
        return (c == '_') || c == '$' || isLetter(c);
    }

    // 是否是标识符后续组成部分 _或$或数字_或字母
    private boolean isIdentifierPart(char c) {
        return isDigit(c) || isIdentifierStart(c);
    }

    // 是否是空白字符
    private boolean isWhiteSpace(char c) {
        return " \r\n\t\f".indexOf(c) >= 0;
    }

    // 预看字符 \0表示已到文尾
    private char peek() {
        return pos < src.length() ? src.charAt(pos) : '\0';
    }

    // 前进
    private char advance() {
        char c = src.charAt(pos++);
        if (c == '\n') {
            ++line;
            column = 1;
        } else {
            ++column;
        }
        return c;
    }

    // 将源代码解析为Token
    public List<GSToken> tokenize(String src) {
        this.src = src;
        List<GSToken> tokens = new ArrayList<>();
        while (pos < src.length()) {
            char c = peek();
            if (isWhiteSpace(c)) {  // 忽略空格
                advance();
            } else if (isDigit(c)) {  // 如果是数字
                StringBuilder number = new StringBuilder();
                do {
                    advance();
                    number.append(c);
                } while (isDigit(c = peek()));
                if (peek() == '.') {
                    do {
                        advance();
                        number.append(c);
                    } while (isDigit(c = peek()));
                    // 添加token
                    tokens.add(new GSToken(GSTokenType.FLOAT, number.toString(), line, column));
                } else if ((number.length() == 1 && number.charAt(0) == '0') && (peek() == 'x' | peek() == 'X')) {
                    do {
                        advance();
                        number.append(c);
                    } while (isDigit(c = peek()));
                    // 添加token
                    tokens.add(new GSToken(GSTokenType.INTEGER_HEX, number.toString(), line, column));
                } else {
                    // 添加token
                    tokens.add(new GSToken(GSTokenType.INTEGER_DECIMAL, number.toString(), line, column));
                }
            } else if (isSymbol(c)) {  // 如果是符号
                advance();
                switch (c) {
                    case '+': {
                        // 这里需要预看下一个符号，看看是不是复合符号
                        switch (peek()) {
                            case '+': { // 自增
                                advance();
                                tokens.add(new GSToken(GSTokenType.INCREMENT, line, column));
                                break;
                            }
                            case '=': { // +=
                                advance();
                                tokens.add(new GSToken(GSTokenType.PLUS_EQUAL, line, column));
                                break;
                            }
                            default: { // +
                                tokens.add(new GSToken(GSTokenType.PLUS, line, column));
                            }
                        }
                        break;
                    }
                    case '-': {
                        // 这里需要预看下一个符号，看看是不是复合符号
                        switch (peek()) {
                            case '-': { // 自减
                                advance();
                                tokens.add(new GSToken(GSTokenType.DECREMENT, line, column));
                                break;
                            }
                            case '=': { // +=
                                advance();
                                tokens.add(new GSToken(GSTokenType.MINUS_EQUAL, line, column));
                                break;
                            }
                            default: {
                                tokens.add(new GSToken(GSTokenType.MINUS, line, column));
                            }
                        }
                        break;
                    }
                    case '*': {
                        if (peek() == '=') {
                            advance();
                            tokens.add(new GSToken(GSTokenType.STAR_EQUAL, line, column));
                        } else {
                            tokens.add(new GSToken(GSTokenType.MUL, line, column));
                        }
                        break;
                    }
                    case '/': {  // 有可能是注释
                        switch (peek()) {
                            case '=': {
                                advance();
                                tokens.add(new GSToken(GSTokenType.SLASH_EQUAL, line, column));
                                break;
                            }
                            case '/': {
                                advance();
                                while (peek() != '\n' && peek() != '\0') {
                                    advance();
                                }
                                break;
                            }
                            default: {
                                tokens.add(new GSToken(GSTokenType.DIV, line, column));
                            }
                        }
                        break;
                    }
                    case '%': {
                        if (peek() == '=') {
                            advance();
                            tokens.add(new GSToken(GSTokenType.PERCENT_EQUAL, line, column));
                        } else {
                            tokens.add(new GSToken(GSTokenType.MODULO, line, column));
                        }
                        break;
                    }
                    case '=': {
                        switch (peek()) {
                            case '=': { // 自增
                                advance();
                                tokens.add(new GSToken(GSTokenType.T_EQ, line, column));
                                break;
                            }
                            default: { // =
                                tokens.add(new GSToken(GSTokenType.EQ, line, column));
                            }
                        }
                        break;
                    }
                    case '!': {
                        switch (peek()) {
                            case '=': { // 自增
                                advance();
                                tokens.add(new GSToken(GSTokenType.T_NEQ, line, column));
                                break;
                            }
                            default: { // =
                                tokens.add(new GSToken(GSTokenType.NOT, line, column));
                            }
                        }
                        break;
                    }
                    case '>': {
                        switch (peek()) {
                            case '>': {
                                advance();
                                tokens.add(new GSToken(GSTokenType.T_RSHIFT, line, column));
                                break;
                            }
                            case '=': {
                                advance();
                                tokens.add(new GSToken(GSTokenType.T_GE, line, column));
                                break;
                            }
                            default: {
                                tokens.add(new GSToken(GSTokenType.GT, line, column));
                            }
                        }
                        break;
                    }
                    case '<': {
                        switch (peek()) {
                            case '<': {
                                advance();
                                tokens.add(new GSToken(GSTokenType.T_LSHIFT, line, column));
                                break;
                            }
                            case '=': {
                                advance();
                                tokens.add(new GSToken(GSTokenType.T_LE, line, column));
                                break;
                            }
                            default: {
                                tokens.add(new GSToken(GSTokenType.LT, line, column));
                            }
                        }
                        break;
                    }
                    case '&': {
                        switch (peek()) {
                            case '&': {
                                advance();
                                tokens.add(new GSToken(GSTokenType.T_AND, line, column));
                                break;
                            }
                            case '=': {
                                advance();
                                tokens.add(new GSToken(GSTokenType.T_AND_ASSIGN, line, column));
                                break;
                            }
                            default: {
                                tokens.add(new GSToken(GSTokenType.BIT_AND, line, column));
                            }
                        }
                        break;
                    }
                    case '|': {
                        switch (peek()) {
                            case '|': {
                                advance();
                                tokens.add(new GSToken(GSTokenType.T_OR, line, column));
                                break;
                            }
                            case '=': {
                                advance();
                                tokens.add(new GSToken(GSTokenType.T_OR_ASSIGN, line, column));
                                break;
                            }
                            default: {
                                tokens.add(new GSToken(GSTokenType.BIT_OR, line, column));
                            }
                        }
                        break;
                    }
                    case '^': {
                        if (peek() == '=') {
                            advance();
                            tokens.add(new GSToken(GSTokenType.T_XOR_ASSIGN, line, column));
                        } else {
                            tokens.add(new GSToken(GSTokenType.BIT_XOR, line, column));
                        }
                        break;
                    }
                    case '~': {
                        tokens.add(new GSToken(GSTokenType.BIT_NOT, line, column));
                        break;
                    }
                    case ',': {
                        tokens.add(new GSToken(GSTokenType.COMMA, line, column));
                        break;
                    }
                    case '[': {
                        tokens.add(new GSToken(GSTokenType.LBRACKET, line, column));
                        break;
                    }
                    case ']': {
                        tokens.add(new GSToken(GSTokenType.RBRACKET, line, column));
                        break;
                    }
                    case '(': {
                        tokens.add(new GSToken(GSTokenType.LPAREN, line, column));
                        break;
                    }
                    case ')': {
                        tokens.add(new GSToken(GSTokenType.RPAREN, line, column));
                        break;
                    }
                    case '{': {
                        tokens.add(new GSToken(GSTokenType.LBRACE, line, column));
                        break;
                    }
                    case '}': {
                        tokens.add(new GSToken(GSTokenType.RBRACE, line, column));
                        break;
                    }
                    case ';': {
                        tokens.add(new GSToken(GSTokenType.SEMICOLON, line, column));
                        break;
                    }
                    case '?': {
                        tokens.add(new GSToken(GSTokenType.QUESTION, line, column));
                        break;
                    }
                    case ':': {
                        tokens.add(new GSToken(GSTokenType.COLON, line, column));
                        break;
                    }
                    case '.': {
                        tokens.add(new GSToken(GSTokenType.DOT, line, column));
                        break;
                    }
                    case '"': {  // 如果是字符串
                        StringBuilder str = new StringBuilder();
                        boolean escaping = false;  // 转义状态标记
                        while (true) {
                            c = advance();
                            if (escaping) {  // 处理转义字符
                                switch (c) {
                                    case 'n': {
                                        str.append('\n');
                                        break;
                                    }
                                    case 'r': {
                                        str.append('\r');
                                        break;
                                    }
                                    case 't': {
                                        str.append('\t');
                                        break;
                                    }
                                    case '\\': {
                                        str.append('\\');
                                        break;
                                    }
                                    case '"': {
                                        str.append('\"');
                                        break;
                                    }
                                    case 'b': {
                                        str.append('\b');
                                        break;
                                    }
                                    case 'f': {
                                        str.append('\f');
                                        break;
                                    }
                                    default: {
                                        throw new RuntimeException("unable to find the corresponding escape symbol, line:" + line + ", column:" + column);
                                    }
                                }
                                escaping = false;
                            } else if (c == '\\') {
                                escaping = true;
                            } else if (c == '"') {
                                tokens.add(new GSToken(GSTokenType.STRING, str.toString(), line, column));
                                break;
                            } else if (c == '\0') {  // 已经到文尾，这里需要报错
                                throw new RuntimeException("failed to parse string: already parsed to the end of the text");
                            } else {
                                str.append(c);
                            }
                        }
                        break;
                    }
                }
            } else if (isIdentifierStart(c)) {  // 如果标识符
                // 找出标识符
                StringBuilder identifier = new StringBuilder();
                do {
                    advance();
                    identifier.append(c);
                } while (isIdentifierPart(c = peek()));
                String value = identifier.toString();
                // 检查是否是关键字
                if (keywords.contains(value)) {
                    GSTokenType token = GSTokenType.IDENTIFIER;
                    switch (value) {
                        case "function": {
                            token = GSTokenType.FUNCTION;
                            break;
                        }
                        case "do": {
                            token = GSTokenType.DO;
                            break;
                        }
                        case "var": {
                            token = GSTokenType.VAR;
                            break;
                        }
                        case "if": {
                            token = GSTokenType.IF;
                            break;
                        }
                        case "else": {
                            token = GSTokenType.ELSE;
                            break;
                        }
                        case "break": {
                            token = GSTokenType.BREAK;
                            break;
                        }
                        case "continue": {
                            token = GSTokenType.CONTINUE;
                            break;
                        }
                        case "for": {
                            token = GSTokenType.FOR;
                            break;
                        }
                        case "while": {
                            token = GSTokenType.WHILE;
                            break;
                        }
                        case "true": {
                            token = GSTokenType.TRUE;
                            break;
                        }
                        case "false": {
                            token = GSTokenType.FALSE;
                            break;
                        }
                        case "null": {
                            token = GSTokenType.NULL;
                            break;
                        }
                        case "switch": {
                            token = GSTokenType.SWITCH;
                            break;
                        }
                        case "case": {
                            token = GSTokenType.CASE;
                            break;
                        }
                        case "return": {
                            token = GSTokenType.RETURN;
                            break;
                        }
                    }
                    tokens.add(new GSToken(token, value, line, column));
                } else {
                    tokens.add(new GSToken(GSTokenType.IDENTIFIER, value, line, column));
                }
            } else if (c == '\0') {  // 解析结束
                break;
            } else {  // 未知的定义
                throw new RuntimeException("failed to parse string: parsing encountered unknown characters, line:" + line + ", column:" + column);
            }
        }
        // 增加结尾
        tokens.add(new GSToken(GSTokenType.EOF, line, 3));
        return tokens;
    }
}
