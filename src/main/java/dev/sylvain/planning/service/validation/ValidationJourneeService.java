package dev.sylvain.planning.service.validation;

import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.ValidationJournee;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.journal.JournalActionService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * « Relu et accepté » : the review state of a day, which the lock mechanism
 * never carried (see {@code docs/decisions/0033-validation-de-relecture.md}).
 *
 * <p>Three things live here and nowhere else: what a validation may name (a
 * day of the edition, and only a whole one), that validating may
 * <em>offer</em> to lay a lock down without ever doing it unasked, and that a
 * solve which moves a seat of a validated day withdraws that validation unless
 * the day was also frozen.</p>
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
     * Withdraws the validations of the days a solve moved a seat on, and says
     * how many were withdrawn — the figure the solve's recap reads out.
     *
     * <p>A day that also carries a {@link TypeVerrouillage#JOUR} lock keeps its
     * validation: the solver could not move anything there, so the reading
     * still describes what is in place. That exception is deliberately the
     * narrow one — a lock on a stand, a créneau or an animateur freezes part of
     * a day, and the rest of it is exactly what nobody has read since.</p>
     */
    public int withdrawMovedDays(Collection<LocalDate> joursBouges) {
        if (joursBouges == null || joursBouges.isEmpty()) {
            return 0;
        }
        Set<LocalDate> lockedDays = lockedDays();
        Set<LocalDate> valides = new LinkedHashSet<>();
        for (ValidationJournee validation : repository.list()) {
            if (joursBouges.contains(validation.jour()) && !lockedDays.contains(validation.jour())) {
                valides.add(validation.jour());
            }
        }
        return repository.deleteJours(valides);
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
