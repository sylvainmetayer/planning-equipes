package dev.sylvain.planning.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The JSON keys this application puts on the wire, frozen.
 *
 * <p>A key is not an implementation detail: it is read by {@code models.ts},
 * by the Angular pages, and by anything else that talks to this API. Renaming
 * a Java accessor or a record component renames the key with it, and the
 * compiler says nothing — the frontend just reads {@code undefined}.</p>
 *
 * <p>That is not hypothetical. The backend franglais remediation moved 13 keys
 * at once; only a handful of them were asserted anywhere, so the others changed
 * shape on the wire with a green build. This test exists so that the next such
 * rename is <b>loud</b>: it fails with the exact list of keys that moved, and
 * updating {@code json-contract.txt} is then a deliberate act rather than an
 * afterthought.</p>
 *
 * <p><b>Failing this test is never fixed by regenerating the file.</b> Each
 * line that moved is a break for every client already reading that key.</p>
 *
 * <h2>How the exposed types are found</h2>
 *
 * <p>Two roots, because one is not enough. The declared return type of a
 * resource method is the obvious one, but roughly half the methods here return
 * {@code Response}, which erases the payload type entirely. So every
 * <b>record declared in the {@code api} package</b> counts as a root too: a
 * record there exists for no other purpose than being somebody's request or
 * response body. Records only, because a resource class is not a payload — and
 * Jackson happily reads {@code getParametresLegaux()} on {@code
 * ParametresResource} as a bean property, which would freeze the shape of a
 * REST endpoint instead of that of a message. From both roots the walk follows
 * Jackson's own view of each
 * type, so what is frozen is what Jackson actually writes — {@code @JsonIgnore}
 * respected, records and getters treated alike.</p>
 */
class JsonContractTest {

    private static final Path SOURCES_API = Path.of("src/main/java/dev/sylvain/planning/api");

    private static final Path CONTRACT = Path.of("src/test/resources/json-contract.txt");

    /** Only this application's own types are walked into; the rest are leaves. */
    private static final String OWN_PACKAGE = "dev.sylvain.planning";

    private static final List<String> HTTP_METHODS = List.of(
            "jakarta.ws.rs.GET",
            "jakarta.ws.rs.POST",
            "jakarta.ws.rs.PUT",
            "jakarta.ws.rs.DELETE",
            "jakarta.ws.rs.PATCH");

    /**
     * Parameters carrying an annotation from this list are not the body: they
     * come from the URL, the headers or the container, and never appear as a
     * JSON key.
     */
    private static final List<String> NOT_A_BODY = List.of(
            "jakarta.ws.rs.PathParam", "jakarta.ws.rs.QueryParam",
            "jakarta.ws.rs.HeaderParam", "jakarta.ws.rs.CookieParam",
            "jakarta.ws.rs.FormParam", "jakarta.ws.rs.MatrixParam",
            "jakarta.ws.rs.core.Context", "jakarta.ws.rs.BeanParam");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Every class of the {@code api} package, nested types included. */
    private static List<Class<?>> apiClasses() throws IOException {
        List<Class<?>> classes = new ArrayList<>();
        try (Stream<Path> files = Files.list(SOURCES_API)) {
            for (Path file :
                    files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String simpleName = file.getFileName().toString().replace(".java", "");
                try {
                    // Loaded without initialising: this test only reads shapes.
                    Class<?> type = Class.forName(
                            "dev.sylvain.planning.api." + simpleName, false, JsonContractTest.class.getClassLoader());
                    classes.add(type);
                    classes.addAll(List.of(type.getDeclaredClasses()));
                } catch (ClassNotFoundException | NoClassDefFoundError _) {
                    // A file carrying no class of that name is simply skipped.
                }
            }
        }
        return classes;
    }

    private static boolean hasAnnotation(Method method, List<String> names) {
        return Stream.of(method.getAnnotations())
                .anyMatch(
                        annotation -> names.contains(annotation.annotationType().getName()));
    }

