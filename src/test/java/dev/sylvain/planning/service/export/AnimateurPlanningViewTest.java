package dev.sylvain.planning.service.export;

import static org.assertj.core.api.Assertions.assertThat;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.referentiel.TypologieLibelles;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The three questions the individual document answers, checked on plain
 * objects rather than on the bytes of a PDF: what the festival looks like,
 * where the person goes, who they are with.
 *
 * <p>Both layouts render this view, so a rule broken here is broken on the
 * booklet <b>and</b> on the folded sheet — which is why it is tested once,
 * here, rather than twice through two text extractions.</p>
 */
class AnimateurPlanningViewTest {

    private static final TypologieLibelles TYPOLOGIES =
            () -> Map.of("ENFANCE", "Enfance", "LOGISTIQUE", "Logistique", "ACCUEIL", "Accueil");

    @Test
    void lesJoursDeLEditionSeSuiventReposCompris() {
        AnimateurPlanningView view = view(planning(), "A-ADA");

        assertThat(view.jours()).extracting(AnimateurPlanningView.Jour::numero).containsExactly(1, 2, 3);
        assertThat(view.jours()).extracting(AnimateurPlanningView.Jour::repos).containsExactly(false, true, false);
        assertThat(view.joursTravailles()).isEqualTo(2);
        assertThat(view.joursRepos()).isEqualTo(1);
        assertThat(view.periode()).isEqualTo("Du vendredi 14 au dimanche 16 août 2026 · 3 jours, dont 1 de repos");
    }

    /** The hours are the effective ones of the seat, and the day's total is their sum. */
    @Test
    void leTotalDHeuresEstCeluiDesHorairesEffectifs() {
        AnimateurPlanningView view = view(planning(), "A-ADA");

        assertThat(view.minutesTravaillees()).isEqualTo(10 * 60);
        assertThat(view.jours().get(0).minutesTravaillees()).isEqualTo(7 * 60);
        assertThat(AnimateurPlanningPdf.heures(view.minutesTravaillees())).isEqualTo("10");
        assertThat(AnimateurPlanningPdf.heures(150)).isEqualTo("2,5");
    }

    /** Met twice, someone comes before someone met once; ties are alphabetical. */
    @Test
    void lesCoequipiersSontTriesParNombreDeCreneauxCommuns() {
        AnimateurPlanningView view = view(planning(), "A-ADA");

        assertThat(view.coequipiers())
                .extracting(AnimateurPlanningView.Coequipier::nom)
                .containsExactly("Alan Turing", "Grace Hopper");
        assertThat(view.coequipiers().get(0).fois()).isEqualTo(2);
        assertThat(view.coequipiers().get(0).moments()).hasSize(2);
    }

    /**
     * Beyond the threshold, a shift says how many people are on it and names
     * none of them — and those names never reach the gathered list either.
     */
    @Test
    void auDelaDuSeuilUnPosteCompteSesPersonnesAuLieuDeLesNommer() {
        AnimateurPlanningView view = view(planningMontage(), "A-ADA");

        AnimateurPlanningView.Vacation montage = view.jours().get(0).vacations().get(0);
        assertThat(montage.nomme()).isFalse();
        assertThat(montage.equipeTexte()).isEqualTo("Avec " + AnimateurPlanningView.SEUIL_NOMS + " personnes");
        assertThat(montage.effectif()).isEqualTo(AnimateurPlanningView.SEUIL_NOMS + 1);
        assertThat(view.coequipiers()).isEmpty();
        assertThat(view.equipesNombreuses())
                .extracting(AnimateurPlanningView.EquipeNombreuse::standNom)
                .containsExactly("Montage du festival");
    }

    /** One below the threshold, everybody is named again. */
    @Test
    void justeSousLeSeuilLesNomsSontEcrits() {
        PlanningEvenement planning = planningMontage();
        planning.getPostes().removeIf(poste -> "renfort-1".equals(poste.getId()));

        AnimateurPlanningView view = view(planning, "A-ADA");

        assertThat(view.jours().get(0).vacations().get(0).nomme()).isTrue();
        assertThat(view.equipesNombreuses()).isEmpty();
        assertThat(view.coequipiers()).hasSize(AnimateurPlanningView.SEUIL_NOMS - 1);
    }

    /** The stands are grouped by place, « lieu non précisé » last. */
    @Test
    void lesStandsSontGroupesParLieuEtComptes() {
        AnimateurPlanningView view = view(planning(), "A-ADA");

        assertThat(view.lieux())
                .extracting(AnimateurPlanningView.Lieu::nom)
                .containsExactly("Kiosque Central", "Lieu non précisé");
        AnimateurPlanningView.StandUsage kiosque = view.lieux().get(0).stands().get(0);
        assertThat(kiosque.creneaux()).isEqualTo(1);
        assertThat(kiosque.minutes()).isEqualTo(3 * 60);
        assertThat(view.daysByLieu().get(0).dates()).hasSize(2);
    }

    /** The axis of the timeline is the event's own amplitude, rounded to the hour. */
    @Test
    void lAxeDeLaFriseEncadreLesHorairesDeLEdition() {
        AnimateurPlanningView view = view(planning(), "A-ADA");

        assertThat(view.amplitudeDebutMinutes()).isEqualTo(9 * 60);
        assertThat(view.amplitudeFinMinutes()).isEqualTo(19 * 60);
    }

    /** An animateur with no seat keeps the empty state: no day, no rest day, nothing. */
    @Test
    void unAnimateurSansAffectationDonneUneVueVide() {
        AnimateurPlanningView view = view(planning(), "A-GRACE-ABSENTE");

        assertThat(view.vide()).isTrue();
        assertThat(view.periode()).isEmpty();
    }

