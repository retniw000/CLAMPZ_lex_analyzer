package lexer;

public class Token {

    public final TokenType type;
    public final String lexeme;
    public final int line;
    // Why the lexeme was rejected; null unless type is INVALID
    public final String error;

    public Token(TokenType type, String lexeme, int line) {
        this(type, lexeme, line, null);
    }

    public Token(TokenType type, String lexeme, int line, String error) {
        this.type = type;
        this.lexeme = lexeme;
        this.line = line;
        this.error = error;
    }

    @Override
    public String toString() {
        return type + " : " + lexeme + " (Line " + line + ")";
    }
}
