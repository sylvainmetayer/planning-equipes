package dev.sylvain.planning.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.ws.rs.HttpMethod;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Route by route, what each non-administrator role can call. {@link
 * InstanceRoutesStructuralTest} classifies whole resources; inside a resource
 * open to an organiser, a new method is open to them too unless somebody marks
 * it {@link InstanceOnly}. This test makes that a decision: the methods open to
 * an organiser, and those open to a responsable de stand, are the two committed
 * inventories under {@code src/test/resources}, and a route in neither fails
 * with the line to add.
 */
class RoutesOuvertesStructuralTest {

    private static final Path ORGANISATEUR = Path.of("src/test/resources/routes-organisateur.txt");
    private static final Path RESPONSABLE = Path.of("src/test/resources/routes-responsable.txt");

    /** The HTTP verb of a resource method (any annotation meta-annotated {@link HttpMethod}), or null. */
    static String verb(Method m) {
        for (Annotation a : m.getAnnotations()) {
            HttpMethod http = a.annotationType().getAnnotation(HttpMethod.class);
            if (http != null) {
                return http.value();
            }
        }
        return null;
    }

    /**
     * One line per route: {@code Class#method VERB /path}. The verb and path
     * keep two overloads apart — keyed on the name alone, a new route reusing
     * an open method's name would pass unseen.
     */
    static String line(Class<?> type, Method m, String verb) {
        jakarta.ws.rs.Path sub = m.getAnnotation(jakarta.ws.rs.Path.class);
        String path = ("/" + type.getAnnotation(jakarta.ws.rs.Path.class).value() + "/"
                        + (sub == null ? "" : sub.value()))
                .replaceAll("/+", "/")
                .replaceAll("(.)/$", "$1");
        return type.getSimpleName() + "#" + m.getName() + " " + verb + " " + path;
    }

    private static Set<String> routesOf(Set<String> classes, boolean withoutInstanceOnly) throws Exception {
        Set<String> routes = new TreeSet<>();
        for (Class<?> type : InstanceRoutesStructuralTest.resources()) {
            if (!classes.contains(type.getSimpleName())) {
                continue;
            }
            // What getDeclaredMethods cannot see would be open unlisted: refuse it.
            assertThat(type.getSuperclass())
                    .as(type.getSimpleName() + ": a route inherited from a superclass escapes the inventory")
                    .isEqualTo(Object.class);
            for (Class<?> contract : type.getInterfaces()) {
                assertThat(Stream.of(contract.getMethods()).filter(m -> verb(m) != null))
                        .as(type.getSimpleName() + ": a route declared on " + contract.getSimpleName()
                                + " escapes the inventory")
                        .isEmpty();
            }
            for (Method m : type.getDeclaredMethods()) {
                String verb = verb(m);
                assertThat(verb == null && m.isAnnotationPresent(jakarta.ws.rs.Path.class))
                        .as(type.getSimpleName() + "#" + m.getName()
                                + " is a sub-resource locator: its routes escape the inventory")
                        .isFalse();
                if (verb != null && !(withoutInstanceOnly && m.isAnnotationPresent(InstanceOnly.class))) {
                    routes.add(line(type, m, verb));
                }
            }
        }
        return routes;
    }

    static Set<String> committed(Path file) throws IOException {
        return new TreeSet<>(Files.readAllLines(file).stream()
                .map(String::strip)
                .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                .toList());
    }

    private static void sameAs(String role, Set<String> actual, Path file, String hint) throws IOException {
        Set<String> expected = committed(file);
        Set<String> unclassified = new TreeSet<>(actual);
        unclassified.removeAll(expected);
        Set<String> gone = new TreeSet<>(expected);
        gone.removeAll(actual);
        assertThat(unclassified)
                .as("route(s) open to the " + role + " that " + file + " does not list. Open on purpose: add the"
                        + " line(s) below. " + hint + "\n" + String.join("\n", unclassified) + "\n")
                .isEmpty();
        assertThat(gone)
                .as("line(s) of " + file + " naming a route that is no longer open to the " + role + " (or gone)")
                .isEmpty();
    }

    @Test
    void everyRouteOpenToAnOrganiserIsListed() throws Exception {
        sameAs(
                "organiser",
                routesOf(InstanceRoutesStructuralTest.PAR_EDITION, true),
                ORGANISATEUR,
                "Not for the organiser: annotate the method @InstanceOnly.");
    }

    @Test
    void everyRouteOpenToAResponsableIsListed() throws Exception {
        sameAs(
                "responsable",
                routesOf(InstanceRoutesStructuralTest.RESPONSABLE, false),
                RESPONSABLE,
                "Keep it read-only and under @RolesAllowed.");
    }
}