    /**
     * The colour of a typologie does not depend on the iteration order of the
     * map it was read from: two runs of the same planning must produce the same
     * document.
     */
    @Test
    void laCouleurDUneTypologieEstStable() {
        TypologiePalette premiere = TypologiePalette.of(TYPOLOGIES);
        TypologiePalette seconde = TypologiePalette.of(() -> Map.of(
                "LOGISTIQUE", "Logistique",
                "ACCUEIL", "Accueil",
                "ENFANCE", "Enfance"));

        assertThat(premiere.couleur("ENFANCE")).isEqualTo(seconde.couleur("ENFANCE"));
        assertThat(premiere.couleur("ACCUEIL")).isNotEqualTo(premiere.couleur("ENFANCE"));
        assertThat(premiere.libelle("ENFANCE")).isEqualTo("Enfance");
        // An id the referential dropped is still drawn, and drawn the same way.
        assertThat(premiere.couleur("DISPARUE")).isEqualTo(seconde.couleur("DISPARUE"));
        assertThat(premiere.libelle("DISPARUE")).isEqualTo("DISPARUE");
    }

    /** The legend names the typologies actually drawn, and nothing else. */
    @Test
    void laLegendeNeNommeQueLesTypologiesDessinees() {
        AnimateurPlanningView view = view(planning(), "A-ADA");

        assertThat(view.legende()).extracting(TypologiePalette.Entree::libelle).containsExactly("Accueil", "Enfance");
    }

    private static AnimateurPlanningView view(PlanningEvenement planning, String animateurId) {
        List<PosteAffectation> postes = planning.getPostes().stream()
                .filter(poste -> poste.getAnimateur() != null
                        && animateurId.equals(poste.getAnimateur().getId()))
                .sorted(PlanningExportService.byCreneauThenStand())
                .toList();
        return AnimateurPlanningView.build(
                "Ada Lovelace",
                postes,
                PlanningExportService.teammatesByPoste(planning, animateurId),
                PlanningExportService.daysOff(planning, animateurId),
                List.of(),
                List.of(),
                Map.of(),
                TypologiePalette.of(TYPOLOGIES));
    }

    /** Three event days, one of them Ada's rest day, two stands, one place named. */
    private static PlanningEvenement planning() {
        Stand accueil = new Stand("STAND-A", "Accueil", Set.of("ACCUEIL"), 1, 4, false);
        accueil.setEmplacement(new Emplacement("EMP-1", "Kiosque Central", 48.8566, 2.3522));
        Stand enfance = new Stand("STAND-E", "Construction", Set.of("ENFANCE"), 1, 4, false);

        Animateur ada = new Animateur("A-ADA", "Ada", "Lovelace", LocalDate.of(1990, 1, 1), false);
        Animateur alan = new Animateur("A-ALAN", "Alan", "Turing", LocalDate.of(1992, 2, 2), false);
        Animateur grace = new Animateur("A-GRACE", "Grace", "Hopper", LocalDate.of(1993, 3, 3), false);

        Creneau j1matin = new Creneau(1L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(9, 0), LocalTime.of(12, 0));
        Creneau j1soir = new Creneau(2L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(15, 0), LocalTime.of(19, 0));
        Creneau j2 = new Creneau(3L, 2, LocalDate.of(2026, 8, 15), LocalTime.of(9, 0), LocalTime.of(12, 0));
        Creneau j3 = new Creneau(4L, 3, LocalDate.of(2026, 8, 16), LocalTime.of(9, 0), LocalTime.of(12, 0));

        List<PosteAffectation> postes = new ArrayList<>();
        postes.add(poste("p1", accueil, j1matin, ada));
        postes.add(poste("p2", accueil, j1matin, alan));
        postes.add(poste("p3", enfance, j1soir, ada));
        postes.add(poste("p4", enfance, j1soir, grace));
        postes.add(poste("p5", enfance, j2, alan));
        postes.add(poste("p6", enfance, j3, ada));
        postes.add(poste("p7", enfance, j3, alan));
        return new PlanningEvenement(LocalDate.of(2026, 8, 14), new ArrayList<>(List.of(ada, alan, grace)), postes);
    }

    /** A set-up exactly one person over the threshold. */
    private static PlanningEvenement planningMontage() {
        Stand montage = new Stand("STAND-M", "Montage du festival", Set.of("LOGISTIQUE"), 1, 20, false);
        Creneau creneau = new Creneau(1L, 1, LocalDate.of(2026, 8, 14), LocalTime.of(13, 0), LocalTime.of(16, 0));
        Animateur ada = new Animateur("A-ADA", "Ada", "Lovelace", LocalDate.of(1990, 1, 1), false);
        List<Animateur> animateurs = new ArrayList<>(List.of(ada));
        List<PosteAffectation> postes = new ArrayList<>(List.of(poste("ada", montage, creneau, ada)));
        for (int i = 1; i <= AnimateurPlanningView.SEUIL_NOMS; i++) {
            Animateur renfort = new Animateur("A-R" + i, "Renfort", String.valueOf(i), LocalDate.of(1990, 1, 1), false);
            animateurs.add(renfort);
            postes.add(poste("renfort-" + i, montage, creneau, renfort));
        }
        return new PlanningEvenement(LocalDate.of(2026, 8, 14), animateurs, postes);
    }

    private static PosteAffectation poste(String id, Stand stand, Creneau creneau, Animateur animateur) {
        PosteAffectation poste = new PosteAffectation(id, stand, creneau);
        poste.setAnimateur(animateur);
        return poste;
    }
}
