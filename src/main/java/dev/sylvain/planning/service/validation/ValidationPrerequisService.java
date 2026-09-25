package dev.sylvain.planning.service.validation;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.ValidationJournee;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.RapportFragilite;
import dev.sylvain.planning.service.analyse.PauseAnalyzer;
import dev.sylvain.planning.service.analyse.PauseAnalyzer.RapportPauses;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.ConstraintDiagnostic;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.PlanningDiagnostic;
import dev.sylvain.planning.service.solve.ConstraintAnalysisStore;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.solver.ConstraintCatalog;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What an organiser is shown <b>before</b> accepting a day, and how far the
 * reading has got over the edition.
 *
 * <p>Nothing here is a new analysis: the four prerequisites are the Problèmes,
 * Pauses and Fragilité screens narrowed to one date, plus the seats that day
 * leaves empty. Recomputing them differently would let this screen and those
 * three tell two stories about the same day.</p>
 *
 * <p><b>A prerequisite never blocks.</b> It is read out, and the validation
 * goes through either way — an organiser who knows a stand will close at
 * 18 h must be able to accept a day the analysis calls incomplete, and say so
 * in the comment. What the screen owes them is that they cannot accept it
 * <em>without knowing</em>.</p>
 */
@ApplicationScoped
public class ValidationPrerequisService {

    /** Zero hard-constraint violation on that day. */
    public static final String ECARTS_DURS = "ECARTS_DURS";
    /** Every break the day owes has somebody on the stand to take the relay. */
    public static final String PAUSES_NON_RELAYEES = "PAUSES_NON_RELAYEES";
    /** No seat of the day is left without an animateur. */
    public static final String SIEGES_VIDES = "SIEGES_VIDES";
    /** No seat of the day rests on somebody nobody could replace. */
    public static final String POSTES_IRREMPLACABLES = "POSTES_IRREMPLACABLES";

    private final PlanningPersistenceService persistenceService;

    private final ConstraintAnalysisStore analysisStore;

    private final PauseAnalyzer pauseAnalyzer;

    private final FragiliteAnalyzer fragiliteAnalyzer;

    private final ValidationJourneeService validationService;

    @Inject
    public ValidationPrerequisService(
            PlanningPersistenceService persistenceService,
            ConstraintAnalysisStore analysisStore,
            PauseAnalyzer pauseAnalyzer,
            FragiliteAnalyzer fragiliteAnalyzer,
            ValidationJourneeService validationService) {
        this.persistenceService = persistenceService;
        this.analysisStore = analysisStore;
        this.pauseAnalyzer = pauseAnalyzer;
        this.fragiliteAnalyzer = fragiliteAnalyzer;
        this.validationService = validationService;
    }

    /**
     * One prerequisite, as a figure rather than a sentence: the wording is the
     * screen's, in the reader's language.
     *
     * @param connu    false when the application cannot answer — no analysis of
     *                 the persisted plan is available. {@code nombre} is then
     *                 meaningless, and « non vérifié » is what the screen says,
     *                 never « satisfait »
     * @param nombre   how many times the prerequisite is missed that day
     */
    @Schema(requiredProperties = {"code", "connu", "nombre", "satisfait"})
    public record Prerequis(String code, boolean connu, boolean satisfait, int nombre) {}

    /**
     * The state of one day for the validation panel.
     *
     * @param validee       whether the day is already accepted
     * @param validationId  the reading that stands, so the panel withdraws
     *                      exactly the one it is showing; {@code null} when
     *                      the day is not accepted
     * @param valideeLe     when it was, {@code null} when it is not
     */
    @Schema(requiredProperties = {"jour", "prerequis", "tousSatisfaits", "validee"})
    public record PrerequisJournee(
            LocalDate jour,
            boolean validee,
            String validationId,
            Instant valideeLe,
            String validePar,
            String commentaire,
            List<Prerequis> prerequis,
            boolean tousSatisfaits) {}

    /**
     * How far the reading has got — « 3 journées sur 12 validées ».
     *
     * @param journees days the edition's timeslots span; zero before the grid
     *                 exists, and the banner then says nothing
     */
    @Schema(requiredProperties = {"journees", "journeesValidees", "joursValides"})
    public record ProgressionValidations(int journees, int journeesValidees, List<LocalDate> joursValides) {}

    /** How far the reading of the current edition has got. */
    public ProgressionValidations progression() {
        return progression(validationService.joursEvenement(), validationService.list());
    }

    /** The rule, on facts alone, so it is tested without a container. */
    static ProgressionValidations progression(Set<LocalDate> joursEvenement, List<ValidationJournee> validations) {
        // Only the days the grid actually holds: a validation left behind by a
        // créneau since deleted must not make the banner read « 13 sur 12 ».
        List<LocalDate> retenus = validations.stream()
                .map(ValidationJournee::jour)
                .filter(joursEvenement::contains)
                .distinct()
                .sorted(Comparator.naturalOrder())
                .toList();
        return new ProgressionValidations(joursEvenement.size(), retenus.size(), retenus);
    }

    /** What the panel shows before accepting {@code jour}. */
    public PrerequisJournee prerequis(LocalDate jour) {
        PlanningEvenement plan = persistenceService.loadPersistedPlanning();
        ConstraintAnalysisStore.StoredAnalysis analyse = analysisStore.latest();
        ValidationJournee validation = validationService.list().stream()
                .filter(candidate -> jour.equals(candidate.jour()))
                .findFirst()
                .orElse(null);
        return assemble(
                jour,
                validation,
                plan,
                analyse == null ? null : analyse.diagnostic(),
                pauseAnalyzer.analyze(plan),
                fragiliteAnalyzer.analyze(plan));
    }

