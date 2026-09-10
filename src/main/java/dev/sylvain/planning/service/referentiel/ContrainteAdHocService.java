package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.ConcurrentModificationGuard;
import dev.sylvain.planning.service.Ids;
import dev.sylvain.planning.service.ReferenceDataChangeTracker;
import dev.sylvain.planning.service.referentiel.ContrainteAdHocContradictions.Contradiction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/** The hand-entered constraints (affinités, incompatibilités, …) the solver reads as problem facts. */
@ApplicationScoped
public class ContrainteAdHocService {

    @Inject
    ContrainteAdHocRepository repository;

    @Inject
    CreneauService creneauService;

    @Inject
    ReferenceDataChangeTracker changeTracker;

    @Inject
    ConcurrentModificationGuard staleWrites;

    public List<ContrainteAdHoc> list() {
        return repository.listContraintes();
    }

    /**
     * Create-or-overwrite by id — the only write this resource has, so the
     * concurrent-edit check of the other referentials lives here: a payload
     * carrying the {@code modifieLe} it loaded is refused when the row moved
     * since, a payload without one (a creation, an import) is not checked.
     */
    public ContrainteAdHoc create(ContrainteAdHoc contrainte) {
        contrainte.setId(Ids.required(contrainte.getId(), "constraint id"));
        refuseContradiction(contrainte);
        if (contrainte.getCreeLe() == null) {
            contrainte.setCreeLe(Instant.now());
        }
        // POST is the only write path for an ajustement: re-posting an id is
        // how it is edited, so a taken id is not a duplicate — the stale
        // precondition below is what protects the other session's version.
        repository.saveContrainte(contrainte, false);
        changeTracker.markModified();
        return contrainte;
    }

    /**
     * Writes several exceptions as one indivisible gesture: every one of them
     * is checked against what is already recorded <b>and</b> against the ones
     * ahead of it in the batch, and nothing is written unless all of them pass.
     *
     * <p>Calling {@link #create} in a loop would leave the first entries behind
     * when the third is refused, which is worse than refusing outright: the
     * caller is told "no" while half of its intent is already in the database.
     * The one gesture that needs this is marking somebody absent for the rest
     * of a day (issue #297) — one exception per remaining timeslot.</p>
     *
     * <p>Every contradiction is named, not just the first, for the same reason
     * a scenario import names them all: the answer has to be actionable in one
     * read.</p>
     */
    public List<ContrainteAdHoc> createAll(List<ContrainteAdHoc> contraintes) {
        List<Creneau> creneaux = creneauService.list();
        List<ContrainteAdHoc> deja = new ArrayList<>(list());
        List<String> messages = new ArrayList<>();
        for (ContrainteAdHoc contrainte : contraintes) {
            contrainte.setId(Ids.required(contrainte.getId(), "constraint id"));
            ContrainteAdHocContradictions.detect(contrainte, deja, creneaux).stream()
                    .map(Contradiction::message)
                    .filter(message -> !messages.contains(message))
                    .forEach(messages::add);
            deja.add(contrainte);
        }
        if (!messages.isEmpty()) {
            throw new BusinessError.Invalid(String.join(" ", messages));
        }
        for (ContrainteAdHoc contrainte : contraintes) {
            if (contrainte.getCreeLe() == null) {
                contrainte.setCreeLe(Instant.now());
            }
            repository.saveContrainte(contrainte, false);
        }
        changeTracker.markModified();
        return contraintes;
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
        List<Contradiction> contradictions = ContrainteAdHocContradictions.detectAll(contraintes, creneaux);
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
