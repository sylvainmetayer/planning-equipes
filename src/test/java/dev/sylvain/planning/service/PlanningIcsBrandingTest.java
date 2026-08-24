package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;

/**
 * What the deployment's name does to an exported calendar.
 *
 * <p>{@code PRODID} and the domain part of a {@code UID} are read by machines,
 * not by people: they must hold no accent, no space and no empty segment,
 * whatever an operator typed into {@code BRANDING_PRODUCT_NAME}. A calendar
 * client that cannot parse them either refuses the file or, worse, silently
 * creates duplicate events on the next import.</p>
 *
 * <p>These identifiers are also <b>stable for a given name</b>: a
 * re-imported calendar updates its events instead of duplicating them only
 * because the same poste yields the same {@code UID}. Renaming the deployment
 * moves them on purpose — that consequence is written in the PR and in the
 * README, and this test is what makes it a decision rather than an accident.</p>
 */
class PlanningIcsBrandingTest {

    private static PlanningEvenement planningWithOnePoste() {
        Creneau creneau = new Creneau();
        creneau.setId(1L);
        creneau.setJour(1);
        creneau.setDate(LocalDate.of(2026, 7, 8));
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

    private static String icsFor(String productName) {
        return new PlanningIcs(new ProductName(productName))
                .exportAnimateurIcs(planningWithOnePoste(), "A1");
    }

    @Test
    void leProdIdEtLUidPortentLaMarqueDuDeploiement() {
        String ics = icsFor("Festival Machin");

        assertThat(ics).contains("PRODID:-//festival-machin//planning//FR");
        assertThat(ics).contains("UID:42@festival-machin");
    }

    @Test
    void lesAccentsEtLesEspacesSontReduitsAvantDentrerDansLeFichier() {
        String ics = icsFor("Planning Équipes");

        assertThat(ics).contains("PRODID:-//planning-equipes//planning//FR");
        assertThat(ics).contains("UID:42@planning-equipes");
    }

    @Test
    void laPonctuationNeLaisseNiSegmentVideNiTiretDeBord() {
        String ics = icsFor("  Festival  du  Jeu !  ");

        // Neither "festival--du--jeu-" nor a trailing hyphen: consecutive
        // separators collapse, and the edges are trimmed.
        assertThat(ics).contains("PRODID:-//festival-du-jeu//planning//FR");
        assertThat(ics).contains("UID:42@festival-du-jeu");
    }

    @Test
    void unNomSansAucuneLettreNiChiffreRetombeSurUnSlugUtilisable() {
        // An empty UID after the at-sign yields a file no client can file
        // away; a generic identifier beats nothing at all.
        String ics = icsFor("!!! ???");

        assertThat(ics).contains("PRODID:-//planning//planning//FR");
        assertThat(ics).contains("UID:42@planning");
    }

    @Test
    void deuxExportsDuMemePlanningDonnentLesMemesIdentifiants() {
        // This is what makes a re-imported calendar update its events instead
        // of duplicating them.
        assertThat(icsFor("Festival Machin")).isEqualTo(icsFor("Festival Machin"));
    }

    @Test
    void renommerLeDeploiementDeplaceLesIdentifiants() {
        // An accepted, documented consequence: a rebrand moves the UIDs, so a
        // re-import recreates the events.
        assertThat(icsFor("Festival Machin")).isNotEqualTo(icsFor("Festival Truc"));
    }

    @Test
    void leConstructeurSansArgumentResteSurLIdentiteNeutre() {
        String ics = new PlanningIcs().exportAnimateurIcs(planningWithOnePoste(), "A1");

        assertThat(ics).contains("PRODID:-//planning-equipes//planning//FR");
    }
}
