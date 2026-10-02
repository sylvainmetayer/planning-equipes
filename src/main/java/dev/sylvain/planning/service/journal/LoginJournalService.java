package dev.sylvain.planning.service.journal;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * The journal of the admin form login: every successful login, every failure
 * and every lockout, with its time and the client address — the trace the
 * history of actions cannot carry, since {@code /j_security_check} is handled
 * before JAX-RS and before any edition is named (ADR 0076).
 *
 * <p><b>A trace never costs a login</b>, as for the history: a write that
 * fails is logged and swallowed, and the attempt is decided all the same.</p>
 *
 * <p>Kept as long as the history ({@code JOURNAL_RETENTION}), purged on the
 * same night: an address is personal data, and the question this journal
 * answers — « qui s'est connecté, et quelqu'un a-t-il forcé le mot de passe ? »
 * — is the same season-long one.</p>
 */
@ApplicationScoped
public class LoginJournalService {

    private static final Logger LOG = Logger.getLogger(LoginJournalService.class);

    /** What one page of the screen shows when the caller does not say. */
    static final int LIMITE_DEFAUT = 200;

    private final LoginJournalRepository repository;

    @Inject
    public LoginJournalService(LoginJournalRepository repository) {
        this.repository = repository;
    }

    /**
     * Records the lines one attempt produced, in order — a failure, and the
     * lockout it triggered when it was the last one tolerated. Blocking: the
     * caller, which runs on the event loop, hands it to a worker.
     *
     * <p>A write that fails says so in one line, without its stack trace: a
     * database gone away while the form is hammered would otherwise bury the
     * log under one trace per attempt.</p>
     */
    public void record(List<LoginJournalEntry> entries) {
        try {
            repository.append(entries);
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            LOG.warnf("An admin login attempt could not be recorded; it was decided all the same: %s", cause);
        }
    }

    /** One page, newest first, after the line {@code before} when the reader is paging. */
    public List<LoginJournalEntry> page(Long before, Integer limite) {
        return repository.page(before, limite == null ? LIMITE_DEFAUT : limite);
    }

    /** Drops what is older than {@code cutoff}; returns how many lines went. */
    public int purgeBefore(Instant cutoff) {
        return repository.purgeBefore(cutoff);
    }
}
