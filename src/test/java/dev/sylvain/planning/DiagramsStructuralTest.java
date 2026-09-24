package dev.sylvain.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.sourceforge.plantuml.code.TranscoderUtil;
import org.junit.jupiter.api.Test;

/**
 * The diagrams of {@code docs/diagrammes/}, checked against the code they draw.
 *
 * <p>A diagram is prose that nobody greps: rename a class, and every sentence
 * naming it shows up in a search, while the box that names it goes on drawing
 * a type that no longer exists. What {@link DocumentationStructuralTest} does
 * for the endpoints and the constraints, this does for the pictures, in the
 * same direction — what a diagram names must exist, not the reverse.</p>
 *
 * <ul>
 *   <li>A committed SVG is the rendering of the {@code .puml} next to it:
 *       PlantUML embeds the source it drew in every SVG, so the check decodes
 *       it rather than rendering again, and does not depend on the fonts of
 *       the machine running it.</li>
 *   <li>A {@code participant} or a {@code class} named like a Java type is
 *       one, and a message {@code method(…)} sent to it is declared in its
 *       source file. Actors, databases and browsers are drawn with the other
 *       keywords ({@code actor}, {@code database}, {@code boundary}, …),
 *       which are not checked.</li>
 *   <li>A cited {@code VERB /api/…} route is declared by a resource.</li>
 *   <li>A state diagram that says {@code ' enum: Name} draws every constant
 *       of that enum, and nothing else.</li>
 *   <li>Every diagram is shown by a document of {@code docs/}, and every
 *       image a document shows exists.</li>
 * </ul>
 */
class DiagramsStructuralTest {

    private static final Path DIAGRAMS = Path.of("docs/diagrammes");
    private static final Path DOCS = Path.of("docs");
    private static final Path SOURCES = Path.of("src/main/java");

    private static final Pattern TYPE_NAME = Pattern.compile("[A-Z][A-Za-z0-9]*");

    /* ------------------------------ the SVGs ------------------------------ */

    /**
     * The SVG shows what the source says. Editing a {@code .puml} without
     * running {@code ./mvnw validate -Pgenerate-diagrams} leaves the page
     * drawing the previous version, which is the drift this directory exists
     * to avoid.
     */
    @Test
    void everySvgIsTheRenderingOfItsSource() throws IOException {
        List<String> stale = new ArrayList<>();
        for (Path puml : diagrams()) {
            Path svg = puml.resolveSibling(name(puml) + ".svg");
            if (!Files.exists(svg)) {
                stale.add(svg + " (missing)");
                continue;
            }
            String embedded = embeddedSource(Files.readString(svg, StandardCharsets.UTF_8));
            if (embedded == null || !embedded.equals(normalisedSource(read(puml)))) {
                stale.add(svg.toString());
            }
        }
        assertThat(stale)
                .as("SVGs that are not the rendering of their .puml — run ./mvnw validate -Pgenerate-diagrams "
                        + "and commit the result")
                .isEmpty();
    }

    @Test
    void everySvgHasItsSource() throws IOException {
        List<String> orphans = new ArrayList<>();
        try (Stream<Path> files = Files.list(DIAGRAMS)) {
            for (Path svg : files.filter(f -> f.toString().endsWith(".svg")).toList()) {
                if (!Files.exists(svg.resolveSibling(name(svg) + ".puml"))) {
                    orphans.add(svg.toString());
                }
            }
        }
        assertThat(orphans)
                .as("SVGs of docs/diagrammes without the .puml they are drawn from")
                .isEmpty();
    }

    /** The source PlantUML wrote into the SVG, decoded; {@code null} when there is none. */
    static String embeddedSource(String svg) throws IOException {
        Matcher matcher = Pattern.compile("<\\?plantuml-src (\\S+)\\?>").matcher(svg);
        if (!matcher.find()) {
            return null;
        }
        return normalisedSource(TranscoderUtil.getDefaultTranscoder().decode(matcher.group(1)));
    }

    private static String normalisedSource(String source) {
        return source.replace("\r\n", "\n").strip();
    }

    /* ----------------------------- documents ------------------------------ */

