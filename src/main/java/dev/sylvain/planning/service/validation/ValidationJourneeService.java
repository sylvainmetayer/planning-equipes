package dev.sylvain.planning.service.validation;

import dev.sylvain.planning.domain.DayState;
import dev.sylvain.planning.domain.PastHorizon;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The state of a day — to be read, read, frozen (ADR 0121) — and the reading
 * behind it (ADR 0039).
 *
 * <p>The state is derived, never stored: {@link DayState#FIGEE} is the
 * {@link TypeVerrouillage#JOUR} lock, {@link DayState#RELUE} a reading
 * without one. Four things live here and nowhere else: what a state may name
 * (a day of the edition, and only a whole one); that a lock is laid down
 * only when asked — {@link DayState#FIGEE}, or the deprecated
 * {@code poserVerrou} of {@link #accept}; that here, only leaving the frozen
 * state lifts one; and that a solve which recomputed a read
 * day withdraws its reading — unless the day was in the frozen past or
 * frozen, or a solve started from the plan in place gave it back unchanged
 * (ADR 0069).</p>
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
     * Whether a day of this edition is read or frozen at all.
     *
     * <p>Read before a solve builds the before-image it would compare days on:
     * that image is the whole persisted plan, seat by seat, and an edition
     * nobody reviews must not pay for it on every run.</p>
     */
    public boolean hasValidations() {
        return !repository.list().isEmpty() || !lockedDays().isEmpty();
    }

    /**
     * One day and where it stands, with the reading behind it when there is
     * one — a frozen day laid down from the Verrouillages screen has none.
     *
     * @param validationId the reading, {@code null} when the day carries none
     */
    @Schema(requiredProperties = {"jour", "etat"})
    public record DayStateView(
            LocalDate jour,
            DayState etat,
            String validationId,
            Instant valideLe,
            String validePar,
            String commentaire) {}

    /** Every day of the edition's grid and its state, in calendar order. */
    public List<DayStateView> states() {
        Map<LocalDate, ValidationJournee> readings = readingsByDay();
        Set<LocalDate> locked = lockedDays();
        return joursEvenement().stream()
                .sorted()
                .map(jour -> view(jour, readings.get(jour), locked.contains(jour)))
                .toList();
    }

    /**
     * Moves one day to {@code etat}, with the comment of the reading it
     * records. Asking for {@link DayState#FIGEE} lays the {@code JOUR} lock
     * down, asking for another state lifts it — never a side effect (ADR
     * 0039).
     *
     * <ul>
     *   <li>{@link DayState#FIGEE}: records the reading, lays the lock if the
     *       day has none;</li>
     *   <li>{@link DayState#RELUE}: records the reading, lifts the day's
     *       lock;</li>
     *   <li>{@link DayState#A_RELIRE}: withdraws both.</li>
     * </ul>
     */
    public DayStateView setState(LocalDate jour, DayState etat, String commentaire) {
        if (jour == null) {
            throw new BusinessError.Invalid("Journée manquante");
        }
        if (etat == null) {
            throw new BusinessError.Invalid("État manquant : A_RELIRE, RELUE ou FIGEE");
        }
        if (etat != DayState.A_RELIRE && !joursEvenement().contains(jour)) {
            throw new BusinessError.Invalid("Aucun créneau ce jour-là : " + jour);
        }
        String checked = checkedComment(commentaire);
        if (etat == DayState.A_RELIRE) {
            repository.deleteJours(List.of(jour));
        } else {
            repository.save(new ValidationJournee(
                    UUID.randomUUID().toString(), jour, Instant.now(), journal.nomAdmin(), checked));
        }
        if (etat == DayState.FIGEE) {
            lockDay(jour);
        } else {
            unlockDay(jour);
        }
        return view(jour, readingsByDay().get(jour), lockedDays().contains(jour));
    }

    /** Where one day stands, grid or not. */
    public DayState stateOf(LocalDate jour) {
        return DayState.of(readingsByDay().containsKey(jour), lockedDays().contains(jour));
    }

    private static DayStateView view(LocalDate jour, ValidationJournee reading, boolean locked) {
        return new DayStateView(
                jour,
                DayState.of(reading != null, locked),
                reading == null ? null : reading.id(),
                reading == null ? null : reading.valideLe(),
                reading == null ? null : reading.validePar(),
                reading == null ? null : reading.commentaire());
    }

    private Map<LocalDate, ValidationJournee> readingsByDay() {
        Map<LocalDate, ValidationJournee> byDay = new LinkedHashMap<>();
        repository.list().stream()
                .filter(reading -> reading.jour() != null)
                .forEach(reading -> byDay.putIfAbsent(reading.jour(), reading));
        return byDay;
    }

    /**
     * The days that are not to be read any more — read or frozen, the rule of
     * {@link DayState#of} — whatever the grid holds: the progression narrows
     * them to the grid's days, the solve's recap does not need to.
     */
    public Set<LocalDate> reviewedDays() {
        return reviewedDays(readingsByDay().keySet(), lockedDays());
    }

    /** The rule, on facts alone: a day read, frozen, or both. */
    static Set<LocalDate> reviewedDays(Collection<LocalDate> read, Collection<LocalDate> locked) {
        Set<LocalDate> days = new TreeSet<>(read);
        days.addAll(locked);
        return days;
    }

    /**
     * The 3.x gesture, kept for the routes and tools it serves until 5.0:
     * records a reading of one day, and lays the lock down when the request
     * asked for one — {@link DayState#RELUE}, or {@link DayState#FIGEE} with
     * the lock. Unlike {@link #setState}, it never lifts a lock: a day frozen
     * before stays frozen.
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

    /**
     * Withdraws one reading: a read day goes back to be read, a frozen one
     * stays frozen (its lock is left alone, as in 3.x). An unknown id is
     * reported rather than silently accepted.
     */
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
        Set<LocalDate> lockedDays = lockedDays();
        Set<LocalDate> reviewed = reviewedDays(readingsByDay().keySet(), lockedDays);
        if (reviewed.isEmpty()) {
            return ReadingsOutcome.NONE;
        }
        Set<LocalDate> bouges = joursBouges == null ? Set.of() : Set.copyOf(joursBouges);
        Set<LocalDate> figes = joursFiges == null ? Set.of() : Set.copyOf(joursFiges);
        Set<LocalDate> retires = new LinkedHashSet<>();
        int keptPast = 0;
        int keptLocked = 0;
        int keptUnchanged = 0;
        for (LocalDate jour : reviewed) {
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
     * rather than on seats not generated yet, by
     * {@link FeasibilityAnalyzer#frozenDays} — a timeslot no stand opens
     * holds no seat and freezes nothing, as on the seats of a solve.
     *
     * @param horizon the moment the past is judged against; {@code null} when
     *                the freeze is off, and then no day is past
     */
    public List<KeptReading> keptByColdStart(PastHorizon horizon) {
        Set<LocalDate> lockedDays = lockedDays();
        Set<LocalDate> reviewed = reviewedDays(readingsByDay().keySet(), lockedDays);
        if (reviewed.isEmpty()) {
            return List.of();
        }
        Set<LocalDate> figes = FeasibilityAnalyzer.frozenDays(
                referenceDataService.listCreneaux(), referenceDataService.listSolvedStands(), horizon);
        List<KeptReading> gardees = new ArrayList<>();
        for (LocalDate jour : reviewed) {
            if (figes.contains(jour)) {
                gardees.add(new KeptReading(jour, KeepReason.PASSE));
            } else if (lockedDays.contains(jour)) {
                gardees.add(new KeptReading(jour, KeepReason.VERROU));
            }
        }
        return gardees;
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
        if (jours == null || jours.isEmpty()) {
            return new LinkedHashSet<>();
        }
        return repository.list().stream()
                .map(ValidationJournee::jour)
                .filter(jours::contains)
                .collect(Collectors.toCollection(LinkedHashSet::new));
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
        return referenceDataService.listVerrouillages().stream()
                .filter(verrouillage ->
                        verrouillage.getType() == TypeVerrouillage.JOUR && verrouillage.getJour() != null)
                .map(VerrouillagePlanning::getJour)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Lifts the day's {@code JOUR} lock — only ever asked for by leaving the frozen state. */
    private void unlockDay(LocalDate jour) {
        referenceDataService.listVerrouillages().stream()
                .filter(verrouillage ->
                        verrouillage.getType() == TypeVerrouillage.JOUR && jour.equals(verrouillage.getJour()))
                .map(VerrouillagePlanning::getId)
                .toList()
                .forEach(referenceDataService::deleteVerrouillage);
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
