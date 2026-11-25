package dev.sylvain.planning.service.compte;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * What a local sign-in handles — a password, a link token, a challenge, a
 * code — never reaches a log line or an exception message. The journals hold
 * an event and an address; this holds the other half, the logs.
 */
class ComptesLocauxSecretsStructuralTest {

    private static final List<Path> FICHIERS = List.of(
            Path.of("src/main/java/dev/sylvain/planning/service/compte/CompteAccesService.java"),
            Path.of("src/main/java/dev/sylvain/planning/service/compte/CompteAccesRepository.java"),
            Path.of("src/main/java/dev/sylvain/planning/service/compte/LocalPassword.java"),
            Path.of("src/main/java/dev/sylvain/planning/api/LocalSignInIdentityProvider.java"),
            Path.of("src/main/java/dev/sylvain/planning/api/AuthResource.java"));

    private static final Pattern SECRET =
            Pattern.compile("(?i)motDePasse|password|jeton|token|defi|\\bcode\\b|secret|hash");

    @Test
    void aucuneLigneDeJournalNiMessageDErreurNePorteUnSecret() throws IOException {
        for (Path fichier : FICHIERS) {
            try (Stream<String> lignes = Files.lines(fichier)) {
                List<String> fautives = lignes.filter(
                                l -> l.contains("Log.") || l.contains("new IllegalStateException("))
                        .filter(l ->
                                SECRET.matcher(l.replaceAll("\"[^\"]*\"", "")).find())
                        .toList();
                assertThat(fautives)
                        .as("%s : un secret dans un log ou une exception", fichier)
                        .isEmpty();
            }
        }
    }

    @Test
    void lesJournauxLogguentUnCompteParSonIdentifiantJamaisParSonAdresse() throws IOException {
        String service = Files.readString(FICHIERS.get(0));
        assertThat(service).doesNotContainPattern("Log\\.[a-z]+f?\\([^;]*\\bemail\\b");
    }
}
