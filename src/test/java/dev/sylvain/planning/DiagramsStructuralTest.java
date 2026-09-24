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
        Pattern image = Pattern.compile("diagrammes/([\\w-]+)\\.svg");
        Set<String> shown = new TreeSet<>();
        Set<String> missing = new TreeSet<>();
        try (Stream<Path> files = Files.walk(DOCS)) {
            for (Path md : files.filter(f -> f.toString().endsWith(".md")).toList()) {
                Matcher matcher = image.matcher(read(md));
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
                if (!declaresMethod(read(types.get(type)), call[1])) {
                    undeclared.add(name(puml) + ": " + type + "." + call[1] + "()");
                }
            }
        }
        assertThat(undeclared)
                .as("methods a diagram calls on a type whose source file declares no such method")
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
     * Alias → type, for every {@code participant} and every {@code class}
     * whose displayed name is a Java type name. A name with a space, a dot or
     * an accent is a role, not a type, and is left alone.
     */
    static Map<String, String> namedTypes(String puml) {
        Pattern declaration = Pattern.compile(
                "^\\s*(?:participant|class)\\s+(?:\"([^\"]+)\"|([^\\s{<]+))(?:\\s+as\\s+([\\w]+))?", Pattern.MULTILINE);
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

    /** {@code [target alias, method]} for every message whose label opens with {@code method(}. */
    static List<String[]> calls(String puml) {
        Pattern message = Pattern.compile(
                "^\\s*[\\w\\[\\]]+\\s*[-.]*-+>{1,2}[ox]?\\s*(\\w+)\\s*(?:\\+\\+|--|\\*\\*|!!)?\\s*:\\s*([a-z]\\w*)\\(",
                Pattern.MULTILINE);
        List<String[]> calls = new ArrayList<>();
        Matcher matcher = message.matcher(puml);
        while (matcher.find()) {
            calls.add(new String[] {matcher.group(1), matcher.group(2)});
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
                @enduml
                """;

        assertThat(namedTypes(puml))
                .containsExactly(Map.entry("pipeline", "SolvePipeline"), Map.entry("StandService", "StandService"));
        assertThat(calls(puml))
                .extracting(a -> a[0] + "." + a[1])
                .containsExactly("pipeline.execute", "StandService.update");
    }

    /**
     * A declaration, not a call: a return type or a modifier before the name.
     * {@code return update(…)} and {@code new Update(…)} are calls.
     */
    static boolean declaresMethod(String javaSource, String method) {
        Pattern declaration = Pattern.compile(
                "^\\s*+(?!return\\b|new\\b|throw\\b|else\\b|yield\\b)[\\w.<>\\[\\]?, ]*[\\w>\\]]\\s+"
                        + Pattern.quote(method) + "\\s*\\(",
                Pattern.MULTILINE);
        return declaration.matcher(javaSource).find();
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
        Matcher transition = Pattern.compile("^\\s*(\\[\\*]|\\w+)\\s*-[\\w-]*->\\s*(\\[\\*]|\\w+)", Pattern.MULTILINE)
                .matcher(puml);
        while (transition.find()) {
            states.add(transition.group(1));
            states.add(transition.group(2));
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
        String body = javaSource
                .substring(start.end())
                .replaceAll("(?s)/\\*.*?\\*/", "")
                .replaceAll("//[^\\n]*", "");
        StringBuilder list = new StringBuilder();
        int depth = 0;
        for (char c : body.toCharArray()) {
            if (depth == 0 && (c == ';' || c == '}')) {
                break;
            }
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
            } else if (depth == 0) {
                list.append(c);
            }
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
        Pattern box = Pattern.compile("^\\s*class\\s+(\\w+)[^{\\n]*\\{([^}]*)}", Pattern.MULTILINE);
        Pattern field = Pattern.compile("^\\s*[-+#~]?\\s*(\\w+)\\s*:", Pattern.MULTILINE);
        for (Path puml : diagrams()) {
            Matcher matcher = box.matcher(read(puml));
            while (matcher.find()) {
                Path file = types.get(matcher.group(1));
                if (file == null) {
                    continue;
                }
                String java = read(file);
                Matcher fields = field.matcher(matcher.group(2));
                while (fields.find()) {
                    if (!Pattern.compile("\\b" + fields.group(1) + "\\b")
                            .matcher(java)
                            .find()) {
                        missingFields.add(name(puml) + ": " + matcher.group(1) + "." + fields.group(1));
                    }
                }
            }
        }
        assertThat(missingFields)
                .as("fields drawn in a class box that the class does not declare")
                .isEmpty();
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

    private static String name(Path file) {
        String name = file.getFileName().toString();
        return name.substring(0, name.lastIndexOf('.'));
    }

    private static String read(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8);
    }
}
