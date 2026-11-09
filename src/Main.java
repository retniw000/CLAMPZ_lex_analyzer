import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import lexer.Lexer;
import lexer.SymbolTable;
import lexer.Token;
import lexer.TokenType;

public class Main {

    private static final int EXIT_USAGE = 64;
    private static final int EXIT_DATA_ERROR = 65;
    private static final int EXIT_FILE_ERROR = 66;
    private static final int EXIT_PROGRAM_ERROR = 70;

    public static void main(String[] args) {

        if (args.length == 0) {
            ClampzIDE.launch();
            return;
        }

        if (args.length > 2) {
            System.err.println("Usage: java Main [program.clampz|program.clz [tokens.txt]]");
            System.exit(EXIT_USAGE);
        }

        Path path;
        String source;
        try {
            path = SourceFiles.toPath(args[0]);
            source = SourceFiles.read(path);
        } catch (IOException e) {
            System.err.println("[File Error] " + e.getMessage());
            System.exit(EXIT_FILE_ERROR);
            return;
        }

        try {
            Lexer lexer = new Lexer(source);
            List<Token> tokens = lexer.scanTokens();

            String table = SymbolTable.format(path.getFileName().toString(), tokens);
            System.out.println("=== CLAMPZ LEXER ===");
            System.out.print(table);
            System.out.println("====================");

            try {
                String name = path.getFileName().toString();
                Path written = args.length == 2
                    ? SymbolTable.writeTo(SourceFiles.toPath(args[1]), name, tokens)
                    : SymbolTable.write(path.toAbsolutePath().getParent(), name, tokens);
                System.out.println("Symbol table written to " + written);
            } catch (IOException e) {
                System.err.println("[File Error] Could not write "
                    + (args.length == 2 ? args[1] : SymbolTable.FILE_NAME)
                    + ": " + e.getMessage());
            }

            List<Token> invalid = tokens.stream()
                .filter(token -> token.type == TokenType.INVALID)
                .toList();
            if (!invalid.isEmpty()) {
                for (Token token : invalid) {
                    System.err.println("[Lexer Error] Line " + token.line + ": Invalid token '"
                        + token.lexeme + "'. " + token.error);
                }
                System.exit(EXIT_DATA_ERROR);
            }

            System.out.println("\nLexical analysis successful!");
            System.out.println("Tokens: " + tokens.size());

        } catch (RuntimeException e) {
            // Lexer errors already include the "[Lexer Error] Line N:" bit
            System.err.println(e.getMessage() != null ? e.getMessage()
                : "[Internal Error] " + e.getClass().getName());
            System.exit(EXIT_PROGRAM_ERROR);
        }
    }
}
