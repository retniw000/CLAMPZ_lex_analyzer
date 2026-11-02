package lexer;

public enum TokenType {

    // Keywords (data types)
    INT(Category.KEYWORD),
    FLOAT(Category.KEYWORD),
    BOOL(Category.KEYWORD),
    STRING(Category.KEYWORD),

    // Keywords
    VOID(Category.KEYWORD),
    CACHE(Category.KEYWORD),
    CLASS(Category.KEYWORD),
    FUNC(Category.KEYWORD),
    SNAPSHOT(Category.KEYWORD),
    PRINT(Category.KEYWORD),
    READ(Category.KEYWORD),
    FOR(Category.KEYWORD),
    UNDO(Category.KEYWORD),
    ON(Category.KEYWORD),
    DESTROY(Category.KEYWORD),
    CREATE(Category.KEYWORD),
    WATCH(Category.KEYWORD),
    PERSIST(Category.KEYWORD),
    SWAP(Category.KEYWORD),
    LIMIT(Category.KEYWORD),
    STOP(Category.KEYWORD),

    // Reserved words
    MAIN(Category.RESERVED_WORD),
    TRUE(Category.RESERVED_WORD),
    FALSE(Category.RESERVED_WORD),
    SKIP(Category.RESERVED_WORD),

    // Optional suffix of a shorthand keyword: per+sist, lim+it, snap+shot, dest+roy
    NOISE_WORD(Category.NOISE_WORD),

    IDENTIFIER(Category.IDENTIFIER),

    // Constants
    INTEGER(Category.CONSTANT),
    FLOAT_LITERAL(Category.CONSTANT),
    STRING_LITERAL(Category.CONSTANT),
    CHAR_LITERAL(Category.CONSTANT),

    // Arithmetic operators
    PLUS(Category.ARITHMETIC_OPERATOR),
    MINUS(Category.ARITHMETIC_OPERATOR),
    MULTIPLY(Category.ARITHMETIC_OPERATOR),
    DIVIDE(Category.ARITHMETIC_OPERATOR),
    MODULO(Category.ARITHMETIC_OPERATOR),
    POWER(Category.ARITHMETIC_OPERATOR),

    // Unary arithmetic operators
    INCREMENT(Category.ARITHMETIC_OPERATOR),
    DECREMENT(Category.ARITHMETIC_OPERATOR),

    ASSIGN(Category.ASSIGNMENT_OPERATOR),

    // Boolean operators: relational
    EQUAL(Category.RELATIONAL_OPERATOR),
    NOT_EQUAL(Category.RELATIONAL_OPERATOR),
    LESS_THAN(Category.RELATIONAL_OPERATOR),
    GREATER_THAN(Category.RELATIONAL_OPERATOR),
    LESS_EQUAL(Category.RELATIONAL_OPERATOR),
    GREATER_EQUAL(Category.RELATIONAL_OPERATOR),

    // Boolean operators: logical
    NOT(Category.LOGICAL_OPERATOR),
    AND(Category.LOGICAL_OPERATOR),
    OR(Category.LOGICAL_OPERATOR),

    // Delimiters and brackets
    SEMICOLON(Category.DELIMITER),
    COMMA(Category.DELIMITER),
    // Member access: object.field
    DOT(Category.DELIMITER),
    LEFT_PAREN(Category.BRACKET),
    RIGHT_PAREN(Category.BRACKET),
    LEFT_BRACE(Category.BRACKET),
    RIGHT_BRACE(Category.BRACKET),
    LEFT_BRACKET(Category.BRACKET),
    RIGHT_BRACKET(Category.BRACKET),

    COMMENT(Category.COMMENT),

    // A lexeme that matches no rule of the language
    INVALID(Category.INVALID),

    EOF(Category.END);

    public enum Category {
        KEYWORD,
        RESERVED_WORD,
        NOISE_WORD,
        IDENTIFIER,
        CONSTANT,
        ARITHMETIC_OPERATOR,
        ASSIGNMENT_OPERATOR,
        RELATIONAL_OPERATOR,
        LOGICAL_OPERATOR,
        DELIMITER,
        BRACKET,
        COMMENT,
        INVALID,
        END
    }

    public final Category category;

    TokenType(Category category) {
        this.category = category;
    }

    // Comments and noise words are recognized by the lexer but carry no meaning for the parser
    public boolean isIgnoredByParser() {
        return category == Category.COMMENT || category == Category.NOISE_WORD;
    }
}
