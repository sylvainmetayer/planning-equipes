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

    @Inject
    ReglageResponsableRepository repository;

    @Inject
    ReferenceDataService referentiel;

    public ReglagesResponsables get() {
        return repository.get();
    }

    /** Replaces the default and the exceptions; an exception on a stand of another edition is refused. */
    public ReglagesResponsables replace(ReglagesResponsables demande) {
        if (demande == null || demande.parDefaut() == null) {
            throw new BusinessError.Invalid("Réglage par défaut manquant.");
        }
        Map<String, ReglageNoms> exceptions = new LinkedHashMap<>();
        Set<String> connus = referentiel.listStands().stream().map(Stand::getId).collect(Collectors.toSet());
        if (demande.parStand() != null) {
            demande.parStand().forEach((standId, reglage) -> {
                if (reglage == null || !connus.contains(standId)) {
                    throw new BusinessError.Invalid("Stand inconnu dans cette édition : " + standId);
                }
                exceptions.put(standId, reglage);
            });
        }
        repository.replace(new ReglagesResponsables(demande.parDefaut(), exceptions));
        return repository.get();
    }
}
