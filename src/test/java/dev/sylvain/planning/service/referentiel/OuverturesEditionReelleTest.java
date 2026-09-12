package dev.sylvain.planning.service.referentiel;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Creneau.SegmentOuvert;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeJoursHoraire;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The openings of a real edition, frozen: 65 stands, 157 recurring rules, 27
 * dated exceptions and 62 créneaux, exactly as an organiser typed them.
 *
 * <p>The unit tests around this package each pin one rule of the layering. What
 * they cannot pin is the layering meeting itself at scale — a stand closing
 * "from 09:00 every day" so that a dated opening list can mean "and nothing
 * else", two rules of equal specificity splitting a fortnight between them, a
 * one-off exception on a day rules already govern. This test is the safety net
 * for changing how openings are <b>entered</b>: whatever the entry becomes, the
 * matrix of open segments this edition resolves to must not move by one minute
 * or one seat.</p>
 *
 * <p>The fixture is structural and anonymous — stands are {@code S01}…{@code S65},
 * nothing carries a name, a place or an organisation. What matters here is the
 * shape of the rules, not who wrote them.</p>
 */
class OuverturesEditionReelleTest {

    private static final String FIXTURE = "/ouvertures/edition-reelle.json";
    private static final Path EMPREINTE = Path.of("src/test/resources/ouvertures/edition-reelle-empreinte.txt");

    /** The edition as read from the fixture: its stands, its créneaux, nothing resolved yet. */
    record Edition(List<Stand> stands, List<Creneau> creneaux) {}

    @Test
    void lesOuverturesResoluesSontCellesDeLEmpreinteGelee() throws IOException {
        Edition edition = readEdition();
        HoraireStandResolver.apply(edition.stands(), edition.creneaux());

        String empreinte = empreinte(edition);

        assertThat(Files.readString(EMPREINTE, StandardCharsets.UTF_8)).as("""
                        Les ouvertures de l'édition de référence ont changé.

                        Si c'est voulu — une simplification de la saisie qui produit \
                        sciemment d'autres ouvertures — régénérer le fichier :
                            src/test/resources/ouvertures/edition-reelle-empreinte.txt
                        Sinon, la modification perd des ouvertures : c'est le défaut \
                        que ce test existe pour attraper.""").isEqualTo(empreinte);
    }

    /**
     * The numbers the fixture is worth quoting by: they say at a glance whether
     * a reading of the edition is the one this test was written against.
     */
    @Test
    void lEditionDeReferencePorteBienLaMasseAttendue() throws IOException {
        Edition edition = readEdition();

        assertThat(edition.stands()).hasSize(65);
        assertThat(edition.creneaux()).hasSize(62);
        assertThat(edition.stands().stream()
                        .mapToInt(stand -> stand.getHoraires().size())
                        .sum())
                .isEqualTo(157);
        assertThat(edition.stands().stream()
                        .mapToInt(stand -> stand.getOuvertures().size()
                                + stand.getIndisponibilites().size())
                        .sum())
                .isEqualTo(27);
    }

    /**
     * The point of the whole change, measured on the edition it was measured
     * on: once a stand that declares openings is closed where it declares
     * none, a third of its rules stop carrying anything — and not one minute
     * of opening moves.
     */
    @Test
    void lElagageRetireUnTiersDesReglesSansDeplacerUneOuverture() throws IOException {
        Edition edition = readEdition();
        HoraireStandResolver.apply(edition.stands(), edition.creneaux());
        String avant = empreinte(edition);

        List<HoraireElagage.LigneElagage> lignes = HoraireElagage.elaguer(edition.stands(), edition.creneaux());

        int restantes = edition.stands().stream()
                .mapToInt(stand -> stand.getHoraires().size())
                .sum();
        assertThat(restantes).isEqualTo(101);
        assertThat(lignes.stream().filter(HoraireElagage.LigneElagage::elague).count())
                .isEqualTo(56);
        HoraireStandResolver.apply(edition.stands(), edition.creneaux());
        assertThat(empreinte(edition)).isEqualTo(avant);
    }

    /* ------------------------------ empreinte ------------------------------ */

    /**
     * One line per open segment: stand, créneau, and the segment as minutes
     * from the créneau's start with its resolved headcount and the seats it
     * generates. A closed cell prints nothing — the file then says only where
     * somebody works, which is the thing that must not change.
     */
    static String empreinte(Edition edition) {
        StringBuilder sortie = new StringBuilder();
        for (Stand stand : edition.stands()) {
            for (Creneau creneau : edition.creneaux()) {
                for (SegmentOuvert segment : creneau.segmentsOuverts(stand)) {
                    sortie.append(stand.getId())
                            .append('|')
                            .append(creneau.getDate())
                            .append('|')
                            .append(creneau.getHeureDebut())
                            .append('-')
                            .append(creneau.getHeureFin())
                            .append('|')
                            .append(segment.debutMinutes())
                            .append("..")
                            .append(segment.finMinutes())
                            .append("|effectif=")
                            .append(segment.effectif())
                            .append("|sieges=")
                            .append(creneau.siegesSegment(segment.effectif()))
                            .append('\n');
                }
            }
        }
        return sortie.toString();
    }

