package dev.sylvain.planning.service.responsable;

import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** What the administration chooses the responsables de stand of the edition read: names or shifts only. */
@ApplicationScoped
public class ReglageResponsableService {

    private final ReglageResponsableRepository repository;
    private final ReferenceDataService referentiel;

    @Inject
    public ReglageResponsableService(ReglageResponsableRepository repository, ReferenceDataService referentiel) {
        this.repository = repository;
        this.referentiel = referentiel;
    }

    public ReglagesResponsables get() {
        return repository.get();
    }

    /**
     * Sets the default and the exceptions the body names ({@code null} lifts one); the stands it
     * does not name keep theirs. An exception on a stand of another edition is refused.
     */
    public ReglagesResponsables replace(ReglagesResponsables demande) {
        if (demande == null || demande.parDefaut() == null) {
            throw new BusinessError.Invalid("Réglage par défaut manquant.");
        }
        Map<String, ReglageNoms> exceptions = new LinkedHashMap<>();
        Set<String> connus = referentiel.listStands().stream().map(Stand::getId).collect(Collectors.toSet());
        if (demande.parStand() != null) {
            demande.parStand().forEach((standId, reglage) -> {
                if (!connus.contains(standId)) {
                    throw new BusinessError.Invalid("Stand inconnu dans cette édition : " + standId);
                }
                // null lifts the exception of that stand; a stand left out of the body is untouched.
                exceptions.put(standId, reglage);
            });
        }
        repository.replace(new ReglagesResponsables(demande.parDefaut(), exceptions));
        return repository.get();
    }
}
