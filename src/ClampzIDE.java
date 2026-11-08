import lexer.Lexer;
import lexer.SymbolTable;
import lexer.TokenType;
import lexer.Token;

import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.plaf.basic.BasicSplitPaneDivider;
import javax.swing.plaf.basic.BasicSplitPaneUI;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.*;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.*;
import java.awt.font.TextAttribute;
import java.awt.geom.Rectangle2D;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Simple desktop editor for running the CLAMPZ lexer on a program
public class ClampzIDE extends JFrame {

    // ---------- Themes ----------

    private record Theme(
        boolean dark,
        Color bg, Color surface, Color surfaceAlt, Color border,
        Color text, Color muted, Color accent, Color onAccent,
        Color ok, Color error, Color errorBg,
        Color selection, Color currentLine, Color hover, Color pressed,
        Color type, Color keyword, Color literal, Color string, Color comment
    ) { }

    // Light is warm paper and ink; dark is the same palette after sundown
    private static final Theme LIGHT = new Theme(false,
        hex(0xE8E4DA), hex(0xFBF9F4), hex(0xF2EFE7), hex(0xD4CEC0),
        hex(0x26231E), hex(0x7C7568), hex(0xB8432A), hex(0xFFF8F0),
        hex(0x3D7A36), hex(0xB3261E), hex(0xF5DCD4),
        hex(0xEEDDB6), hex(0xF4F0E6), hex(0xDDD8CB), hex(0xD1CBBC),
        hex(0x22577A), hex(0x8A2846), hex(0x9A5B13), hex(0x4A7A2C), hex(0x9C9486));

    private static final Theme DARK = new Theme(true,
        hex(0x151412), hex(0x1D1C19), hex(0x191816), hex(0x302E29),
        hex(0xE4DED2), hex(0x8D8678), hex(0xE07A52), hex(0x1A120E),
        hex(0x8DBF6A), hex(0xE8695A), hex(0x3A201B),
        hex(0x3F3829), hex(0x24221F), hex(0x2A2824), hex(0x35322C),
        hex(0x86B6D6), hex(0xD98AA6), hex(0xE0A85C), hex(0xA8C686), hex(0x6F695D));

    private static Color hex(int rgb) {
        return new Color(rgb);
    }

    private static Color mix(Color a, Color b, double t) {
        return new Color(
            (int) (a.getRed() + (b.getRed() - a.getRed()) * t),
            (int) (a.getGreen() + (b.getGreen() - a.getGreen()) * t),
            (int) (a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    private static final Preferences PREFS = Preferences.userRoot().node("clampz");

    private Theme theme = PREFS.getBoolean("darkMode", false) ? DARK : LIGHT;

    // ---------- Fonts and constants ----------

    private static final Font CODE_FONT = new Font(
        firstInstalled("JetBrains Mono", "Cascadia Code", "Consolas", Font.MONOSPACED), Font.PLAIN, 15);
    private static final Font UI_FONT = new Font(
        firstInstalled("Segoe UI", "Inter", Font.DIALOG), Font.PLAIN, 13);
    // Small spaced-out capitals for tabs and column headings
    private static final Font LABEL_FONT = UI_FONT.deriveFont(Font.BOLD, 11f)
        .deriveFont(Map.of(TextAttribute.TRACKING, 0.08f));

    private static String firstInstalled(String... families) {
        List<String> installed = Arrays.asList(
            GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
        for (String family : families) {
            if (installed.contains(family)) {
                return family;
            }
        }
        return families[families.length - 1];
    }

    private static final int MAX_OUTPUT_LINES = 5000;

    private static final String EXAMPLE =
        "// sum 1..5, skipping 3\n\n" +
        "int total = 0;\n\n" +
        "for (int i = 1; i <= 5; i++) {\n" +
        "    on (i == 3) {\n" +
        "        skip;\n" +
        "    }\n" +
        "    total = total + i;\n" +
        "    print(\"i = \" + i);\n" +
        "}\n\n" +
        "print(\"total = \" + total);\n";

    private enum OutKind { NORMAL, ERROR, MUTED }

    private enum StatusKind { MUTED, BUSY, OK, ERROR }

    private record OutLine(String text, OutKind kind) { }

    // ---------- Components ----------

    private final JTextPane editor = new JTextPane() {
        // Disable line wrapping so long lines scroll horizontally
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return getUI().getPreferredSize(this).width <= getParent().getSize().width;
        }

        // Paint the background ourselves so the caret's line can be highlighted
        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(theme.surface());
            g.fillRect(0, 0, getWidth(), getHeight());
            try {
                Rectangle2D r = modelToView2D(getCaretPosition());
                if (r != null) {
                    g.setColor(theme.currentLine());
                    g.fillRect(0, (int) r.getY(), getWidth(), (int) r.getHeight());
                }
            } catch (BadLocationException ignored) {
                // Caret is always inside the document
            }
            super.paintComponent(g);
        }
    };
    private final LineGutter gutter = new LineGutter();
    private final JTextPane output = new JTextPane();
    private final List<OutLine> outputLines = new ArrayList<>();
    private final DefaultTableModel tokenModel =
        new DefaultTableModel(new Object[] {"Line", "Lexeme", "Token", "Category"}, 0) {
            @Override
            public boolean isCellEditable(int row, int column) {
                return false;
            }
        };
    private final JTable tokenTable = new JTable(tokenModel);
    private final List<JScrollPane> scrollPanes = new ArrayList<>();

    private final CardLayout resultCards = new CardLayout();
    private final JPanel resultBody = themedPanel(resultCards, Theme::surface);
    private final FlatButton outputTab = new FlatButton("OUTPUT", null, Kind.TAB);
    private final FlatButton tokensTab = new FlatButton("TOKENS", null, Kind.TAB);

    private final JLabel fileLabel = themedLabel("", Theme::text);
    private final JLabel pathLabel = themedLabel("", Theme::muted);
    private final JLabel modeTag = new JLabel() {
        // Inverted block naming the editor's state, like a vim mode line
        @Override
        public Color getBackground() {
            return theme == null ? super.getBackground() : statusColor(theme);
        }

        @Override
        public Color getForeground() {
            return theme == null ? super.getForeground() : theme.surface();
        }
    };
    private final JLabel status = themedLabel(" ", Theme::text);
    private final JLabel caretLabel = themedLabel("Ln 1, Col 1", Theme::muted);
    private final FlatButton runButton = new FlatButton("Analyze", "F5", Kind.PRIMARY);
    private final FlatButton stopButton = new FlatButton("Stop", null, Kind.MENU);
    private final FlatButton themeButton = new FlatButton("Dark", null, Kind.MENU);
    private final UndoManager undo = new UndoManager();

    private Path currentFile;
    // Tokens from the last analysis, kept for Save Tokens; null when the table is empty
    private List<Token> lastTokens;
    private String lastSourceName;
    private boolean dirty;
    private int errorLine = -1;
    private Object errorHighlight;
    private StatusKind statusKind = StatusKind.MUTED;
    private volatile boolean stopRequested;

    public static void launch() {
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {
                // Fall back to the default look and feel
            }
            new ClampzIDE().setVisible(true);
        });
    }

