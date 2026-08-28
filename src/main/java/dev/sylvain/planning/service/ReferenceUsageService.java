package dev.sylvain.planning.service;

import java.util.Collection;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * How much a deletion would take with it, for the confirmation that precedes
 * it. One call answers for a whole selection: a bulk delete shows one total,
 * not one line per row.
 *
 * <p><b>An id nobody knows counts as zero rather than as a refusal.</b> The
 * caller is a confirmation dialog opened on rows a screen is already showing;
 * a row someone else deleted meanwhile must not turn that dialog into an error
 * message — the deletion itself will say so, with the 404 it deserves. The one
 * request refused here is the empty one, which asks nothing.</p>
 */
@ApplicationScoped
public class ReferenceUsageService {

    @Inject
    ReferenceUsageRepository repository;

    public ReferenceUsage forStands(List<String> ids) {
        return repository.forStands(required(ids));
    }

    public ReferenceUsage forAnimateurs(List<String> ids) {
        return repository.forAnimateurs(required(ids));
    }

    public ReferenceUsage forCreneaux(List<Long> ids) {
        return repository.forCreneaux(required(ids));
    }

    private static <T> Collection<T> required(List<T> ids) {
        if (ids == null || ids.isEmpty()) {
            throw new BusinessError.Invalid("At least one id must be given to count what references it");
        }
        return ids;
    }
}
