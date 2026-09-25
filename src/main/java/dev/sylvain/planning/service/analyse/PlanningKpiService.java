package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.EffectiveWork;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.PlanningDiagnostic;
import dev.sylvain.planning.service.consigne.ConsigneService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.ConstraintAnalysisStore;
import dev.sylvain.planning.service.solve.PlanSnapshotService.AffectationSnapshot;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Aggregate KPI of a plan (issues #89 and #70): score, coverage, hours
 * dispersion, manual-modification rate and per-constraint violation counts —
 * everything the snapshot comparator and the KPI history need, computed from
 * the services that already exist rather than by a divergent re-count.
 *
 * <p>Deliberately <b>non-nominative</b> (an RGPD requirement of #89): fairness
 * is expressed as the dispersion of hours (mean / standard deviation / min /
 * max), never as a ranking of named animateurs. Animateur ids are only used
 * transiently as grouping keys and never leave this service.</p>
 *
 * <p>The hours are <b>planned amplitude</b>, like the Heures and Équité
 * screens: a break declared taken on the post is not deducted, where the legal
 * caps of {@code LegalConstraints} do deduct it. A KPI history compared across
 * editions must count the same thing whatever an edition declared about its
 * breaks. See {@code docs/contraintes.md}, « Ce qui déduit la pause, et ce qui
 * compte l'amplitude ».</p>
 */
@ApplicationScoped
public class PlanningKpiService {

    private static final Pattern SCORE_PATTERN = Pattern.compile("(-?+\\d++)hard/(-?+\\d++)medium/(-?+\\d++)soft");

    private final PlanningPersistenceService persistenceService;

    private final ReferenceDataService referenceDataService;

    private final ConstraintAnalysisStore analysisStore;

    private final ConsigneService consigneService;

    @Inject
    public PlanningKpiService(
            PlanningPersistenceService persistenceService,
            ReferenceDataService referenceDataService,
            ConstraintAnalysisStore analysisStore,
            ConsigneService consigneService) {
        this.persistenceService = persistenceService;
        this.referenceDataService = referenceDataService;
        this.analysisStore = analysisStore;
        this.consigneService = consigneService;
    }

    /**
     * Everything a plan is measured by. The nullable fields are the ones that
     * depend on a stored score analysis: when none exists — a plan persisted
     * without a subsequent analyse — they are absent rather than invented,
     * because a zero score and an unknown score are not the same fact.
     *
     * @param heuresIncompletes true when some staffed seats referenced a
     *                          créneau the referential no longer holds, so the
     *                          hour metrics under-count them
     * @param scoreMediumHorsPlancher the medium score minus its floor (issue
     *                          #495) — what a solve can actually move, the
     *                          figure to compare between two runs. {@code null}
     *                          when the floor was not measured: a snapshot
     *                          captured before it existed, or a plan without a
     *                          stored analysis — same rule as
     *                          {@code violationsParContrainte}
     * @param plancherMedium    the constant part of the medium score, signed
     *                          like it; {@code null} when not measured
     * @param journeesSousConsigne dates a consigne governed when the plan was
     *                          measured (issue #4); {@code null} on a KPI
     *                          computed before the figure existed
     * @param heuresFermeesParConsigne seat-hours the bands of those consignes
     *                          took away from the nominal days; {@code null}
     *                          for the same reason
     * @param lecture           the reading of the score (see
     *                          {@link ScoreReading}) of the analysis these
     *                          figures were taken from — what the Comparateur
     *                          shows for each plan. {@code null} when no
     *                          analysis was read, and on a snapshot captured
     *                          before the reading existed
     */
    @Schema(
            requiredProperties = {
                "animateursAffectes",
                "creneauxDistincts",
                "heuresIncompletes",
                "postesPourvus",
                "postesTotal",
                "standsDistincts"
            })
    public record PlanningKpi(
            String score,
            Integer scoreHard,
            Integer scoreMedium,
            Integer scoreSoft,
            int postesTotal,
            int postesPourvus,
            int animateursAffectes,
            int standsDistincts,
            int creneauxDistincts,
            Double heuresTotal,
            Double heuresMoyenne,
            Double heuresEcartType,
            Double heuresMin,
            Double heuresMax,
            boolean heuresIncompletes,
            Integer modificationsManuelles,
            Double tauxModificationsManuelles,
            Long dureeSolveSecondes,
            Map<String, Integer> violationsParContrainte,
            Integer scoreMediumHorsPlancher,
            Integer plancherMedium,
            Integer journeesSousConsigne,
            Double heuresFermeesParConsigne,
            List<ScoreReading.ScoreSentence> lecture) {

        /** The same figures carrying {@code lecture}, the reading of the analysis they were taken from. */
        public PlanningKpi withReading(List<ScoreReading.ScoreSentence> lecture) {
            return new PlanningKpi(
                    score,
                    scoreHard,
                    scoreMedium,
                    scoreSoft,
                    postesTotal,
                    postesPourvus,
                    animateursAffectes,
                    standsDistincts,
                    creneauxDistincts,
                    heuresTotal,
                    heuresMoyenne,
                    heuresEcartType,
                    heuresMin,
                    heuresMax,
                    heuresIncompletes,
                    modificationsManuelles,
                    tauxModificationsManuelles,
                    dureeSolveSecondes,
                    violationsParContrainte,
                    scoreMediumHorsPlancher,
                    plancherMedium,
                    journeesSousConsigne,
                    heuresFermeesParConsigne,
                    lecture);
        }
    }

    /** One staffed-or-empty seat reduced to what the KPI need: who, for how long. */
    record AffectationKpi(String standId, String creneauId, String animateurId, Integer dureeMinutes) {}

    /**
     * KPI of the currently persisted plan. Score and violations come from the
     * latest stored analysis (the one recorded at the end of the solve that
     * produced this plan); both are {@code null}/empty when no analysis exists.
     *
     * @param dureeSolveSecondes real duration of the solve that produced the
     *                           plan, when the caller (the solve job) knows it
     */
    public PlanningKpi computeCurrent(Long dureeSolveSecondes) {
        PlanningEvenement planning = persistenceService.loadPersistedPlanning();
        List<AffectationKpi> affectations = new ArrayList<>();
        for (PosteAffectation poste : planning.getPostes()) {
            affectations.add(new AffectationKpi(
                    poste.getStand().getId(),
                    String.valueOf(poste.getCreneau().getId()),
                    poste.getAnimateur() == null ? null : poste.getAnimateur().getId(),
                    poste.getDureeEffectiveMinutes()));
        }
        ConstraintAnalysisStore.StoredAnalysis analysis = analysisStore.latest();
        PlanningDiagnostic diagnostic = analysis == null ? null : analysis.diagnostic();
        int modifications = referenceDataService.listContraintesAdHoc().size()
                + referenceDataService.listVerrouillages().size();
        List<ConsigneService.Indicateur> indicateurs = consigneService.indicateurs();
        return compute(new KpiInputs(
                        affectations,
                        diagnostic == null ? null : diagnostic.score(),
                        violationsByContrainte(diagnostic),
                        modifications,
                        dureeSolveSecondes,
                        diagnostic == null ? null : diagnostic.plancherMedium(),
                        indicateurs.size(),
                        indicateurs.stream()
                                        .mapToInt(ConsigneService.Indicateur::minutesFermees)
                                        .sum()
                                / 60.0,
                        EffectiveWork.breakMinutesPerAnimateur(planning.getPostes(), planning.parametresLegaux())))
                .withReading(diagnostic == null ? null : diagnostic.lecture());
    }

    /**
     * Degraded recomputation for a snapshot captured before KPI were stored
     * (issue #70): coverage and volumetry stay exact, the hours are resolved
     * against the referential of the snapshot's own edition — so the caller
     * must already be running in that edition (see
     * {@code SnapshotComparisonService}) — and score details are limited to
     * what the snapshot's meta carries. Violations are left empty rather than
     * zeroed, and the floor is left unmeasured: nothing measured them, and an
     * unmeasured constraint is not a respected one.
     */
    public PlanningKpi computeFromSnapshot(List<AffectationSnapshot> affectations, String score) {
        Map<String, Creneau> creneauxParId = new HashMap<>();
        for (Creneau creneau : referenceDataService.listCreneaux()) {
            creneauxParId.put(String.valueOf(creneau.getId()), creneau);
        }
        List<AffectationKpi> reduites = new ArrayList<>();
        for (AffectationSnapshot affectation : affectations) {
            reduites.add(new AffectationKpi(
                    affectation.standId(),
                    affectation.creneauId(),
                    affectation.animateurId(),
                    dureeMinutes(affectation, creneauxParId.get(affectation.creneauId()))));
        }
        return compute(reduites, score, Map.of(), null, null, null);
    }

    /**
     * Effective duration of a snapshotted seat: its own effective window when
     * the capture stored one (a stand closed for part of a créneau), the
     * créneau's own span otherwise, and {@code null} when the créneau no longer
     * exists to answer — which is what raises {@code heuresIncompletes}.
     */
    private static Integer dureeMinutes(AffectationSnapshot affectation, Creneau creneau) {
        if (affectation.heureDebutEffective() != null && affectation.heureFinEffective() != null) {
            PosteAffectation fenetre = new PosteAffectation(affectation.posteId(), null, null);
            fenetre.setHeureDebutEffective(LocalTime.parse(affectation.heureDebutEffective()));
            fenetre.setHeureFinEffective(LocalTime.parse(affectation.heureFinEffective()));
            return fenetre.getDureeEffectiveMinutes();
        }
        return creneau == null ? null : creneau.getDureeMinutes();
    }

    /** Match counts per constraint, in the diagnostic's order. Never nominative. */
    public static Map<String, Integer> violationsByContrainte(PlanningDiagnostic diagnostic) {
        if (diagnostic == null) {
            return Map.of();
        }
        Map<String, Integer> violations = new LinkedHashMap<>();
        for (PlanningDiagnosticService.ConstraintDiagnostic contrainte : diagnostic.contraintes()) {
            violations.put(contrainte.name(), contrainte.matchCount());
        }
        return violations;
    }

    /**
     * The aggregation itself, static and free of any I/O so it can be
     * unit-tested without a database.
     *
     * @param plancherMedium the constant part of the medium score the analysis
     *                       measured, {@code null} when it did not — the
     *                       score net of it is then not invented either
     */
    public static PlanningKpi compute(
            List<AffectationKpi> affectations,
            String score,
            Map<String, Integer> violationsParContrainte,
            Integer modificationsManuelles,
            Long dureeSolveSecondes,
            Integer plancherMedium) {
        return compute(new KpiInputs(
                affectations,
                score,
                violationsParContrainte,
                modificationsManuelles,
                dureeSolveSecondes,
                plancherMedium,
                null,
                null,
                Map.of()));
    }

    /**
     * What the aggregation reads.
     *
     * @param plancherMedium           the constant part of the medium score the
     *                                 analysis measured, {@code null} when it did not
     * @param journeesSousConsigne     how many dates a consigne governed (issue #4)
     * @param heuresFermeesParConsigne the seat-hours their bands took away
     * @param breakMinutesPerAnimateur minutes of legal break each
     *        animateur's days owe, deducted from their hours so this report
     *        counts travail effectif like every other hour read-out (ADR 0048).
     *        Empty when the caller cannot know — the degraded recomputation of
     *        an old snapshot, whose seats carry no start time: those rows stay
     *        at amplitude, and the KPI page says so.
     */
    record KpiInputs(
            List<AffectationKpi> affectations,
            String score,
            Map<String, Integer> violationsParContrainte,
            Integer modificationsManuelles,
            Long dureeSolveSecondes,
            Integer plancherMedium,
            Integer journeesSousConsigne,
            Double heuresFermeesParConsigne,
            Map<String, Integer> breakMinutesPerAnimateur) {}

    /** Same, with every figure the caller holds — the consigne ones and the breaks owed included. */
    public static PlanningKpi compute(KpiInputs inputs) {
        SeatTally tally = SeatTally.of(inputs.affectations());
        Map<String, Double> heuresParAnimateur = tally.heuresParAnimateur;
        inputs.breakMinutesPerAnimateur()
                .forEach((animateurId, minutes) ->
                        heuresParAnimateur.computeIfPresent(animateurId, (id, heures) -> heures - minutes / 60.0));
        Dispersion dispersion = dispersion(heuresParAnimateur.values());
        int[] niveaux = parseScore(inputs.score());
        int total = inputs.affectations().size();
        Integer modificationsManuelles = inputs.modificationsManuelles();
        Double taux = modificationsManuelles == null || total == 0 ? null : modificationsManuelles / (double) total;
        return new PlanningKpi(
                inputs.score(),
                level(niveaux, 0),
                level(niveaux, 1),
                level(niveaux, 2),
                total,
                tally.pourvus,
                heuresParAnimateur.size(),
                tally.stands.size(),
                tally.creneaux.size(),
                dispersion.total(),
                dispersion.moyenne(),
                dispersion.ecartType(),
                dispersion.min(),
                dispersion.max(),
                tally.heuresIncompletes,
                modificationsManuelles,
                taux,
                inputs.dureeSolveSecondes(),
                inputs.violationsParContrainte() == null ? Map.of() : inputs.violationsParContrainte(),
                mediumNetOfFloor(niveaux, inputs.plancherMedium()),
                inputs.plancherMedium(),
                inputs.journeesSousConsigne(),
                inputs.heuresFermeesParConsigne(),
                null);
    }

    /** The seats counted once: distinct stands and créneaux, the filled ones, and the hours per animateur. */
    private static final class SeatTally {
        private final Set<String> stands = new LinkedHashSet<>();
        private final Set<String> creneaux = new LinkedHashSet<>();
        private final Map<String, Double> heuresParAnimateur = new LinkedHashMap<>();
        private int pourvus;
        private boolean heuresIncompletes;

        static SeatTally of(List<AffectationKpi> affectations) {
            SeatTally tally = new SeatTally();
            affectations.forEach(tally::add);
            return tally;
        }

        private void add(AffectationKpi affectation) {
            stands.add(affectation.standId());
            creneaux.add(affectation.creneauId());
            if (affectation.animateurId() == null) {
                return;
            }
            pourvus++;
            if (affectation.dureeMinutes() == null) {
                heuresIncompletes = true;
            } else {
                heuresParAnimateur.merge(affectation.animateurId(), affectation.dureeMinutes() / 60.0, Double::sum);
            }
        }
    }

    private static Integer level(int[] niveaux, int index) {
        return niveaux.length == 0 ? null : niveaux[index];
    }

    /** The medium score net of its floor, unmeasured when either is. */
    private static Integer mediumNetOfFloor(int[] niveaux, Integer plancherMedium) {
        return niveaux.length == 0 || plancherMedium == null ? null : niveaux[1] - plancherMedium;
    }

    /** Spread of the hours per animateur; every figure {@code null} when nobody holds a seat. */
    private record Dispersion(Double total, Double moyenne, Double ecartType, Double min, Double max) {
        static final Dispersion NONE = new Dispersion(null, null, null, null, null);
    }

    private static Dispersion dispersion(Collection<Double> valeurs) {
        if (valeurs.isEmpty()) {
            return Dispersion.NONE;
        }
        double total = 0;
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (double valeur : valeurs) {
            total += valeur;
            min = Math.min(min, valeur);
            max = Math.max(max, valeur);
        }
        double moyenne = total / valeurs.size();
        double variance = 0;
        for (double valeur : valeurs) {
            variance += (valeur - moyenne) * (valeur - moyenne);
        }
        return new Dispersion(total, moyenne, Math.sqrt(variance / valeurs.size()), min, max);
    }

    /**
     * The three levels of a {@code HardMediumSoftScore} rendered as text (e.g.
     * {@code 0hard/-3medium/-120soft}), or an empty array when the text does
     * not carry them (no score, unsolved score, older format). An
     * {@code -Ninit/} prefix is tolerated: the levels behind it still parse.
     */
    static int[] parseScore(String score) {
        if (score == null) {
            return new int[0];
        }
        Matcher matcher = SCORE_PATTERN.matcher(score);
        if (!matcher.find()) {
            return new int[0];
        }
        return new int[] {
            Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))
        };
    }
}
