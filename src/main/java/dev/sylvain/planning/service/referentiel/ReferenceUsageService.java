package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.service.BusinessError;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * How much a deletion would take with it, for the confirmation that precedes
 * it. One call answers for a whole selection: a bulk delete shows one total,
 * not one line per row.
 *
 * <p><b>An id nobody knows counts as zero rather than as a refusal.</b> The
 * caller is a confirmation dialog opened on rows a screen is already showing;
 * a row someone else deleted meanwhile must not turn that dialog into an error
 * message — the deletion itself will say so, with the 404 it deserves.</p>
 *
 * <p>Two requests are refused, and both name a broken query rather than a
 * missing row: the empty one, which asks nothing, and a timeslot id that is
 * not a number. Both answer {@code 400} — see {@link #creneauId}.</p>
 */
@ApplicationScoped
public class ReferenceUsageService {

    private final ReferenceUsageRepository repository;

    @Inject
    public ReferenceUsageService(ReferenceUsageRepository repository) {
        this.repository = repository;
    }

    public ReferenceUsage forStands(List<String> ids) {
        return repository.forStands(required(ids));
    }

    public ReferenceUsage forAnimateurs(List<String> ids) {
        return repository.forAnimateurs(required(ids));
    }

    /** Timeslot ids arrive as text and are converted here; see {@link #creneauId}. */
    public ReferenceUsage forCreneaux(List<String> ids) {
        return repository.forCreneaux(
                required(ids).stream().map(ReferenceUsageService::creneauId).toList());
    }

    private static List<String> required(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessError.Invalid("Aucun identifiant fourni : préciser au moins un id à compter");
        }
        return ids;
    }

    /**
     * A timeslot id is database-generated and numeric, yet the endpoint takes
     * it as text and converts it here.
     *
     * <p>Letting JAX-RS bind a {@code List<Long>} directly looks tidier and is
     * wrong: the container's conversion runs <em>before</em> the method is
     * entered and answers {@code 404} when it fails, which says "no such
     * resource" about a query field the caller mistyped — and contradicts what
     * this endpoint documents, where {@code 404} is precisely what an unknown
     * id never gets.</p>
     */
    private static long creneauId(String id) {
        try {
            return Long.parseLong(id.trim());
        } catch (NumberFormatException e) {
            throw new BusinessError.Invalid("Identifiant de créneau invalide : « " + id + " »", e);
        }
    }
}
