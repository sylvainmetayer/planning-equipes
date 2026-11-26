package dev.sylvain.planning.service.consigne;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.ConsigneEdition;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlageStand;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.referentiel.HoraireStandResolver;
import dev.sylvain.planning.service.scenario.ScenarioYamlReader;
import dev.sylvain.planning.service.scenario.ScenarioYamlReader.ReferenceScenario;
import dev.sylvain.planning.service.scenario.ScenariosLivres;
import dev.sylvain.planning.service.scenario.ShippedSchedulesDigestTest;
import dev.sylvain.planning.service.solve.ProblemBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * A consigne written as plages opens every stand, day by day, exactly where the
 * fourth layer of 3.x opened it (ADR 0125).
 *
 * <p>No shipped scenario carries a consigne, so each one gets the same two:
 * on its first day, 12h-18h closed and every third stand reopened 18h-22h at
 * the headcount it loses; on its second, closed from 14h to midnight and every
 * fourth stand reopened 8h-15h at 2 — a window that straddles the band. The
 * créneaux the openings need are added as {@code ConsigneService.planifier}
 * adds them. What is compared is what the solver receives: the open segments
 * of every stand on every créneau, and the seat list {@code ProblemBuilder}
 * builds, in order.</p>
 *
 * <p>{@code consignes-resolues-avant-4-0.txt} holds one line per shipped file,
 * written by running this class with the 3.x resolver on the last commit that
 * had it ({@code -Dconsignes.resolues.generer=...}). It is never regenerated
 * from the plages: a shipped scenario that changes on main is read again with
 * the layer, on main, and only then compared.</p>
 */
class ConsigneAsPlagesEquivalenceTest {

    private static final Path EMPREINTES = Path.of("src/test/resources/consignes-resolues-avant-4-0.txt");

    @Test
    void everyShippedScenarioUnderAConsigneOpensItsStandsExactlyAsTheLayerDid() throws IOException {
        List<String> lignes = new ArrayList<>();
        for (Path scenario : ScenariosLivres.all()) {
            lignes.add(ligne(scenario));
        }
        String generer = System.getProperty("consignes.resolues.generer");
        if (generer != null) {
            Files.write(Path.of(generer), lignes, StandardCharsets.UTF_8);
            return;
        }
        List<String> attendues = Files.readAllLines(EMPREINTES, StandardCharsets.UTF_8).stream()
                .filter(l -> !l.isBlank() && !l.startsWith("#"))
                .toList();
        assertThat(lignes).containsExactlyElementsOf(attendues);
    }

    /** {@code name sha256 seats}. */
    private static String ligne(Path scenario) throws IOException {
        List<Stand> stands;
        List<Creneau> grille;
        try {
            ReferenceScenario reference = ScenarioYamlReader.loadReferenceScenario(
                    scenario.getFileName().toString());
            stands = new ArrayList<>(reference.standsById().values());
            grille = new ArrayList<>(reference.creneauxParId().values());
        } catch (RuntimeException e) {
            return ScenariosLivres.nom(scenario) + " ERREUR";
        }
        stands.sort(Comparator.comparing(Stand::getId));
        List<LocalDate> jours =
                grille.stream().map(Creneau::getDate).distinct().sorted().toList();
        if (jours.size() < 2 || stands.isEmpty()) {
            return ScenariosLivres.nom(scenario) + " SANS-OBJET";
        }
        long prochainId = grille.stream().mapToLong(Creneau::getId).max().orElse(0) + 1;
        List<ConsigneEdition> consignes = new ArrayList<>();
        for (ConsigneEdition demandee : List.of(
                consigne(jours.get(0), LocalTime.of(12, 0), LocalTime.of(18, 0), stands, 3, 18, 22, null),
                consigne(jours.get(1), LocalTime.of(14, 0), null, stands, 4, 8, 15, 2))) {
            List<Creneau> duJour = grille.stream()
                    .filter(c -> demandee.date().equals(c.getDate()))
                    .toList();
            List<Long> ajoutes = new ArrayList<>();
            for (Creneau aCreer :
                    ConsigneService.planifier(demandee, duJour, duJour).aCreer()) {
                Creneau ajoute =
                        new Creneau(prochainId++, 0, demandee.date(), aCreer.getHeureDebut(), aCreer.getHeureFin());
                grille.add(ajoute);
                ajoutes.add(ajoute.getId());
            }
            consignes.add(demandee.withCreneauxAjoutes(ajoutes));
        }
        Creneau.assignerJours(grille);
        grille.sort(Comparator.comparing(Creneau::getDate)
                .thenComparing(Creneau::getHeureDebut)
                .thenComparing(Creneau::getId));

        lay(stands, consignes, grille);

        StringBuilder texte = new StringBuilder();
        for (Creneau creneau : grille) {
            for (Stand stand : stands) {
                texte.append(creneau.getId())
                        .append('|')
                        .append(stand.getId())
                        .append('|')
                        .append(creneau.segmentsOuverts(stand))
                        .append('\n');
            }
        }
        List<PosteAffectation> postes = ProblemBuilder.buildPostes(stands, grille);
        for (PosteAffectation poste : postes) {
            texte.append(poste.getStand().getId())
                    .append('|')
                    .append(poste.getCreneau().getId())
                    .append('|')
                    .append(poste.heureDebutEffectif())
                    .append('|')
                    .append(poste.heureFinEffectif())
                    .append('\n');
        }
        return ScenariosLivres.nom(scenario) + " " + ShippedSchedulesDigestTest.sha256(texte.toString()) + " "
                + postes.size();
    }

    /** The consignes written as plages on the stands, then read the way {@code StandService.resolve} reads them. */
    private static void lay(List<Stand> stands, List<ConsigneEdition> consignes, List<Creneau> grille) {
        List<Map<String, List<PlageStand>>> ecrites = new ArrayList<>();
        for (ConsigneEdition consigne : consignes) {
            ecrites.add(ConsignePlages.of(stands, consigne, grille));
        }
        for (Map<String, List<PlageStand>> parStand : ecrites) {
            for (Stand stand : stands) {
                stand.getPlages().addAll(parStand.get(stand.getId()));
            }
        }
        HoraireStandResolver.apply(stands, grille);
    }

    /** Every {@code pas}-th stand reopened on {@code [debut, fin)}, at {@code effectif} or what it inherits. */
    private static ConsigneEdition consigne(
            LocalDate date,
            LocalTime bandeDebut,
            LocalTime bandeFin,
            List<Stand> stands,
            int pas,
            int debut,
            int fin,
            Integer effectif) {
        List<ConsigneEdition.Ouverture> ouvertures = new ArrayList<>();
        for (int i = 0; i < stands.size(); i += pas) {
            ouvertures.add(new ConsigneEdition.Ouverture(
                    stands.get(i).getId(), LocalTime.of(debut, 0), LocalTime.of(fin, 0), effectif));
        }
        return new ConsigneEdition(
                date, bandeDebut, bandeFin, "Arrêté", null, List.of(), ouvertures, List.of(), null, null, null);
    }
}