    /**
     * A diagram nobody shows is a diagram nobody reads, and so nobody
     * corrects. Each one belongs to the document that owns its subject — the
     * rule of {@code AGENTS.md} for prose holds for pictures.
     */
    @Test
    void everyDiagramIsShownByADocumentAndEveryImageShownExists() throws IOException {
        Set<String> shown = new TreeSet<>();
        Set<String> missing = new TreeSet<>();
        try (Stream<Path> files = Files.walk(DOCS)) {
            for (Path md : files.filter(f -> f.toString().endsWith(".md")).toList()) {
                Matcher matcher = SHOWN_DIAGRAM.matcher(withoutFencedCode(read(md)));
                while (matcher.find()) {
                    shown.add(matcher.group(1));
                    if (!Files.exists(DIAGRAMS.resolve(matcher.group(1) + ".svg"))) {
                        missing.add(md + " → " + matcher.group());
                    }
                }
            }
        }
        Set<String> neverShown = new TreeSet<>();
        for (Path puml : diagrams()) {
            if (!shown.contains(name(puml))) {
                neverShown.add(puml.toString());
            }
        }
        assertThat(missing)
                .as("images of docs/diagrammes shown by a document but absent")
                .isEmpty();
        assertThat(neverShown).as("diagrams that no document of docs/ shows").isEmpty();
    }

    /**
     * The Tests workflow skips a push that only touches {@code docs/}, except
     * the paths it lists. A document that shows a diagram and is not listed
     * could drop its image, or misspell it, and merge green: the check above
     * would never have run.
     */
    @Test
    void everyDocumentShowingADiagramTriggersTheTests() throws IOException {
        String workflow = read(Path.of(".github/workflows/tests.yml"));
        // One path list per trigger (push, pull_request): a document must be in each.
        int triggers = occurrences(workflow, "- 'docs/diagrammes/**'");
        assertThat(triggers)
                .as("path lists of tests.yml naming docs/diagrammes/**")
                .isPositive();
        Set<String> unlisted = new TreeSet<>();
        try (Stream<Path> files = Files.walk(DOCS)) {
            for (Path md : files.filter(f -> f.toString().endsWith(".md")).toList()) {
                String path = md.toString().replace('\\', '/');
                if (SHOWN_DIAGRAM.matcher(withoutFencedCode(read(md))).find()
                        && occurrences(workflow, "- '" + path + "'") != triggers) {
                    unlisted.add(path);
                }
            }
        }
        assertThat(unlisted)
                .as("documents showing a diagram that the paths of .github/workflows/tests.yml leave out")
                .isEmpty();
    }

    /** A Markdown image of this directory — the syntax a reader sees rendered, not a mention of the path. */
    private static final Pattern SHOWN_DIAGRAM = Pattern.compile("!\\[[^\\]]*]\\(diagrammes/([\\w-]+)\\.svg\\)");

    /**
     * The document without its fenced blocks: an example of the embedding
     * syntax, like the one {@code docs/developpement.md} gives, shows nothing
     * and must not count as showing the diagram it names.
     */
    static String withoutFencedCode(String markdown) {
        StringBuilder kept = new StringBuilder();
        boolean fenced = false;
        for (String line : markdown.split("\n", -1)) {
            if (line.stripLeading().startsWith("```")) {
                fenced = !fenced;
                continue;
            }
            if (!fenced) {
                kept.append(line).append('\n');
            }
        }
        return kept.toString();
    }

    @Test
    void anImageInAFencedExampleShowsNothing() {
        String markdown = """
                ![Un solve](diagrammes/solve.svg)

                ```markdown
                ![Exemple](diagrammes/exemple.svg)
                ```

                Le fichier `diagrammes/cite.svg` est seulement nommé.
                """;

        Matcher matcher = SHOWN_DIAGRAM.matcher(withoutFencedCode(markdown));
        List<String> shown = new ArrayList<>();
        while (matcher.find()) {
            shown.add(matcher.group(1));
        }
        assertThat(shown).containsExactly("solve");
    }

    /* ------------------------- participants, calls ------------------------ */

