package dev.sylvain.planning.service.responsable;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.api.ResponsableResource;
import jakarta.ws.rs.GET;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The responsable de stand's scope, held by the types and the sources rather
 * than by review (issue #295).
 *
 * <ul>
 *   <li><b>Read only</b>: every public method of {@link ResponsableResource}
 *       is a {@code GET}.</li>
 *   <li><b>A projection, never the domain</b>: what those methods return is
 *       made of the records of this package, strings, booleans, numbers and
 *       dates — walked component by component, so an {@code Animateur}, a
 *       {@code Stand}, a plan or a {@code Compte} nested three levels down
 *       fails as surely as one returned outright. The records name
 *       {@code nom} and {@code prenom}; this test also refuses a component
 *       whose name says contact, birth or token.</li>
 *   <li><b>One door</b>: the published plan is a snapshot read whole, so the
 *       stand predicate cannot be a SQL {@code WHERE} (the edition's own
 *       {@code IsolationEditionStructurelleTest} holds that one). It is
 *       {@link StandScope}, and nothing else in the package reads the
 *       seats or lists the stands.</li>
 * </ul>
 */
class ResponsableProjectionStructurelleTest {

    private static final Path PAQUET = Path.of("src/main/java/dev/sylvain/planning/service/responsable");

    private static final Set<Class<?>> FEUILLES = Set.of(
            String.class, boolean.class, Boolean.class, int.class, Integer.class, Instant.class, LocalDateTime.class);

    private static final Pattern COMPOSANT_INTERDIT =
            Pattern.compile("(?i).*(mail|telephone|phone|naissance|birth|token|jeton|adresse|id)$");

    @Test
    void leResponsableNeFaitQueLire() {
        for (Method methode : publicMethods()) {
            assertThat(methode.isAnnotationPresent(GET.class))
                    .as("%s must be a GET: the responsable writes nothing", methode.getName())
                    .isTrue();
        }
    }

    @Test
    void ceQuiSortEstUneProjectionDuPaquet() {
        List<String> fautes = new ArrayList<>();
        for (Method methode : publicMethods()) {
            walk(methode.getGenericReturnType(), methode.getName(), new HashSet<>(), fautes);
        }
        assertThat(fautes).isEmpty();
    }

    /** The {@code *Id} components are the stand's and the edition's own, which the screen routes by. */
    private static final Set<String> IDENTIFIANTS_PERMIS = Set.of("standId", "editionId");

    private static void walk(Type type, String chemin, Set<Type> vus, List<String> fautes) {
        if (type instanceof ParameterizedType parametre) {
            if (parametre.getRawType() != List.class) {
                fautes.add(chemin + ": " + type + " is not a List");
            }
            for (Type argument : parametre.getActualTypeArguments()) {
                walk(argument, chemin + "[]", vus, fautes);
            }
            return;
        }
        if (!(type instanceof Class<?> classe)) {
            fautes.add(chemin + ": " + type);
            return;
        }
        if (FEUILLES.contains(classe) || !vus.add(classe)) {
            return;
        }
        if (!classe.isRecord() || !classe.getPackageName().equals(ResponsableService.class.getPackageName())) {
            fautes.add(chemin + ": " + classe.getName() + " is not a projection of the responsable package");
            return;
        }
        for (RecordComponent composant : classe.getRecordComponents()) {
            String nom = composant.getName();
            if (COMPOSANT_INTERDIT.matcher(nom).matches() && !IDENTIFIANTS_PERMIS.contains(nom)) {
                fautes.add(chemin + "." + nom + ": a component the responsable must not read");
            }
            walk(composant.getGenericType(), chemin + "." + nom, vus, fautes);
        }
    }

    @Test
    void seulLePerimetreLitLesSiegesEtLesStands() throws IOException {
        List<String> fautes = new ArrayList<>();
        Pattern listeDesStands = Pattern.compile("listStands\\(\\)");
        try (Stream<Path> fichiers = Files.list(PAQUET)) {
            for (Path fichier :
                    fichiers.filter(f -> f.toString().endsWith(".java")).toList()) {
                String source = Files.readString(fichier);
                boolean porte = fichier.getFileName().toString().equals("StandScope.java");
                if (!porte && source.contains("getPostes(")) {
                    fautes.add(fichier.getFileName() + " reads the seats outside StandScope");
                }
                if (!porte && source.contains("getAnimateurs(")) {
                    fautes.add(fichier.getFileName() + " reads the animateurs of the plan");
                }
                Matcher appel = listeDesStands.matcher(source);
                while (appel.find()) {
                    String avant = source.substring(Math.max(0, appel.start() - 80), appel.start());
                    if (!avant.replaceAll("\\s+", "").contains("scope.stands(")) {
                        fautes.add(fichier.getFileName() + " lists the stands without StandScope");
                    }
                }
            }
        }
        assertThat(fautes).isEmpty();
    }

    private static List<Method> publicMethods() {
        return Stream.of(ResponsableResource.class.getDeclaredMethods())
                .filter(m -> Modifier.isPublic(m.getModifiers()))
                .toList();
    }
}