    /* -------------------------------- lecture ------------------------------- */

    static Edition readEdition() throws IOException {
        JsonNode racine;
        try (InputStream flux = OuverturesEditionReelleTest.class.getResourceAsStream(FIXTURE)) {
            if (flux == null) {
                throw new UncheckedIOException(new IOException("fixture absente : " + FIXTURE));
            }
            racine = new ObjectMapper().readTree(flux);
        }
        List<Creneau> creneaux = new ArrayList<>();
        long id = 1;
        for (JsonNode noeud : racine.get("creneaux")) {
            Creneau creneau = new Creneau(
                    id++,
                    0,
                    LocalDate.parse(noeud.get("date").asText()),
                    LocalTime.parse(noeud.get("heureDebut").asText()),
                    LocalTime.parse(noeud.get("heureFin").asText()));
            creneau.setCouverturePause(noeud.path("couverturePause").asBoolean(false));
            creneaux.add(creneau);
        }
        List<Stand> stands = new ArrayList<>();
        for (JsonNode noeud : racine.get("stands")) {
            stands.add(stand(noeud));
        }
        return new Edition(stands, creneaux);
    }

    private static Stand stand(JsonNode noeud) {
        Stand stand = new Stand();
        stand.setId(noeud.get("id").asText());
        stand.setNom(noeud.get("id").asText());
        stand.setEffectifMin(noeud.get("effectifMin").asInt());
        stand.setEffectifMax(noeud.get("effectifMax").asInt());
        List<HoraireStand> horaires = new ArrayList<>();
        for (JsonNode regle : noeud.path("horaires")) {
            horaires.add(horaire(regle));
        }
        stand.setHoraires(horaires);
        List<OuvertureStand> ouvertures = new ArrayList<>();
        for (JsonNode fenetre : noeud.path("ouvertures")) {
            ouvertures.add(new OuvertureStand(
                    null,
                    LocalDate.parse(fenetre.get("date").asText()),
                    LocalTime.parse(fenetre.get("heureDebut").asText()),
                    heure(fenetre, "heureFin"),
                    null,
                    entier(fenetre, "effectif")));
        }
        stand.setOuvertures(ouvertures);
        List<IndisponibiliteStand> fermetures = new ArrayList<>();
        for (JsonNode fenetre : noeud.path("indisponibilites")) {
            fermetures.add(new IndisponibiliteStand(
                    null,
                    LocalDate.parse(fenetre.get("date").asText()),
                    LocalTime.parse(fenetre.get("heureDebut").asText()),
                    heure(fenetre, "heureFin"),
                    null));
        }
        stand.setIndisponibilites(fermetures);
        return stand;
    }

    private static HoraireStand horaire(JsonNode noeud) {
        HoraireStand horaire = new HoraireStand();
        horaire.setMode(ModeHoraire.valueOf(noeud.get("mode").asText()));
        horaire.setJours(TypeJoursHoraire.valueOf(noeud.get("jours").asText()));
        TreeSet<DayOfWeek> joursSemaine = new TreeSet<>();
        for (JsonNode jour : noeud.path("joursSemaine")) {
            joursSemaine.add(DayOfWeek.valueOf(jour.asText()));
        }
        horaire.setJoursSemaine(joursSemaine);
        if (noeud.hasNonNull("dateDebut")) {
            horaire.setDateDebut(LocalDate.parse(noeud.get("dateDebut").asText()));
        }
        if (noeud.hasNonNull("dateFin")) {
            horaire.setDateFin(LocalDate.parse(noeud.get("dateFin").asText()));
        }
        TreeSet<LocalDate> dates = new TreeSet<>();
        for (JsonNode date : noeud.path("dates")) {
            dates.add(LocalDate.parse(date.asText()));
        }
        horaire.setDates(dates);
        List<FenetreHoraire> fenetres = new ArrayList<>();
        for (JsonNode fenetre : noeud.path("fenetres")) {
            fenetres.add(new FenetreHoraire(
                    LocalTime.parse(fenetre.get("heureDebut").asText()),
                    heure(fenetre, "heureFin"),
                    entier(fenetre, "effectif")));
        }
        horaire.setFenetres(fenetres);
        return horaire;
    }

    private static LocalTime heure(JsonNode noeud, String champ) {
        return noeud.hasNonNull(champ) ? LocalTime.parse(noeud.get(champ).asText()) : null;
    }

    private static Integer entier(JsonNode noeud, String champ) {
        return noeud.hasNonNull(champ) ? noeud.get(champ).asInt() : null;
    }

    /** Regenerates the frozen fingerprint; run by hand when a change is meant to move it. */
    public static void main(String[] args) throws IOException {
        Edition edition = readEdition();
        HoraireStandResolver.apply(edition.stands(), edition.creneaux());
        Files.writeString(EMPREINTE, empreinte(edition), StandardCharsets.UTF_8);
    }
}
