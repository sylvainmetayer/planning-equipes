package dev.sylvain.planning.service;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.service.ContrainteAdHocContradictions.Contradiction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** The hand-entered constraints (affinités, incompatibilités, …) the solver reads as problem facts. */
@ApplicationScoped
public class ContrainteAdHocService {

    @Inject
    ContrainteAdHocRepository repository;

    @Inject
    CreneauService creneauService;

    @Inject
    ReferenceDataChangeTracker changeTracker;

    public List<ContrainteAdHoc> list() {
        return repository.listContraintes();
    }

    public ContrainteAdHoc create(ContrainteAdHoc contrainte) {
        contrainte.setId(Ids.required(contrainte.getId(), "constraint id"));
        refuseContradiction(contrainte);
        if (contrainte.getCreeLe() == null) {
            contrainte.setCreeLe(Instant.now());
        }
        repository.saveContrainte(contrainte);
        changeTracker.markModified();
        return contrainte;
    }

    public void delete(String id) {
        repository.deleteContrainte(id);
        changeTracker.markModified();
    }

    /**
     * Refuses a whole set at once, for the one entry point that writes several
     * exceptions in a single go: a scenario import. Same rules as the
     * single-entry check below — a file may not install a combination the form
     * refuses.
     *
     * <p>Every contradiction is named, not just the first: an import is fixed
     * by editing the file, and answering one contradiction per round-trip
     * would be a poor way to spend an afternoon.</p>
     */
    public void checkNoContradiction(List<ContrainteAdHoc> contraintes, List<Creneau> creneaux) {
        List<Contradiction> contradictions =
                ContrainteAdHocContradictions.detectAll(contraintes, creneaux);
        if (contradictions.isEmpty()) {
            return;
        }
        String detail = contradictions.stream().map(Contradiction::message).collect(Collectors.joining(" "));
        throw new BusinessError.Invalid("Les contraintes ad hoc de ce scénario se contredisent : " + detail);
    }

    /**
     * An exception that cannot hold alongside one already recorded is refused
     * here rather than discovered as a negative hard score two minutes into
     * the next solve (issue #84) — the failure mode this replaces is silent
     * and expensive: the user reads "the solver can't do it" where the truth
     * is "your own exceptions contradict each other".
     *
     * <p>The créneaux are read because two forced assignments only clash when
     * their slots overlap in time, and a {@link ContrainteAdHoc} carries the
     * créneau id alone. See {@link ContrainteAdHocContradictions} for what
     * counts as a contradiction — and for what is deliberately left to the
     * solver.</p>
     */
    private void refuseContradiction(ContrainteAdHoc contrainte) {
        List<Contradiction> contradictions =
                ContrainteAdHocContradictions.detect(contrainte, list(), creneauService.list());
        if (!contradictions.isEmpty()) {
            throw new BusinessError.Invalid(contradictions.getFirst().message());
        }
    }
}
