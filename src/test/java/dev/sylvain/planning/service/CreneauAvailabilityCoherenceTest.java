package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.service.solve.PlanningWhatIf.AnimateurAvailability;
import dev.sylvain.planning.service.solve.PlanningWhatIf.CreneauAvailability;
import dev.sylvain.planning.service.solve.PlanningWhatIf.MotifExclusion;
import dev.sylvain.planning.service.solve.PlanningWhatIf.SeatStatus;
import dev.sylvain.planning.service.solve.PlanningWhatIf.SuggestionReparation;
import dev.sylvain.planning.service.solve.PlanningWhatIf.SuggestionsReparation;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import dev.sylvain.planning.service.solve.PlanningService;
import dev.sylvain.planning.service.solve.PlanningWhatIf;

/**
 * The acceptance criterion of issue #303, written down: an animateur the
 * « banc de touche » declares out of reach for a seat must never be a viable
 * candidate for that same seat in {@link PlanningService#suggererReparations},
 * and the other way round.
 *
 * <p>It is worth a test of its own because the two features answer the same
 * question from opposite ends — one lists who cannot, the other looks for who
 * can — and the whole point of the issue is that they must not become two
 * opinions. They cannot, structurally: both filter on
 * {@code EligibleAnimateurMoveFilter} and both read the hard-score delta of
 * the same hypothesis. This test is what keeps that true after the next edit.</p>
 *
 * <p><b>What is pinned is an implication, not an equality</b>, and that is a
 * deliberate retreat from an earlier, false claim. The assistant applies two
 * filters — a plan-wide hard-score test, and « no hard violation introduced on
 * this very seat », the second one read off the per-seat constraint
 * <i>matches</i>. Reproducing the second exactly here would mean
 * re-implementing that match analysis, which is the duplication issue #303
 * exists to remove. The screen instead applies its own, <b>stricter</b>
 * instrument (no hard constraint worsened at all, measured against the seat
 * being empty), so what holds is: anyone shown as {@code disponible} is a
 * candidate the assistant proposes. The converse is false, on purpose.</p>
 */
class CreneauAvailabilityCoherenceTest {

    private static final LocalDate JOUR = LocalDate.of(2026, 7, 16);
    private static final long CRENEAU_CIBLE = 1L;

    private final PlanningService planningService = new PlanningService(3L, 2L,
            ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT,
            new EmptyReferenceData(), new FeasibilityAnalyzer(), ConfigProvider.getConfig());

    /**
     * The criterion: anyone this screen shows as available is a candidate the
     * repair assistant proposes for the same seat. Restricted to the population
     * the banc covers — the assistant also weighs animateurs already busy on
     * that créneau, who are by definition not on the bench.
     */
    @Test
    void anyoneShownAsAvailableIsAlsoACandidateForTheRepairAssistant() {
        PlanningEvenement planning = planning();

        CreneauAvailability banc = planningService.creneauAvailability(planning, CRENEAU_CIBLE, null, null);
        SuggestionsReparation reparations = planningService.suggererReparations(planning, banc.posteCibleId(),
                PlanningService.SUGGESTIONS_PLAFOND_MAX);

        Set<String> proposes = reparations.suggestions().stream()
                .map(SuggestionReparation::animateurId)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(ids(banc.animateurs().stream().filter(AnimateurAvailability::disponible)))
                .isNotEmpty()
                .allMatch(proposes::contains);
        assertThat(banc.animateurs()).noneMatch(ligne -> ligne.disponible() && ligne.degradeLePlan());
    }

    /**
     * The case that made the equality claim false, and the one the assistant's
     * second filter exists for: an ad hoc {@code INDISPONIBILITE_FORCEE} is a
     * hard rule of weight 1 that {@code EligibleAnimateurMoveFilter} does not
     * know about. Filling an empty seat with that animateur settles
     * {@code posteDoitEtrePourvu} (+1) and spends it on
     * {@code indisponibiliteForcee} (−1): the plan-wide hard score comes out
     * flat, so a screen reading only that score would announce « disponible ».
     *
     * <p>Reading per-constraint totals instead is what catches it, and it must
     * keep catching it whatever the weights happen to be.</p>
     */
    @Test
    void anAdHocBanIsSeenEvenWhenItCostsThePlanNothingOverall() {
        PlanningEvenement planning = planningWithAdHocBan();

        CreneauAvailability banc = planningService.creneauAvailability(planning, CRENEAU_CIBLE, null, null);

        AnimateurAvailability interdit = row(banc, "A-INTERDIT");
        assertThat(constraintNames(banc, "A-INTERDIT")).contains("indisponibiliteForcee");
        assertThat(interdit.disponible()).isFalse();
        // The very reason the screen cannot lean on this verdict alone.
        assertThat(interdit.delta().hardScore()).isZero();
        assertThat(interdit.degradeLePlan()).isFalse();
    }

