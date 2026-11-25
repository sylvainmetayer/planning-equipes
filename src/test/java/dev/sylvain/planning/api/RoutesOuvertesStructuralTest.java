package dev.sylvain.planning.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
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

    private static boolean isRoute(Method m) {
        return m.isAnnotationPresent(GET.class)
                || m.isAnnotationPresent(POST.class)
                || m.isAnnotationPresent(PUT.class)
                || m.isAnnotationPresent(DELETE.class)
                || m.isAnnotationPresent(PATCH.class)
                || m.isAnnotationPresent(HEAD.class);
    }

    private static Set<String> routesOf(Set<String> classes, boolean withoutInstanceOnly) throws Exception {
        Set<String> routes = new TreeSet<>();
        for (Class<?> type : InstanceRoutesStructuralTest.resources()) {
            if (!classes.contains(type.getSimpleName())) {
                continue;
            }
            for (Method m : type.getDeclaredMethods()) {
                if (isRoute(m) && !(withoutInstanceOnly && m.isAnnotationPresent(InstanceOnly.class))) {
                    routes.add(type.getSimpleName() + "#" + m.getName());
                }
            }
        }
        return routes;
    }

    private static Set<String> committed(Path file) throws IOException {
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