    @Test
    void everyTypeNamedByADiagramExists() throws IOException {
        Map<String, Path> types = declaredTypes();
        Set<String> unknown = new TreeSet<>();
        for (Path puml : diagrams()) {
            for (String type : namedTypes(read(puml)).values()) {
                if (!types.containsKey(type)) {
                    unknown.add(name(puml) + ": " + type);
                }
            }
        }
        assertThat(unknown)
                .as("participants or classes named like a Java type that src/main/java does not declare — "
                        + "a renamed class, or an actor that should be drawn as `actor`/`boundary`")
                .isEmpty();
    }

    @Test
    void everyMethodCalledOnATypeIsDeclaredInIt() throws IOException {
        Map<String, Path> types = declaredTypes();
        Set<String> undeclared = new TreeSet<>();
        for (Path puml : diagrams()) {
            String source = read(puml);
            Map<String, String> participants = namedTypes(source);
            for (String[] call : calls(source)) {
                String type = participants.get(call[0]);
                if (type == null || !types.containsKey(type)) {
                    continue;
                }
                if (!declaresMethodInHierarchy(type, call[1], types, new LinkedHashSet<>())) {
                    undeclared.add(name(puml) + ": " + type + "." + call[1] + "()");
                }
            }
        }
        assertThat(undeclared)
                .as("methods a diagram calls on a type that neither it nor a supertype declares")
                .isEmpty();
    }

    @Test
    void everyRouteCitedInADiagramExists() throws IOException {
        Set<String> declared = DocumentationStructuralTest.declaredRoutes();
        Set<String> nonexistent = new TreeSet<>();
        for (Path puml : diagrams()) {
            // A PlantUML label breaks its lines with a literal \\n, which would
            // otherwise end up glued to the path it follows.
            for (String[] citation :
                    DocumentationStructuralTest.citations(read(puml).replace("\\n", " "))) {
                if (!declared.contains(citation[0] + " " + DocumentationStructuralTest.normalise(citation[1]))) {
                    nonexistent.add(name(puml) + ": " + citation[0] + " " + citation[1]);
                }
            }
        }
        assertThat(nonexistent)
                .as("endpoints cited in a diagram that no resource declares")
                .isEmpty();
    }

    /**
     * Alias → type, for every {@code participant} and every box of a class
     * diagram ({@code class}, {@code abstract class}, {@code interface},
     * {@code enum}, …) whose displayed name is a Java type name. A name with
     * a space, a dot or an accent is a role, not a type, and is left alone.
     */
    static Map<String, String> namedTypes(String puml) {
        Pattern declaration = Pattern.compile(
                "^\\s*" + TYPE_KEYWORD + "\\s+(?:\"([^\"]+)\"|([^\\s{<]+))(?:\\s+as\\s+([\\w]+))?", Pattern.MULTILINE);
        Map<String, String> types = new LinkedHashMap<>();
        Matcher matcher = declaration.matcher(puml);
        while (matcher.find()) {
            String name = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            if (TYPE_NAME.matcher(name).matches()) {
                types.put(matcher.group(3) != null ? matcher.group(3) : name, name);
            }
        }
        return types;
    }

    /** The keywords that declare a participant or a box named after a type. */
    private static final String TYPE_KEYWORD =
            "(?:participant|(?:abstract\\s+)?class|abstract|interface|enum|annotation)";

    /**
     * {@code [target alias, method]} for every message whose label opens with
     * {@code method(}, whichever way the arrow points and however it is
     * styled: {@code A -> B}, {@code A ->> B}, {@code A -[#red]> B},
     * {@code B <- A}, {@code B <<-- A}. A two-headed arrow names no single
     * receiver and is skipped.
     */
    static List<String[]> calls(String puml) {
        Pattern message = Pattern.compile(
                "^\\s*([\\w\\[\\]]+)\\s*([ox]?<{1,2})?-+(?:\\[[^\\]]*])?-*(>{1,2}[ox]?|[\\\\/]{1,2}[ox]?)?"
                        + "\\s*([\\w\\[\\]]+)\\s*(?:\\+\\+|--|\\*\\*|!!)?\\s*:\\s*([a-z]\\w*)\\(",
                Pattern.MULTILINE);
        List<String[]> calls = new ArrayList<>();
        Matcher matcher = message.matcher(puml);
        while (matcher.find()) {
            boolean left = matcher.group(2) != null;
            boolean right = matcher.group(3) != null;
            if (left != right) {
                calls.add(new String[] {right ? matcher.group(4) : matcher.group(1), matcher.group(5)});
            }
        }
        return calls;
    }