    public static void main(String[] args) {
        launch();
    }

    private ClampzIDE() {
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setSize(1040, 740);
        setMinimumSize(new Dimension(640, 420));
        setLocationRelativeTo(null);

        JPanel content = themedPanel(new BorderLayout(), Theme::bg);
        setContentPane(content);
        content.add(buildHeader(), BorderLayout.NORTH);
        content.add(buildCenter(), BorderLayout.CENTER);
        content.add(buildStatusBar(), BorderLayout.SOUTH);

        setupEditor();
        setupShortcuts();
        setupFileDrop();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (confirmDiscard()) {
                    dispose();
                    System.exit(0);
                }
            }
        });

        applyTheme();

        Path sample = Path.of("programs", "test.clampz");
        if (Files.exists(sample)) {
            openFile(sample);
        } else {
            setEditorText(EXAMPLE);
            currentFile = null;
            setDirty(false);
        }
        setStatus("Ctrl+Enter or F5 runs the lexer.", StatusKind.MUTED);
    }

    // ---------- Layout ----------

    private JComponent buildHeader() {
        JPanel header = themedPanel(new BorderLayout(), Theme::bg);
        header.setBorder(BorderFactory.createCompoundBorder(
            new SideBorder(false, true), BorderFactory.createEmptyBorder(6, 12, 6, 8)));

        JPanel left = row(Theme::bg);
        JLabel brand = themedLabel("clampz", Theme::accent);
        brand.setFont(CODE_FONT.deriveFont(Font.BOLD, 15f));
        left.add(brand);
        left.add(Box.createHorizontalStrut(10));
        left.add(new VerticalRule());
        left.add(Box.createHorizontalStrut(4));
        left.add(menu("New", "Start a new program (Ctrl+N)", this::newFile));
        left.add(menu("Open", "Open a .clampz or .clz file (Ctrl+O), or drop one onto the window", this::chooseAndOpen));
        left.add(menu("Save", "Save (Ctrl+S)", () -> save(false)));
        left.add(menu("Example", "Load a short example program", () -> {
            if (confirmDiscard()) {
                setEditorText(EXAMPLE);
                currentFile = null;
                setDirty(false);
            }
        }));

        JPanel right = row(Theme::bg);
        themeButton.setToolTipText("Switch theme (Ctrl+Shift+D)");
        themeButton.addActionListener(e -> toggleTheme());
        right.add(themeButton);
        stopButton.setToolTipText("Stop the analysis");
        stopButton.setEnabled(false);
        stopButton.addActionListener(e -> stopRequested = true);
        right.add(stopButton);
        right.add(Box.createHorizontalStrut(8));
        runButton.setToolTipText("Run the lexer (Ctrl+Enter or F5)");
        runButton.addActionListener(e -> run());
        right.add(runButton);

        header.add(left, BorderLayout.WEST);
        header.add(right, BorderLayout.EAST);
        return header;
    }

    private JComponent buildCenter() {
        editor.setFont(CODE_FONT);
        editor.setOpaque(false);
        editor.setMargin(new Insets(6, 12, 6, 12));
        editor.putClientProperty("caretWidth", 2);
        JScrollPane editorScroll = scroll(editor, Theme::surface);
        editorScroll.setRowHeaderView(gutter);

        // Strip above the editor naming the open file and its folder
        JPanel fileStrip = row(Theme::surfaceAlt);
        fileStrip.setBorder(BorderFactory.createCompoundBorder(
            new SideBorder(false, true), BorderFactory.createEmptyBorder(5, 14, 5, 14)));
        fileLabel.setFont(CODE_FONT.deriveFont(Font.BOLD, 12f));
        pathLabel.setFont(CODE_FONT.deriveFont(12f));
        fileStrip.add(fileLabel);
        fileStrip.add(Box.createHorizontalStrut(10));
        fileStrip.add(pathLabel);

        JPanel editorPane = themedPanel(new BorderLayout(), Theme::surface);
        editorPane.add(fileStrip, BorderLayout.NORTH);
        editorPane.add(editorScroll, BorderLayout.CENTER);

        output.setEditable(false);
        output.setFont(CODE_FONT.deriveFont(13f));
        output.setMargin(new Insets(8, 14, 8, 14));

        tokenTable.setFont(CODE_FONT.deriveFont(13f));
        tokenTable.setRowHeight(22);
        tokenTable.setShowGrid(false);
        tokenTable.setIntercellSpacing(new Dimension(0, 0));
        tokenTable.setFillsViewportHeight(true);
        tokenTable.getColumnModel().getColumn(0).setMaxWidth(64);
        tokenTable.getTableHeader().setReorderingAllowed(false);
        tokenTable.getTableHeader().setDefaultRenderer(new HeaderRenderer());
        tokenTable.setDefaultRenderer(Object.class, new CellRenderer());
        tokenTable.addMouseListener(new MouseAdapter() {
            // Double-click a token to jump to its line
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = tokenTable.getSelectedRow();
                if (e.getClickCount() == 2 && row >= 0) {
                    goToLine((Integer) tokenModel.getValueAt(row, 0));
                }
            }
        });

        resultBody.add(scroll(output, Theme::surface), "Output");
        resultBody.add(scroll(tokenTable, Theme::surface), "Tokens");
        outputTab.addActionListener(e -> showResultTab("Output"));
        tokensTab.addActionListener(e -> showResultTab("Tokens"));
        showResultTab("Output");

        JPanel resultHeader = themedPanel(new BorderLayout(), Theme::surfaceAlt);
        resultHeader.setBorder(BorderFactory.createCompoundBorder(
            new SideBorder(true, true), BorderFactory.createEmptyBorder(0, 14, 0, 6)));
        JPanel tabs = row(Theme::surfaceAlt);
        tabs.add(outputTab);
        tabs.add(Box.createHorizontalStrut(18));
        tabs.add(tokensTab);
        resultHeader.add(tabs, BorderLayout.WEST);
        JPanel resultActions = row(Theme::surfaceAlt);
        resultActions.add(menu("Save tokens", "Save the tokens to a .txt file (Ctrl+Shift+T)", this::saveTokens));
        resultActions.add(menu("Clear", "Clear the output panel", this::clearOutput));
        resultHeader.add(resultActions, BorderLayout.EAST);

        JPanel results = themedPanel(new BorderLayout(), Theme::surface);
        results.add(resultHeader, BorderLayout.NORTH);
        results.add(resultBody, BorderLayout.CENTER);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, editorPane, results);
        split.setUI(new BasicSplitPaneUI() {
            @Override
            public BasicSplitPaneDivider createDefaultDivider() {
                BasicSplitPaneDivider divider = new BasicSplitPaneDivider(this) {
                    @Override
                    public void paint(Graphics g) {
                        g.setColor(theme.bg());
                        g.fillRect(0, 0, getWidth(), getHeight());
                        g.setColor(theme.border());
                        g.fillRect(0, 0, getWidth(), 1);
                    }
                };
                divider.setBorder(null);
                return divider;
            }
        });
        split.setDividerSize(6);
        split.setResizeWeight(0.65);
        split.setDividerLocation(440);
        split.setBorder(null);
        split.setContinuousLayout(true);
        return split;
    }

    private JComponent buildStatusBar() {
        JPanel bar = themedPanel(new BorderLayout(10, 0), Theme::bg);
        bar.setBorder(new SideBorder(true, false));
        Font small = CODE_FONT.deriveFont(12f);
        modeTag.setOpaque(true);
        modeTag.setFont(CODE_FONT.deriveFont(Font.BOLD, 11f));
        modeTag.setHorizontalAlignment(SwingConstants.CENTER);
        modeTag.setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 10));
        // Fixed width so the message doesn't shift as the tag changes
        modeTag.setPreferredSize(new Dimension(
            modeTag.getFontMetrics(modeTag.getFont()).stringWidth("ERROR") + 22, 22));
        status.setFont(small);
        caretLabel.setFont(small);
        caretLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 14));
        bar.add(modeTag, BorderLayout.WEST);
        bar.add(status, BorderLayout.CENTER);
        bar.add(caretLabel, BorderLayout.EAST);
        return bar;
    }

    private void updateTokenTab() {
        int count = tokenModel.getRowCount();
        tokensTab.setText(count == 0 ? "TOKENS" : "TOKENS  " + count);
        tokensTab.revalidate();
        tokensTab.repaint();
    }

    private void showResultTab(String name) {
        resultCards.show(resultBody, name);
        outputTab.selected = name.equals("Output");
        tokensTab.selected = name.equals("Tokens");
        outputTab.repaint();
        tokensTab.repaint();
    }

    // ---------- Theme switching ----------

    private void toggleTheme() {
        theme = theme.dark() ? LIGHT : DARK;
        PREFS.putBoolean("darkMode", theme.dark());
        applyTheme();
    }

    // Push colours that Swing components don't read dynamically
    private void applyTheme() {
        themeButton.setText(theme.dark() ? "Light" : "Dark");
        themeButton.revalidate();

        editor.setForeground(theme.text());
        editor.setCaretColor(theme.accent());
        editor.setSelectionColor(theme.selection());
        editor.setSelectedTextColor(null);

        output.setBackground(theme.surface());
        output.setSelectionColor(theme.selection());
        output.setCaretColor(theme.surface());

        tokenTable.setBackground(theme.surface());
        tokenTable.setForeground(theme.text());
        tokenTable.setGridColor(theme.border());
        tokenTable.setSelectionBackground(theme.selection());
        tokenTable.setSelectionForeground(theme.text());
        tokenTable.getTableHeader().setBackground(theme.surfaceAlt());

        for (JScrollPane pane : scrollPanes) {
            pane.getViewport().setBackground(theme.surface());
        }

        highlightSyntax();
        renderOutput();
        if (errorLine != -1) {
            paintErrorLine(errorLine);
        }
        getContentPane().repaint();
    }

    // ---------- Editor behaviour ----------

    private void setupEditor() {
        editor.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { onEdit(); }

            @Override
            public void removeUpdate(DocumentEvent e) { onEdit(); }

            @Override
            public void changedUpdate(DocumentEvent e) { }
        });

        // Record typing for undo, but ignore colour changes from highlighting
        editor.getDocument().addUndoableEditListener(e -> {
            if (!"style change".equals(e.getEdit().getPresentationName())) {
                undo.addEdit(e.getEdit());
            }
        });

        editor.addCaretListener(e -> {
            Element root = editor.getDocument().getDefaultRootElement();
            int line = root.getElementIndex(e.getDot());
            int col = e.getDot() - root.getElement(line).getStartOffset();
            caretLabel.setText("Ln " + (line + 1) + ", Col " + (col + 1));
            editor.repaint();
            gutter.repaint();
        });

        // Tab inserts four spaces; Enter keeps the current indentation
        editor.getInputMap().put(KeyStroke.getKeyStroke("TAB"), "indent");
        editor.getActionMap().put("indent", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                editor.replaceSelection("    ");
            }
        });
        editor.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "smartNewline");
        editor.getActionMap().put("smartNewline", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                editor.replaceSelection("\n" + currentIndent());
            }
        });
    }

    private String currentIndent() {
        try {
            Element root = editor.getDocument().getDefaultRootElement();
            Element line = root.getElement(root.getElementIndex(editor.getCaretPosition()));
            String text = editor.getDocument().getText(
                line.getStartOffset(), editor.getCaretPosition() - line.getStartOffset());
            Matcher m = Pattern.compile("^\\s*").matcher(text);
            String indent = m.find() ? m.group() : "";
            return text.trim().endsWith("{") ? indent + "    " : indent;
        } catch (BadLocationException ex) {
            return "";
        }
    }

    private void onEdit() {
        setDirty(true);
        clearErrorLine();
        SwingUtilities.invokeLater(this::highlightSyntax);
        gutter.revalidate();
        gutter.repaint();
    }

    private void setupShortcuts() {
        JRootPane root = getRootPane();
        int ctrl = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        int shift = InputEvent.SHIFT_DOWN_MASK;
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, ctrl), "run", this::run);
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "run", this::run);
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_S, ctrl), "save", () -> save(false));
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_S, ctrl | shift), "saveAs", () -> save(true));
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_T, ctrl | shift), "saveTokens", this::saveTokens);
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_O, ctrl), "open", this::chooseAndOpen);
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_N, ctrl), "new", this::newFile);
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_D, ctrl | shift), "theme", this::toggleTheme);
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_Z, ctrl), "undo", () -> {
            if (undo.canUndo()) undo.undo();
        });
        bind(root, KeyStroke.getKeyStroke(KeyEvent.VK_Y, ctrl), "redo", () -> {
            if (undo.canRedo()) undo.redo();
        });
    }

    private void bind(JComponent target, KeyStroke key, String name, Runnable action) {
        target.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(key, name);
        // The editor would otherwise swallow Ctrl+Enter as a newline
        editor.getInputMap().put(key, name);
        AbstractAction wrapped = new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        };
        target.getActionMap().put(name, wrapped);
        editor.getActionMap().put(name, wrapped);
    }

    // ---------- Syntax highlighting ----------

    private static final Pattern TYPES = Pattern.compile("\\b(int|float|bool|string|void)\\b");
    private static final Pattern KEYWORDS = Pattern.compile(
        "\\b(print|read|for|on|stop|skip|cache|class|func|snapshot|undo|destroy|" +
        "create|watch|persist|swap|limit|main|per|lim|snap|dest)\\b");
    private static final Pattern LITERALS = Pattern.compile("\\b(true|false|\\d+(\\.\\d+)?)\\b");
    private static final Pattern STRINGS = Pattern.compile("\"[^\"]*\"?");
    private static final Pattern COMMENTS = Pattern.compile("//[^\\n]*|/\\*[\\s\\S]*?(\\*/|$)");

    private static SimpleAttributeSet style(Color color, boolean bold, boolean italic) {
        SimpleAttributeSet set = new SimpleAttributeSet();
        StyleConstants.setForeground(set, color);
        StyleConstants.setBold(set, bold);
        StyleConstants.setItalic(set, italic);
        return set;
    }

    private void highlightSyntax() {
        StyledDocument doc = editor.getStyledDocument();
        String text;
        try {
            text = doc.getText(0, doc.getLength());
        } catch (BadLocationException e) {
            return;
        }
        doc.setCharacterAttributes(0, text.length(), style(theme.text(), false, false), true);
        paint(doc, text, TYPES, style(theme.type(), true, false));
        paint(doc, text, KEYWORDS, style(theme.keyword(), true, false));
        paint(doc, text, LITERALS, style(theme.literal(), false, false));
        paint(doc, text, STRINGS, style(theme.string(), false, false));
        paint(doc, text, COMMENTS, style(theme.comment(), false, true));
    }

    private void paint(StyledDocument doc, String text, Pattern pattern, AttributeSet attrs) {
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            doc.setCharacterAttributes(m.start(), m.end() - m.start(), attrs, true);
        }
    }

    // ---------- Running the lexer ----------

    // Thrown from the output stream when the user presses Stop
    private static class StopRequested extends RuntimeException { }

    private void run() {
        if (!runButton.isEnabled()) {
            return;
        }

        String source = editor.getText();
        clearOutput();
        clearErrorLine();
        clearTokens();
        showResultTab("Tokens");
        runButton.setEnabled(false);
        stopButton.setEnabled(true);
        stopRequested = false;
        setStatus("Analyzing…", StatusKind.BUSY);

        // The symbol table goes next to the program, or the working folder for an unsaved one
        Path file = currentFile;
        Path folder = file != null ? file.toAbsolutePath().getParent() : Path.of("").toAbsolutePath();
        String sourceName = file != null ? file.getFileName().toString() : "(unsaved program)";

        Thread worker = new Thread(() -> execute(source, folder, sourceName), "clampz-run");
        worker.setDaemon(true);
        worker.start();
    }

    private void execute(String source, Path folder, String sourceName) {
        PrintStream originalOut = System.out;
        PrintStream originalErr = System.err;
        int[] lines = {0};
        boolean[] hadError = {false};

        Consumer<String> toOutput = line -> {
            if (++lines[0] > MAX_OUTPUT_LINES) {
                throw new StopRequested();
            }
            SwingUtilities.invokeLater(() -> append(line, OutKind.NORMAL));
        };
        Consumer<String> toError = line -> {
            hadError[0] = true;
            SwingUtilities.invokeLater(() -> {
                append(line, OutKind.ERROR);
                markErrorLine(line);
            });
        };

        long started = System.nanoTime();
        String summary;
        StatusKind summaryKind;

        try {
            System.setOut(new PrintStream(new LineStream(toOutput), true, StandardCharsets.UTF_8));
            System.setErr(new PrintStream(new LineStream(toError), true, StandardCharsets.UTF_8));

            List<Token> tokens = new Lexer(source).scanTokens();
            SwingUtilities.invokeLater(() -> {
                lastTokens = tokens;
                lastSourceName = sourceName;
                for (Token t : tokens) {
                    if (t.type != TokenType.EOF) {
                        tokenModel.addRow(new Object[] {t.line, t.lexeme, t.type, t.type.category});
                    }
                }
                updateTokenTab();
            });

            try {
                Path written = SymbolTable.write(folder, sourceName, tokens);
                System.out.println("Symbol table written to " + written);
            } catch (IOException e) {
                System.err.println("[File Error] Could not write " + SymbolTable.FILE_NAME
                    + ": " + e.getMessage());
            }

            List<Token> invalid = tokens.stream()
                .filter(t -> t.type == TokenType.INVALID)
                .toList();
            if (!invalid.isEmpty()) {
                for (Token t : invalid) {
                    System.err.println("[Lexer Error] Line " + t.line + ": Invalid token '"
                        + t.lexeme + "'. " + t.error);
                }
                throw new RuntimeException("[Lexer Error] Line " + invalid.get(0).line + ": "
                    + invalid.size() + " invalid token(s) found; see the Tokens tab.");
            }

            long ms = (System.nanoTime() - started) / 1_000_000;
            if (hadError[0]) {
                summary = "Finished with errors — see the Output panel.";
                summaryKind = StatusKind.ERROR;
            } else {
                summary = "Finished in " + ms + " ms  ·  " + (tokens.size() - 1) + " tokens";
                summaryKind = StatusKind.OK;
            }
        } catch (StopRequested e) {
            summary = lines[0] > MAX_OUTPUT_LINES
                ? "Stopped: output passed " + MAX_OUTPUT_LINES + " lines (possible infinite loop)."
                : "Stopped by user.";
            summaryKind = StatusKind.ERROR;
        } catch (RuntimeException | StackOverflowError e) {
            // Lexer errors carry a "[... Error] Line N:" prefix
            String message = e instanceof StackOverflowError
                ? "[Internal Error] The lexer ran out of stack space."
                : e.getMessage() != null ? e.getMessage()
                : "[Internal Error] " + e.getClass().getName();
            SwingUtilities.invokeLater(() -> {
                append(message, OutKind.ERROR);
                markErrorLine(message);
            });
            summary = message;
            summaryKind = StatusKind.ERROR;
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }

        String finalSummary = summary;
        StatusKind finalKind = summaryKind;
        SwingUtilities.invokeLater(() -> {
            if (outputLines.isEmpty()) {
                append("(no output)", OutKind.MUTED);
            }
            setStatus(finalSummary, finalKind);
            runButton.setEnabled(true);
            stopButton.setEnabled(false);
        });
    }

    // Splits written bytes into lines and hands each one to a consumer
    private class LineStream extends OutputStream {
        private final Consumer<String> sink;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        LineStream(Consumer<String> sink) {
            this.sink = sink;
        }

        @Override
        public void write(int b) {
            if (stopRequested) {
                throw new StopRequested();
            }
            if (b == '\n') {
                String line = buffer.toString(StandardCharsets.UTF_8);
                buffer.reset();
                sink.accept(line.endsWith("\r") ? line.substring(0, line.length() - 1) : line);
            } else {
                buffer.write(b);
            }
        }
    }

    // ---------- Output panel ----------

    private void append(String text, OutKind kind) {
        OutLine line = new OutLine(text, kind);
        outputLines.add(line);
        insertLine(line);
        output.setCaretPosition(output.getDocument().getLength());
    }

    private void insertLine(OutLine line) {
        SimpleAttributeSet attrs = new SimpleAttributeSet();
        StyleConstants.setForeground(attrs, switch (line.kind()) {
            case NORMAL -> theme.text();
            case ERROR -> theme.error();
            case MUTED -> theme.muted();
        });
        StyleConstants.setItalic(attrs, line.kind() == OutKind.MUTED);
        try {
            Document doc = output.getDocument();
            doc.insertString(doc.getLength(), line.text() + "\n", attrs);
        } catch (BadLocationException ignored) {
            // Appending at the end cannot fail
        }
    }

    // Redraw every output line using the current theme's colours
    private void renderOutput() {
        output.setText("");
        for (OutLine line : outputLines) {
            insertLine(line);
        }
    }

    private void clearOutput() {
        outputLines.clear();
        output.setText("");
    }

    // ---------- Error line marking ----------

    private static final Pattern LINE_NUMBER = Pattern.compile("[Ll]ine (\\d+)");

    private void markErrorLine(String message) {
        Matcher m = LINE_NUMBER.matcher(message);
        if (!m.find() || errorLine != -1) {
            return;
        }
        int line = Integer.parseInt(m.group(1));
        if (paintErrorLine(line)) {
            goToLine(line);
        }
    }

    private boolean paintErrorLine(int line) {
        Element root = editor.getDocument().getDefaultRootElement();
        if (line < 1 || line > root.getElementCount()) {
            return false;
        }
        if (errorHighlight != null) {
            editor.getHighlighter().removeHighlight(errorHighlight);
        }
        Element element = root.getElement(line - 1);
        try {
            errorHighlight = editor.getHighlighter().addHighlight(
                element.getStartOffset(), element.getEndOffset(),
                new DefaultHighlighter.DefaultHighlightPainter(theme.errorBg()));
        } catch (BadLocationException ignored) {
            return false;
        }
        errorLine = line;
        gutter.repaint();
        return true;
    }

    private void clearErrorLine() {
        if (errorHighlight != null) {
            editor.getHighlighter().removeHighlight(errorHighlight);
            errorHighlight = null;
        }
        errorLine = -1;
        gutter.repaint();
    }

    private void goToLine(int line) {
        Element root = editor.getDocument().getDefaultRootElement();
        if (line >= 1 && line <= root.getElementCount()) {
            editor.setCaretPosition(root.getElement(line - 1).getStartOffset());
            editor.requestFocusInWindow();
        }
    }

    // ---------- Files ----------

    private void newFile() {
        if (confirmDiscard()) {
            setEditorText("");
            currentFile = null;
            setDirty(false);
            clearOutput();
            clearTokens();
        }
    }

    private void chooseAndOpen() {
        if (!confirmDiscard()) {
            return;
        }
        JFileChooser chooser = fileChooser();
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            openFile(chooser.getSelectedFile().toPath());
        }
    }

    private void openFile(Path path) {
        try {
            setEditorText(SourceFiles.read(path));
            currentFile = path;
            setDirty(false);
            clearOutput();
            clearTokens();
            setStatus("Opened " + path.getFileName(), StatusKind.MUTED);
        } catch (IOException e) {
            setStatus("Could not open " + path.getFileName(), StatusKind.ERROR);
            showError(e.getMessage());
        }
    }

    // ---------- Drag and drop ----------

    // Accept files dropped anywhere on the window; text drag/paste keeps working in the panes
    private void setupFileDrop() {
        setTransferHandler(fileDropHandler(null));
        editor.setTransferHandler(fileDropHandler(editor.getTransferHandler()));
        output.setTransferHandler(fileDropHandler(output.getTransferHandler()));
        tokenTable.setTransferHandler(fileDropHandler(tokenTable.getTransferHandler()));
    }

    private TransferHandler fileDropHandler(TransferHandler fallback) {
        return new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    if (support.isDrop()) {
                        support.setDropAction(COPY);
                    }
                    return true;
                }
                return fallback != null && fallback.canImport(support);
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                    return fallback != null && fallback.importData(support);
                }
                List<?> files;
                try {
                    files = (List<?>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                } catch (UnsupportedFlavorException e) {
                    setStatus("Drop failed: the dragged item is not a file.", StatusKind.ERROR);
                    return false;
                } catch (IOException e) {
                    setStatus("Drop failed: could not read the dragged file list ("
                        + e.getMessage() + ").", StatusKind.ERROR);
                    return false;
                }
                // Defer dialogs until the drag has finished so the OS drop isn't left hanging
                SwingUtilities.invokeLater(() -> openDropped(files));
                return true;
            }

            @Override
            public int getSourceActions(JComponent c) {
                return fallback != null ? fallback.getSourceActions(c) : NONE;
            }

            @Override
            public void exportAsDrag(JComponent comp, InputEvent e, int action) {
                if (fallback != null) {
                    fallback.exportAsDrag(comp, e, action);
                }
            }

            @Override
            public void exportToClipboard(JComponent comp, Clipboard clip, int action) {
                if (fallback != null) {
                    fallback.exportToClipboard(comp, clip, action);
                }
            }
        };
    }

    private void openDropped(List<?> files) {
        if (files.isEmpty()) {
            setStatus("Drop failed: nothing was dropped.", StatusKind.ERROR);
            return;
        }
        Path path = ((File) files.get(0)).toPath();
        if (Files.isDirectory(path)) {
            showError("'" + path.getFileName() + "' is a folder.\nDrop a single "
                + ".clampz or .clz file to open it.");
            return;
        }
        if (!SourceFiles.hasClampzExtension(path)) {
            setStatus("Could not open " + path.getFileName(), StatusKind.ERROR);
            showError(SourceFiles.INVALID_TYPE_MESSAGE);
            return;
        }
        if (!confirmDiscard()) {
            return;
        }
        openFile(path);
        if (files.size() > 1 && path.equals(currentFile)) {
            setStatus("Opened " + path.getFileName() + " — only one file can be open, so the other "
                + (files.size() - 1) + " dropped file(s) were ignored.", StatusKind.MUTED);
        }
    }

    private boolean save(boolean saveAs) {
        if (currentFile == null || saveAs) {
            JFileChooser chooser = fileChooser();
            if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
                return false;
            }
            Path chosen = chooser.getSelectedFile().toPath();
            if (!SourceFiles.hasClampzExtension(chosen)) {
                chosen = Path.of(chosen + SourceFiles.EXTENSION);
            }
            currentFile = chosen;
        }
        try {
            SourceFiles.write(currentFile, editor.getText());
            setDirty(false);
            setStatus("Saved " + currentFile.getFileName(), StatusKind.MUTED);
            return true;
        } catch (IOException e) {
            setStatus("Could not save " + currentFile.getFileName(), StatusKind.ERROR);
            showError(e.getMessage());
            return false;
        }
    }

    private void clearTokens() {
        tokenModel.setRowCount(0);
        lastTokens = null;
        lastSourceName = null;
        updateTokenTab();
    }

    // Save the token table from the last analysis as a plain-text symbol table
    private void saveTokens() {
        if (lastTokens == null) {
            setStatus("Nothing to save yet — press Analyze first.", StatusKind.ERROR);
            return;
        }
        Path dir = currentFile != null ? currentFile.toAbsolutePath().getParent() : Path.of("");
        String base = currentFile != null
            ? currentFile.getFileName().toString().replaceFirst("(?i)\\.(clampz|clz)$", "") + "_tokens.txt"
            : SymbolTable.FILE_NAME;
        JFileChooser chooser = new JFileChooser(Files.isDirectory(dir) ? dir.toFile() : null);
        chooser.setFileFilter(new FileNameExtensionFilter("Text files (*.txt)", "txt"));
        chooser.setSelectedFile(dir.toAbsolutePath().resolve(base).toFile());
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path chosen = chooser.getSelectedFile().toPath();
        if (!chosen.getFileName().toString().toLowerCase().endsWith(".txt")) {
            chosen = Path.of(chosen + ".txt");
        }
        if (Files.exists(chosen) && JOptionPane.showConfirmDialog(this,
                chosen.getFileName() + " already exists. Replace it?", "Replace file",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.YES_OPTION) {
            return;
        }
        try {
            SourceFiles.write(chosen, SymbolTable.format(lastSourceName, lastTokens));
            setStatus("Tokens saved to " + chosen.toAbsolutePath(), StatusKind.OK);
        } catch (IOException e) {
            setStatus("Could not save tokens to " + chosen.getFileName(), StatusKind.ERROR);
            showError(e.getMessage());
        }
    }

    private JFileChooser fileChooser() {
        Path dir = currentFile != null ? currentFile.toAbsolutePath().getParent() : Path.of("programs");
        JFileChooser chooser = new JFileChooser(Files.isDirectory(dir) ? dir.toFile() : null);
        // The filter can be bypassed with "All Files", so SourceFiles.read() still checks the extension
        chooser.setFileFilter(new FileNameExtensionFilter("CLAMPZ files", "clampz", "clz"));
        return chooser;
    }

    // Ask before throwing away unsaved changes
    private boolean confirmDiscard() {
        if (!dirty) {
            return true;
        }
        int choice = JOptionPane.showConfirmDialog(this,
            "Save changes to " + displayName() + "?", "Unsaved changes",
            JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (choice == JOptionPane.YES_OPTION) {
            return save(false);
        }
        return choice == JOptionPane.NO_OPTION;
    }

    // ---------- Helpers ----------

    private void setEditorText(String text) {
        editor.setText(text.replace("\r\n", "\n"));
        editor.setCaretPosition(0);
        undo.discardAllEdits();
        highlightSyntax();
    }

    private void setDirty(boolean value) {
        dirty = value;
        fileLabel.setText(displayName() + (dirty ? " *" : ""));
        Path folder = currentFile != null ? currentFile.toAbsolutePath().getParent() : null;
        Path cwd = Path.of("").toAbsolutePath();
        // Show folders inside the working directory relative to it, so the strip stays short
        pathLabel.setText(folder == null ? "not saved"
            : folder.startsWith(cwd) ? "./" + cwd.relativize(folder).toString().replace('\\', '/')
            : folder.toString());
        setTitle(displayName() + (dirty ? "*" : "") + " - CLAMPZ");
    }

    private String displayName() {
        return currentFile != null ? currentFile.getFileName().toString() : "untitled.clampz";
    }

    private void setStatus(String text, StatusKind kind) {
        statusKind = kind;
        modeTag.setText(switch (kind) {
            case MUTED -> "READY";
            case BUSY -> "LEXING";
            case OK -> "DONE";
            case ERROR -> "ERROR";
        });
        status.setText(text);
        modeTag.repaint();
        status.repaint();
    }

    private Color statusColor(Theme t) {
        if (statusKind == null) {
            return t.muted();
        }
        return switch (statusKind) {
            case MUTED -> t.muted();
            case BUSY -> t.accent();
            case OK -> t.ok();
            case ERROR -> t.error();
        };
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, "CLAMPZ", JOptionPane.ERROR_MESSAGE);
    }

    private static Graphics2D antialiased(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
            RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
        return g2;
    }

    // ---------- Themed building blocks ----------

    // A panel whose background follows the current theme
    private JPanel themedPanel(LayoutManager layout, Function<Theme, Color> background) {
        return new JPanel(layout) {
            @Override
            public Color getBackground() {
                return theme == null ? super.getBackground() : background.apply(theme);
            }
        };
    }

    // A horizontal row of components with a themed background
    private JPanel row(Function<Theme, Color> background) {
        JPanel panel = themedPanel(null, background);
        panel.setLayout(new BoxLayout(panel, BoxLayout.X_AXIS));
        return panel;
    }

    // A label whose text colour follows the current theme
    private JLabel themedLabel(String text, Function<Theme, Color> foreground) {
        return new JLabel(text) {
            @Override
            public Color getForeground() {
                return theme == null ? super.getForeground() : foreground.apply(theme);
            }
        };
    }

    private FlatButton menu(String text, String tip, Runnable action) {
        FlatButton button = new FlatButton(text, null, Kind.MENU);
        button.setToolTipText(tip);
        button.addActionListener(e -> action.run());
        return button;
    }

    private JScrollPane scroll(JComponent view, Function<Theme, Color> background) {
        JScrollPane pane = new JScrollPane(view);
        pane.setBorder(BorderFactory.createEmptyBorder());
        pane.getVerticalScrollBar().setUI(new ThinScrollBarUI());
        pane.getHorizontalScrollBar().setUI(new ThinScrollBarUI());
        pane.getVerticalScrollBar().setPreferredSize(new Dimension(12, 12));
        pane.getHorizontalScrollBar().setPreferredSize(new Dimension(12, 12));
        pane.getVerticalScrollBar().setUnitIncrement(16);
        pane.setCorner(ScrollPaneConstants.LOWER_RIGHT_CORNER, themedPanel(null, background));
        pane.setCorner(ScrollPaneConstants.LOWER_LEFT_CORNER, themedPanel(null, background));
        pane.setCorner(ScrollPaneConstants.UPPER_RIGHT_CORNER, themedPanel(null, Theme::surfaceAlt));
        scrollPanes.add(pane);
        return pane;
    }

    // A 1px themed line on the top and/or bottom edge
    private class SideBorder extends AbstractBorder {
        private final boolean top;
        private final boolean bottom;

        SideBorder(boolean top, boolean bottom) {
            this.top = top;
            this.bottom = bottom;
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            g.setColor(theme.border());
            if (top) g.fillRect(x, y, width, 1);
            if (bottom) g.fillRect(x, y + height - 1, width, 1);
        }

        @Override
        public Insets getBorderInsets(Component c) {
            return new Insets(top ? 1 : 0, 0, bottom ? 1 : 0, 0);
        }
    }

    private enum Kind { MENU, PRIMARY, TAB }

    // Flat square-cornered button painted by hand so it matches both themes
    private class FlatButton extends JButton {
        private static final int HINT_GAP = 12;

        private final Kind kind;
        // Faded keyboard shortcut drawn after the label, or null
        private final String hint;
        boolean selected;

        FlatButton(String text, String hint, Kind kind) {
            super(text);
            this.kind = kind;
            this.hint = hint;
            setFont(switch (kind) {
                case PRIMARY -> UI_FONT.deriveFont(Font.BOLD, 13f);
                case TAB -> LABEL_FONT;
                case MENU -> UI_FONT;
            });
            setBorder(switch (kind) {
                case PRIMARY -> BorderFactory.createEmptyBorder(6, 14, 6, 12);
                case TAB -> BorderFactory.createEmptyBorder(9, 0, 9, 0);
                case MENU -> BorderFactory.createEmptyBorder(5, 9, 5, 9);
            });
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setFocusable(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        private Font hintFont() {
            return CODE_FONT.deriveFont(11f);
        }

        private int contentWidth(FontMetrics fm) {
            int width = fm.stringWidth(getText());
            if (hint != null) {
                width += HINT_GAP + getFontMetrics(hintFont()).stringWidth(hint);
            }
            return width;
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            Insets in = getInsets();
            return new Dimension(contentWidth(fm) + in.left + in.right,
                fm.getHeight() + in.top + in.bottom);
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = antialiased(g);
            ButtonModel model = getModel();
            boolean hover = model.isRollover() && isEnabled();
            boolean pressed = model.isPressed() && isEnabled();

            Color fill = null;
            Color fg;
            switch (kind) {
                case PRIMARY -> {
                    fill = pressed ? mix(theme.accent(), Color.BLACK, 0.18)
                        : hover ? mix(theme.accent(), Color.BLACK, 0.08) : theme.accent();
                    fg = theme.onAccent();
                }
                case TAB -> fg = selected || hover ? theme.text() : theme.muted();
                default -> {
                    fill = pressed ? theme.pressed() : hover ? theme.hover() : null;
                    fg = theme.text();
                }
            }

            if (!isEnabled()) {
                g2.setComposite(AlphaComposite.SrcOver.derive(0.35f));
            }
            if (fill != null) {
                g2.setColor(fill);
                g2.fillRect(0, 0, getWidth(), getHeight());
            }
            if (kind == Kind.TAB && selected) {
                g2.setColor(theme.accent());
                g2.fillRect(0, getHeight() - 2, getWidth(), 2);
            }

            FontMetrics fm = g2.getFontMetrics(getFont());
            int x = kind == Kind.PRIMARY ? getInsets().left : (getWidth() - contentWidth(fm)) / 2;
            int baseline = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
            g2.setColor(fg);
            g2.setFont(getFont());
            g2.drawString(getText(), x, baseline);
            if (hint != null) {
                g2.setFont(hintFont());
                g2.setColor(new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 150));
                g2.drawString(hint, x + fm.stringWidth(getText()) + HINT_GAP, baseline);
            }
            g2.dispose();
        }
    }

    // Thin upright line separating groups in the header
    private class VerticalRule extends JComponent {
        @Override
        public Dimension getPreferredSize() {
            return new Dimension(1, 18);
        }

        @Override
        public Dimension getMaximumSize() {
            return getPreferredSize();
        }

        @Override
        protected void paintComponent(Graphics g) {
            g.setColor(theme.border());
            g.fillRect(0, 0, 1, getHeight());
        }
    }

    // ---------- Scrollbars and table ----------

    // Slim scrollbar without arrow buttons
    private class ThinScrollBarUI extends BasicScrollBarUI {
        @Override
        protected JButton createDecreaseButton(int orientation) {
            return zeroButton();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return zeroButton();
        }

        private JButton zeroButton() {
            JButton button = new JButton();
            button.setPreferredSize(new Dimension(0, 0));
            return button;
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
            g.setColor(theme.surface());
            g.fillRect(r.x, r.y, r.width, r.height);
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            if (r.isEmpty()) {
                return;
            }
            Graphics2D g2 = antialiased(g);
            int alpha = isThumbRollover() || isDragging ? 90 : 50;
            g2.setColor(theme.dark() ? new Color(255, 255, 255, alpha) : new Color(0, 0, 0, alpha));
            g2.fillRect(r.x + 4, r.y + 2, r.width - 8, r.height - 4);
            g2.dispose();
        }
    }

    private class HeaderRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean selected, boolean focused, int row, int col) {
            super.getTableCellRendererComponent(
                table, value == null ? "" : value.toString().toUpperCase(), false, false, row, col);
            setFont(LABEL_FONT);
            setBackground(theme.surface());
            setForeground(theme.muted());
            setHorizontalAlignment(col == 0 ? RIGHT : LEFT);
            setBorder(BorderFactory.createCompoundBorder(
                new SideBorder(false, true), BorderFactory.createEmptyBorder(7, 12, 7, 12)));
            return this;
        }
    }

    private class CellRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
                JTable table, Object value, boolean selected, boolean focused, int row, int col) {
            super.getTableCellRendererComponent(table, value, selected, false, row, col);
            setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
            // Faint banding every other row keeps long tables readable without grid lines
            setBackground(selected ? theme.selection()
                : row % 2 == 1 ? theme.surfaceAlt() : theme.surface());
            setHorizontalAlignment(col == 0 ? RIGHT : LEFT);
            boolean invalid = tokenModel.getValueAt(row, 2) == TokenType.INVALID;
            setForeground(invalid && col > 0 ? theme.error() : switch (col) {
                case 0, 3 -> theme.muted();
                case 2 -> theme.keyword();
                default -> theme.text();
            });
            return this;
        }
    }

    // Draws line numbers beside the editor, marking the current and error lines
    private class LineGutter extends JComponent {

        LineGutter() {
            setFont(CODE_FONT.deriveFont(12f));
        }

        @Override
        public Dimension getPreferredSize() {
            int lines = editor.getDocument().getDefaultRootElement().getElementCount();
            int digits = Math.max(3, String.valueOf(lines).length());
            int width = getFontMetrics(getFont()).charWidth('0') * digits + 22;
            return new Dimension(width, editor.getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = antialiased(g);
            Rectangle clip = g.getClipBounds();
            g2.setColor(theme.surfaceAlt());
            g2.fillRect(clip.x, clip.y, clip.width, clip.height);
            g2.setColor(theme.border());
            g2.fillRect(getWidth() - 1, clip.y, 1, clip.height);

            Element root = editor.getDocument().getDefaultRootElement();
            int first = root.getElementIndex(editor.viewToModel2D(new Point(0, clip.y)));
            int last = root.getElementIndex(editor.viewToModel2D(new Point(0, clip.y + clip.height)));
            int caretLine = root.getElementIndex(editor.getCaretPosition()) + 1;

            FontMetrics fm = g2.getFontMetrics(getFont());
            int editorAscent = editor.getFontMetrics(CODE_FONT).getAscent();

            for (int i = first; i <= last; i++) {
                try {
                    Rectangle2D r = editor.modelToView2D(root.getElement(i).getStartOffset());
                    int line = i + 1;
                    String number = String.valueOf(line);
                    if (line == errorLine) {
                        g2.setColor(theme.error());
                        g2.fillRect(0, (int) r.getY(), 3, (int) r.getHeight());
                    }
                    g2.setColor(line == errorLine ? theme.error()
                        : line == caretLine ? theme.text() : theme.comment());
                    g2.setFont(line == errorLine || line == caretLine
                        ? getFont().deriveFont(Font.BOLD) : getFont());
                    int x = getWidth() - fm.stringWidth(number) - 10;
                    g2.drawString(number, x, (int) r.getY() + editorAscent);
                } catch (BadLocationException ignored) {
                    // Line vanished while painting
                }
            }
            g2.dispose();
        }
    }
}
