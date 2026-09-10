package dev.sylvain.planning.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
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
 */
class McpConfidentialiteStructurelleTest {

    private static final Set<String> COMPOSANTS_INTERDITS =
            Set.of("prenom", "datenaissance", "nomdefamille", "email", "accesstoken");

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
        if (!classe.isRecord()) {
            return;
        }
        for (RecordComponent composant : classe.getRecordComponents()) {
            assertThat(composant.getName().toLowerCase(Locale.ROOT))
                    .as("champ exposé par l'outil %s (record %s)", outil, classe.getSimpleName())
                    .isNotIn(COMPOSANTS_INTERDITS);
            checkType(composant.getGenericType(), outil, visites);
        }
    }
}