    /**
     * The four readings of one day, decided on the reports alone.
     *
     * <p>{@code diagnostic} null is the honest « not known »: the analysis
     * lives in memory and a restart empties it, so the hard-violation line says
     * « non vérifié » rather than claiming zero.</p>
     */
    static PrerequisJournee assemble(
            LocalDate jour,
            ValidationJournee validation,
            PlanningEvenement plan,
            PlanningDiagnostic diagnostic,
            RapportPauses pauses,
            RapportFragilite fragilite) {
        List<Prerequis> prerequis = List.of(
                ecartsDurs(jour, plan, diagnostic),
                siegesVides(jour, plan),
                pausesNonRelayees(jour, pauses),
                postesIrremplacables(jour, fragilite));
        boolean tous = prerequis.stream().allMatch(p -> p.connu() && p.satisfait());
        return new PrerequisJournee(
                jour,
                validation != null,
                validation == null ? null : validation.id(),
                validation == null ? null : validation.valideLe(),
                validation == null ? null : validation.validePar(),
                validation == null ? null : validation.commentaire(),
                prerequis,
                tous);
    }

    /**
     * Hard violations landing on that day. A match is placed by the timeslot it
     * names — the only date a violation carries — so a rule matching on nothing
     * dated (a whole-edition rule) counts for no day in particular, which is
     * what the Problèmes screen already shows it as.
     */
    private static Prerequis ecartsDurs(LocalDate jour, PlanningEvenement plan, PlanningDiagnostic diagnostic) {
        if (diagnostic == null || diagnostic.contraintes() == null) {
            return new Prerequis(ECARTS_DURS, false, false, 0);
        }
        Map<Long, LocalDate> datesByCreneau = datesByCreneau(plan);
        int ecarts = 0;
        for (ConstraintDiagnostic contrainte : diagnostic.contraintes()) {
            if (!ConstraintCatalog.NOMS_DURS.contains(contrainte.name()) || contrainte.references() == null) {
                continue;
            }
            for (var reference : contrainte.references()) {
                if (reference.creneauId() != null && jour.equals(datesByCreneau.get(reference.creneauId()))) {
                    ecarts++;
                }
            }
        }
        return new Prerequis(ECARTS_DURS, true, ecarts == 0, ecarts);
    }

    /** Seats of the day nobody holds — the « sièges vides » of the issue. */
    private static Prerequis siegesVides(LocalDate jour, PlanningEvenement plan) {
        int vides = 0;
        for (PosteAffectation poste : seatsOfDay(plan, jour)) {
            if (poste.getAnimateur() == null) {
                vides++;
            }
        }
        return new Prerequis(SIEGES_VIDES, true, vides == 0, vides);
    }

    /**
     * Breaks that day with nobody on the stand to take the relay — the same
     * count the Pauses screen puts under « relais manquants », narrowed to one
     * date.
     */
    private static Prerequis pausesNonRelayees(LocalDate jour, RapportPauses pauses) {
        if (pauses == null || pauses.journees() == null) {
            return new Prerequis(PAUSES_NON_RELAYEES, false, false, 0);
        }
        int manquants = 0;
        for (var journee : pauses.journees()) {
            if (!jour.equals(journee.date()) || journee.sequences() == null) {
                continue;
            }
            for (var sequence : journee.sequences()) {
                for (var pause : sequence.pausesDues()) {
                    if (!pause.relaisDisponible()) {
                        manquants++;
                    }
                }
            }
        }
        return new Prerequis(PAUSES_NON_RELAYEES, true, manquants == 0, manquants);
    }

    /**
     * Seats of the day resting on somebody nobody could replace. Read off the
     * detailed seats of the fragility report: an animateur whose seats were
     * summarised rather than listed ({@code postesNonDetailles}) contributes
     * nothing here, which under-reports rather than invents.
     */
    private static Prerequis postesIrremplacables(LocalDate jour, RapportFragilite fragilite) {
        if (fragilite == null || fragilite.animateurs() == null) {
            return new Prerequis(POSTES_IRREMPLACABLES, false, false, 0);
        }
        int irremplacables = 0;
        for (var animateur : fragilite.animateurs()) {
            if (animateur.postes() == null) {
                continue;
            }
            for (FragiliteAnalyzer.PosteFragile poste : animateur.postes()) {
                if (poste.irremplacable() && jour.equals(poste.date())) {
                    irremplacables++;
                }
            }
        }
        return new Prerequis(POSTES_IRREMPLACABLES, true, irremplacables == 0, irremplacables);
    }

    private static List<PosteAffectation> seatsOfDay(PlanningEvenement plan, LocalDate jour) {
        List<PosteAffectation> postes = new ArrayList<>();
        if (plan == null || plan.getPostes() == null) {
            return postes;
        }
        for (PosteAffectation poste : plan.getPostes()) {
            Creneau creneau = poste.getCreneau();
            if (creneau != null && jour.equals(creneau.getDate())) {
                postes.add(poste);
            }
        }
        return postes;
    }

    /** Which date each timeslot of the plan falls on — what places a violation on a day. */
    static Map<Long, LocalDate> datesByCreneau(PlanningEvenement plan) {
        Map<Long, LocalDate> dates = new LinkedHashMap<>();
        if (plan == null) {
            return dates;
        }
        if (plan.getPostes() == null) {
            return dates;
        }
        // The seats are where the timeslots of a persisted plan are reachable
        // from: the solution holds no créneau list of its own.
        for (PosteAffectation poste : plan.getPostes()) {
            Creneau creneau = poste.getCreneau();
            if (creneau != null && creneau.getId() != null && creneau.getDate() != null) {
                dates.putIfAbsent(creneau.getId(), creneau.getDate());
            }
        }
        return dates;
    }
}