    /**
     * The graver half of the same lesson, on an <b>occupied</b> seat — the
     * normal case on a solved plan.
     *
     * <p>Measured against the seat as it stands, a candidate who breaks exactly
     * the rule its occupant already breaks leaves the constraint's total flat,
     * and the answer used to come back with <b>no motif at all</b>: « Disponible »
     * for somebody physically on duty elsewhere at that hour. Measuring against
     * the seat <em>empty</em> is what removes the whole class, and this is the
     * fixture that proves it: {@code A-TITULAIRE} holds the target seat and an
     * overlapping one, {@code A-DOUBLE} holds an overlapping one too, so the
     * swap keeps {@code pasDeChevauchementHoraire} at exactly one match.</p>
     */
    @Test
    void aClashIsSeenEvenWhenTheOccupantAlreadyHasTheSameOne() {
        PlanningEvenement planning = planningWithDoubleBookedOccupant();

        CreneauAvailability banc = planningService.creneauAvailability(planning, CRENEAU_CIBLE, null, null);

        assertThat(banc.animateurCibleId()).isEqualTo("A-TITULAIRE");
        assertThat(constraintNames(banc, "A-DOUBLE")).contains("pasDeChevauchementHoraire");
        assertThat(row(banc, "A-DOUBLE").disponible()).isFalse();
        // Flat plan-wide: one clash traded for another. That is exactly why the
        // per-constraint totals have to be read against an empty seat.
        assertThat(row(banc, "A-DOUBLE").delta().hardScore()).isZero();
    }

    /**
     * And the case that makes the two verdicts necessary: someone physically on
     * duty elsewhere at that hour is never « disponible », even when the score
     * says handing them the empty seat costs the plan nothing.
     */
    @Test
    void aPhysicalClashIsNeverShownAsAvailableEvenWhenItIsScoreNeutral() {
        CreneauAvailability banc = planningService.creneauAvailability(planning(), CRENEAU_CIBLE, null, null);

        AnimateurAvailability chevauche = row(banc, "A-CHEVAUCHE");
        assertThat(chevauche.disponible()).isFalse();
        assertThat(constraintNames(banc, "A-CHEVAUCHE")).contains("pasDeChevauchementHoraire");
    }

    /** The seat probed is the créneau's free one, and everyone on duty then is off the list. */
    @Test
    void theBenchHoldsExactlyTheAnimateursNotOnDutyOnThatCreneau() {
        CreneauAvailability banc = planningService.creneauAvailability(planning(), CRENEAU_CIBLE, null, null);

        assertThat(banc.posteCibleId()).isEqualTo("P-LIBRE");
        assertThat(banc.animateurCibleId()).isNull();
        assertThat(banc.animateurs()).extracting(AnimateurAvailability::animateurId)
                .doesNotContain("A-TITULAIRE")
                .contains("A-DISPO", "A-INDISPONIBLE", "A-CHEVAUCHE", "A-PLAFOND", "A-CUMUL");
        assertThat(banc.total()).isEqualTo(banc.animateurs().size());
        assertThat(banc.disponibles()).isEqualTo(
                (int) banc.animateurs().stream().filter(AnimateurAvailability::disponible).count());
    }

    /**
     * Reasons are the names of the rules that refuse, taken from
     * {@code LegalConstraints} / {@code AffectationConstraints} through the
     * filter and the hypotheses — the four families the issue asks to tell
     * apart, each carrying the wording {@code ConstraintCatalog} holds for it.
     */
    @Test
    void eachRefusalNamesTheConstraintThatCausesIt() {
        CreneauAvailability banc = planningService.creneauAvailability(planning(), CRENEAU_CIBLE, null, null);

        assertThat(constraintNames(banc, "A-INDISPONIBLE")).contains("animateurDisponible");
        assertThat(constraintNames(banc, "A-CHEVAUCHE")).contains("pasDeChevauchementHoraire");
        assertThat(constraintNames(banc, "A-PLAFOND")).contains("dureeQuotidienneMaxMajeur");
        assertThat(row(banc, "A-DISPO").disponible()).isTrue();
        assertThat(row(banc, "A-DISPO").motifs()).noneMatch(motif -> "HARD".equals(motif.niveau()));

        assertThat(row(banc, "A-INDISPONIBLE").motifs())
                .allSatisfy(motif -> {
                    assertThat(motif.niveau()).isNotNull();
                    assertThat(motif.categorie()).isNotNull();
                    assertThat(motif.description()).isNotBlank();
                });
    }

