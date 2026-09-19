package dev.sylvain.planning.service.journal;

import dev.sylvain.planning.service.journal.EntreeJournal.Resultat;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Writes and reads the history of what was done in the application
 * (issue #406).
 *
 * <p><b>A trace never costs an action.</b> Every write here is best-effort and
 * swallows its own failure, like the automatic snapshot and the KPI row: an
 * animateur that was created must not be un-created because the journal was
 * unreachable. The failure is logged, loudly enough for an operator to notice
 * the history has stopped filling.</p>
 *
 * <p><b>And it never carries an identity.</b> What goes in is an identifier
 * and a list of field names; the screen joins the name from the referential
 * when it reads, so a deleted fiche leaves a line naming nobody — the same
 * rule as {@code notification_planifiee}, and the reason this table can
 * outlive the people it describes for as long as its retention allows.</p>
 */
@ApplicationScoped
public class JournalActionService {

    private static final Logger LOG = Logger.getLogger(JournalActionService.class);

    /** What one page of the screen shows when the caller does not say. */
    static final int LIMITE_DEFAUT = 200;

    @Inject
    JournalActionRepository repository;

    @Inject
    SecurityIdentity identity;

    /**
     * How long a line is kept. Ninety days by default: long enough to answer
     * « qui a touché à ça avant l'événement ? » a full season later, short
     * enough that a journal nobody reads does not become a personal-data
     * store of its own (RGPD, limitation de conservation).
     */
    @ConfigProperty(name = "planning.journal.retention", defaultValue = "P90D")
    Duration retention;

    /** A day at the very least: below that the nightly sweep would empty the table it purges. */
    static final Duration RETENTION_MINIMALE = Duration.ofDays(1);

    /** Ten years: past that the journal stops being a journal and becomes an archive nobody decided to keep. */
    static final Duration RETENTION_MAXIMALE = Duration.ofDays(3650);

    /**
     * Refuses to start on a retention outside its bounds, rather than trimming
     * it in silence — the same contract as {@code BACKUP_RETENTION}, and for a
     * sharper reason: {@code JOURNAL_RETENTION=P0D} makes every night's sweep
     * delete the entire history, and the only trace of it would be one
     * information line in a log.
     */
    void verifierRetention(@Observes StartupEvent demarrage) {
        if (retention.compareTo(RETENTION_MINIMALE) < 0 || retention.compareTo(RETENTION_MAXIMALE) > 0) {
            throw new IllegalStateException("planning.journal.retention must be between " + RETENTION_MINIMALE + " and "
                    + RETENTION_MAXIMALE + ", but is " + retention);
        }
    }

    /** Records an action carried out through a request, with the status it ended on. */
    public void record(
            ActionJournalisee action,
            Acteur acteur,
            String acteurId,
            String entiteId,
            List<String> champs,
            int statut) {
        append(new EntreeJournal(
                0,
                Instant.now(),
                acteur,
                acteurId,
                action.code(),
                action.entite() == null ? null : action.entite().name(),
                entiteId,
                champs,
                statut >= 400 ? Resultat.REFUS : Resultat.SUCCES,
                statut));
    }

    /**
     * Records a second action the admin's request carried out, beside the one
     * its route names.
     *
     * <p>The filter writes exactly one line per call, which is right for the
     * ordinary case and wrong for a call that acts on several people at once:
     * a publication that defers three messages performs three acts on three
     * fiches, and « Planning publié » alone would leave no trace of any of
     * them. What goes in is the id, as everywhere else here — the name is
     * joined when the history is read.</p>
     */
    public void recordAdminAction(String code, String entiteId) {
        ActionJournalisee action = CatalogueActions.systeme(code);
        append(new EntreeJournal(
                0,
                Instant.now(),
                Acteur.ADMIN,
                nomAdmin(),
                action.code(),
                action.entite() == null ? null : action.entite().name(),
                entiteId,
                List.of(),
                Resultat.SUCCES,
                null));
    }

    /** Records something the application did on its own, off any request. */
    public void recordSystemAction(String code, String entiteId) {
        ActionJournalisee action = CatalogueActions.systeme(code);
        append(new EntreeJournal(
                0,
                Instant.now(),
                Acteur.SYSTEME,
                null,
                action.code(),
                action.entite() == null ? null : action.entite().name(),
                entiteId,
                List.of(),
                Resultat.SUCCES,
                null));
    }

    private void append(EntreeJournal entree) {
        try {
            repository.append(entree);
        } catch (RuntimeException e) {
            LOG.errorf(
                    e, "The action %s could not be recorded in the history; it happened all the same", entree.action());
        }
    }

    /** The edition's most recent actions, newest first. */
    public List<EntreeJournal> list(Integer limite) {
        return repository.list(limite == null ? LIMITE_DEFAUT : limite);
    }

    /**
     * What changed in the problem since {@code depuis}: the count per
     * referential family, and the {@code limite} most recent lines.
     *
     * <p>Only what {@code CatalogueActions} declares as changing the data a
     * solve is given — the screens ask this to say <em>what</em> moved under
     * « des données de référence ont été modifiées depuis cette
     * résolution », and a mail sent since is not an answer to that.</p>
     */
    public ReferenceDataChanges changesSince(Instant depuis, int limite) {
        Set<String> codes = CatalogueActions.codesChangingData();
        return ReferenceDataChanges.of(
                repository.countSince(depuis, codes), repository.listSince(depuis, codes, limite));
    }

    /**
     * Drops what has aged out. Called by the nightly job; returns how many
     * lines went, so the caller can log a figure rather than a fact.
     */
    public int purge() {
        return repository.purgeBefore(Instant.now().minus(retention));
    }

    public Duration retention() {
        return retention;
    }

    /** The admin's principal name, {@code null} when the request carries no identity. */
    public String nomAdmin() {
        try {
            return identity == null || identity.isAnonymous() || identity.getPrincipal() == null
                    ? null
                    : identity.getPrincipal().getName();
        } catch (RuntimeException e) {
            // No active request (a scheduled job, a solver thread): there is
            // simply nobody to name.
            return null;
        }
    }
}