    @Test
    void aMessageToAParticipantIsReadAsACallOnItsType() {
        String puml = """
                @startuml
                actor "Coordinateur" as admin
                participant "SolvePipeline" as pipeline
                participant StandService
                participant "solver-stream.ts" as stream
                admin -> pipeline ++ : execute(problème)
                pipeline --> admin -- : Resolution
                pipeline ->> StandService : update(id, stand)
                StandService -> stream : événement
                StandService <- admin : saveStand(stand)
                admin -[#red]> pipeline : stop()
                pipeline <<-- admin : cancel(job)
                admin <-> pipeline : both(ways)
                @enduml
                """;

        assertThat(namedTypes(puml))
                .containsExactly(Map.entry("pipeline", "SolvePipeline"), Map.entry("StandService", "StandService"));
        assertThat(calls(puml))
                .extracting(a -> a[0] + "." + a[1])
                .containsExactly(
                        "pipeline.execute",
                        "StandService.update",
                        "StandService.saveStand",
                        "pipeline.stop",
                        "pipeline.cancel");
    }

    @Test
    void everyKindOfClassBoxNamesItsType() {
        String puml = """
                @startuml
                abstract class Foo
                interface Bar {
                }
                enum Baz
                class "Avec espace" as avec
                @enduml
                """;

        assertThat(namedTypes(puml).values()).containsExactly("Foo", "Bar", "Baz");
    }

    /**
     * A declaration, not a call: a return type or a modifier before the name,
     * annotations allowed in front on the same line. {@code return update(…)},
     * {@code assert update(…)} and {@code new Update(…)} are calls.
     */
    static boolean declaresMethod(String javaSource, String method) {
        Pattern declaration = Pattern.compile(
                "^\\s*+(?:@[\\w.]+(?:\\([^)]*\\))?\\s+)*+"
                        + "(?!return\\b|new\\b|throw\\b|else\\b|yield\\b|assert\\b|case\\b)"
                        + "[\\w.<>\\[\\]?, ]*[\\w>\\]]\\s+"
                        + Pattern.quote(method) + "\\s*\\(",
                Pattern.MULTILINE);
        return declaration.matcher(javaSource).find();
    }

    /**
     * The method is declared by the type or by one of its supertypes that
     * the main sources declare — a call to an inherited or default method is
     * a call on the type all the same. A supertype from a library is not
     * searched, and cannot vouch for anything.
     */
    private static boolean declaresMethodInHierarchy(
            String type, String method, Map<String, Path> types, Set<String> visited) throws IOException {
        Path file = types.get(type);
        if (file == null || !visited.add(type)) {
            return false;
        }
        String source = read(file);
        if (declaresMethod(source, method)) {
            return true;
        }
        for (String supertype : supertypes(source, type)) {
            if (declaresMethodInHierarchy(supertype, method, types, visited)) {
                return true;
            }
        }
        return false;
    }

    /** The simple names after {@code extends} and {@code implements} in the header of {@code type}. */
    static List<String> supertypes(String javaSource, String type) {
        Matcher header = Pattern.compile(
                        "\\b(?:class|interface|enum|record)\\s+" + Pattern.quote(type) + "\\b([^{]*)\\{")
                .matcher(javaSource);
        if (!header.find()) {
            return List.of();
        }
        String clauses = header.group(1).replaceAll("<[^<>]*>", "").replaceAll("<[^<>]*>", "");
        Matcher named = Pattern.compile(
                        "\\b(?:extends|implements)\\s+([\\w.\\s,]+?)(?=\\bimplements\\b|\\bpermits\\b|$)")
                .matcher(clauses.replaceAll("\\s+", " ").trim());
        List<String> supertypes = new ArrayList<>();
        while (named.find()) {
            for (String name : named.group(1).split(",")) {
                String simple = name.trim().replaceAll(".*\\.", "");
                if (!simple.isEmpty()) {
                    supertypes.add(simple);
                }
            }
        }
        return supertypes;
    }

