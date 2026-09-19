package dev.sylvain.planning.service.export;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.PauseAnalyzer;
import dev.sylvain.planning.service.edition.EtiquetteEdition;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The subscribed calendar says which édition it is (issue #608).
 *
 * <p>An animateur who subscribed two years running has two feeds in the same
 * client. Before this, neither carried a name at all: {@code X-WR-CALNAME} was
 * simply absent, so both showed up under whatever the client makes up from the
 * URL — the same nothing, twice.</p>
 */
class PlanningIcsEditionTest {

    private static final EtiquetteEdition FESTIVAL_26 =
            new EtiquetteEdition("Festival 26", LocalDate.parse("2026-02-01"), LocalDate.parse("2026-02-16"));

    private static PlanningEvenement planningWithOnePoste() {
        Creneau creneau = new Creneau();
        creneau.setId(1L);
        creneau.setJour(1);
        creneau.setDate(LocalDate.of(2026, 2, 8));
        creneau.setHeureDebut(LocalTime.of(9, 0));
        creneau.setHeureFin(LocalTime.of(13, 0));

        Stand stand = new Stand();
        stand.setId("STAND-A");
        stand.setNom("Stand A");

        Animateur animateur = new Animateur();
        animateur.setId("A1");
        animateur.setPrenom("Alice");
        animateur.setNom("Referente");

        PosteAffectation poste = new PosteAffectation();
        poste.setId("42");
        poste.setCreneau(creneau);
        poste.setStand(stand);
        poste.setAnimateur(animateur);

        PlanningEvenement planning = new PlanningEvenement();
        planning.setPostes(List.of(poste));
        planning.setAnimateurs(List.of(animateur));
        return planning;
    }

    private static String ics(EtiquetteEdition edition) {
        return new PlanningIcs()
                .exportAnimateurIcs(planningWithOnePoste(), "A1", List.<PauseAnalyzer.PauseAnimateurView>of(), edition);
    }

    @Test
    void namesTheCalendarAfterTheEditionAndItsDates() {
        assertThat(ics(FESTIVAL_26))
                .contains("X-WR-CALNAME:Mon planning — Festival 26 — du 1er au 16 février 2026\r\n");
    }

    @Test
    void repeatsTheEditionInTheDescriptionTheClientShowsUnderTheName() {
        assertThat(ics(FESTIVAL_26)).contains("X-WR-CALDESC:Festival 26 — du 1er au 16 février 2026 — Généré le ");
    }

    /**
     * An unreadable édition leaves the calendar unnamed rather than named
     * « — » : a client showing an empty title is worse than one falling back on
     * the URL, which is what it did before.
     */
    @Test
    void writesNoNameAtAllWhenTheEditionCannotBeRead() {
        String ics = ics(EtiquetteEdition.INCONNUE);

        assertThat(ics).doesNotContain("X-WR-CALNAME");
        assertThat(ics).contains("X-WR-CALDESC:Généré le ");
    }

    /** Two éditions, two calendars: the name is what tells them apart in the sidebar. */
    @Test
    void twoEditionsGiveTwoDifferentCalendarNames() {
        EtiquetteEdition festival25 =
                new EtiquetteEdition("Festival 25", LocalDate.parse("2025-02-02"), LocalDate.parse("2025-02-17"));

        assertThat(ics(FESTIVAL_26)).contains("Festival 26");
        assertThat(ics(festival25)).contains("Festival 25 — du 2 au 17 février 2025");
    }

    /** The name is content, so its separators follow the format's escaping rules. */
    @Test
    void escapesTheEditionNameLikeAnyOtherIcsText() {
        String ics = ics(new EtiquetteEdition("Festival 26, la revanche", null, null));

        assertThat(ics).contains("X-WR-CALNAME:Mon planning — Festival 26\\, la revanche\r\n");
    }
}
