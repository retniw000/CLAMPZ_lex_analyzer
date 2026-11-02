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

    private SymbolTable() { }

    public static String format(String sourceName, List<Token> tokens) {
        StringBuilder out = new StringBuilder();
        String rule = "-".repeat(100) + "\n";

        out.append("CLAMPZ SYMBOL TABLE\n");
        out.append("Source file: ").append(sourceName).append("\n");
        out.append(rule);
        out.append(String.format("%-5s %-6s %-30s %-16s %-21s %s%n",
            "No.", "Line", "Lexeme", "Token", "Category", "Remarks"));
        out.append(rule);

        Map<TokenType.Category, Integer> counts = new EnumMap<>(TokenType.Category.class);
        int number = 0;
        for (Token token : tokens) {
            if (token.type == TokenType.EOF) {
                continue;
            }
            counts.merge(token.type.category, 1, Integer::sum);
            out.append(String.format("%-5d %-6d %-30s %-16s %-21s %s%n",
                ++number, token.line, display(token.lexeme), token.type,
                token.type.category, token.error != null ? token.error : ""));
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

    // Keep multi-line comments and strings on one row of the table
    private static String display(String lexeme) {
        return lexeme.replace("\r", "").replace("\n", "\\n").replace("\t", "\\t");
    }
}