    @Test
    void aMethodIsDeclaredOnlyWhereItHasAReturnType() {
        String source = """
                class StandService {
                    public void update(String id, Stand stand) {
                        return validate(stand);
                    }
                    private static <T> List<T> list(
                            int n) {}
                }
                """;

        assertThat(declaresMethod(source, "update")).isTrue();
        assertThat(declaresMethod(source, "list")).isTrue();
        assertThat(declaresMethod(source, "validate")).isFalse();
        assertThat(declaresMethod("        assert check(x);\n", "check")).isFalse();
        assertThat(declaresMethod("    @Override public void run() {}\n", "run"))
                .isTrue();
        assertThat(declaresMethod("    @Tool(name = \"x\") @Journalise Object tool(int n) {\n", "tool"))
                .isTrue();
    }

    @Test
    void aSupertypeIsReadFromTheHeader() {
        String source = """
                public final class SolverJobService extends Base<Job, Map<String, Integer>>
                        implements dev.sylvain.Stoppable, Closeable {
                }
                """;

        assertThat(supertypes(source, "SolverJobService")).containsExactly("Base", "Stoppable", "Closeable");
        assertThat(supertypes("interface Foo extends Bar, Baz {}", "Foo")).containsExactly("Bar", "Baz");
    }

    /* ---------------------------- state diagrams -------------------------- */

    /**
     * A lifecycle drawn from an enum draws all of it. A constant added to the
     * enum and missing from the picture is the transition a reader will not
     * know exists; a state kept in the picture after its constant went is one
     * they will look for in vain.
     */
    @Test
    void aStateDiagramDrawsItsEnumExactly() throws IOException {
        Map<String, Path> types = declaredTypes();
        List<String> mismatches = new ArrayList<>();
        Pattern enumMarker = Pattern.compile("^\\s*' enum: (\\w+)\\s*$", Pattern.MULTILINE);
        for (Path puml : diagrams()) {
            String source = read(puml);
            Matcher matcher = enumMarker.matcher(source);
            if (!matcher.find()) {
                continue;
            }
            String enumName = matcher.group(1);
            if (!types.containsKey(enumName)) {
                mismatches.add(name(puml) + ": enum " + enumName + " is not declared");
                continue;
            }
            Set<String> constants = constants(read(types.get(enumName)), enumName);
            Set<String> states = states(source);
            if (!constants.equals(states)) {
                mismatches.add(name(puml) + ": draws " + states + ", " + enumName + " declares " + constants);
            }
        }
        assertThat(mismatches).isEmpty();
    }

    /** The states of a PlantUML state diagram, the pseudo-states {@code [*]} aside. */
    static Set<String> states(String puml) {
        Set<String> states = new TreeSet<>();
        // Any arrow PlantUML accepts: -->, ->, -[#blue]->, -down->, -[dotted]->, and the same pointing left.
        Matcher transition = Pattern.compile(
                        "^\\s*(\\[\\*]|\\w+)\\s*(<?)-+(?:\\[[^\\]]*]|[a-z]+)?-*(>?)\\s*(\\[\\*]|\\w+)",
                        Pattern.MULTILINE)
                .matcher(puml);
        while (transition.find()) {
            if (transition.group(2).isEmpty() != transition.group(3).isEmpty()) {
                states.add(transition.group(1));
                states.add(transition.group(4));
            }
        }
        Matcher declaration =
                Pattern.compile("^\\s*state\\s+(\\w+)", Pattern.MULTILINE).matcher(puml);
        while (declaration.find()) {
            states.add(declaration.group(1));
        }
        states.remove("[*]");
        return states;
    }

