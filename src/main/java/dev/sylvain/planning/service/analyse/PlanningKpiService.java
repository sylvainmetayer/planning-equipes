package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Creneau;
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
 */
@ApplicationScoped
public class PlanningKpiService {

    private static final Pattern SCORE_PATTERN = Pattern.compile("(-?\\d+)hard/(-?\\d+)medium/(-?\\d+)soft");

    @Inject
    PlanningPersistenceService persistenceService;

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    ConstraintAnalysisStore analysisStore;

    @Inject
    ConsigneService consigneService;

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
            Double heuresFermeesParConsigne) {}

    /**
     * One staffed-or-empty seat reduced to what the KPI need: who, for how
     * long, and whether the seat was owed at all.
     *
     * <p>The flag splits the two readings a renfort needs (issue #505). The
     * <b>coverage</b> — {@code postesTotal} and {@code postesPourvus} — counts
     * owed seats only: a stand declaring a capacity it rarely fills would
     * otherwise lower the coverage of a perfectly staffed plan, and no
     * historised row would compare with the ones recorded before. The
     * <b>hours</b> count every seat somebody holds, renfort included:
     * whoever sits there really worked, and the dispersion is about people,
     * not about what their seat was called.</p>
     */
    record AffectationKpi(
            String standId, String creneauId, String animateurId, Integer dureeMinutes, boolean optionnel) {}

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
                    poste.getDureeEffectiveMinutes(),
                    poste.isOptionnel()));
        }
        ConstraintAnalysisStore.StoredAnalysis analysis = analysisStore.latest();
        PlanningDiagnostic diagnostic = analysis == null ? null : analysis.diagnostic();
        int modifications = referenceDataService.listContraintesAdHoc().size()
                + referenceDataService.listVerrouillages().size();
        List<ConsigneService.Indicateur> indicateurs = consigneService.indicateurs();
        return compute(
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
                        / 60.0);
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
                    dureeMinutes(affectation, creneauxParId.get(affectation.creneauId())),
                    Boolean.TRUE.equals(affectation.optionnel())));
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
        return compute(
                affectations,
                score,
                violationsParContrainte,
                modificationsManuelles,
                dureeSolveSecondes,
                plancherMedium,
                null,
                null);
    }

    /**
     * Same, with the consigne figures of the plan (issue #4): how many dates a
     * consigne governed, and the seat-hours their bands took away.
     */
    public static PlanningKpi compute(
            List<AffectationKpi> affectations,
            String score,
            Map<String, Integer> violationsParContrainte,
            Integer modificationsManuelles,
            Long dureeSolveSecondes,
            Integer plancherMedium,
            Integer journeesSousConsigne,
            Double heuresFermeesParConsigne) {
        Set<String> stands = new LinkedHashSet<>();
        Set<String> creneaux = new LinkedHashSet<>();
        Map<String, Double> heuresParAnimateur = new LinkedHashMap<>();
        int pourvus = 0;
        int total = 0;
        boolean heuresIncompletes = false;
        for (AffectationKpi affectation : affectations) {
            stands.add(affectation.standId());
            creneaux.add(affectation.creneauId());
            if (!affectation.optionnel()) {
                total++;
            }
            if (affectation.animateurId() == null) {
                continue;
            }
            if (!affectation.optionnel()) {
                pourvus++;
            }
            if (affectation.dureeMinutes() == null) {
                heuresIncompletes = true;
            } else {
                heuresParAnimateur.merge(affectation.animateurId(), affectation.dureeMinutes() / 60.0, Double::sum);
            }
        }
        Dispersion dispersion = dispersion(heuresParAnimateur.values());
        int[] niveaux = parseScore(score);
        Double taux = modificationsManuelles == null || total == 0 ? null : modificationsManuelles / (double) total;
        return new PlanningKpi(
                score,
                niveaux == null ? null : niveaux[0],
                niveaux == null ? null : niveaux[1],
                niveaux == null ? null : niveaux[2],
                total,
                pourvus,
                heuresParAnimateur.size(),
                stands.size(),
                creneaux.size(),
                dispersion == null ? null : dispersion.total,
                dispersion == null ? null : dispersion.moyenne,
                dispersion == null ? null : dispersion.ecartType,
                dispersion == null ? null : dispersion.min,
                dispersion == null ? null : dispersion.max,
                heuresIncompletes,
                modificationsManuelles,
                taux,
                dureeSolveSecondes,
                violationsParContrainte == null ? Map.of() : violationsParContrainte,
                niveaux == null || plancherMedium == null ? null : niveaux[1] - plancherMedium,
                plancherMedium,
                journeesSousConsigne,
                heuresFermeesParConsigne);
    }

    private record Dispersion(double total, double moyenne, double ecartType, double min, double max) {}

    private static Dispersion dispersion(Collection<Double> valeurs) {
        if (valeurs.isEmpty()) {
            return null;
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
     * {@code 0hard/-3medium/-120soft}), or {@code null} when the text does not
     * carry them (unsolved score, older format). An {@code -Ninit/} prefix is
     * tolerated: the levels behind it still parse.
     */
    static int[] parseScore(String score) {
        if (score == null) {
            return null;
        }
        Matcher matcher = SCORE_PATTERN.matcher(score);
        if (!matcher.find()) {
            return null;
        }
        return new int[] {
            Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))
        };
    }
}
