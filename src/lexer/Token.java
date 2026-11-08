package lexer;

public class Token {

    public final TokenType type;
    public final String lexeme;
    public final int line;
    // Why the lexeme was rejected; null unless type is INVALID
    public final String error;
    // Extra note for the symbol table (e.g. "Shorthand of 'persist'"); may be null
    public final String remarks;

    public Token(TokenType type, String lexeme, int line) {
        this(type, lexeme, line, null, null);
    }

    public Token(TokenType type, String lexeme, int line, String error) {
        this(type, lexeme, line, error, null);
    }

    public Token(TokenType type, String lexeme, int line, String error, String remarks) {
        this.type = type;
        this.lexeme = lexeme;
        this.line = line;
        this.error = error;
        this.remarks = remarks;
    }

    @Override
    public String toString() {
        return type + " : " + lexeme + " (Line " + line + ")";
    }
}
