package lexer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

// Formats the lexer's tokens as the Symbol Table text file
public final class SymbolTable {

    public static final String FILE_NAME = "SymbolTable.txt";

    // Longest lexeme shown in full; longer ones are cut to 27 characters + "..."
    private static final int MAX_LEXEME = 30;

    // Fixed column widths: No. | Line | Lexeme | Token | Category | Remarks
    private static final String ROW = "%-6s%-7s%-31s%-21s%-22s%s%n";

    private SymbolTable() { }

    public static String format(String sourceName, List<Token> tokens) {
        StringBuilder out = new StringBuilder();
        String rule = "-".repeat(110) + "\n";

        out.append("CLAMPZ SYMBOL TABLE\n");
        out.append("Source file: ").append(sourceName).append("\n");
        out.append(rule);
        out.append(String.format(ROW, "No.", "Line", "Lexeme", "Token", "Category", "Remarks"));
        out.append(rule);

        // Every category starts at 0 so all of them (NOISE_WORD included) appear in the totals
        Map<TokenType.Category, Integer> counts = new EnumMap<>(TokenType.Category.class);
        for (TokenType.Category category : TokenType.Category.values()) {
            if (category != TokenType.Category.END) {
                counts.put(category, 0);
            }
        }

        int number = 0;
        for (Token token : tokens) {
            if (token.type == TokenType.EOF) {
                continue;
            }
            counts.merge(token.type.category, 1, Integer::sum);
            out.append(String.format(ROW,
                ++number, token.line, display(token.lexeme), token.type,
                token.type.category, remarks(token)));
        }

        out.append(rule);
        out.append("Total tokens: ").append(number).append("\n");
        for (Map.Entry<TokenType.Category, Integer> entry : counts.entrySet()) {
            out.append(String.format("  %-21s %d%n", entry.getKey(), entry.getValue()));
        }
        return out.toString();
    }

    public static Path write(Path folder, String sourceName, List<Token> tokens) throws IOException {
        return writeTo(folder.resolve(FILE_NAME), sourceName, tokens);
    }

    // Save the table to a file the user picked
    public static Path writeTo(Path file, String sourceName, List<Token> tokens) throws IOException {
        Files.writeString(file, format(sourceName, tokens), StandardCharsets.UTF_8);
        return file;
    }

    // Keep multi-line comments and strings on one row of the table:
    // line breaks and tabs become spaces, and long lexemes are shortened
    private static String display(String lexeme) {
        String flat = lexeme.replace("\r\n", " ").replace('\r', ' ')
            .replace('\n', ' ').replace('\t', ' ');
        if (flat.length() > MAX_LEXEME) {
            flat = flat.substring(0, MAX_LEXEME - 3) + "...";
        }
        return flat;
    }

    private static String remarks(Token token) {
        if (token.error != null) {
            return token.error;
        }
        return token.remarks != null ? token.remarks : "";
    }
}
