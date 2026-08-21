package dev.sylvain.planning.service;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.Stand;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;

/** CRUD of the stand referential, plus the two views the rest of the app reads it through. */
@ApplicationScoped
public class StandService {

    @Inject
    StandRepository repository;

    @Inject
    CreneauService creneaux;

    @Inject
    TypologieService typologies;

    @Inject
    ReferenceDataChangeTracker changeTracker;

    /**
     * Stands as entered: the recurring {@link HoraireStand} rules and the dated
     * exceptions, side by side, with no expansion. This is the CRUD view — what
     * the admin UI edits and what a save writes back. Anything that needs the
     * <em>effective</em> windows of a given day wants {@link #listResolus()}
     * instead.
     */
    public List<Stand> list() {
        return repository.listStands();
    }

    /**
     * Stands with their rules already expanded against the edition's days, so
     * {@link Creneau#segmentsOuvertsMinutes(Stand)} sees the effective
     * windows — what the solver, the poste generation and the feasibility
     * analysis all build on.
     *
     * <p>The expansion lands on {@link Stand#setFenetresEffectives} and never on
     * the persisted lists, so these instances stay safe to hand to a save path
     * (see {@link HoraireStandResolver}).</p>
     */
    public List<Stand> listResolus() {
        List<Stand> stands = list();
        HoraireStandResolver.appliquer(stands, creneaux.list());
        return stands;
    }

    public Stand create(Stand stand) {
        stand.setId(Identifiants.requis(stand.getId(), "stand id"));
        valider(stand);
        repository.saveStand(stand);
        changeTracker.markModified();
        return stand;
    }

    public Stand update(String id, Stand stand) {
        if (!repository.standExists(id)) {
            throw new NotFoundException("Stand not found: " + id);
        }
        stand.setId(id);
        valider(stand);
        repository.saveStand(stand);
        changeTracker.markModified();
        return stand;
    }

    public void delete(String id) {
        repository.deleteStand(id);
        changeTracker.markModified();
    }

    /** Every proposed typologie must reference an id already present in the {@code typologie} referential. */
    private void valider(Stand stand) {
        ValidationStand.verifier(stand);
        if (stand.getTypologiesProposees() != null) {
            typologies.validerIds(stand.getTypologiesProposees());
        }
    }

    /**
     * Rewrites every stand's hand-entered dated windows as the recurring
     * horaires they repeat, against the edition's days. With
     * {@code appliquer} false nothing is written: the returned report describes
     * what the operation <em>would</em> do, which is what makes it safe to show
     * before committing to it.
     *
     * <p>Only stands the compaction proved equivalent are saved
     * ({@link CompactageHoraires#ecartMaximalMinutes}); the others come back in
     * the report with the reason they were left alone. Each one is saved
     * individually so a single problematic stand cannot roll back the rest.</p>
     */
    public CompactageHoraires.RapportCompactage compacterHoraires(boolean appliquer) {
        List<Stand> stands = list();
        CompactageHoraires.RapportCompactage rapport =
                CompactageHoraires.compacter(stands, creneaux.list(), appliquer);
        if (!appliquer) {
            return rapport;
        }
        Set<String> compactes = rapport.stands().stream()
                .filter(CompactageHoraires.LigneCompactage::compacte)
                .map(CompactageHoraires.LigneCompactage::standId)
                .collect(Collectors.toSet());
        boolean modifie = false;
        for (Stand stand : stands) {
            if (compactes.contains(stand.getId())) {
                repository.saveStand(stand);
                modifie = true;
            }
        }
        if (modifie) {
            changeTracker.markModified();
        }
        return rapport;
    }
}
