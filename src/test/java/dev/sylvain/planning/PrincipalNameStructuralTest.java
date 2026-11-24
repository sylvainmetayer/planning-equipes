package dev.sylvain.planning;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Under an OIDC session the principal's name is the person's address. Nothing
 * stored or journalled may read it: an author is the account id
 * ({@code JournalActionService.nomAdmin()}). {@code AuthResource} answers
 * {@code /api/auth/me} with it, to the person themselves — the one exception.
 */
class PrincipalNameStructuralTest {

    private static final List<String> ALLOWED = List.of("api/AuthResource.java");

    @Test
    void noOneReadsThePrincipalNameButTheSessionProbe() throws IOException {
        Path root = Path.of("src/main/java/dev/sylvain/planning");
        try (Stream<Path> files = Files.walk(root)) {
            List<String> offenders = files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> ALLOWED.stream().noneMatch(a -> p.toString().endsWith(a)))
                    .filter(p -> {
                        try {
                            return Files.readString(p).contains("getPrincipal().getName()");
                        } catch (IOException e) {
                            throw new IllegalStateException(e);
                        }
                    })
                    .map(Path::toString)
                    .toList();
            assertThat(offenders)
                    .as("use JournalActionService.nomAdmin(), never an address")
                    .isEmpty();
        }
    }
}
