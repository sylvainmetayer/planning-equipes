package dev.sylvain.planning.service.validation;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.ValidationJournee;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.analyse.FeasibilityAnalyzer;
import dev.sylvain.planning.service.journal.JournalActionService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * « Relu et accepté » : the review state of a day, which the lock mechanism
 * never carried (see {@code docs/decisions/0033-validation-de-relecture.md}).
 *
 * <p>Three things live here and nowhere else: what a validation may name (a
 * day of the edition, and only a whole one), that validating may
 * <em>offer</em> to lay a lock down without ever doing it unasked, and that a
 * solve which recomputed a validated day withdraws that validation — unless
 * the day was in the frozen past or under a {@code JOUR} lock, or a solve
 * started from the plan in place gave it back unchanged.</p>
 */
@ApplicationScoped
public class ValidationJourneeService {

    /** Longest comment a reviewer may leave; past that it is a document, not a note. */
    static final int COMMENTAIRE_MAX = 500;

    private final ValidationJourneeRepository repository;

    private final ReferenceDataService referenceDataService;

    private final JournalActionService journal;

    @Inject
    public ValidationJourneeService(
            ValidationJourneeRepository repository,
            ReferenceDataService referenceDataService,
            JournalActionService journal) {
        this.repository = repository;
        this.referenceDataService = referenceDataService;
        this.journal = journal;
    }

    /**
     * What a validation request carries.
     *
     * @param poserVerrou also freeze the day for the next solves. Offered, never
     *                    implied: the two answer different questions, and a
     *                    reviewer who wants to keep re-solving must be able to
     *                    say « relu » without saying « figé »
     */
    public record DemandeValidation(LocalDate jour, String commentaire, boolean poserVerrou) {}

    /** A validation, and the lock it laid down when it was asked to. */
    public record ResultatValidation(ValidationJournee validation, boolean verrouPose) {}

    public List<ValidationJournee> list() {
        return repository.list();
    }

    /**
     * Whether this edition carries any reading at all.
     *
     * <p>Read before a solve builds the before-image it would compare days on:
     * that image is the whole persisted plan, seat by seat, and an edition
     * nobody reviews must not pay for it on every run.</p>
     */
    public boolean hasValidations() {
        return !repository.list().isEmpty();
    }

    /**
     * Records a reading of one day, and lays the lock down when the request
     * asked for one.
     *
     * <p>Re-validating a day already read replaces its row rather than piling a
     * second one: what is worth keeping is the reading that stands, with its
     * date and its comment.</p>
     */
    public ResultatValidation accept(DemandeValidation demande) {
        if (demande == null || demande.jour() == null) {
            throw new BusinessError.Invalid("Journée à valider manquante");
        }
        LocalDate jour = demande.jour();
        if (!joursEvenement().contains(jour)) {
            throw new BusinessError.Invalid("Aucun créneau ce jour-là : " + jour);
        }
        String commentaire = checkedComment(demande.commentaire());
        ValidationJournee validation = new ValidationJournee(
                UUID.randomUUID().toString(), jour, Instant.now(), journal.nomAdmin(), commentaire);
        repository.save(validation);
        boolean verrouPose = demande.poserVerrou() && lockDay(jour);
        return new ResultatValidation(validation, verrouPose);
    }

    /** Withdraws one reading. An unknown id is reported rather than silently accepted. */
    public void withdraw(String id) {
        if (id == null || id.isBlank()) {
            throw new BusinessError.Invalid("Id de validation manquant");
        }
        if (repository.delete(id) == 0) {
            throw new BusinessError.NotFound("Validation introuvable : " + id);
        }
    }

    /**
     * Why a reading outlived a solve — one line of the recap each.
     *
     * <ul>
     *   <li>{@link #PASSE}: every seat of the day had started; the solve
     *       re-seeded and pinned them (ADR 0044), so what was read is what was
     *       worked;</li>
     *   <li>{@link #VERROU}: the day carries a {@link TypeVerrouillage#JOUR}
     *       lock, and the solver could move nothing there;</li>
     *   <li>{@link #INCHANGEE}: a solve started from the plan in place gave the
     *       day back exactly as it was read. Never on a cold start, which keeps
     *       no reading of a day it recomputed.</li>
     * </ul>
     */
    public enum KeepReason {
        PASSE,
        VERROU,
        INCHANGEE
    }

