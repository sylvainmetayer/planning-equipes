package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.referentiel.ContrainteAdHocContradictions;
import dev.sylvain.planning.service.referentiel.ContrainteAdHocContradictions.Contradiction;
import dev.sylvain.planning.service.referentiel.ForcedAssignmentOnDayOff;
import dev.sylvain.planning.service.referentiel.ForcedAssignmentOnExcludedSeats;
import dev.sylvain.planning.service.referentiel.ForcedAssignmentOnLockedSchedule;
import dev.sylvain.planning.solver.ConstraintCatalog;
import dev.sylvain.planning.solver.EligibleAnimateurMoveFilter;
import dev.sylvain.planning.solver.constraints.ExclusionEligibilite;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Plain-Java (no Timefold) capacity check meant for non-technical users: given
 * the reference data, is there even a theoretical chance to fill every seat,
 * or is the problem structurally short of animateurs regardless of how long
 * the solver runs?
 *
 * <p>The result is a ranked list of blocking causes
 * ({@link CauseInfaisabilite}), each carrying a severity and the concrete
 * entities involved, so the setup screen can display them before any solve is
 * launched. Five kinds of cause are detected:
 * {@link TypeCauseInfaisabilite#CRENEAU_SOUS_EFFECTIF} — the demand of a
 * créneau exceeds the number of animateurs available to serve it —
 * {@link TypeCauseInfaisabilite#CONTRAINTES_AD_HOC_CONTRADICTOIRES} — two
 * hand-entered exceptions that cannot both hold (issue #84) — and the three
 * {@code AFFECTATION_FORCEE_*}, one forced assignment nobody can honour
 * (issue #30): its animateurs declared the days off, no seat of its scope may
 * hold them, or their schedule is locked over the whole scope.
 *
 * <p>The last four are reported although the write already said so: an
 * exception recorded before the check existed, or imported together with
 * others, is exactly the one nobody will find by re-reading the form — and the
 * three forced-assignment readings are warnings, never refusals, so what they
 * describe is written and stays until somebody acts on it.</p>
 *
 * <p>The demand of a créneau is counted exactly as
 * {@link ProblemBuilder#buildPostes(List, List)} generates seats: for every
 * stand open on that créneau, the seats of its busiest open segment —
 * {@link Creneau#siegesSimultanes(Stand)}, i.e. the window's own effectif or
 * the stand's {@code effectifMin} when the window names none, floored to one
 * and halved on a break-covering shift. Segments of one slot follow each
 * other, so what has to be staffed at any instant is the largest of them, not
 * their sum. {@code effectifMax} is the upper capacity a stand <em>could</em>
 * accept, not the number of seats that must be staffed, and closed stands
 * generate no seat at all — counting either of them as demand overstates it
 * and reports a shortfall on plannings the solver fills without trouble.
 *
 * <p>Against that demand we count, for each créneau, every animateur present
 * that day — an optimistic upper bound on how many seats that créneau could
 * fill, since it ignores which specific stand each animateur would need to
 * cover. The reported shortfall is therefore a floor: the real gap can only be
 * equal or worse.
 *
 * <p>Competence is deliberately <b>not</b> part of this capacity count: the
 * business treats it as an administrator's post-formation appreciation,
 * enforced only as a medium constraint
 * ({@code QualiteConstraints.appreciationIncompatible}), so any available
 * animateur can literally be assigned to any stand — an appreciation mismatch
 * is a quality penalty visible on the calendar and the constraint score, never
 * a pre-solve blocking cause.</p>
 */
@ApplicationScoped
public class FeasibilityAnalyzer {

    /** Number of causes returned to the caller; the rest is only counted. */
    private static final int MAX_CAUSES = 10;

    /** Number of stand names spelled out in a créneau message before eliding. */
    private static final int MAX_STANDS_NOMMES = 3;

    /**
     * Causes are ranked so the first ones are the most blocking: CRITIQUE
     * before ELEVE, then by decreasing shortfall, and finally by créneau id so
     * two runs on the same data return the same order.
     */
    private static final Comparator<CauseInfaisabilite> ORDRE_CAUSES =
            Comparator.<CauseInfaisabilite, SeveriteInfaisabilite>comparing(CauseInfaisabilite::severite)
                    .thenComparingInt(FeasibilityAnalyzer::rank)
                    .thenComparing(CauseInfaisabilite::manque, Comparator.reverseOrder())
                    .thenComparingLong(cause -> cause.creneauId() == null ? Long.MIN_VALUE : cause.creneauId());

    /**
     * Within one severity, a contradiction between two exceptions comes first:
     * it is a one-click fix on data the user typed themselves, where a
     * shortfall of animateurs takes recruiting or reopening a stand. Not the
     * enum's own order — that one is on the wire and is only ever appended to.
     */
    /**
     * The legal parameters this estimate reads break lengths from: the domain's
     * defaults, because none of its callers holds the edition's. The only
     * eligibility motif that reads one is the minor's daily cap on a single
     * créneau, and the default is the floor of art. L3162-3, so the reading is
     * the strictest an edition can produce.
     */
    private static final ParametresLegaux PAUSES_PAR_DEFAUT = new ParametresLegaux();

    private static int rank(CauseInfaisabilite cause) {
        return cause.type() == TypeCauseInfaisabilite.CRENEAU_SOUS_EFFECTIF ? 1 : 0;
    }

    /**
     * Capacity check alone, for the callers analysing a hypothetical variant of
     * the stands, animateurs or créneaux ({@code CreneauGridService}): the ad
     * hoc exceptions are not what those screens vary, and their contradictions
     * are already reported by the edition's own report.
     */
    public FeasibilityReport analyze(List<Animateur> animateurs, List<Stand> stands, List<Creneau> creneaux) {
        return analyze(animateurs, stands, creneaux, List.of());
    }

    /**
     * Reads {@code encadrementMineursActif} off the edition's disabled set,
     * so the five callers that have one say it the same way rather than each
     * spelling the constraint name out.
     */
    public static boolean encadrementMineursActif(Set<String> contraintesDesactivees) {
        return contraintesDesactivees == null
                || !contraintesDesactivees.contains(ExclusionEligibilite.ENCADREMENT_DES_MINEURS);
    }

    /** With the supervision of minors left at whatever the catalogue says — see the five-argument variant. */
    public FeasibilityReport analyze(
            List<Animateur> animateurs,
            List<Stand> stands,
            List<Creneau> creneaux,
            List<ContrainteAdHoc> contraintesAdHoc) {
        return analyze(
                animateurs,
                stands,
                creneaux,
                contraintesAdHoc,
                ConstraintCatalog.activeByDefault(ExclusionEligibilite.ENCADREMENT_DES_MINEURS));
    }

    /**
     * @param encadrementMineursActif whether {@code mineurNecessiteEncadrementMajeur}
     *        applies to this edition. It changes how many seats a team can
     *        hold — beside an adult only, or on their own — so an estimate that
     *        assumed it while the edition switched it off called feasible
     *        plannings impossible, and the screens said so before any solve.
     *        The catalogue ships the rule off (issue #595); a caller that knows
     *        the edition passes its real state rather than that default.
     */
    public FeasibilityReport analyze(
            List<Animateur> animateurs,
            List<Stand> stands,
            List<Creneau> creneaux,
            List<ContrainteAdHoc> contraintesAdHoc,
            boolean encadrementMineursActif) {
        return analyze(animateurs, stands, creneaux, contraintesAdHoc, encadrementMineursActif, PlanContext.NONE);
    }

    /**
     * @param contexte what the caller knows about the plan — see
     *        {@link PlanContext}. A caller that can read the locks passes them,
     *        and a forced assignment nobody it names is free to honour is
     *        reported as a blocking cause; one that cannot passes
     *        {@link PlanContext#NONE}, and that single check is skipped rather
     *        than guessed at.
     */
    public FeasibilityReport analyze(
            List<Animateur> animateurs,
            List<Stand> stands,
            List<Creneau> creneaux,
            List<ContrainteAdHoc> contraintesAdHoc,
            boolean encadrementMineursActif,
            PlanContext contexte) {
        List<Animateur> animateursSurs = animateurs == null ? List.of() : animateurs;
        List<Stand> standsSurs = stands == null ? List.of() : stands;
        List<Creneau> creneauxSurs = creneaux == null ? List.of() : creneaux;

        List<CauseInfaisabilite> causes = new ArrayList<>(
                creneauxSousEffectif(animateursSurs, standsSurs, creneauxSurs, encadrementMineursActif));
        causes.addAll(contraintesContradictoires(contraintesAdHoc, creneauxSurs));
        PastHorizon horizon = contexte == null ? null : contexte.horizon();
        causes.addAll(
                affectationsForceesIntenables(contraintesAdHoc, animateursSurs, standsSurs, creneauxSurs, horizon));
        causes.addAll(affectationsForceesHorsEligibilite(
                contraintesAdHoc, animateursSurs, standsSurs, creneauxSurs, horizon));
        causes.addAll(affectationsForceesVerrouillees(contraintesAdHoc, standsSurs, creneauxSurs, contexte));
        causes.sort(ORDRE_CAUSES);

        int manqueAnimateurs = causes.stream()
                .filter(cause -> cause.type() == TypeCauseInfaisabilite.CRENEAU_SOUS_EFFECTIF)
                .mapToInt(CauseInfaisabilite::manque)
                .max()
                .orElse(0);
        int totalCauses = causes.size();
        // Counted before the cap: the list is trimmed for reading, the counts
        // are not. A screen showing « 10 bloquants » on an edition with sixty
        // under-staffed timeslots states the size of the cap, not the size of
        // the problem.
        int causesCritiques = (int) causes.stream()
                .filter(cause -> cause.severite() == SeveriteInfaisabilite.CRITIQUE)
                .count();
        int causesElevees = totalCauses - causesCritiques;
        // Nothing is feasible without an animateur (issue #416): an edition
        // whose stands are all closed has no shortfall to list, and a report
        // saying « réalisable » there would be read as a green light.
        boolean sansAnimateur = animateursSurs.isEmpty();
        boolean sansCreneau = creneauxSurs.isEmpty();
        // Seats are counted once per timeslot, like the demand of a cause.
        boolean sansPoste = !sansCreneau
                && creneauxSurs.stream().allMatch(creneau -> standsSurs.stream().noneMatch(creneau::isStandOpen));
        boolean feasible = totalCauses == 0 && !sansAnimateur && !sansCreneau && !sansPoste;
        List<CauseInfaisabilite> topCauses = List.copyOf(causes.subList(0, Math.min(MAX_CAUSES, totalCauses)));

        return new FeasibilityReport(
                feasible,
                manqueAnimateurs,
                topCauses,
                totalCauses,
                causesCritiques,
                causesElevees,
                sansAnimateur
                        ? buildMessageWithoutAnimateur(totalCauses)
                        : sansCreneau
                                ? MESSAGE_SANS_CRENEAU
                                : sansPoste
                                        ? MESSAGE_SANS_POSTE
                                        : buildMessage(feasible, manqueAnimateurs, totalCauses, topCauses));
    }

    private List<CauseInfaisabilite> creneauxSousEffectif(
            List<Animateur> animateurs, List<Stand> stands, List<Creneau> creneaux, boolean encadrementMineursActif) {
        List<CauseInfaisabilite> causes = new ArrayList<>();
        for (Creneau creneau : creneaux) {
            List<Stand> standsOuverts =
                    stands.stream().filter(stand -> creneau.isStandOpen(stand)).toList();
            int demande =
                    standsOuverts.stream().mapToInt(creneau::siegesSimultanes).sum();
            long capacite = capacite(animateurs, standsOuverts, creneau, demande, encadrementMineursActif);
            int manque = (int) Math.max(0, demande - capacite);
            if (manque <= 0) {
                continue;
            }
            causes.add(new CauseInfaisabilite(
                    TypeCauseInfaisabilite.CRENEAU_SOUS_EFFECTIF,
                    manque >= demande ? SeveriteInfaisabilite.CRITIQUE : SeveriteInfaisabilite.ELEVE,
                    "Le " + describeCreneau(creneau) + ", il manque " + manque + " " + motAnimateur(manque)
                            + " pour couvrir " + describeStands(standsOuverts) + ".",
                    creneau.getId(),
                    creneau.getDate(),
                    creneau.getHeureDebut(),
                    creneau.getHeureFin(),
                    standsOuverts.stream().map(Stand::getId).toList(),
                    List.of(),
                    demande,
                    (int) capacite,
                    manque));
        }
        return causes;
    }

    /**
     * One cause per impossible combination of hand-entered exceptions. Always
     * CRITIQUE: unlike a shortfall of animateurs — which the solver can still
     * mitigate — a contradiction guarantees a negative hard score, whatever
     * time budget it is given.
     */
    private List<CauseInfaisabilite> contraintesContradictoires(
            List<ContrainteAdHoc> contraintes, List<Creneau> creneaux) {
        List<CauseInfaisabilite> causes = new ArrayList<>();
        for (Contradiction contradiction : ContrainteAdHocContradictions.detectAll(contraintes, creneaux)) {
            causes.add(new CauseInfaisabilite(
                    TypeCauseInfaisabilite.CONTRAINTES_AD_HOC_CONTRADICTOIRES,
                    SeveriteInfaisabilite.CRITIQUE,
                    contradiction.message(),
                    null,
                    null,
                    null,
                    null,
                    List.of(),
                    contradiction.contrainteIds(),
                    0,
                    0,
                    0));
        }
        return causes;
    }

    /**
     * One cause per forced assignment its animateurs' days off make impossible.
     * CRITIQUE for the same reason as a contradiction: whatever the budget, the
     * solve gives up either the exception or the day off. Read against the
     * days declared now, since they usually arrive after the exception.
     */
    private List<CauseInfaisabilite> affectationsForceesIntenables(
            List<ContrainteAdHoc> contraintes,
            List<Animateur> animateurs,
            List<Stand> stands,
            List<Creneau> creneaux,
            PastHorizon horizon) {
        List<CauseInfaisabilite> causes = new ArrayList<>();
        for (ForcedAssignmentOnDayOff.Conflit conflit :
                ForcedAssignmentOnDayOff.detectAll(contraintes, animateurs, stands, creneaux, horizon)) {
            causes.add(forcedAssignmentCause(
                    TypeCauseInfaisabilite.AFFECTATION_FORCEE_JOUR_INDISPONIBLE,
                    conflit.contrainte(),
                    conflit.message(),
                    conflit.dates()));
        }
        return causes;
    }

    /**
     * One cause per forced assignment no seat of its scope may hold — a minor
     * on a night slot, on a public holiday, on an adults-only stand, past their
     * daily cap. CRITIQUE like its two siblings: the rules it breaks are hard
     * ones, and no budget buys a way round them.
     */
    private List<CauseInfaisabilite> affectationsForceesHorsEligibilite(
            List<ContrainteAdHoc> contraintes,
            List<Animateur> animateurs,
            List<Stand> stands,
            List<Creneau> creneaux,
            PastHorizon horizon) {
        List<CauseInfaisabilite> causes = new ArrayList<>();
        for (ForcedAssignmentOnExcludedSeats.Conflit conflit :
                ForcedAssignmentOnExcludedSeats.detectAll(contraintes, animateurs, stands, creneaux, horizon)) {
            causes.add(forcedAssignmentCause(
                    TypeCauseInfaisabilite.AFFECTATION_FORCEE_MOTIF_LEGAL,
                    conflit.contrainte(),
                    conflit.message(),
                    conflit.dates()));
        }
        return causes;
    }

    /**
     * One cause per forced assignment every animateur it names is locked out of.
     * Runs only when the caller brought locks, and reads the persisted seats
     * only then: with none recorded there is nothing to cross and nothing to
     * pay for.
     */
    private List<CauseInfaisabilite> affectationsForceesVerrouillees(
            List<ContrainteAdHoc> contraintes, List<Stand> stands, List<Creneau> creneaux, PlanContext contexte) {
        // Both halves are read before the supplier is: the seats cost a full
        // scan of the assignments, and with no lock or no forced assignment
        // there is nothing to cross them with.
        if (contexte == null
                || contexte.verrouillages().isEmpty()
                || contraintes == null
                || contraintes.stream()
                        .noneMatch(contrainte ->
                                contrainte != null && contrainte.getType() == TypeContrainteAdHoc.AFFECTATION_FORCEE)) {
            return List.of();
        }
        List<CauseInfaisabilite> causes = new ArrayList<>();
        for (ForcedAssignmentOnLockedSchedule.Conflit conflit : ForcedAssignmentOnLockedSchedule.detectAll(
                contraintes,
                contexte.verrouillages(),
                stands,
                creneaux,
                contexte.placesTenues().get(),
                contexte.horizon())) {
            causes.add(forcedAssignmentCause(
                    TypeCauseInfaisabilite.AFFECTATION_FORCEE_SIEGE_VERROUILLE,
                    conflit.contrainte(),
                    conflit.message(),
                    conflit.dates()));
        }
        return causes;
    }

    /**
     * The shape the three « this forced assignment cannot be honoured » causes
     * share: the exception named, a date only when the scope is one day, and no
     * stand — a stand on a cause sends the reader to the openings, and nothing
     * about the openings is wrong here.
     */
    private static CauseInfaisabilite forcedAssignmentCause(
            TypeCauseInfaisabilite type, ContrainteAdHoc contrainte, String message, List<LocalDate> dates) {
        Creneau creneau = contrainte.getCreneau();
        return new CauseInfaisabilite(
                type,
                SeveriteInfaisabilite.CRITIQUE,
                message,
                creneau == null ? null : creneau.getId(),
                dates.size() == 1 ? dates.getFirst() : null,
                null,
                null,
                List.of(),
                List.of(contrainte.getId()),
                0,
                0,
                0);
    }

    /**
     * An edition without any timeslot has nothing to plan: « réalisable »
     * would be read as a green light, exactly as on an empty roster.
     */
    public static final String MESSAGE_SANS_CRENEAU =
            "Aucun créneau n'est saisi : il n'y a rien à planifier tant que la"
                    + " grille est vide. Saisissez les créneaux, puis les ouvertures des stands.";

    /** Timeslots, but no stand open on any of them: no seat to fill, and nothing a solve can produce. */
    public static final String MESSAGE_SANS_POSTE =
            "Aucun stand n'ouvre sur les créneaux saisis : il n'y a aucun poste à"
                    + " pourvoir. Vérifiez les stands et leurs ouvertures.";

    /**
     * How many of a timeslot's seats the available animateurs could hold, at
     * most — still optimistic, skills are not read, but no longer counting a
     * minor where the law or the supervision rule keeps them out.
     *
     * <p>A minor counts only where nothing on the seat itself excludes them —
     * night, a public holiday, a timeslot past their daily cap, an adults-only
     * stand, the checks of {@link EligibleAnimateurMoveFilter}, read under
     * {@link #PAUSES_PAR_DEFAUT}: this estimate does not hold the edition's
     * parameters, and the default break is the legal floor, so what it deducts
     * from a long créneau is the least any edition deducts. An edition granting
     * a longer break opens a créneau or two more to a minor than this counts —
     * a shortfall reported here is one the solver would meet, which is the
     * direction an estimate must err in. When {@code encadrementMineursActif}, a minor also counts only
     * beside an adult: each adult placed on a stand of {@code s} seats opens
     * {@code s − 1} seats to minors, adults going first to the largest stands,
     * and a team of minors only therefore holds nothing — where counting heads
     * called it feasible. With the rule switched off, the seats are simply
     * open to them, and an estimate still pairing them off would call a
     * perfectly staffed evening impossible.</p>
     */
    private static long capacite(
            List<Animateur> animateurs,
            List<Stand> standsOuverts,
            Creneau creneau,
            int demande,
            boolean encadrementMineursActif) {
        Stand standOrdinaire = new Stand("capacite", "capacite", Set.of(), 1, 1, false);
        PosteAffectation siegeOrdinaire = new PosteAffectation("capacite", standOrdinaire, creneau);
        long majeurs = 0;
        long mineurs = 0;
        for (Animateur animateur : animateurs) {
            if (animateur.isIndisponibleOn(creneau.getDate())) {
                continue;
            }
            if (!animateur.isMineurOn(creneau.getDate())) {
                majeurs++;
            } else if (EligibleAnimateurMoveFilter.motifs(siegeOrdinaire, animateur, PAUSES_PAR_DEFAUT)
                    .isEmpty()) {
                mineurs++;
            }
        }
        // Seats a minor may sit on at all. `standReserveAuxMajeurs` is a hard
        // rule of its own: switching the pairing rule off does not open an
        // adults-only stand to them, so this filter applies to both readings
        // below. Counting every available minor as capacity would report a
        // créneau whose only open stand is adults-only as fully staffed while
        // the solver cannot place anybody on it.
        List<Integer> places = standsOuverts.stream()
                .filter(stand -> !stand.isReserveMajeurs())
                .map(creneau::siegesSimultanes)
                .filter(sieges -> sieges > 0)
                .sorted(Comparator.reverseOrder())
                .toList();
        if (!encadrementMineursActif) {
            // No pairing to honour: a minor takes any seat of an open stand
            // that is not reserved to adults, host or no host.
            long ouvertesAuxMineurs =
                    places.stream().mapToLong(Integer::longValue).sum();
            return Math.min(demande, majeurs + Math.min(mineurs, ouvertesAuxMineurs));
        }
        long hotes = Math.min(majeurs, places.size());
        long placesPourMineurs =
                places.stream().limit(hotes).mapToLong(sieges -> sieges - 1L).sum();
        return Math.min(demande, majeurs + Math.min(mineurs, placesPourMineurs));
    }

    /**
     * Said first and by name, rather than left to be inferred from one
     * CRITIQUE cause per timeslot: the per-timeslot causes describe the seats,
     * where the thing to fix is the empty animateur list.
     */
    private static String buildMessageWithoutAnimateur(int totalCauses) {
        String message =
                "Aucun animateur n'est saisi : rien n'est réalisable tant que la liste des animateurs est vide."
                        + " Renseignez les animateurs avant de lancer une résolution";
        return totalCauses == 0 ? message + "." : message + " — " + causesPhrase(totalCauses) + " sur les créneaux.";
    }

    private static String causesPhrase(int totalCauses) {
        return totalCauses > 1
                ? totalCauses + " causes bloquantes ont été détectées"
                : "1 cause bloquante a été détectée";
    }

    private String buildMessage(boolean feasible, int manque, int totalCauses, List<CauseInfaisabilite> topCauses) {
        if (feasible) {
            return "Le planning est réalisable : il y a assez d'animateurs disponibles pour couvrir chaque créneau.";
        }
        String causesPhrase = causesPhrase(totalCauses);
        if (manque <= 0) {
            return "Ce planning n'est pas réalisable avec les données actuelles : " + causesPhrase + ". Par exemple : "
                    + topCauses.getFirst().message();
        }
        return "Ce planning n'est pas réalisable avec les animateurs actuels : il manque au moins " + manque + " "
                + motAnimateur(manque) + " sur un créneau, et " + causesPhrase + ".";
    }

    private static String motAnimateur(int manque) {
        return manque > 1 ? "animateurs" : "animateur";
    }

    private static String describeCreneau(Creneau creneau) {
        if (creneau.getDate() == null) {
            return "créneau " + creneau.getId();
        }
        if (creneau.getHeureDebut() == null || creneau.getHeureFin() == null) {
            return String.valueOf(creneau.getDate());
        }
        return creneau.getDate() + " " + creneau.getHeureDebut() + "-" + creneau.getHeureFin();
    }

    /**
     * Spells out at most {@value #MAX_STANDS_NOMMES} stand names so the
     * sentence stays readable on an event with dozens of open stands.
     */
    private static String describeStands(List<Stand> stands) {
        if (stands.isEmpty()) {
            return "les stands ouverts";
        }
        String nommes = stands.stream()
                .limit(MAX_STANDS_NOMMES)
                .map(FeasibilityAnalyzer::nomStand)
                .collect(Collectors.joining(", "));
        int restants = stands.size() - MAX_STANDS_NOMMES;
        return restants > 0 ? nommes + " et " + restants + (restants > 1 ? " autres stands" : " autre stand") : nommes;
    }

    private static String nomStand(Stand stand) {
        return stand.getNom() == null || stand.getNom().isBlank() ? stand.getId() : stand.getNom();
    }

    /**
     * What the caller knows about the plan around the reference data: the locks
     * of the edition, the seats of the persisted plan the animateurs of an
     * exception may already hold, and the moment the past is judged against.
     *
     * <p>The seats come as a {@link Supplier} and not as a set, because reading
     * them is a full scan of the assignments: with no lock recorded — the usual
     * case — nothing needs them, and this analysis runs on every load of the
     * Solveur, Édition and Problèmes screens. {@link #NONE} is the caller that
     * has none of the three to offer, and the lock check is then simply not
     * run.</p>
     *
     * @param horizon the moment « le passé est figé » is judged against (ADR
     *        0044), or {@code null} when the freeze is off or the caller cannot
     *        read it. The three forced-assignment readings stay silent on a
     *        scope entirely behind it: those seats are re-seeded and pinned, and
     *        the constraints count them without reproaching them — reporting one
     *        as blocking would ask the operator to undo a day already worked.
     */
    public record PlanContext(
            List<VerrouillagePlanning> verrouillages,
            Supplier<Set<ForcedAssignmentOnLockedSchedule.PlaceTenue>> placesTenues,
            PastHorizon horizon) {

        public static final PlanContext NONE = new PlanContext(List.of(), Set::of, null);

        public PlanContext {
            verrouillages = verrouillages == null ? List.of() : List.copyOf(verrouillages);
            Objects.requireNonNull(placesTenues, "placesTenues");
        }
    }

    /** Kind of blocking cause detected before any solve. */
    public enum TypeCauseInfaisabilite {
        CRENEAU_SOUS_EFFECTIF,
        CONTRAINTES_AD_HOC_CONTRADICTOIRES,
        /** A forced assignment falling only on days its animateurs declared off — see {@link ForcedAssignmentOnDayOff}. */
        AFFECTATION_FORCEE_JOUR_INDISPONIBLE,
        /**
         * A forced assignment no seat of its scope may hold, for a reason read
         * on the (seat, animateur) pair alone — see
         * {@link ForcedAssignmentOnExcludedSeats}. {@code contrainteIds} names
         * the exception; the message names the rules of the catalogue.
         */
        AFFECTATION_FORCEE_MOTIF_LEGAL,
        /**
         * A forced assignment whose every named animateur has a locked schedule
         * over its whole scope, without already sitting in it — see
         * {@link ForcedAssignmentOnLockedSchedule}.
         */
        AFFECTATION_FORCEE_SIEGE_VERROUILLE
    }

    /**
     * Severity ordered from the most to the least blocking: the enum order is
     * the sort order used by {@link #ORDRE_CAUSES}.
     */
    public enum SeveriteInfaisabilite {
        CRITIQUE,
        ELEVE
    }

    /**
     * One blocking cause, ready to display.
     *
     * @param message       French sentence describing the cause
     * @param standIds      stands concerned, empty on a cause that names none
     * @param contrainteIds ad hoc constraints concerned, empty on a cause that
     *                      names none — the exceptions to edit or delete when
     *                      the cause is a contradiction between them
     * @param demande       seats to fill, {@code 0} outside a capacity cause
     * @param capacite      animateurs able to fill them
     * @param manque        {@code demande - capacite}
     */
    @Schema(requiredProperties = {"capacite", "demande", "manque"})
    public record CauseInfaisabilite(
            TypeCauseInfaisabilite type,
            SeveriteInfaisabilite severite,
            String message,
            Long creneauId,
            LocalDate date,
            LocalTime heureDebut,
            LocalTime heureFin,
            List<String> standIds,
            List<String> contrainteIds,
            int demande,
            int capacite,
            int manque) {}

    /**
     * @param manqueAnimateurs worst single-créneau shortfall, {@code 0} when no
     *                         créneau is under-staffed
     * @param causes           ranked causes, capped to the ten most blocking
     * @param totalCauses      number of causes found <em>before</em> capping,
     *                         so the UI can say "+N autres"
     */
    @Schema(requiredProperties = {"feasible", "manqueAnimateurs", "totalCauses"})
    /**
     * @param causes           the first {@value #MAX_CAUSES}, sorted worst
     *                         first: enough to read, never the whole list
     * @param totalCauses      how many were found, before that cap
     * @param causesCritiques  how many of them block, before that cap — a
     *                         caller counting the severities of {@code causes}
     *                         counts the cap instead of the edition
     * @param causesElevees    the rest: worth a warning, not a blocker
     */
    public record FeasibilityReport(
            boolean feasible,
            int manqueAnimateurs,
            List<CauseInfaisabilite> causes,
            int totalCauses,
            int causesCritiques,
            int causesElevees,
            String message) {}
}