    /** The constants of {@code enum nomEnum} in a Java source, comments and arguments removed. */
    static Set<String> constants(String javaSource, String enumName) {
        Matcher start =
                Pattern.compile("\\benum\\s+" + enumName + "\\b[^{]*\\{").matcher(javaSource);
        if (!start.find()) {
            return Set.of();
        }
        // One pass that skips comments and literals, so a `//` or a `)` inside
        // a string cannot cut the list, and that counts braces as well as
        // parentheses, so a constant with a body does not end the enum.
        StringBuilder list = new StringBuilder();
        int depth = 0;
        int i = start.end();
        while (i < javaSource.length()) {
            char c = javaSource.charAt(i);
            if (javaSource.startsWith("//", i)) {
                int end = javaSource.indexOf('\n', i);
                i = end < 0 ? javaSource.length() : end;
                continue;
            }
            if (javaSource.startsWith("/*", i)) {
                int end = javaSource.indexOf("*/", i + 2);
                i = end < 0 ? javaSource.length() : end + 2;
                continue;
            }
            if (c == '"' || c == '\'') {
                i = afterLiteral(javaSource, i);
                continue;
            }
            if (depth == 0 && (c == ';' || c == '}')) {
                break;
            }
            if (c == '(' || c == '{') {
                depth++;
            } else if (c == ')' || c == '}') {
                depth--;
            } else if (depth == 0) {
                list.append(c);
            }
            i++;
        }
        Set<String> constants = new TreeSet<>();
        for (String element : list.toString().split(",")) {
            String name = element.replaceAll("@\\w+", "").trim();
            if (name.matches("[A-Z][A-Z0-9_]*")) {
                constants.add(name);
            }
        }
        return constants;
    }

    /** The index just after the string, text block or char literal that opens at {@code start}. */
    private static int afterLiteral(String source, int start) {
        if (source.startsWith("\"\"\"", start)) {
            int end = source.indexOf("\"\"\"", start + 3);
            return end < 0 ? source.length() : end + 3;
        }
        char quote = source.charAt(start);
        int i = start + 1;
        while (i < source.length() && source.charAt(i) != quote) {
            i += source.charAt(i) == '\\' ? 2 : 1;
        }
        return i + 1;
    }

    @Test
    void anEnumConstantWithABodyOrAQuotedArgumentKeepsTheRestOfTheList() {
        assertThat(constants("enum E { A { void f() {} }, B, C; }", "E")).containsExactly("A", "B", "C");
        assertThat(constants("enum E { A(\"http://x\"), B(')'), C }", "E")).containsExactly("A", "B", "C");
    }

    @Test
    void aStateReachedOnlyThroughAStyledOrLeftArrowIsDrawn() {
        String puml = """
                [*] --> A
                A -[#blue]-> B
                C <-- A
                A -down-> D
                """;

        assertThat(states(puml)).containsExactly("A", "B", "C", "D");
    }

    @Test
    void anEnumIsReadWithoutItsCommentsOrArguments() {
        String source = """
                public class SolverJobService {
                    public enum JobStatus {
                        /** Waiting for the executor. */
                        PENDING,
                        // replayed after a restart
                        QUEUED("en file"),
                        RUNNING;

                        JobStatus() {}
                    }
                }
                """;
        String puml = """
                @startuml
                ' enum: JobStatus
                [*] --> PENDING
                PENDING -[#blue]-> RUNNING : run
                state QUEUED : en file
                RUNNING --> [*]
                @enduml
                """;

        assertThat(constants(source, "JobStatus")).containsExactly("PENDING", "QUEUED", "RUNNING");
        assertThat(states(puml)).containsExactly("PENDING", "QUEUED", "RUNNING");
    }

    /* ------------------------------ class diagrams ------------------------ */

    /**
     * A field drawn in a class box is a field of that class. The box of
     * {@code docs/domaine.md} is the one reading of the model many people
     * start from, and a renamed planning variable in it misleads more than a
     * missing one.
     */
    @Test
    void everyFieldDrawnInAClassExists() throws IOException {
        Map<String, Path> types = declaredTypes();
        Set<String> missingFields = new TreeSet<>();
        Pattern box = Pattern.compile("^\\s*" + TYPE_KEYWORD + "\\s+(\\w+)[^{\\n]*\\{([^}]*)}", Pattern.MULTILINE);
        Pattern field = Pattern.compile("^\\s*[-+#~]?\\s*(\\w+)\\s*:", Pattern.MULTILINE);
        Pattern constant = Pattern.compile("^\\s*([A-Z][A-Z0-9_]*)\\s*$", Pattern.MULTILINE);
        for (Path puml : diagrams()) {
            Matcher matcher = box.matcher(read(puml));
            while (matcher.find()) {
                String type = matcher.group(1);
                Path file = types.get(type);
                if (file == null) {
                    continue;
                }
                String java = read(file);
                Matcher fields = field.matcher(matcher.group(2));
                while (fields.find()) {
                    if (!declaresField(java, type, fields.group(1))) {
                        missingFields.add(name(puml) + ": " + type + "." + fields.group(1));
                    }
                }
                Matcher constants = constant.matcher(matcher.group(2));
                while (constants.find()) {
                    if (!constants(java, type).contains(constants.group(1))) {
                        missingFields.add(name(puml) + ": " + type + "." + constants.group(1));
                    }
                }
            }
        }
        assertThat(missingFields)
                .as("fields or enum constants drawn in a box that the type does not declare")
                .isEmpty();
    }

