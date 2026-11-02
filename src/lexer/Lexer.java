package lexer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Lexer {

    private final String source;
    private final List<Token> tokens = new ArrayList<>();

    private int start = 0;
    private int current = 0;
    private int line = 1;
    // Line the current token started on, so multi-line strings and comments report their first line
    private int startLine = 1;

    private static final Map<String, TokenType> keywords = Map.ofEntries(
        Map.entry("int", TokenType.INT),
        Map.entry("float", TokenType.FLOAT),
        Map.entry("bool", TokenType.BOOL),
        Map.entry("string", TokenType.STRING),
        Map.entry("void", TokenType.VOID),
        Map.entry("cache", TokenType.CACHE),
        Map.entry("class", TokenType.CLASS),
        Map.entry("func", TokenType.FUNC),
        Map.entry("snapshot", TokenType.SNAPSHOT),
        Map.entry("print", TokenType.PRINT),
        Map.entry("read", TokenType.READ),
        Map.entry("for", TokenType.FOR),
        Map.entry("undo", TokenType.UNDO),
        Map.entry("on", TokenType.ON),
        Map.entry("destroy", TokenType.DESTROY),
        Map.entry("create", TokenType.CREATE),
        Map.entry("watch", TokenType.WATCH),
        Map.entry("persist", TokenType.PERSIST),
        Map.entry("swap", TokenType.SWAP),
        Map.entry("limit", TokenType.LIMIT),
        Map.entry("stop", TokenType.STOP),
        Map.entry("main", TokenType.MAIN),
        Map.entry("true", TokenType.TRUE),
        Map.entry("false", TokenType.FALSE),
        Map.entry("skip", TokenType.SKIP),

        // Shorthand forms of keywords that have a noise word
        Map.entry("per", TokenType.PERSIST),
        Map.entry("lim", TokenType.LIMIT),
        Map.entry("snap", TokenType.SNAPSHOT),
        Map.entry("dest", TokenType.DESTROY)
    );

    // Full notation -> shorthand; the rest of the word is the noise word
    private static final Map<String, String> noiseForms = Map.of(
        "persist", "per",
        "limit", "lim",
        "snapshot", "snap",
        "destroy", "dest"
    );

    public Lexer(String source) {
        this.source = source;
    }

    public List<Token> scanTokens() {

        while (!isAtEnd()) {
            start = current;
            startLine = line;
            scanToken();
        }

        tokens.add(new Token(TokenType.EOF, "", line));

        return tokens;
    }

    private void scanToken() {

        char c = advance();

        switch (c) {

            // Single-character tokens
            case '(': addToken(TokenType.LEFT_PAREN); break;
            case ')': addToken(TokenType.RIGHT_PAREN); break;
            case '{': addToken(TokenType.LEFT_BRACE); break;
            case '}': addToken(TokenType.RIGHT_BRACE); break;
            case '[': addToken(TokenType.LEFT_BRACKET); break;
            case ']': addToken(TokenType.RIGHT_BRACKET); break;
            case ';': addToken(TokenType.SEMICOLON); break;
            case ',': addToken(TokenType.COMMA); break;
            case '.': addToken(TokenType.DOT); break;

            case '+':
                if (match('+')) {
                    addToken(TokenType.INCREMENT);
                } else {
                    addToken(TokenType.PLUS);
                }
                break;

            case '-':
                if (match('-')) {
                    addToken(TokenType.DECREMENT);
                } else {
                    addToken(TokenType.MINUS);
                }
                break;

            case '*': addToken(TokenType.MULTIPLY); break;
            case '%': addToken(TokenType.MODULO); break;
            case '^': addToken(TokenType.POWER); break;

            case '/':
                if (match('/')) {
                    while (peek() != '\n' && !isAtEnd()) {
                        advance();
                    }
                    addToken(TokenType.COMMENT);
                } else if (match('*')) {
                    blockComment();
                } else {
                    addToken(TokenType.DIVIDE);
                }
                break;

            case '=':
                addToken(match('=') ? TokenType.EQUAL : TokenType.ASSIGN);
                break;

            case '!':
                addToken(match('=') ? TokenType.NOT_EQUAL : TokenType.NOT);
                break;

            case '<':
                addToken(match('=') ? TokenType.LESS_EQUAL : TokenType.LESS_THAN);
                break;

            case '>':
                addToken(match('=') ? TokenType.GREATER_EQUAL : TokenType.GREATER_THAN);
                break;

            case '&':
                if (match('&')) {
                    addToken(TokenType.AND);
                } else {
                    invalid("'&' must be written as '&&'.");
                }
                break;

            case '|':
                if (match('|')) {
                    addToken(TokenType.OR);
                } else {
                    invalid("'|' must be written as '||'.");
                }
                break;

            case ' ':
            case '\r':
            case '\t':
                break;

            case '\n':
                line++;
                break;

            case '"':
                string();
                break;

            case '\'':
                character();
                break;

            case '_':
                // Identifiers must start with a letter
                consumeWordRest();
                invalid("Identifiers must start with a letter.");
                break;

            default:
                if (isDigit(c)) {
                    number();
                } else if (isAlpha(c)) {
                    identifier();
                } else {
                    invalid("Unrecognized character '" + c + "'.");
                }
                break;
        }
    }

    private void identifier() {

        consumeWordRest();

        String text = source.substring(start, current);

        // Full notation such as "persist" is the keyword "per" followed by the noise word "sist"
        String shorthand = noiseForms.get(text);
        if (shorthand != null) {
            tokens.add(new Token(keywords.get(text), shorthand, startLine));
            tokens.add(new Token(TokenType.NOISE_WORD,
                text.substring(shorthand.length()), startLine));
            return;
        }

        TokenType type = keywords.getOrDefault(
            text, TokenType.IDENTIFIER
        );

        addToken(type);
    }

    private void number() {

        while (isDigit(peek())) {
            advance();
        }

        TokenType type = TokenType.INTEGER;

        if (peek() == '.' && isDigit(peekNext())) {

            advance();

            while (isDigit(peek())) {
                advance();
            }

            type = TokenType.FLOAT_LITERAL;
        }

        // Lexemes like 12abc, 3.14.15 or 5. match no rule
        if (isAlpha(peek()) || peek() == '_' || peek() == '.') {
            while (isAlphaNumeric(peek()) || peek() == '_' || peek() == '.') {
                advance();
            }
            invalid("Malformed number or identifier (identifiers must start with a letter).");
            return;
        }

        addToken(type);
    }

    private void string() {

        while (peek() != '"' && !isAtEnd()) {

            if (peek() == '\n') {
                line++;
            }

            advance();
        }

        if (isAtEnd()) {
            invalid("Unterminated string.");
            return;
        }

        advance();

        addToken(TokenType.STRING_LITERAL);
    }

    // A character constant holds exactly one character: 'a'
    private void character() {

        if (peek() != '\'' && peek() != '\n' && !isAtEnd() && peekNext() == '\'') {
            advance();
            advance();
            addToken(TokenType.CHAR_LITERAL);
            return;
        }

        while (peek() != '\'' && peek() != '\n' && !isAtEnd()) {
            advance();
        }

        if (match('\'')) {
            invalid("A character constant must hold exactly one character.");
        } else {
            invalid("Unterminated character constant.");
        }
    }

    private void blockComment() {

        while (!isAtEnd()) {

            if (peek() == '*' && peekNext() == '/') {
                advance();
                advance();
                addToken(TokenType.COMMENT);
                return;
            }

            if (peek() == '\n') {
                line++;
            }

            advance();
        }

        invalid("Unterminated block comment.");
    }

    private void consumeWordRest() {
        while (isAlphaNumeric(peek()) || peek() == '_') {
            advance();
        }
    }

    private char advance() {
        return source.charAt(current++);
    }

    private boolean match(char expected) {

        if (isAtEnd() || source.charAt(current) != expected) {
            return false;
        }

        current++;
        return true;
    }

    private char peek() {
        return isAtEnd() ? '\0' : source.charAt(current);
    }

    private char peekNext() {

        if (current + 1 >= source.length()) {
            return '\0';
        }

        return source.charAt(current + 1);
    }

    private boolean isAtEnd() {
        return current >= source.length();
    }

    private boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    private boolean isAlpha(char c) {
        return (c >= 'a' && c <= 'z') ||
               (c >= 'A' && c <= 'Z');
    }

    private boolean isAlphaNumeric(char c) {
        return isAlpha(c) || isDigit(c);
    }

    private void addToken(TokenType type) {

        String text = source.substring(start, current);

        tokens.add(new Token(type, text, startLine));
    }

    // Record the current lexeme as an invalid token instead of dropping it
    private void invalid(String reason) {
        tokens.add(new Token(TokenType.INVALID,
            source.substring(start, current).stripTrailing(), startLine, reason));
    }
}