    /** The types a resource method reads from, or writes to, the wire. */
    private static void collectRoots(Class<?> type, Deque<JavaType> roots) {
        for (Method method : type.getDeclaredMethods()) {
            if (!hasAnnotation(method, HTTP_METHODS)) {
                continue;
            }
            roots.add(MAPPER.getTypeFactory().constructType(method.getGenericReturnType()));
            for (var parameter : method.getParameters()) {
                boolean fromUrl = Stream.of(parameter.getAnnotations())
                        .anyMatch(annotation ->
                                NOT_A_BODY.contains(annotation.annotationType().getName()));
                if (!fromUrl) {
                    roots.add(MAPPER.getTypeFactory().constructType(parameter.getParameterizedType()));
                }
            }
        }
    }

    private static boolean isOwnType(JavaType type) {
        Class<?> raw = type.getRawClass();
        return raw.getName().startsWith(OWN_PACKAGE) && !raw.isEnum() && !raw.isInterface();
    }

    /** Container types hide their payload one level down; unwrap them all. */
    private static void enqueueContained(JavaType type, Deque<JavaType> queue) {
        for (int i = 0; i < type.containedTypeCount(); i++) {
            queue.add(type.containedType(i));
        }
        if (type.isContainerType()) {
            if (type.getContentType() != null) {
                queue.add(type.getContentType());
            }
            if (type.getKeyType() != null) {
                queue.add(type.getKeyType());
            }
        }
    }

    /** Type name to JSON keys, for everything reachable from the REST layer. */
    static SortedMap<String, SortedSet<String>> exposedKeys() throws IOException {
        Deque<JavaType> queue = new ArrayDeque<>();
        for (Class<?> type : apiClasses()) {
            collectRoots(type, queue);
            if (type.isRecord()) {
                queue.add(MAPPER.getTypeFactory().constructType(type));
            }
        }

        SortedMap<String, SortedSet<String>> contract = new TreeMap<>();
        Set<JavaType> seen = new HashSet<>();
        while (!queue.isEmpty()) {
            JavaType type = queue.poll();
            if (!seen.add(type)) {
                continue;
            }
            enqueueContained(type, queue);
            if (!isOwnType(type) || type.isContainerType()) {
                continue;
            }

            BeanDescription description = MAPPER.getSerializationConfig().introspect(type);
            SortedSet<String> keys = new TreeSet<>();
            for (var property : description.findProperties()) {
                if (!property.couldSerialize()) {
                    continue;
                }
                keys.add(property.getName());
                queue.add(property.getPrimaryType());
            }
            if (!keys.isEmpty()) {
                contract.put(type.getRawClass().getName().replace(OWN_PACKAGE + ".", ""), keys);
            }
        }
        return contract;
    }

    private static List<String> asLines(SortedMap<String, SortedSet<String>> contract) {
        return contract.entrySet().stream()
                .map(entry -> entry.getKey() + " = " + String.join(", ", entry.getValue()))
                .toList();
    }

    @Test
    void everyExposedTypeStillPutsTheSameKeysOnTheWire() throws IOException {
        List<String> frozen = Files.readAllLines(CONTRACT).stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();

        assertThat(asLines(exposedKeys()))
                .as("JSON keys on the wire, against src/test/resources/json-contract.txt — a "
                        + "difference here is a break for every client already reading the old "
                        + "key. Move the line only once that break is intended and handled")
                .containsExactlyElementsOf(frozen);
    }

    /**
     * The test above is only worth what its walk is worth: a discovery that
     * stopped finding types would freeze an empty contract and pass for the
     * worst of reasons.
     */
    @Test
    void theWalkReachesTheTypesTheApiReallyExposes() throws IOException {
        SortedMap<String, SortedSet<String>> contract = exposedKeys();

        assertThat(contract)
                .as("types reachable from the REST layer")
                .hasSizeGreaterThan(40)
                .as("the walk must reach a domain type returned bare, a nested response record, "
                        + "and a type only ever reached through another one's property")
                .containsKeys("domain.Animateur", "api.AnimateurResource$AnimateurToken", "domain.FenetreHoraire");
        assertThat(contract.get("domain.Animateur"))
                .as("the animateur carries the espace access token on the wire")
                .isNotEmpty();
    }
}
