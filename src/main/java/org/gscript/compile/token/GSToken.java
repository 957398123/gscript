package org.gscript.compile.token;

public class GSToken {

    /**
     * token类型
     */
    public GSTokenType type;

    /**
     * token的值
     * 符号类token是不需要求值的，标识符、数值、字符串类型的token是需要求值的。
     */
    public String value;

    /**
     * token所在源代码文本行
     */
    public int line;

    /**
     * token所在源代码文本列
     */
    public int column;

    /**
     * 创建一个没有值的token
     * @param type
     * @param line
     * @param column
     */
    public GSToken(GSTokenType type, int line, int column) {
        this.type = type;
        this.value = type.getValue();
        this.line = line;
        this.column = column - value.length();
    }

    /**
     * 创建一个有值的token
     * @param type
     * @param value
     * @param line
     * @param column
     */
    public GSToken(GSTokenType type, String value, int line, int column) {
        this.type = type;
        this.value = value;
        this.line = line;
        this.column = column - value.length();
    }
}
