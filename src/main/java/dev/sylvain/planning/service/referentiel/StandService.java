package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HorairesRecurrents;
import dev.sylvain.planning.domain.PlageStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.IdGenerator;
import dev.sylvain.planning.service.JdbcEditionScope;
import dev.sylvain.planning.service.ReferenceDataChangeTracker;
import dev.sylvain.planning.service.consigne.ConsigneRepository;
import dev.sylvain.planning.service.consigne.ConsigneResolver;
import dev.sylvain.planning.service.solve.RefusedWhileSolving;
import dev.sylvain.planning.service.solve.SolverJobService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** CRUD of the stand referential, plus the two views the rest of the app reads it through. */
@ApplicationScoped
public class StandService {

    /** How a refused code names the stand it would have collided with. */
    private static final String THE_STAND = "le stand";

    private final StandRepository repository;

    private final CreneauService creneaux;

    private final TypologieService typologies;

    private final ReferenceDataChangeTracker changeTracker;

    private final SolverJobService solverJobs;

    private final ConsigneRepository consignes;

    private final IdGenerator ids;

    private final JdbcEditionScope scope;

    private final GelReferentielService gel;

    @Inject
    public StandService(
            StandRepository repository,
            CreneauService creneaux,
            TypologieService typologies,
            ReferenceDataChangeTracker changeTracker,
            SolverJobService solverJobs,
            ConsigneRepository consignes,
            IdGenerator ids,
            JdbcEditionScope scope,
            GelReferentielService gel) {
        this.repository = repository;
        this.creneaux = creneaux;
        this.typologies = typologies;
        this.changeTracker = changeTracker;
        this.solverJobs = solverJobs;
        this.consignes = consignes;
        this.ids = ids;
        this.scope = scope;
        this.gel = gel;
    }

    /**
     * Stands as entered: their stored plages, nothing laid on top. This is the
     * CRUD view — what the admin UI edits and what a save writes back. Anything that needs the
     * <em>effective</em> windows of a given day wants {@link #listSolved()}
     * instead.
     */
    public List<Stand> list() {
        return repository.listStands();
    }

    /**
     * Stands with the consignes already laid on their plages, so
     * {@link Creneau#segmentsOuvertsMinutes(Stand)} sees the effective
     * windows — what the solver, the poste generation and the feasibility
     * analysis all build on.
     *
     * <p>The result lands on {@link Stand#setPlagesEffectives} and never on
     * the persisted list, so these instances stay safe to hand to a save path
     * (see {@link HoraireStandResolver}).</p>
     */
    public List<Stand> listSolved() {
        List<Stand> stands = list();
        resolve(stands, creneaux.list());
        return stands;
    }

    /**
     * The stored plages first, then the edition's consignes on top (issue
     * #4): the one entry point every reader of effective windows goes
     * through. A caller that read the plages itself would silently ignore
     * a consigne and staff a band an arrêté closed —
     * {@code ConsigneCoucheStructurelleTest} refuses any such caller.
     */
    public void resolve(List<Stand> stands, List<Creneau> creneaux) {
        HoraireStandResolver.apply(stands, creneaux);
        ConsigneResolver.apply(stands, consignes.list(), creneaux);
    }

    /** Id of the stand carrying {@code code}, {@code null} when none does. */
    public String idByCode(String code) {
        return repository.idByCode(code);
    }

    /** One stand as persisted, or {@code null}: what a write compares itself against. */
    public Stand find(String id) {
        return repository.findStand(id);
    }

    /**
     * Creates the stand under an id the application draws (ADR 0050): an id
     * the caller sent is overwritten.
     */
    @RefusedWhileFrozen(ReferentialFamily.STANDS)
    public Stand create(Stand stand) {
        // A new stand has nothing stored to be unfolded from: whatever a client marked is a typed plage.
        stand.getPlages().forEach(plage -> plage.setDepliee(false));
        validate(stand);
        scope.write("Failed to create stand " + stand.getNom(), connection -> {
            stand.setCode(Codes.normalise(stand.getCode(), IdGenerator.Kind.STAND));
            Codes.refuseTaken(stand.getCode(), repository.idByCode(connection, stand.getCode()), null, THE_STAND);
            stand.setId(ids.next(connection, IdGenerator.Kind.STAND));
            repository.saveStand(connection, stand, true);
        });
        changeTracker.markModified();
        return stand;
    }

