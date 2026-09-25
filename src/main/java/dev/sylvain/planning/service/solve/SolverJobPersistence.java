package dev.sylvain.planning.service.solve;

import dev.sylvain.planning.service.solve.SolverJobRepository.LigneJob;
import dev.sylvain.planning.service.solve.SolverJobService.SolverJob;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * The {@code solver_job} row behind a {@link SolverJob}: written on every
 * transition, deleted when the job is forgotten, purged when it has aged out.
 *
 * <p>Split out of {@code SolverJobService} (issue #392, A11) because it holds
 * no lock and no state: it is the one part of the service whose failures are
 * <em>never</em> the run's — a row that could not be written is logged and the
 * solve goes on, which is the contract every caller here relies on. Keeping
 * that policy in one class is what keeps it uniform.</p>
 */
@ApplicationScoped
public class SolverJobPersistence {

    private static final Logger LOG = Logger.getLogger(SolverJobPersistence.class);

    private final SolverJobRepository repository;

    @Inject
    public SolverJobPersistence(SolverJobRepository repository) {
        this.repository = repository;
    }

    /** Every row, for the replay at startup. */
    List<LigneJob> list() {
        return repository.list();
    }

    /** Writes the job as it stands; a database refusal is logged, never propagated. */
    void store(SolverJob job) {
        try {
            repository.save(new LigneJob(
                    job.getId(),
                    job.getEditionId(),
                    job.getEditionNom(),
                    job.getType(),
                    job.getStatus(),
                    job.getSecondsLimit(),
                    job.getPlateauSeconds(),
                    job.getCappedFrom(),
                    job.getPerimetre(),
                    job.getReamorcage(),
                    job.isRejouable(),
                    job.getError(),
                    job.getSubmittedAt(),
                    job.getStartedAt(),
                    job.getFinishedAt()));
        } catch (RuntimeException e) {
            LOG.warnf(e, "Solver job %s could not be persisted; the run itself is unaffected", job.getId());
        }
    }

    void forget(String jobId) {
        try {
            repository.delete(jobId);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Solver job %s could not be deleted from the database", jobId);
        }
    }

    void purgeFinishedBefore(Instant cutoff) {
        try {
            repository.purgeFinishedBefore(cutoff);
        } catch (RuntimeException e) {
            LOG.warn("Expired solver jobs could not be purged from the database", e);
        }
    }
}