    /**
     * All applicable reasons, not only the most blocking one: knowing that
     * lifting the indisponibilité would still leave an overlap behind is the
     * difference between a seat worth negotiating and one that is not.
     */
    @Test
    void anAnimateurRefusedForSeveralReasonsGetsAllOfThem() {
        CreneauAvailability banc = planningService.creneauAvailability(planning(), CRENEAU_CIBLE, null, null);

        assertThat(constraintNames(banc, "A-CUMUL"))
                .contains("animateurDisponible", "pasDeChevauchementHoraire")
                .hasSizeGreaterThan(1);
    }

    /** Narrowing to a stand picks that stand's free seat; an unknown one is a 404, not a silent fallback. */
    @Test
    void theProbedSeatCanBeNarrowedToOneStand() {
        PlanningEvenement planning = planning();

        assertThat(planningService.creneauAvailability(planning, CRENEAU_CIBLE, "S-VOISIN", null).posteCibleId())
                .isEqualTo("P-VOISIN");
        assertThatThrownBy(() -> planningService.creneauAvailability(planning, CRENEAU_CIBLE, null, "P-AILLEURS"))
                .isInstanceOf(BusinessError.Invalid.class);
    }

    /**
     * The regression this screen shipped with: the selector is fed by the
     * referential, which holds more créneaux than the plan does, so it offered
     * créneaux carrying no seat — and the answer was a {@code NotFound} the
     * user could not act on, on the very first render.
     *
     * <p>Having nothing to show is one of the answers this screen exists to
     * give, so it is now said rather than thrown.</p>
     */
    @Test
    void aCreneauTheSavedPlanHoldsNoSeatOnIsAnAnswerNotARefusal() {
        PlanningEvenement planning = planning();

        CreneauAvailability sansSiege = planningService.creneauAvailability(planning, 99L, null, null);

        assertThat(sansSiege.statut()).isEqualTo(SeatStatus.NO_SEAT);
        assertThat(sansSiege.creneauId()).isEqualTo(99L);
        assertThat(sansSiege.posteCibleId()).isNull();
        assertThat(sansSiege.standCibleId()).isNull();
        assertThat(sansSiege.animateurCibleId()).isNull();
        assertThat(sansSiege.animateurs()).isEmpty();
        assertThat(sansSiege.total()).isZero();
        assertThat(sansSiege.disponibles()).isZero();
    }

    /** Same for a stand that exists but is not open on that créneau. */
    @Test
    void aStandWithNoSeatOnThatCreneauIsAnAnswerToo() {
        CreneauAvailability sansSiege =
                planningService.creneauAvailability(planning(), CRENEAU_CIBLE, "S-INCONNU", null);

        assertThat(sansSiege.statut()).isEqualTo(SeatStatus.NO_SEAT);
        assertThat(sansSiege.animateurs()).isEmpty();
    }

    /** Nothing saved at all deserves its own wording: « lancez une résolution », not « changez de créneau ». */
    @Test
    void anEmptyPlanIsToldApartFromACreneauWithoutSeats() {
        PlanningEvenement vide = new PlanningEvenement(JOUR, List.of(), List.of());

        CreneauAvailability sansPlan = planningService.creneauAvailability(vide, CRENEAU_CIBLE, null, null);

        assertThat(sansPlan.statut()).isEqualTo(SeatStatus.NO_PLAN);
        assertThat(sansPlan.creneauxAvecSieges()).isEmpty();
        assertThat(sansPlan.animateurs()).isEmpty();
    }

    /**
     * What lets the screen point at a créneau worth opening instead of leaving
     * the user to try them one by one — the fix's other half.
     */
    @Test
    void theAnswerNamesEveryCreneauTheSavedPlanHoldsASeatOn() {
        CreneauAvailability banc = planningService.creneauAvailability(planning(), CRENEAU_CIBLE, null, null);

        assertThat(banc.statut()).isEqualTo(SeatStatus.EVALUATED);
        assertThat(banc.creneauxAvecSieges()).extracting(PlanningWhatIf.CreneauSiege::id)
                .containsExactly(1L, 2L, 3L, 4L);
        // Described, not merely named: the selector is built from this alone,
        // so the screen never reads the créneau referential.
        assertThat(banc.creneauxAvecSieges()).allSatisfy(creneau -> {
            assertThat(creneau.date()).isNotNull();
            assertThat(creneau.heureDebut()).isNotNull();
            assertThat(creneau.heureFin()).isNotNull();
        });
    }