    /** A reading a cold start would keep, and why — what its confirmation lists. */
    @Schema(requiredProperties = {"jour", "motif"})
    public record KeptReading(LocalDate jour, KeepReason motif) {}

    /**
     * What a solve did to the readings: how many it withdrew, and how many it
     * kept, by reason. All zero on an edition nobody reviews.
     */
    public record ReadingsOutcome(int withdrawn, int keptPast, int keptLocked, int keptUnchanged) {

        public static final ReadingsOutcome NONE = new ReadingsOutcome(0, 0, 0, 0);

        public boolean isEmpty() {
            return withdrawn + keptPast + keptLocked + keptUnchanged == 0;
        }
    }

    /**
     * Withdraws the readings a solve made stale, and says what it kept and why
     * — the figures of the solve's recap.
     *
     * <p>A reading survives a solve when the day it describes was not
     * recomputed, and only then (ADR 0039, revised). Two kinds of day are never
     * recomputed, whatever the solve: a day entirely in the frozen past, and a
     * day under a {@link TypeVerrouillage#JOUR} lock. Every other day is,
     * and what happens to its reading depends on where the solve started:</p>
     *
     * <ul>
     *   <li>from the plan in place — the default, and the incremental re-solve —
     *       the solve improved what was read: a day it gave back unchanged keeps
     *       its reading, a day where a seat moved loses it;</li>
     *   <li>from scratch ({@code coldStart}) — « Recommencer de zéro » — the plan
     *       that was read was thrown away, so every reading of a recomputed day
     *       goes, even if the day happened to land on the same crew: an
     *       identical result is a coincidence nobody has looked at.</li>
     * </ul>
     *
     * <p>Only a {@code JOUR} lock saves a reading: a lock on a stand, a timeslot
     * or an animateur freezes part of a day, and the rest of it is exactly what
     * nobody has read since.</p>
     *
     * @param joursBouges the days on which the solve moved a seat
     * @param joursFiges  the days whose every seat had started (ADR 0044)
     * @param coldStart   whether the solve started from scratch
     */
    public ReadingsOutcome withdrawAfterSolve(
            Collection<LocalDate> joursBouges, Collection<LocalDate> joursFiges, boolean coldStart) {
        List<ValidationJournee> validations = repository.list();
        if (validations.isEmpty()) {
            return ReadingsOutcome.NONE;
        }
        Set<LocalDate> bouges = joursBouges == null ? Set.of() : Set.copyOf(joursBouges);
        Set<LocalDate> figes = joursFiges == null ? Set.of() : Set.copyOf(joursFiges);
        Set<LocalDate> lockedDays = lockedDays();
        Set<LocalDate> retires = new LinkedHashSet<>();
        int keptPast = 0;
        int keptLocked = 0;
        int keptUnchanged = 0;
        for (LocalDate jour : validatedDays(validations)) {
            if (figes.contains(jour)) {
                keptPast++;
            } else if (lockedDays.contains(jour)) {
                keptLocked++;
            } else if (coldStart || bouges.contains(jour)) {
                retires.add(jour);
            } else {
                keptUnchanged++;
            }
        }
        int withdrawn = retires.isEmpty() ? 0 : repository.deleteJours(retires);
        return new ReadingsOutcome(withdrawn, keptPast, keptLocked, keptUnchanged);
    }

    /**
     * The readings a cold start would keep, and why — read before it is
     * launched, so its confirmation can say which reviewed days survive it.
     * The same rule as {@link #withdrawAfterSolve}, judged on the timeslots
     * rather than on seats not generated yet: a day is in the frozen past once
     * every one of its timeslots has started at its effective start, as
     * {@link FeasibilityAnalyzer#hasStarted} reads it.
     *
     * @param horizon the moment the past is judged against; {@code null} when
     *                the freeze is off, and then no day is past
     */
    public List<KeptReading> keptByColdStart(PastHorizon horizon) {
        List<ValidationJournee> validations = repository.list();
        if (validations.isEmpty()) {
            return List.of();
        }
        Set<LocalDate> lockedDays = lockedDays();
        Set<LocalDate> figes = horizon == null ? Set.of() : frozenDays(horizon);
        List<KeptReading> gardees = new ArrayList<>();
        for (LocalDate jour : validatedDays(validations)) {
            if (figes.contains(jour)) {
                gardees.add(new KeptReading(jour, KeepReason.PASSE));
            } else if (lockedDays.contains(jour)) {
                gardees.add(new KeptReading(jour, KeepReason.VERROU));
            }
        }
        return gardees;
    }

