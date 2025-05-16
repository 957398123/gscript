package gscript.token;

public class GSToken {

    public GSTokenType type;

    public String value;

    public int line;

    public int column;

    public GSToken(GSTokenType type, int line, int column) {
        this.type = type;
        this.value = type.getValue();
        this.line = line;
        this.column = column - value.length();
    }

    public GSToken(GSTokenType type, String value, int line, int column) {
        this.type = type;
        this.value = value;
        this.line = line;
        this.column = column - value.length();
    }
}