    /**
     * Asked for no créneau in particular, the answer picks one that has
     * something to show. The screen cannot name a valid créneau before its
     * first call — its selector only offers staffed ones, which this very
     * answer carries — so guessing is the server's job, not its.
     */
    @Test
    void withoutACreneauTheAnswerPicksAStaffedOne() {
        CreneauAvailability banc = planningService.creneauAvailability(planning(), null, null, null);

        assertThat(banc.statut()).isEqualTo(SeatStatus.EVALUATED);
        assertThat(banc.creneauId()).isEqualTo(CRENEAU_CIBLE);
        assertThat(banc.posteCibleId()).isNotNull();
    }

    /**
     * Nothing staffed at all: no créneau to offer and none to fall back on. The
     * selector then has nothing to show, so the answer has to say why rather
     * than leave an empty list to be interpreted.
     */
    @Test
    void withNothingStaffedThereIsNoCreneauToOfferAndTheAnswerSaysSo() {
        PlanningEvenement vide = new PlanningEvenement(JOUR, List.of(), List.of());

        CreneauAvailability banc = planningService.creneauAvailability(vide, null, null, null);

        assertThat(banc.statut()).isEqualTo(SeatStatus.NO_PLAN);
        assertThat(banc.creneauId()).isNull();
        assertThat(banc.creneauxAvecSieges()).isEmpty();
        assertThat(banc.animateurs()).isEmpty();
    }

    /** Read-only: asking the question must not move a single seat. */
    @Test
    void askingTheQuestionChangesNothingInThePlan() {
        PlanningEvenement planning = planning();
        List<String> avant = occupants(planning);

        planningService.creneauAvailability(planning, CRENEAU_CIBLE, null, null);

        assertThat(occupants(planning)).isEqualTo(avant);
    }

    private static List<String> occupants(PlanningEvenement planning) {
        return planning.getPostes().stream()
                .map(poste -> poste.getId() + "="
                        + (poste.getAnimateur() == null ? "-" : poste.getAnimateur().getId()))
                .toList();
    }

    private static Set<String> ids(java.util.stream.Stream<AnimateurAvailability> lignes) {
        return lignes.map(AnimateurAvailability::animateurId).collect(java.util.stream.Collectors.toSet());
    }

    private static List<String> constraintNames(CreneauAvailability banc, String animateurId) {
        return row(banc, animateurId).motifs().stream().map(MotifExclusion::contrainte).toList();
    }

