package lexer;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        Map.entry("print", TokenType.PRINT),
        Map.entry("read", TokenType.READ),
        Map.entry("for", TokenType.FOR),
        Map.entry("undo", TokenType.UNDO),
        Map.entry("on", TokenType.ON),
        Map.entry("create", TokenType.CREATE),
        Map.entry("watch", TokenType.WATCH),
        Map.entry("swap", TokenType.SWAP),
        Map.entry("stop", TokenType.STOP),

        Map.entry("main", TokenType.MAIN),
        Map.entry("skip", TokenType.SKIP),

        // Reserved words that are counted as constants
        Map.entry("true", TokenType.BOOL_LITERAL),
        Map.entry("false", TokenType.BOOL_LITERAL),

        Map.entry("per", TokenType.PERSIST),
        Map.entry("lim", TokenType.LIMIT),
        Map.entry("snap", TokenType.SNAPSHOT),
        Map.entry("dest", TokenType.DESTROY)
    );

    // Full form -> {shorthand keyword, noise word, token}
    // "persist" is written to the symbol table as "per" (PERSIST) + "sist" (NOISE_WORD)
    private static final Map<String, String[]> fullForms = Map.of(
        "persist",  new String[] {"per",  "sist", "PERSIST"},
        "limit",    new String[] {"lim",  "it",   "LIMIT"},
        "snapshot", new String[] {"snap", "shot", "SNAPSHOT"},
        "destroy",  new String[] {"dest", "roy",  "DESTROY"}
    );

    // Tokens that end an operand; a '-' after one of these is subtraction, not a sign
    private static final Set<TokenType> operandEnds = EnumSet.of(
        TokenType.IDENTIFIER,
        TokenType.INTEGER,
        TokenType.FLOAT_LITERAL,
        TokenType.STRING_LITERAL,
        TokenType.CHAR_LITERAL,
        TokenType.BOOL_LITERAL,
        TokenType.RIGHT_PARENTHESIS,
        TokenType.RIGHT_BRACKET,
        TokenType.INCREMENT,
        TokenType.DECREMENT
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

            case '(': addToken(TokenType.LEFT_PARENTHESIS); break;
            case ')': addToken(TokenType.RIGHT_PARENTHESIS); break;
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
                } else if (isDigit(peek()) && !followsOperand()) {
                    // Integer rule: Digits | - Digits, e.g. x = -7
                    advance();
                    number();
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
                    addToken(TokenType.SINGLE_LINE_COMMENT);
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

        // A noise word written apart from its shorthand: "lim it", "per sist".
        // Only the token right before it counts (comments skipped), so "limit it"
        // does not turn the second "it" into a noise word as well.
        Token previous = lastTokenSkippingComments();
        if (previous != null && previous.type.category == TokenType.Category.KEYWORD) {
            String fullForm = fullFormOf(previous.lexeme);
            if (fullForm != null && text.equals(fullForms.get(fullForm)[1])) {
                addToken(TokenType.NOISE_WORD, "Optional noise word of '" + fullForm + "'");
                return;
            }
        }

        // Maximal munch: the whole word is looked up, so "persistence" stays one IDENTIFIER
        String[] parts = fullForms.get(text);
        if (parts != null) {
            // Full form = shorthand keyword + noise word, both on the same line
            tokens.add(new Token(TokenType.valueOf(parts[2]), parts[0], startLine,
                null, "Shorthand of '" + text + "'"));
            tokens.add(new Token(TokenType.NOISE_WORD, parts[1], startLine,
                null, "Optional noise word of '" + text + "'"));
            return;
        }

        TokenType type = keywords.getOrDefault(
            text, TokenType.IDENTIFIER
        );

        if (type == TokenType.BOOL_LITERAL) {
            addToken(type, "Boolean constant (reserved word)");
        } else {
            addToken(type);
        }
    }

    private static String fullFormOf(String shorthand) {
        for (Map.Entry<String, String[]> entry : fullForms.entrySet()) {
            if (entry.getValue()[0].equals(shorthand)) {
                return entry.getKey();
            }
        }
        return null;
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
                addToken(TokenType.MULTI_LINE_COMMENT,
                    startLine == line ? "Line " + line : "Lines " + startLine + "-" + line);
                return;
            }

            if (peek() == '\n') {
                line++;
            }

            advance();
        }

        invalid("Unterminated block comment.");
    }

    // Last token the parser will see, skipping comments and noise words
    private Token previousToken() {
        for (int i = tokens.size() - 1; i >= 0; i--) {
            if (!tokens.get(i).type.isIgnoredByParser()) {
                return tokens.get(i);
            }
        }
        return null;
    }

    // Last token of any kind except comments (noise words included)
    private Token lastTokenSkippingComments() {
        for (int i = tokens.size() - 1; i >= 0; i--) {
            if (tokens.get(i).type.category != TokenType.Category.COMMENT) {
                return tokens.get(i);
            }
        }
        return null;
    }

    private boolean followsOperand() {
        Token previous = previousToken();
        return previous != null && operandEnds.contains(previous.type);
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
        addToken(type, null);
    }

    private void addToken(TokenType type, String remarks) {

        String text = source.substring(start, current);

        tokens.add(new Token(type, text, startLine, null, remarks));
    }

    private void invalid(String reason) {
        tokens.add(new Token(TokenType.INVALID,
            source.substring(start, current).stripTrailing(), startLine, reason));
    }
}
