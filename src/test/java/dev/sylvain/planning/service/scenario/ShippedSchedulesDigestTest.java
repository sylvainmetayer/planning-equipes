package dev.sylvain.planning.service.scenario;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.scenario.ScenarioBinder;
import dev.sylvain.planning.scenario.dto.ScenarioDto;
import dev.sylvain.planning.service.referentiel.HoraireStandResolver;
import dev.sylvain.planning.service.scenario.ScenarioYamlReader.ReferenceScenario;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The windows a stand opens and closes on, day by day, must be the very ones 3.x
 * resolved from the same file (ADR 0104: the recurring rules became plages, and
 * the conversion must change nothing on any day of any shipped scenario).
 *
 * <p>{@code horaires-resolus-avant-4-0.txt} holds one line per shipped file: the
 * SHA-256 of every effective window of every stand on every day the file's
 * timeslots cover, as the 3.x resolver computed them (the file was written by
 * running this very class on the last 3.x-model commit with
 * {@code -Dhoraires.resolus.generer=...}). It is never regenerated: a line that
 * moves means a stand opens somewhere else than it did.</p>
 */
public class ShippedSchedulesDigestTest {

    private static final Path EMPREINTES = Path.of("src/test/resources/horaires-resolus-avant-4-0.txt");

    @Test
    void everyShippedScenarioOpensItsStandsExactlyAsBefore() throws IOException {
        List<String> lignes = new ArrayList<>();
        for (Path scenario : ScenariosLivres.all()) {
            lignes.add(ligne(scenario));
        }
        String generer = System.getProperty("horaires.resolus.generer");
        if (generer != null) {
            Files.write(Path.of(generer), lignes, StandardCharsets.UTF_8);
            return;
        }
        List<String> attendues = Files.readAllLines(EMPREINTES, StandardCharsets.UTF_8).stream()
                .filter(l -> !l.isBlank() && !l.startsWith("#"))
                .toList();
        assertThat(lignes).containsExactlyElementsOf(attendues);
    }

    /** {@code name sha256 stands days windows}. */
    static String ligne(Path scenario) throws IOException {
        List<Stand> stands;
        List<Creneau> creneaux;
        try {
            ScenarioDto dto = ScenarioBinder.bind(Files.readString(scenario));
            ReferenceScenario reference = ScenarioDomainMapper.reference(dto);
            stands = new ArrayList<>(reference.standsById().values());
            creneaux = new ArrayList<>(reference.creneauxParId().values());
        } catch (RuntimeException e) {
            return ScenariosLivres.nom(scenario) + " ERREUR";
        }
        HoraireStandResolver.apply(stands, creneaux);
        Set<LocalDate> jours = HoraireStandResolver.datesConcernees(creneaux);
        stands.sort(Comparator.comparing(Stand::getId));
        StringBuilder texte = new StringBuilder();
        int fenetres = 0;
        for (Stand stand : stands) {
            for (LocalDate jour : jours) {
                List<String> du = new ArrayList<>();
                for (var ouverture : stand.getOuverturesEffectives()) {
                    if (jour.equals(ouverture.getDate())) {
                        du.add("O|" + ouverture.getHeureDebut() + "|" + ouverture.getHeureFin() + "|"
                                + ouverture.getEffectif());
                    }
                }
                for (var fermeture : stand.getIndisponibilitesEffectives()) {
                    if (jour.equals(fermeture.getDate())) {
                        du.add("F|" + fermeture.getHeureDebut() + "|" + fermeture.getHeureFin() + "|null");
                    }
                }
                du.sort(Comparator.naturalOrder());
                fenetres += du.size();
                texte.append(stand.getId())
                        .append('|')
                        .append(jour)
                        .append('|')
                        .append(du)
                        .append('\n');
            }
        }
        return ScenariosLivres.nom(scenario) + " " + sha256(texte.toString()) + " " + stands.size() + " " + jours.size()
                + " " + fenetres;
    }

    public static String sha256(String texte) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(texte.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