    private static AnimateurAvailability row(CreneauAvailability banc, String animateurId) {
        return banc.animateurs().stream()
                .filter(ligne -> ligne.animateurId().equals(animateurId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Absent du banc de touche : " + animateurId));
    }

    /**
     * An empty seat, one animateur banned from it by an ad hoc rule, and one
     * with nothing against them. Nothing else, so the arithmetic stays legible:
     * the ban costs exactly the hard point filling the seat earns back.
     */
    private static PlanningEvenement planningWithAdHocBan() {
        Stand stand = new Stand("S-CIBLE", "Chamboule-tout", Set.of(), 1, 2, false);
        Creneau matin = new Creneau(CRENEAU_CIBLE, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(13, 0));
        Animateur interdit = new Animateur("A-INTERDIT", "Inès", "Durand", LocalDate.of(1990, 1, 1), false);
        Animateur libre = new Animateur("A-LIBRE", "Léa", "Martin", LocalDate.of(1990, 1, 1), false);

        PosteAffectation vide = new PosteAffectation("P-LIBRE", stand, matin);
        ContrainteAdHoc interdiction = new ContrainteAdHoc("C-BAN", TypeContrainteAdHoc.INDISPONIBILITE_FORCEE);
        interdiction.setAnimateursConcernes(List.of(interdit));
        interdiction.setCreneau(matin);
        interdiction.setRaison("Écarté de ce créneau par l'organisateur");

        return new PlanningEvenement(JOUR, List.of(interdit, libre), List.of(vide), List.of(interdiction));
    }

    /**
     * The occupied-seat trap: the seat's own occupant is already double-booked
     * on an overlapping créneau, and so is the candidate. Swapping them keeps
     * {@code pasDeChevauchementHoraire} at one match, so any reading based on
     * the seat as it stands reports nothing at all.
     */
    private static PlanningEvenement planningWithDoubleBookedOccupant() {
        Stand cible = new Stand("S-CIBLE", "Chamboule-tout", Set.of(), 1, 2, false);
        Stand ailleurs = new Stand("S-AILLEURS", "Molkky", Set.of(), 1, 2, false);
        Creneau matin = new Creneau(CRENEAU_CIBLE, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(13, 0));
        Creneau chevauchant = new Creneau(2L, 1, JOUR, LocalTime.of(12, 0), LocalTime.of(15, 0));

        Animateur titulaire = new Animateur("A-TITULAIRE", "Théo", "Roux", LocalDate.of(1990, 1, 1), false);
        Animateur doublon = new Animateur("A-DOUBLE", "Dina", "Martin", LocalDate.of(1990, 1, 1), false);

        PosteAffectation siege = new PosteAffectation("P-CIBLE", cible, matin);
        siege.setAnimateur(titulaire);
        PosteAffectation autreDuTitulaire = new PosteAffectation("P-AUTRE-1", ailleurs, chevauchant);
        autreDuTitulaire.setAnimateur(titulaire);
        PosteAffectation autreDuDoublon = new PosteAffectation("P-AUTRE-2", ailleurs, chevauchant);
        autreDuDoublon.setAnimateur(doublon);

        return new PlanningEvenement(JOUR, List.of(titulaire, doublon),
                List.of(siege, autreDuTitulaire, autreDuDoublon));
    }

    /**
     * One morning créneau with a free seat, and a cast built so every family of
     * refusal is represented once: nothing against them, unavailable that day,
     * busy on an overlapping créneau, already close to the daily cap, and one
     * cumulating two reasons.
     */
    private static PlanningEvenement planning() {
        Stand cible = new Stand("S-CIBLE", "Chamboule-tout", Set.of(), 1, 2, false);
        Stand voisin = new Stand("S-VOISIN", "Pêche aux canards", Set.of(), 1, 1, false);
        Stand ailleurs = new Stand("S-AILLEURS", "Molkky", Set.of(), 1, 2, false);

        Creneau matin = new Creneau(CRENEAU_CIBLE, 1, JOUR, LocalTime.of(10, 0), LocalTime.of(13, 0));
        Creneau chevauchant = new Creneau(2L, 1, JOUR, LocalTime.of(12, 0), LocalTime.of(15, 0));
        Creneau apresMidi = new Creneau(3L, 1, JOUR, LocalTime.of(14, 0), LocalTime.of(18, 0));
        Creneau soiree = new Creneau(4L, 1, JOUR, LocalTime.of(18, 30), LocalTime.of(22, 30));

        Animateur titulaire = new Animateur("A-TITULAIRE", "Théo", "Roux", LocalDate.of(1990, 1, 1), false);
        Animateur dispo = new Animateur("A-DISPO", "Dina", "Martin", LocalDate.of(1990, 1, 1), false);
        Animateur indisponible = new Animateur("A-INDISPONIBLE", "Inès", "Durand",
                LocalDate.of(1990, 1, 1), false);
        indisponible.setJoursIndisponibles(Set.of(JOUR));
        Animateur chevauche = new Animateur("A-CHEVAUCHE", "Chloé", "Bernard", LocalDate.of(1990, 1, 1), false);
        Animateur plafond = new Animateur("A-PLAFOND", "Paul", "Petit", LocalDate.of(1990, 1, 1), false);
        Animateur cumul = new Animateur("A-CUMUL", "Camille", "Leroy", LocalDate.of(1990, 1, 1), false);
        cumul.setJoursIndisponibles(Set.of(JOUR));

        PosteAffectation libre = new PosteAffectation("P-LIBRE", cible, matin);
        PosteAffectation tenu = new PosteAffectation("P-TENU", cible, matin);
        tenu.setAnimateur(titulaire);
        PosteAffectation libreVoisin = new PosteAffectation("P-VOISIN", voisin, matin);
        // A-CHEVAUCHE and A-CUMUL are on duty on a créneau that overlaps the
        // target one, so giving them the seat would be a physical clash.
        PosteAffectation surChevauchant = new PosteAffectation("P-AILLEURS", ailleurs, chevauchant);
        surChevauchant.setAnimateur(chevauche);
        PosteAffectation surChevauchantBis = new PosteAffectation("P-AILLEURS-2", ailleurs, chevauchant);
        surChevauchantBis.setAnimateur(cumul);
        // A-PLAFOND already works 8 h that day, in two stretches separated by a
        // legal break: three more hours would break the 10 h daily cap.
        PosteAffectation apres = new PosteAffectation("P-APRES", ailleurs, apresMidi);
        apres.setAnimateur(plafond);
        PosteAffectation soir = new PosteAffectation("P-SOIR", ailleurs, soiree);
        soir.setAnimateur(plafond);

        return new PlanningEvenement(JOUR,
                List.of(titulaire, dispo, indisponible, chevauche, plafond, cumul),
                List.of(libre, tenu, libreVoisin, surChevauchant, surChevauchantBis, apres, soir));
    }
}
