package dev.sylvain.planning.service.responsable;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.api.ResponsableResource;
import jakarta.ws.rs.GET;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * What a responsable de stand receives is a projection, and the route that
 * serves it reads only. Read off the types, so that a field added to the
 * domain tomorrow cannot reach them: what is not a component here cannot leak.
 */
class ResponsableProjectionStructurelleTest {

    private static final Set<Class<?>> SCALAIRES =
            Set.of(String.class, Instant.class, LocalDateTime.class, int.class, boolean.class);

    @Test
    void theViewHoldsOnlyScalarsAndItsOwnRecords() {
        List<String> intrus = new ArrayList<>();
        walk(ResponsableView.class, intrus);
        assertThat(intrus)
                .as("components that are neither a scalar nor a record of the view")
                .isEmpty();
    }

    @Test
    void noComponentIsNamedLikeAContactOrAnAge() {
        List<String> noms = new ArrayList<>();
        collect(ResponsableView.class, noms);
        assertThat(noms)
                .as("a contact or an age never reaches a responsable")
                .noneMatch(nom -> nom.matches("(?i).*(mail|telephone|naissance|age|mineur|majeur|token).*"));
    }

    @Test
    void theResourceOnlyReads() {
        for (Method methode : ResponsableResource.class.getDeclaredMethods()) {
            if (Modifier.isPublic(methode.getModifiers())) {
                assertThat(methode.isAnnotationPresent(GET.class))
                        .as(methode.getName() + " must be a GET")
                        .isTrue();
            }
        }
    }

    private static void walk(Class<?> record, List<String> intrus) {
        for (RecordComponent composant : record.getRecordComponents()) {
            Type type = composant.getGenericType();
            if (type instanceof ParameterizedType liste) {
                type = liste.getActualTypeArguments()[0];
            }
            Class<?> classe = (Class<?>) type;
            if (classe.isRecord() && classe.getName().startsWith(ResponsableView.class.getName())) {
                walk(classe, intrus);
            } else if (!SCALAIRES.contains(classe)) {
                intrus.add(record.getSimpleName() + "." + composant.getName() + " : " + classe.getName());
            }
        }
    }

    private static void collect(Class<?> record, List<String> noms) {
        for (RecordComponent composant : record.getRecordComponents()) {
            noms.add(composant.getName());
            Type type = composant.getGenericType();
            if (type instanceof ParameterizedType liste) {
                type = liste.getActualTypeArguments()[0];
            }
            if (type instanceof Class<?> classe && classe.isRecord()) {
                collect(classe, noms);
            }
        }
    }
}
