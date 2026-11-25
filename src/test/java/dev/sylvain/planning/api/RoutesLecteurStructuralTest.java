package dev.sylvain.planning.api;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.ws.rs.Produces;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * What a lecteur (ADR 0099) may call, route by route, calqued on {@link
 * RoutesOuvertesStructuralTest}: the open ones are the committed inventory
 * {@code routes-lecteur.txt}, a route in neither the inventory nor refused
 * fails with the line to add, and the three rules the filter applies are held
 * over every resource opened to an organiser — no write is open (the only
 * non-{@code GET} that is, is a PDF or ICS render marked {@link
 * ExportForLecteur}), and no raw-data export is.
 *
 * <p>The filter is {@link LecteurFilter#opens}: this test asks it, so the
 * inventory cannot say something the server does not do. The sweep of the
 * published contract with a real lecteur account is {@code LecteurCanariTest}.</p>
 */
class RoutesLecteurStructuralTest {

    private static final Path LECTEUR = Path.of("src/test/resources/routes-lecteur.txt");

    /** What a PDF or ICS render produces, the zips being bundles of those. */
    private static final Set<String> RENDERS = Set.of("application/pdf", "text/calendar", "application/zip");

    /** Raw data: a lecteur never downloads these (ADR 0099). */
    private static final Set<String> RAW =
            Set.of("text/csv", "application/zip", "application/sql", "application/x-yaml");

    private record Route(Class<?> type, Method method, String verb) {
        String line() {
            return RoutesOuvertesStructuralTest.line(type, method, verb);
        }
    }

    /** The routes an organiser account can call: the lecteur's candidates. */
    private static java.util.List<Route> candidates() throws Exception {
        java.util.List<Route> routes = new java.util.ArrayList<>();
        for (Class<?> type : InstanceRoutesStructuralTest.resources()) {
            if (!InstanceRoutesStructuralTest.PAR_EDITION.contains(type.getSimpleName())) {
                continue;
            }
            for (Method m : type.getDeclaredMethods()) {
                String verb = RoutesOuvertesStructuralTest.verb(m);
                if (verb != null && !m.isAnnotationPresent(InstanceOnly.class)) {
                    routes.add(new Route(type, m, verb));
                }
            }
        }
        return routes;
    }

    @Test
    void everyRouteOpenToALecteurIsListed() throws Exception {
        Set<String> open = new TreeSet<>();
        for (Route r : candidates()) {
            if (LecteurFilter.opens(r.verb(), r.method())) {
                open.add(r.line());
            }
        }
        Set<String> expected = RoutesOuvertesStructuralTest.committed(LECTEUR);
        Set<String> unclassified = new TreeSet<>(open);
        unclassified.removeAll(expected);
        Set<String> gone = new TreeSet<>(expected);
        gone.removeAll(open);
        assertThat(unclassified)
                .as("route(s) open to a lecteur that " + LECTEUR + " does not list. Open on purpose: add the line(s)"
                        + " below. Not for a lecteur: annotate the GET @NotForLecteur.\n"
                        + String.join("\n", unclassified) + "\n")
                .isEmpty();
        assertThat(gone)
                .as("line(s) of " + LECTEUR + " naming a route that is no longer open to a lecteur (or gone)")
                .isEmpty();
    }

    @Test
    void theOnlyOpenWritesArePdfAndIcsRenders() throws Exception {
        for (Route r : candidates()) {
            boolean read = Set.of("GET", "HEAD", "OPTIONS").contains(r.verb());
            if (read) {
                assertThat(r.method().isAnnotationPresent(ExportForLecteur.class))
                        .as(r.line() + ": @ExportForLecteur opens a write route, not a read")
                        .isFalse();
                continue;
            }
            assertThat(LecteurFilter.opens(r.verb(), r.method()))
                    .as(r.line() + " is open to a lecteur: only a PDF or ICS render may be")
                    .isEqualTo(r.method().isAnnotationPresent(ExportForLecteur.class));
            if (r.method().isAnnotationPresent(ExportForLecteur.class)) {
                Produces produces = r.method().getAnnotation(Produces.class);
                assertThat(produces).as(r.line() + " declares what it produces").isNotNull();
                assertThat(Set.of(produces.value()))
                        .as(r.line() + ": an export open to a lecteur renders a PDF or an ICS")
                        .isSubsetOf(RENDERS);
            }
            assertThat(r.method().isAnnotationPresent(NotForLecteur.class))
                    .as(r.line() + ": @NotForLecteur is for a GET")
                    .isFalse();
        }
    }

    @Test
    void aRawDataExportIsRefused() throws Exception {
        for (Route r : candidates()) {
            Produces produces = r.method().getAnnotation(Produces.class);
            if (r.verb().equals("GET")
                    && produces != null
                    && Set.of(produces.value()).stream().anyMatch(RAW::contains)) {
                assertThat(LecteurFilter.opens(r.verb(), r.method()))
                        .as(r.line() + " hands out raw data (" + String.join(",", produces.value())
                                + "): annotate it @NotForLecteur")
                        .isFalse();
            }
        }
    }
}
