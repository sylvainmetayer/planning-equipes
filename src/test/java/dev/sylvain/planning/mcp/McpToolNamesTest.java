package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.quarkiverse.mcp.server.Prompt;
import io.quarkiverse.mcp.server.Tool;

/**
 * An MCP tool name cited outside the Java code — in the ready-to-copy prompt of
 * the MCP page, in its English translation, or in {@code docs/mcp.md} — must
 * name a tool that actually exists.
 *
 * <p>Nothing enforced that, and it had already drifted: the prompt handed to
 * users on the MCP page told them to call {@code expliquer_score_poste}, a tool
 * this application has never exposed (the real one is {@code
 * expliquer_affectation}). The prompt is a localized string, so the same wrong
 * name sat in the English translation too. {@code npm run i18n-check} compares
 * ids and placeholders, never the text, so nothing could catch it.</p>
 *
 * <p>The list of tools is read by <b>reflection</b> rather than by parsing the
 * source, deliberately: writing this test, two successive regexes over the
 * {@code @Tool} annotations both lied — inventing tools out of description text
 * and missing {@code lister_editions} and {@code ajouter_horaire_stand}. A net
 * woven from a fragile parse is worse than no net, because it is believed.</p>
 */
class McpToolNamesTest {

    private static final Path SOURCES_MCP =
            Path.of("src/main/java/dev/sylvain/planning/mcp");

    /**
     * Every file that quotes tool names at the user rather than calling them.
     *
     * <p>The prompts and the resources are Java, but they are documents all
     * the same: their text is read by an assistant, not compiled against the
     * tools it names. A wrong name there fails exactly the way the ready-to-copy
     * prompt of the MCP page once did.</p>
     */
    private static final Path PROMPTS = Path.of("src/main/java/dev/sylvain/planning/mcp/McpPrompts.java");
    private static final Path DOC_MCP = Path.of("docs/mcp.md");

    private static final List<Path> DOCUMENTS = List.of(
            Path.of("src/main/webui/src/app/pages/mcp/mcp-page.ts"),
            Path.of("src/main/webui/public/i18n/messages.en.json"),
            DOC_MCP,
            PROMPTS,
            Path.of("src/main/java/dev/sylvain/planning/mcp/McpResources.java"));

    /** A tool name shape: {@code lister_stands}, {@code creer_stand_complet}. */
    private static final Pattern SNAKE_CASE = Pattern.compile("\\b[a-z][a-z0-9]*(?:_[a-z0-9]+)+\\b");

    /** Inline {@code `code`} spans — in Markdown, prose is not a citation. */
    private static final Pattern SPAN_CODE = Pattern.compile("`([^`\\n]+)`");

    /**
     * Snake-case words that are deliberately not tool names.
     *
     * <ul>
     *   <li>{@code p_token} is Pangolin's query-string parameter, quoted in the
     *       English message catalogue.</li>
     * </ul>
     */
    private static final Set<String> NOT_TOOL_NAMES = Set.of("p_token");

    /**
     * Every feature the MCP server actually announces, read from the
     * annotations themselves.
     *
     * <p>Prompts count as well as tools: a prompt is named in the same
     * snake_case, and {@link #DOCUMENTS} now holds the file that declares
     * them — its own names must not read as citations of tools that do not
     * exist.</p>
     */
    private static Set<String> exposedTools() throws IOException {
        Set<String> tools = new TreeSet<>();
        try (Stream<Path> files = Files.list(SOURCES_MCP)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String simpleName = file.getFileName().toString().replace(".java", "");
                Class<?> type;
                try {
                    // Loaded without initialising: this test only reads annotations.
                    type = Class.forName(SOURCES_MCP.toString().replace('/', '.')
                            .replace("src.main.java.", "") + "." + simpleName,
                            false, McpToolNamesTest.class.getClassLoader());
                } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                    continue;
                }
                for (var method : type.getDeclaredMethods()) {
                    if (method.isAnnotationPresent(Tool.class) || method.isAnnotationPresent(Prompt.class)) {
                        tools.add(method.getName());
                    }
                }
            }
        }
        return tools;
    }

    /** The tool names a document quotes at its reader. */
    private static Set<String> citedNames(Path document) throws IOException {
        String content = Files.readString(document);
        Set<String> cited = new TreeSet<>();
        if (document.toString().endsWith(".md")) {
            Matcher span = SPAN_CODE.matcher(content);
            while (span.find()) {
                collect(span.group(1), cited);
            }
        } else {
            collect(content, cited);
        }
        cited.removeAll(NOT_TOOL_NAMES);
        return cited;
    }

    private static void collect(String text, Set<String> into) {
        Matcher word = SNAKE_CASE.matcher(text);
        while (word.find()) {
            into.add(word.group());
        }
    }

    @Test
    void everyToolNameQuotedOutsideTheCodeExists() throws IOException {
        Set<String> exposed = exposedTools();
        List<String> unknown = new ArrayList<>();
        for (Path document : DOCUMENTS) {
            for (String cited : citedNames(document)) {
                if (!exposed.contains(cited)) {
                    unknown.add(document.getFileName() + " : " + cited);
                }
            }
        }

        assertThat(unknown)
                .as("tool names quoted at users but not exposed by the server — either the name is "
                        + "wrong, or it is not a tool name and belongs in NOT_TOOL_NAMES")
                .isEmpty();
    }

    /**
     * The test above is only worth anything if it reads real tools and real
     * citations. A reflection lookup that found nothing, or a document that
     * moved, would turn it green for the worst of reasons.
     *
     * <p>{@code docs/mcp.md} is only required to quote <em>some</em> tool name,
     * not the whole catalogue: the server announces its own tools, and the
     * documentation deliberately stops at what the server cannot say.</p>
     *
     * <p>The MCP page is deliberately <b>not</b> one of the two witnesses any
     * more: it no longer holds a prompt of its own, it reads them from
     * {@code /api/mcp/prompts}. It stays in {@link #DOCUMENTS} so that putting
     * a hand-written prompt back would be caught, but the citations now live
     * in {@link #PROMPTS}.</p>
     */
    @Test
    void theScanReadsRealToolsAndRealCitations() throws IOException {
        assertThat(exposedTools())
                .as("tools found by reflection on @Tool")
                .hasSizeGreaterThan(60);

        for (Path document : DOCUMENTS) {
            assertThat(document).exists();
        }
        assertThat(citedNames(PROMPTS))
                .as("the prompts the server serves name the tools they chain")
                .isNotEmpty();
        assertThat(citedNames(DOC_MCP))
                .as("docs/mcp.md still quotes tool names, so the check above has something to bite on")
                .isNotEmpty();
    }

    /** A name on the exceptions list that no document quotes any more must leave it. */
    @Test
    void everyExceptionStillAppearsSomewhere() throws IOException {
        Set<String> everything = new TreeSet<>();
        for (Path document : DOCUMENTS) {
            collect(Files.readString(document), everything);
        }
        assertThat(everything).containsAll(NOT_TOOL_NAMES);
    }
}
