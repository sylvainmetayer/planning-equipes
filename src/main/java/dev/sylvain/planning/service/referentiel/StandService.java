package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.ConcurrentModificationGuard;
import dev.sylvain.planning.service.Ids;
import dev.sylvain.planning.service.ReferenceDataChangeTracker;
import dev.sylvain.planning.service.consigne.ConsigneRepository;
import dev.sylvain.planning.service.consigne.ConsigneResolver;
import dev.sylvain.planning.service.solve.SolverJobService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
    ConcurrentModificationGuard staleWrites;

    @Inject
    SolverJobService solverJobs;

    @Inject
    ConsigneRepository consignes;

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
        resolve(stands, creneaux.list());
        return stands;
    }

    /**
     * Rules and dated exceptions first, then the edition's consignes on top
     * (issue #4): the one entry point every reader of effective windows goes
     * through. A caller that expanded the rules itself would silently ignore
     * a consigne and staff a band an arrêté closed —
     * {@code ConsigneCoucheStructurelleTest} refuses any such caller.
     */
    public void resolve(List<Stand> stands, List<Creneau> creneaux) {
        HoraireStandResolver.apply(stands, creneaux);
        ConsigneResolver.apply(stands, consignes.list(), creneaux);
    }

    /** One stand as persisted, or {@code null}: what a write compares itself against. */
    public Stand find(String id) {
        return repository.findStand(id);
    }

    public Stand create(Stand stand) {
        stand.setId(Ids.required(stand.getId(), "stand id"));
        validate(stand);
        repository.saveStand(stand, true);
        changeTracker.markModified();
        return stand;
    }

    /**
     * {@link #create(Stand)} inside a caller's transaction — the typologies the
     * stand names may have been written by the same unit of work a moment
     * ago, so they are checked on that connection, where they exist.
     */
    Stand create(Connection connection, Stand stand) throws SQLException {
        stand.setId(Ids.required(stand.getId(), "stand id"));
        StandValidator.check(stand);
        if (stand.getTypologiesProposees() != null) {
            typologies.validateIds(connection, stand.getTypologiesProposees());
        }
        repository.saveStand(connection, stand, true);
        // Marked by the caller once committed, see TypologieService.create.
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
            throw new BusinessError.NotFound("Stand inconnu : " + id);
        }
        stand.setId(id);
        validate(stand);
        repository.saveStand(stand, false);
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

    /**
     * Saves the schedules typed in the stand × créneau grid, one stand at a
     * time: each is converted ({@link GrilleHorairesStands#apply}), validated
     * and written on its own, so one stand the validator refuses does not roll
     * back the others — the report says what happened to each.
     *
     * <p>Every stand is converted and validated first, then all are written in
     * a single transaction: the report is a promise, and a half-written batch
     * would break it. Refused as a whole while a solve holds the solver, for the
     * reason {@link #update} gives: the landing persist would revert the bounds
     * and windows just written.</p>
     */
    public List<GrilleHorairesStands.LigneGrille> saisirGrille(List<GrilleHorairesStands.SaisieStand> saisies) {
        solverJobs.refuseIfSolving();
        List<Creneau> edition = creneaux.list();
        // Resolved, not raw: a partial cell saved unchanged keeps the segments
        // the stand actually has there, which only the effective windows say.
        List<Stand> tous = listSolved();
        Map<String, Stand> parId = new LinkedHashMap<>();
        tous.forEach(stand -> parId.put(stand.getId(), stand));
        Set<Long> idsEdition =
                edition.stream().map(Creneau::getId).filter(Objects::nonNull).collect(Collectors.toSet());

        List<GrilleHorairesStands.LigneGrille> lignes = new ArrayList<>();
        List<Stand> aEcrire = new ArrayList<>();
        for (GrilleHorairesStands.SaisieStand saisie : saisies) {
            Stand stand = parId.get(saisie.standId());
            if (stand == null) {
                // A body field naming nothing is a bad request, not a missing page.
                throw new BusinessError.Invalid("Stand inconnu dans la grille : " + saisie.standId());
            }
            List<GrilleHorairesStands.SaisieCellule> cellules =
                    saisie.cellules() == null ? List.of() : saisie.cellules();
            for (GrilleHorairesStands.SaisieCellule cellule : cellules) {
                if (!idsEdition.contains(cellule.creneauId())) {
                    throw new BusinessError.Invalid(
                            "Créneau inconnu dans la grille du stand " + stand.getId() + " : " + cellule.creneauId());
                }
            }
            lignes.add(GrilleHorairesStands.apply(stand, edition, cellules, saisie.aplatir()));
            // The precondition the grid read, not the row's own stamp: without
            // this the screen that rewrites the most would be the only one
            // able to overwrite another session in silence (issue #362).
            stand.setModifieLe(saisie.modifieLe());
            // The hours, not the typologies: see StandValidator#checkSchedule.
            StandValidator.checkSchedule(stand);
            aEcrire.add(stand);
        }
        // One transaction for the lot: a report announcing twelve stands after a
        // rollback would be a promise nothing could keep.
        if (!aEcrire.isEmpty()) {
            repository.saveStands(aEcrire);
            changeTracker.markModified();
        }
        return lignes;
    }

    /** Every proposed typologie must reference an id already present in the {@code typologie} referential. */
    private void validate(Stand stand) {
        StandValidator.check(stand);
        if (stand.getTypologiesProposees() != null) {
            typologies.validateIds(stand.getTypologiesProposees());
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
        HoraireCompaction.RapportCompactage rapport = HoraireCompaction.compact(stands, creneaux.list(), apply);
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
        try {
            for (Stand stand : stands) {
                if (compactes.contains(stand.getId())) {
                    repository.saveStand(stand, false);
                    modifie = true;
                }
            }
        } finally {
            // One transaction per stand, so a failure midway leaves the earlier
            // ones committed: mark what was written rather than nothing. See
            // CreneauService#createInBulk for why over-marking is the safe
            // direction.
            if (modifie) {
                changeTracker.markModified();
            }
        }
        return rapport;
    }
}
