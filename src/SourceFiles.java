import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

// Reading and writing .clampz / .clz files, with error messages that say exactly what went wrong
public final class SourceFiles {

    // Default extension, used when saving a program without one
    public static final String EXTENSION = ".clampz";

    // Every extension the lexical analyzer accepts
    public static final String[] EXTENSIONS = {".clampz", ".clz"};

    public static final String INVALID_TYPE_MESSAGE =
        "Invalid file type. Only .clampz or .clz files are allowed.";

    // Larger files are almost certainly not hand-written programs
    private static final long MAX_SIZE = 5L * 1024 * 1024;

    private SourceFiles() { }

    public static boolean hasClampzExtension(Path path) {
        if (path.getFileName() == null) {
            return false;
        }
        String fileName = path.getFileName().toString().toLowerCase();
        for (String extension : EXTENSIONS) {
            if (fileName.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    public static Path toPath(String text) throws IOException {
        try {
            return Path.of(text);
        } catch (InvalidPathException e) {
            throw new IOException("'" + text + "' is not a valid file path: " + e.getReason() + ".");
        }
    }

    public static String read(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            throw new IOException("'" + path + "' is a folder, not a file.");
        }
        // The lexical analyzer only reads CLAMPZ source files; checked before anything is read
        if (!hasClampzExtension(path)) {
            throw new IOException(INVALID_TYPE_MESSAGE);
        }
        try {
            if (Files.size(path) > MAX_SIZE) {
                throw new IOException("'" + path.getFileName() + "' is larger than "
                    + (MAX_SIZE / (1024 * 1024)) + " MB and is probably not a CLAMPZ program.");
            }
            String text = Files.readString(path, StandardCharsets.UTF_8);
            // Editors like Notepad add a byte-order mark the lexer would reject
            if (!text.isEmpty() && text.charAt(0) == 0xFEFF) {
                text = text.substring(1);
            }
            if (text.indexOf('\0') >= 0) {
                throw new IOException("'" + path.getFileName()
                    + "' looks like a binary file, not a text program.");
            }
            return text;
        } catch (IOException e) {
            throw new IOException(describe("open", path, e), e);
        }
    }

    public static void write(Path path, String text) throws IOException {
        try {
            Files.writeString(path, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IOException(describe("save", path, e), e);
        }
    }

    // Turn a low-level I/O failure into a sentence a user can act on
    private static String describe(String action, Path path, IOException e) {
        String name = path.toAbsolutePath().toString();
        if (e instanceof NoSuchFileException) {
            return action.equals("open")
                ? "File not found: " + name
                : "Cannot save: the folder for " + name + " does not exist.";
        }
        if (e instanceof AccessDeniedException) {
            return "Permission denied: cannot " + action + " " + name
                + (action.equals("save") ? " (the file may be read-only)." : ".");
        }
        if (e instanceof CharacterCodingException) {
            return "Cannot open " + name + ": the file is not valid UTF-8 text.";
        }
        if (e instanceof FileSystemException fse && fse.getReason() != null) {
            // e.g. the file is locked by another program
            return "Cannot " + action + " " + name + ": " + fse.getReason();
        }
        if (e.getMessage() != null && e.getCause() == null && e.getClass() == IOException.class) {
            // One of our own messages from read()
            return e.getMessage();
        }
        return "Cannot " + action + " " + name + ": "
            + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
    }
}
