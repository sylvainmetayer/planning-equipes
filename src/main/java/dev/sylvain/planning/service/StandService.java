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

    @Inject
    SolverJobService solverJobs;

    /**
     * Stands as entered: the recurring {@link HoraireStand} rules and the dated
     * exceptions, side by side, with no expansion. This is the CRUD view — what
     * the admin UI edits and what a save writes back. Anything that needs the
     * <em>effective</em> windows of a given day wants {@link #listSolved()}
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
    public List<Stand> listSolved() {
        List<Stand> stands = list();
        HoraireStandResolver.apply(stands, creneaux.list());
        return stands;
    }

    public Stand create(Stand stand) {
        stand.setId(Ids.required(stand.getId(), "stand id"));
        validate(stand);
        repository.saveStand(stand);
        changeTracker.markModified();
        return stand;
    }

    /**
     * Saves the stand as edited.
     *
     * <p>Refused while a solve holds this edition's solver, for the same reason
     * as {@link #delete}: the landing persist rewrites {@code nom},
     * {@code effectif_min}, {@code effectif_max} and {@code reserve_majeurs}
     * from the stand captured when the problem was built — plus its typologies,
     * indisponibilités and ouvertures — so a rename or a raised effectif would
     * quietly revert minutes later. See {@link SolverJobService#refuseIfSolving}.</p>
     *
     * <p>The bulk edit of the referential screen is this same method, once per
     * row: {@code ReferenceDataStore.saveMany} issues one
     * {@code PUT /api/stands/{id}} per stand, one transaction each, so there is
     * no server-side batch to check once — unlike {@code CreneauService.deleteInBulk},
     * which really is one.</p>
     */
    public Stand update(String id, Stand stand) {
        solverJobs.refuseIfSolving();
        if (!repository.standExists(id)) {
            throw new NotFoundException("Stand not found: " + id);
        }
        stand.setId(id);
        validate(stand);
        repository.saveStand(stand);
        changeTracker.markModified();
        return stand;
    }

    /**
     * Removes the stand, and with it the seats opened on it (see
     * {@link StandRepository#deleteStand}).
     *
     * <p>Refused while a solve holds the solver: that solve built its problem
     * from the referential as it stood at its start, and persisting its result
     * would re-insert the stand — and re-insert it <em>degraded</em>, since
     * {@code PlanningPersistenceService}'s own upsert writes only nom, effectifs
     * and reserveMajeurs, dropping emplacement, premium and niveauEffort. See
     * {@link SolverJobService#refuseIfSolving}.</p>
     */
    public void delete(String id) {
        solverJobs.refuseIfSolving();
        repository.deleteStand(id);
        changeTracker.markModified();
    }

    /** Every proposed typologie must reference an id already present in the {@code typologie} referential. */
    private void validate(Stand stand) {
        StandValidator.check(stand);
        if (stand.getTypologiesProposees() != null) {
            typologies.validerIds(stand.getTypologiesProposees());
        }
    }

    /**
     * Rewrites every stand's hand-entered dated windows as the recurring
     * horaires they repeat, against the edition's days. With
     * {@code apply} false nothing is written: the returned report describes
     * what the operation <em>would</em> do, which is what makes it safe to show
     * before committing to it.
     *
     * <p>Only stands the compaction proved equivalent are saved
     * ({@link HoraireCompaction#maxGapMinutes}); the others come back in
     * the report with the reason they were left alone. Each one is saved
     * individually so a single problematic stand cannot roll back the rest.</p>
     */
    public HoraireCompaction.RapportCompactage compactHoraires(boolean apply) {
        List<Stand> stands = list();
        HoraireCompaction.RapportCompactage rapport =
                HoraireCompaction.compact(stands, creneaux.list(), apply);
        if (!apply) {
            return rapport;
        }
        // Checked once for the whole compaction, not per stand: it rewrites every
        // compacted stand through saveStand, and a landing solve would revert
        // their typologies, indisponibilités and ouvertures. A dry run writes
        // nothing, hence the check sitting after the early return.
        solverJobs.refuseIfSolving();
        Set<String> compactes = rapport.stands().stream()
                .filter(HoraireCompaction.LigneCompactage::compacte)
                .map(HoraireCompaction.LigneCompactage::standId)
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
