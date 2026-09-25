package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.service.referentiel.Avertissement;
import io.quarkiverse.mcp.server.Tool;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Structural privacy guard for issue #107, whose follow-up comment kept
 * exactly one restriction when it opened MCP to every endpoint: animateur
 * names and birth dates must not leave over MCP.
 *
 * <p>Rather than re-asserting it tool by tool (a new tool would simply not be
 * covered), this walks <em>every</em> {@code @Tool} method of the package and
 * refuses, recursively through generics and record components, any return
 * type that could carry a personal field — the raw {@link Animateur} domain
 * class, or a component named {@code prenom}/{@code dateNaissance}. A new
 * tool returning a domain object instead of a filtered view fails here.
 *
 * <p>The free texts a person types are refused by name too — a comment, the
 * admin's answer, the reason of a lock or of an ad hoc constraint: that is
 * where a name or a medical appointment comes back in, and a view carries
 * whether one was written instead (see {@code TextesLibres}). A stand's
 * {@code motif} is not on the list: it describes a stand, not a person.</p>
 */
class McpConfidentialiteStructurelleTest {

    private static final Set<String> COMPOSANTS_INTERDITS = Set.of(
            "prenom",
            "datenaissance",
            "nomdefamille",
            "email",
            "accesstoken",
            "commentaire",
            "commentaireadmin",
            "raison");

    /**
     * Components that bear a forbidden name but that the server writes itself,
     * as {@code Record.component}. Named one by one, with what they hold, so the
     * list cannot quietly become the way around the rule.
     */
    private static final Set<String> TEXTES_DU_SERVEUR = Set.of(
            // Why a stand's dated windows were not compacted, e.g. « Aucune
            // fenêtre datée à compacter »: HoraireCompaction writes every one.
            "LigneCompactage.raison");

    @Test
    void aucunOutilNeRenvoieDeDonneePersonnelleIdentifiante() throws Exception {
        for (Method outil : OutilsMcp.all()) {
            checkType(outil.getGenericReturnType(), outil.getName(), new HashSet<>());
        }
    }

    @Test
    void chaqueOutilPorteUneDescription() throws Exception {
        for (Method outil : OutilsMcp.all()) {
            assertThat(outil.getAnnotation(Tool.class).description())
                    .as("description de l'outil %s", outil.getName())
                    .isNotBlank();
        }
    }

    private static void checkType(Type type, String outil, Set<Class<?>> visites) {
        if (type instanceof ParameterizedType parameterized) {
            checkType(parameterized.getRawType(), outil, visites);
            for (Type argument : parameterized.getActualTypeArguments()) {
                checkType(argument, outil, visites);
            }
            return;
        }
        if (!(type instanceof Class<?> classe) || !visites.add(classe)) {
            return;
        }
        assertThat(classe)
                .as("l'outil %s ne doit pas exposer la classe de domaine Animateur", outil)
                .isNotEqualTo(Animateur.class);
        // A warning travels as a code, never as its sentence: the message of
        // MINEUR_PENDANT_EVENEMENT dates the majority, that is the birth date
        // shifted by eighteen years — the very field these views withhold.
        // The rule was a convention applied by hand in three helpers; a tool
        // returning List<Avertissement> compiled and passed every test.
        assertThat(classe)
                .as("l'outil %s ne doit pas exposer un Avertissement en phrase : passer par WarningCodes", outil)
                .isNotEqualTo(Avertissement.class);
        // A sealed return type (one tool answering several shapes) is walked
        // through every shape it permits: declaring the interface must not be
        // the way around the rule.
        if (classe.isSealed()) {
            for (Class<?> permise : classe.getPermittedSubclasses()) {
                checkType(permise, outil, visites);
            }
            return;
        }
        if (!classe.isRecord()) {
            return;
        }
        for (RecordComponent composant : classe.getRecordComponents()) {
            if (!TEXTES_DU_SERVEUR.contains(classe.getSimpleName() + "." + composant.getName())) {
                assertThat(composant.getName().toLowerCase(Locale.ROOT))
                        .as("champ exposé par l'outil %s (record %s)", outil, classe.getSimpleName())
                        .isNotIn(COMPOSANTS_INTERDITS);
            }
            checkType(composant.getGenericType(), outil, visites);
        }
    }
}