    /**
     * {@link #create(Stand)} inside a caller's transaction — the typologies the
     * stand names may have been written by the same unit of work a moment
     * ago, so they are checked on that connection, where they exist.
     */
    @RefusedWhileFrozen(ReferentialFamily.STANDS)
    Stand create(Connection connection, Stand stand) throws SQLException {
        layDownReceivedRules(stand);
        StandValidator.check(stand);
        stand.setCode(Codes.normalise(stand.getCode(), IdGenerator.Kind.STAND));
        Codes.refuseTaken(stand.getCode(), repository.idByCode(connection, stand.getCode()), null, THE_STAND);
        stand.setId(ids.next(connection, IdGenerator.Kind.STAND));
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
     *
     * <p>Under a {@link ReferentialFamily#STANDS} freeze, refused only when the
     * edit moves a frozen field — compared with the stand as stored, so a
     * rename or a new location stays open (ADR 0052). Checked here rather than
     * by {@link RefusedWhileFrozen}, which can only refuse the whole call.</p>
     */
    @RefusedWhileSolving
    public Stand update(String id, Stand stand) {
        if (!repository.standExists(id)) {
            throw new BusinessError.NotFound("Stand inconnu : " + id);
        }
        stand.setId(id);
        if (!stand.getPlages().isEmpty()) {
            markOrigins(stand, repository.findStand(id));
        }
        // Before the freeze compares: the game categories may be named by
        // their code, and a code is not the id the stored stand holds.
        validate(stand);
        // The before-image only under a freeze: it reads the whole referential.
        gel.refuseIfFrozen(
                ReferentialFamily.STANDS,
                () -> GelReferentielService.changesFrozenStandFields(repository.findStand(id), stand));
        stand.setCode(Codes.normalise(stand.getCode(), IdGenerator.Kind.STAND));
        Codes.refuseTaken(stand.getCode(), repository.idByCode(stand.getCode()), id, THE_STAND);
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
    @RefusedWhileFrozen(ReferentialFamily.STANDS)
    @RefusedWhileSolving
    public void delete(String id) {
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
    @RefusedWhileFrozen(ReferentialFamily.STANDS)
    @RefusedWhileSolving
    public List<GrilleHorairesStands.LigneGrille> saisirGrille(List<GrilleHorairesStands.SaisieStand> saisies) {
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
                    throw new BusinessError.Invalid("Créneau inconnu dans la grille du stand "
                            + CoherenceAnalyzer.standLabel(stand) + " : " + cellule.creneauId());
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

    /**
     * What a stand does with the recurring rules it is sent under {@code horaires} (ADR 0104), the
     * bridge of the 3.x schedule editor until the plages editor replaces it. They are always the
     * stand's <b>model</b>, stored as sent — the editor reads them back — and, when the edition has
     * days, also unfolded into plages on them, with the reading 3.x applied at every read:
     * <ul>
     * <li>none sent, or the stored ones sent back unchanged: nothing moves;</li>
     * <li>no timeslot: no day to lay them on, the model waits for the days the edition gains
     * ({@code CreneauRepository});</li>
     * <li>timeslots: the plages the previous model had unfolded are withdrawn (recomputed, then
     * removed where they are still there unchanged), the new model is unfolded, and a day that
     * carries a plage of its own keeps it.</li>
     * </ul>
     */
    private void layDownReceivedRules(Stand stand) {
        List<HorairesRecurrents.Regle> regles = stand.horairesEnvoyes();
        if (regles == null) {
            return;
        }
        for (HorairesRecurrents.Regle regle : regles) {
            if (regle.fenetres().isEmpty()) {
                throw new BusinessError.Invalid("Un horaire de stand requiert au moins une fenêtre horaire");
            }
            if (!regle.fenetres().stream().allMatch(FenetreHoraire::hasValidRange)) {
                throw new BusinessError.Invalid("Une fenêtre horaire requiert une heure de début, et une heure de "
                        + "fin après elle (vide : jusqu'à la fermeture) — une fenêtre ne peut pas chevaucher minuit");
            }
        }
        StandValidator.checkRules(stand, regles);
        List<HorairesRecurrents.Regle> stored =
                stand.getId() == null ? List.of() : repository.modelRules(stand.getId());
        if (sameRules(regles, stored)) {
            stand.setHoraires(null);
            return;
        }
        Set<LocalDate> jours = HorairesRecurrents.datesConcernees(creneaux.list());
        if (jours.isEmpty() && !regles.isEmpty()) {
            return;
        }
        // The plages the previous model unfolded carry their origin: they go, whatever day they are on, and
        // what somebody typed stays — even when it looks exactly like one of them.
        List<PlageStand> handMade =
                stand.getPlages().stream().filter(plage -> !plage.isDepliee()).toList();
        stand.setPlages(HorairesRecurrents.expand(regles, handMade, jours));
    }

    /**
     * The origin of a plage is the server's to say, never the client's: a plage sent back as it was
     * stored keeps its mark, one the editor changed, or a client wrote, is typed by hand.
     */
    private static void markOrigins(Stand sent, Stand stored) {
        Set<String> unfolded = new HashSet<>();
        if (stored != null) {
            stored.getPlages().stream().filter(PlageStand::isDepliee).forEach(plage -> unfolded.add(key(plage)));
        }
        sent.getPlages().forEach(plage -> plage.setDepliee(unfolded.contains(key(plage))));
    }

    /** What makes two plages the same one: everything but the row id and the origin. */
    private static String key(PlageStand plage) {
        return plage.getMode() + "|" + plage.getDate() + "|" + plage.getHeureDebut() + "|" + plage.getHeureFin() + "|"
                + plage.getEffectif() + "|" + plage.getMotif();
    }

    private static boolean sameRules(List<HorairesRecurrents.Regle> sent, List<HorairesRecurrents.Regle> stored) {
        return sent.stream()
                .map(HorairesRecurrents.Regle::withoutId)
                .toList()
                .equals(stored.stream().map(HorairesRecurrents.Regle::withoutId).toList());
    }

    /** Every proposed typologie must reference an id already present in the {@code typologie} referential. */
    private void validate(Stand stand) {
        layDownReceivedRules(stand);
        stand.setTypologiesProposees(typologies.resolveIds(stand.getTypologiesProposees()));
        StandValidator.check(stand);
        if (stand.getTypologiesProposees() != null) {
            typologies.validateIds(stand.getTypologiesProposees());
        }
    }
}