    /**
     * {@code type} declares {@code field}: a field declaration from the type's
     * own header onwards, or a component of its record header. A word that
     * only shows up in a comment or a parameter is not a field. A local
     * variable declared on a line of its own still reads like one: telling
     * them apart takes a parser, and the regression this guards against — a
     * field renamed while the diagram kept the old name — leaves no local
     * behind.
     */
    static boolean declaresField(String javaSource, String type, String field) {
        Matcher header = Pattern.compile("\\b(?:class|interface|enum|record)\\s+" + Pattern.quote(type) + "\\b")
                .matcher(javaSource);
        if (!header.find()) {
            return false;
        }
        String declared = javaSource.substring(header.start());
        Matcher record = Pattern.compile("^record\\s+\\w+\\s*(?:<[^>]*>)?\\s*\\(([^)]*)\\)")
                .matcher(declared);
        if (record.find()
                && Pattern.compile("\\s" + Pattern.quote(field) + "\\s*(?:,|$)")
                        .matcher(record.group(1).strip())
                        .find()) {
            return true;
        }
        return Pattern.compile(
                        "^\\s*(?:@[\\w.]+(?:\\([^)]*\\))?\\s+)*"
                                + "(?:(?:private|protected|public|static|final|transient|volatile)\\s+)*"
                                + "[\\w.<>\\[\\]?, ]*[\\w>\\]]\\s+"
                                + Pattern.quote(field) + "\\s*[;=]",
                        Pattern.MULTILINE)
                .matcher(declared)
                .find();
    }

    @Test
    void aFieldIsADeclarationNotAWord() {
        String source = """
                /** The seat is passe once it has started. */
                public class PosteAffectation {
                    @PlanningPin
                    private boolean verrouille;
                    private Map<String, List<Integer>> competences = new HashMap<>();

                    void mark(boolean passe) {
                        int local = 0;
                    }
                }
                """;

        assertThat(declaresField(source, "PosteAffectation", "verrouille")).isTrue();
        assertThat(declaresField(source, "PosteAffectation", "competences")).isTrue();
        assertThat(declaresField(source, "PosteAffectation", "passe")).isFalse();
        assertThat(declaresField("public record R(\n  String standId, LocalDate date) {}", "R", "date"))
                .isTrue();
        assertThat(declaresField("public record R(String standId) {}", "R", "date"))
                .isFalse();
    }

    /* -------------------------------- helpers ----------------------------- */

    private static List<Path> diagrams() throws IOException {
        try (Stream<Path> files = Files.list(DIAGRAMS)) {
            return files.filter(f -> f.toString().endsWith(".puml")).sorted().toList();
        }
    }

    /**
     * Simple name → file, for every class, interface, enum and record of the
     * main sources, nested ones included: a nested enum is found in the file
     * of the class that encloses it.
     */
    private static Map<String, Path> declaredTypes() throws IOException {
        Pattern declaration = Pattern.compile("\\b(?:class|interface|enum|record)\\s+([A-Z]\\w*)");
        Map<String, Path> types = new HashMap<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                Set<String> declaredHere = new LinkedHashSet<>();
                Matcher matcher = declaration.matcher(read(file));
                while (matcher.find()) {
                    declaredHere.add(matcher.group(1));
                }
                declaredHere.forEach(type -> types.putIfAbsent(type, file));
                types.put(name(file), file);
            }
        }
        return types;
    }

    private static int occurrences(String text, String fragment) {
        int count = 0;
        for (int i = text.indexOf(fragment); i >= 0; i = text.indexOf(fragment, i + 1)) {
            count++;
        }
        return count;
    }

    private static String name(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.lastIndexOf('.'));
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