    /** The days every timeslot of which had started at {@code horizon}. */
    private Set<LocalDate> frozenDays(PastHorizon horizon) {
        List<Stand> stands = referenceDataService.listSolvedStands();
        Map<LocalDate, Boolean> parJour = new HashMap<>();
        for (Creneau creneau : referenceDataService.listCreneaux()) {
            if (creneau.getDate() != null) {
                parJour.merge(
                        creneau.getDate(),
                        FeasibilityAnalyzer.hasStarted(creneau, stands, horizon),
                        Boolean::logicalAnd);
            }
        }
        Set<LocalDate> figes = new HashSet<>();
        parJour.forEach((jour, commence) -> {
            if (commence) {
                figes.add(jour);
            }
        });
        return figes;
    }

    private static List<LocalDate> validatedDays(List<ValidationJournee> validations) {
        return validations.stream()
                .map(ValidationJournee::jour)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * Withdraws the validations of {@code jours}, lock or not, and says how
     * many were withdrawn. A consigne (issue #4) closes a band on the day and
     * reopens stands on it: the day that was read is no longer the day that
     * will be worked, frozen or not — a lock pins seats, and the seats of the
     * band are gone.
     */
    public int withdrawDays(Collection<LocalDate> jours) {
        return repository.deleteJours(daysValidatedAmong(jours));
    }

    /** Same as {@link #withdrawDays(Collection)}, inside the caller's transaction (ADR 0028). */
    public int withdrawDays(Connection connection, Collection<LocalDate> jours) throws SQLException {
        return repository.deleteJours(connection, daysValidatedAmong(jours));
    }

    private Set<LocalDate> daysValidatedAmong(Collection<LocalDate> jours) {
        Set<LocalDate> valides = new LinkedHashSet<>();
        if (jours == null || jours.isEmpty()) {
            return valides;
        }
        for (ValidationJournee validation : repository.list()) {
            if (jours.contains(validation.jour())) {
                valides.add(validation.jour());
            }
        }
        return valides;
    }

    /** The days the edition's timeslots span — the only ones a reading can name. */
    Set<LocalDate> joursEvenement() {
        Set<LocalDate> jours = new LinkedHashSet<>();
        referenceDataService.listCreneaux().forEach(creneau -> {
            if (creneau.getDate() != null) {
                jours.add(creneau.getDate());
            }
        });
        return jours;
    }

    private Set<LocalDate> lockedDays() {
        Set<LocalDate> jours = new LinkedHashSet<>();
        for (VerrouillagePlanning verrouillage : referenceDataService.listVerrouillages()) {
            if (verrouillage.getType() == TypeVerrouillage.JOUR && verrouillage.getJour() != null) {
                jours.add(verrouillage.getJour());
            }
        }
        return jours;
    }

    /** Whether a lock had to be created; an already-frozen day is not one laid down here. */
    private boolean lockDay(LocalDate jour) {
        if (lockedDays().contains(jour)) {
            return false;
        }
        VerrouillagePlanning verrouillage = new VerrouillagePlanning();
        verrouillage.setType(TypeVerrouillage.JOUR);
        verrouillage.setJour(jour);
        verrouillage.setRaison("Journée relue et acceptée");
        referenceDataService.createVerrouillage(verrouillage);
        return true;
    }

    private static String checkedComment(String commentaire) {
        if (commentaire == null || commentaire.isBlank()) {
            return null;
        }
        String propre = commentaire.strip();
        if (propre.length() > COMMENTAIRE_MAX) {
            throw new BusinessError.Invalid(
                    "Commentaire de validation trop long (maximum " + COMMENTAIRE_MAX + " caractères)");
        }
        return propre;
    }
}
