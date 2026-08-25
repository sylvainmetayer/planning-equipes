package dev.sylvain.planning.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.DemandeEchange;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.StatutDemandeEchange;
import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.PlanningService.EchangeSimulation;
import dev.sylvain.planning.service.PlanningService.HardViolation;
import dev.sylvain.planning.service.notification.Notification;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;

/**
 * The demande d'échange lifecycle (issue #165): submission with hard-constraint
 * prevalidation from the espace animateur, and the admin decisions — accept
 * (apply the swap exactly as simulated, pin it, notify) or refuse (touch
 * nothing, notify).
 *
 * <p>Carries its own {@code demande_echange} SQL, like
 * {@link PlanSnapshotService}: every statement is edition-scoped through
 * {@code prepareScoped}, same convention as the referential repositories.</p>
 */
@ApplicationScoped
public class DemandeEchangeService {

    @Inject
    DataSource dataSource;

    @Inject
    JdbcEditionScope scope;

    @Inject
    EditionContext editionContext;

    @Inject
    PlanningService planningService;

    @Inject
    PlanningPersistenceService persistenceService;

    @Inject
    ReferenceDataService referenceDataService;

    /**
     * Notifications are fired as facts, not sent: their best-effort delivery
     * policy lives in {@code NotificationDispatcher}, so a broken SMTP server
     * can never roll back a demande that was really submitted.
     */
    @Inject
    Event<Notification> notifications;

    /**
     * One demande as typed in the espace animateur, before any validation.
     * {@code creneauCibleId}/{@code standCibleId} optional: set, they make the
     * exchange DIRECTED — the demandeur names the colleague's seat they want
     * in return ("I give you my Monday, I take your Tuesday").
     */
    public record NouvelleDemande(Long creneauId, String standId, String cibleId, String motif,
            Long creneauCibleId, String standCibleId) {
    }

    /**
     * The single wording of a closed-foire refusal, shared with
     * {@code FoireOpenFilter}: the reads guarded at the route and the writes
     * guarded here say the same thing to the animateur.
     */
    public static final String FOIRE_FERMEE =
            "La foire au planning est fermée : les demandes d'échange ne sont plus ouvertes";

    /* ------------------------------ Animateur ------------------------------ */

    /**
     * Submits a batch of demandes for {@code demandeurId}. Each one is
     * prevalidated against the hard constraints (see
     * {@link PlanningService#simulateEchange}) but stored <b>whatever the
     * verdict</b> — the animateur is simply told which ones are infeasible in
     * the current planning. One notification per solicited colleague — a batch
     * spread over several of them asks each of them, not just the first.
     *
     * @throws IllegalArgumentException when a demande does not reference one of
     *                                  the demandeur's own seats or a known colleague
     */
    public List<DemandeEchange> submit(String demandeurId, List<NouvelleDemande> nouvelles) {
        checkFoireOpen();
        if (nouvelles == null || nouvelles.isEmpty()) {
            return List.of();
        }
        PlanningEvenement planning = persistenceService.loadPersistedPlanning();
        PlanningPersistenceService.PlanningResolution resolution = persistenceService.loadResolution();
        List<DemandeEchange> demandes = new ArrayList<>();
        for (NouvelleDemande nouvelle : nouvelles) {
            demandes.add(buildDemande(demandeurId, nouvelle, planning, resolution));
        }
        for (DemandeEchange demande : demandes) {
            inserer(demande);
        }
        // The colleague agrees first (see acceptByTarget); the admin is only
        // notified once that agreement lands — never having to ask both sides.
        // One mail per SOLICITED COLLEAGUE, not one per batch: a batch may
        // propose seats to several of them, and each has to be asked for their
        // own agreement. The count is theirs alone, so nobody is told about
        // demandes addressed to someone else.
        Map<String, Integer> parCible = new LinkedHashMap<>();
        for (DemandeEchange demande : demandes) {
            parCible.merge(demande.getCibleId(), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> sollicitation : parCible.entrySet()) {
            notifications.fire(new Notification.TargetSolicited(
                    emailOf(sollicitation.getKey()), nomComplet(demandeurId), sollicitation.getValue()));
        }
        return demandes;
    }

    private DemandeEchange buildDemande(String demandeurId, NouvelleDemande nouvelle,
            PlanningEvenement planning, PlanningPersistenceService.PlanningResolution resolution) {
        if (nouvelle.creneauId() == null || nouvelle.standId() == null || nouvelle.standId().isBlank()) {
            throw new BusinessError.Invalid("Créneau ou stand manquant sur une demande");
        }
        if (nouvelle.cibleId() == null || nouvelle.cibleId().isBlank()) {
            throw new BusinessError.Invalid("Animateur avec qui échanger manquant sur une demande");
        }
        if (nouvelle.cibleId().equals(demandeurId)) {
            throw new BusinessError.Invalid("Impossible d'échanger un créneau avec soi-même");
        }
        if (nouvelle.creneauCibleId() != null
                && (nouvelle.standCibleId() == null || nouvelle.standCibleId().isBlank())) {
            throw new BusinessError.Invalid("Stand du créneau souhaité manquant sur une demande dirigée");
        }
        // Throws on an unknown seat/target — the UI only offers the animateur's
        // own seats (and the colleague's real ones for a directed exchange),
        // so this only rejects stale or hand-crafted payloads.
        EchangeSimulation simulation = simulate(planning, demandeurId, nouvelle.cibleId(),
                nouvelle.creneauId(), nouvelle.standId(), nouvelle.creneauCibleId(), nouvelle.standCibleId());

        DemandeEchange demande = new DemandeEchange();
        demande.setId(UUID.randomUUID().toString());
        demande.setDemandeurId(demandeurId);
        demande.setCibleId(nouvelle.cibleId());
        demande.setCreneauId(nouvelle.creneauId());
        demande.setStandId(nouvelle.standId());
        demande.setCreneauCibleId(nouvelle.creneauCibleId());
        demande.setStandCibleId(nouvelle.creneauCibleId() == null ? null : nouvelle.standCibleId());
        demande.setMotif(nouvelle.motif());
        demande.setStatut(StatutDemandeEchange.EN_ATTENTE_CIBLE);
        demande.setPrevalidationOk(!simulation.casseContrainteDure());
        demande.setContraintesViolees(simulation.nouvellesViolationsDures().stream()
                .map(HardViolation::description)
                .toList());
        demande.setCreeLe(Instant.now());
        return demande;
    }

    /** The demandeur withdraws one of their own, still-pending demandes. */
    public void cancel(String demandeurId, String demandeId) {
        checkFoireOpen();
        // Not prepareScoped: the SET clause claims placeholder 1, so the
        // edition_id predicate is bound explicitly.
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        """
                        UPDATE demande_echange
                        SET statut = 'ANNULEE', decide_le = ?
                        WHERE edition_id = ?
                        AND id = ?
                        AND demandeur_id = ?
                        AND statut IN ('PROPOSEE', 'EN_ATTENTE_CIBLE')""")) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setString(2, editionContext.editionIdCourant());
            ps.setString(3, demandeId);
            ps.setString(4, demandeurId);
            if (ps.executeUpdate() == 0) {
                throw new BusinessError.Invalid("Demande introuvable ou déjà décidée");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to cancel demande " + demandeId, e);
        }
    }

    public List<DemandeEchange> listForRequester(String demandeurId) {
        return list(" AND demandeur_id = ?", demandeurId);
    }

    /** Demandes targeting {@code cibleId} — the "reçues" tab of their espace, every statut for context. */
    public List<DemandeEchange> listForTarget(String cibleId) {
        return list(" AND cible_id = ?", cibleId);
    }

    /**
     * The targeted colleague agrees: the demande enters the admin queue
     * (PROPOSEE) and the admin is notified — with the guarantee that both
     * sides are already OK with the swap.
     */
    public DemandeEchange acceptByTarget(String cibleId, String demandeId) {
        checkFoireOpen();
        DemandeEchange demande = decidedByTarget(cibleId, demandeId, StatutDemandeEchange.PROPOSEE);
        notifications.fire(new Notification.DemandesSoumises(
                nomComplet(demande.getDemandeurId()), List.of(demande)));
        return demande;
    }

    /** The targeted colleague declines: terminal, the admin never has to arbitrate; the demandeur is told. */
    public DemandeEchange declineByTarget(String cibleId, String demandeId) {
        checkFoireOpen();
        DemandeEchange demande = decidedByTarget(cibleId, demandeId, StatutDemandeEchange.REFUSEE_CIBLE);
        notifications.fire(new Notification.DemandeDeclinee(emailOf(demande.getDemandeurId()),
                nomComplet(cibleId), libelleCreneau(demande)));
        return demande;
    }

    private DemandeEchange decidedByTarget(String cibleId, String demandeId, StatutDemandeEchange statut) {
        Instant maintenant = Instant.now();
        // Not prepareScoped: the SET clause claims placeholder 1, so the
        // edition_id predicate is bound explicitly. The cible_id predicate is
        // the authorisation: nobody answers a demande that does not target them.
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        """
                        UPDATE demande_echange
                        SET statut = ?, cible_decide_le = ?
                        WHERE edition_id = ? AND id = ? AND cible_id = ? AND statut = 'EN_ATTENTE_CIBLE'""")) {
            ps.setString(1, statut.name());
            ps.setTimestamp(2, Timestamp.from(maintenant));
            ps.setString(3, editionContext.editionIdCourant());
            ps.setString(4, demandeId);
            ps.setString(5, cibleId);
            if (ps.executeUpdate() == 0) {
                throw new BusinessError.Invalid(
                        "Demande introuvable, déjà traitée, ou ne vous concernant pas");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to answer demande " + demandeId, e);
        }
        DemandeEchange demande = requiredDemande(demandeId);
        return demande;
    }

    private String emailOf(String animateurId) {
        return referenceDataService.listAnimateurs().stream()
                .filter(animateur -> animateur.getId().equals(animateurId))
                .findFirst()
                .map(Animateur::getEmail)
                .orElse(null);
    }

    /* --------------------------- Foire open/close --------------------------- */

    /**
     * True when animateurs may submit and withdraw demandes. Open by default:
     * the row only exists once the admin has decided something.
     */
    public boolean isFoireOpen() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection,
                        "SELECT foire_ouverte FROM parametres_echange WHERE edition_id = ?");
                ResultSet rs = ps.executeQuery()) {
            return !rs.next() || rs.getBoolean("foire_ouverte");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the foire state", e);
        }
    }

    /**
     * Admin decision: opens or closes the foire for the current edition. The
     * closure is enforced server-side ({@link #submit}, {@link #cancel}),
     * not merely hidden in the interface.
     */
    public void openFoire(boolean ouverte) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection,
                        """
                        INSERT INTO parametres_echange (edition_id, foire_ouverte)
                        VALUES (?, ?)
                        ON CONFLICT (edition_id)
                        DO UPDATE SET foire_ouverte = EXCLUDED.foire_ouverte""")) {
            ps.setBoolean(2, ouverte);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to store the foire state", e);
        }
    }

    private void checkFoireOpen() {
        if (!isFoireOpen()) {
            throw new BusinessError.Invalid(FOIRE_FERMEE);
        }
    }

    /* -------------------------------- Admin -------------------------------- */

    public List<DemandeEchange> list() {
        return list("", null);
    }

    /**
     * Re-simulates one pending demande against the <b>current</b> persisted
     * planning — the situation may have changed since submission, so the admin
     * screen always shows a fresh impact, never the stored one.
     */
    public EchangeSimulation impact(String demandeId) {
        DemandeEchange demande = requiredDemande(demandeId);
        PlanningEvenement planning = persistenceService.loadPersistedPlanning();
        return simulate(planning, demande.getDemandeurId(), demande.getCibleId(),
                demande.getCreneauId(), demande.getStandId(),
                demande.getCreneauCibleId(), demande.getStandCibleId());
    }

    /** Dispatches to the plain or the directed simulation, depending on the demande's shape. */
    private EchangeSimulation simulate(PlanningEvenement planning, String demandeurId, String cibleId,
            Long creneauId, String standId, Long creneauCibleId, String standCibleId) {
        if (creneauCibleId == null) {
            return planningService.simulateEchange(planning, demandeurId, cibleId, creneauId, standId);
        }
        return planningService.simulateDirectedEchange(planning, demandeurId, cibleId,
                creneauId, standId, creneauCibleId, standCibleId);
    }

    /**
     * Accepts a pending demande: applies the swap to the persisted planning
     * exactly as just re-simulated, pins both animateurs on the créneau with
     * {@link TypeVerrouillage#ANIMATEUR_CRENEAU} locks (a future regeneration
     * keeps the échange), marks the demande and notifies the demandeur. The
     * solver is <b>not</b> relaunched — regeneration stays an explicit admin
     * action.
     */
    public DemandeEchange accept(String demandeId, String commentaire) {
        DemandeEchange demande = requiredDemande(demandeId);
        requirePending(demande);
        PlanningEvenement planning = persistenceService.loadPersistedPlanning();
        EchangeSimulation simulation = simulate(planning, demande.getDemandeurId(), demande.getCibleId(),
                demande.getCreneauId(), demande.getStandId(),
                demande.getCreneauCibleId(), demande.getStandCibleId());
        if (demande.getCreneauCibleId() != null) {
            persistenceService.applyDirectedEchange(demande.getCreneauId(), demande.getStandId(),
                    demande.getDemandeurId(), demande.getCibleId(),
                    demande.getCreneauCibleId(), demande.getStandCibleId());
        } else {
            persistenceService.applyEchange(demande.getCreneauId(), demande.getStandId(),
                    demande.getDemandeurId(), demande.getCibleId(), simulation.standCibleId());
        }
        poserVerrouillages(demande);
        recordDecision(demande, StatutDemandeEchange.ACCEPTEE, commentaire);
        notifierDemandeur(demande);
        return demande;
    }

    /** Refuses a pending demande: the planning is untouched, the demandeur is told why. */
    public DemandeEchange refuse(String demandeId, String commentaire) {
        DemandeEchange demande = requiredDemande(demandeId);
        requireRefusable(demande);
        recordDecision(demande, StatutDemandeEchange.REFUSEE, commentaire);
        notifierDemandeur(demande);
        return demande;
    }

    private static void requirePending(DemandeEchange demande) {
        if (demande.getStatut() == StatutDemandeEchange.EN_ATTENTE_CIBLE) {
            throw new BusinessError.Invalid(
                    "Le collègue concerné n'a pas encore donné son accord — l'acceptation attend le sien");
        }
        if (demande.getStatut() != StatutDemandeEchange.PROPOSEE) {
            throw new BusinessError.Invalid("La demande n'est plus en attente (statut "
                    + demande.getStatut() + ")");
        }
    }

    /** A refusal may also kill a demande still waiting for the colleague — the admin always has the last word. */
    private static void requireRefusable(DemandeEchange demande) {
        if (demande.getStatut() != StatutDemandeEchange.PROPOSEE
                && demande.getStatut() != StatutDemandeEchange.EN_ATTENTE_CIBLE) {
            throw new BusinessError.Invalid("La demande n'est plus en attente (statut "
                    + demande.getStatut() + ")");
        }
    }

    /**
     * One lock per animateur on the échanged créneau: what each of them holds
     * there after the swap is frozen, the rest of their planning stays free.
     */
    private void poserVerrouillages(DemandeEchange demande) {
        // Directed exchange: each animateur is pinned on the créneau they now
        // hold a seat on (the target took the demandeur's créneau and vice
        // versa). Plain exchange: both end up on the demandeur's créneau.
        boolean dirige = demande.getCreneauCibleId() != null;
        record Verrou(String animateurId, Long creneauId) {
        }
        List<Verrou> verrous = dirige
                ? List.of(new Verrou(demande.getCibleId(), demande.getCreneauId()),
                        new Verrou(demande.getDemandeurId(), demande.getCreneauCibleId()))
                : List.of(new Verrou(demande.getDemandeurId(), demande.getCreneauId()),
                        new Verrou(demande.getCibleId(), demande.getCreneauId()));
        for (Verrou verrou : verrous) {
            VerrouillagePlanning verrouillage = new VerrouillagePlanning(null, TypeVerrouillage.ANIMATEUR_CRENEAU);
            verrouillage.setAnimateurId(verrou.animateurId());
            verrouillage.setCreneauId(verrou.creneauId());
            verrouillage.setRaison("Échange validé (demande " + demande.getId() + ")");
            referenceDataService.createVerrouillage(verrouillage);
        }
    }

    private void recordDecision(DemandeEchange demande, StatutDemandeEchange statut, String commentaire) {
        Instant decideLe = Instant.now();
        // Not prepareScoped for the same reason as cancel: SET comes first.
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        """
                        UPDATE demande_echange
                        SET statut = ?, commentaire_admin = ?, decide_le = ?
                        WHERE edition_id = ? AND id = ? AND statut IN ('PROPOSEE', 'EN_ATTENTE_CIBLE')""")) {
            ps.setString(1, statut.name());
            ps.setString(2, commentaire == null || commentaire.isBlank() ? null : commentaire);
            ps.setTimestamp(3, Timestamp.from(decideLe));
            ps.setString(4, editionContext.editionIdCourant());
            ps.setString(5, demande.getId());
            if (ps.executeUpdate() == 0) {
                throw new BusinessError.Invalid("La demande n'est plus en attente");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to decide demande " + demande.getId(), e);
        }
        demande.setStatut(statut);
        demande.setCommentaireAdmin(commentaire == null || commentaire.isBlank() ? null : commentaire);
        demande.setDecideLe(decideLe);
    }

    private void notifierDemandeur(DemandeEchange demande) {
        Animateur demandeur = referenceDataService.listAnimateurs().stream()
                .filter(animateur -> animateur.getId().equals(demande.getDemandeurId()))
                .findFirst()
                .orElse(null);
        if (demandeur != null) {
            notifications.fire(new Notification.DemandeTranchee(
                    demandeur.getEmail(), demande, libelleCreneau(demande)));
        }
    }

    private String libelleCreneau(DemandeEchange demande) {
        return referenceDataService.listCreneaux().stream()
                .filter(creneau -> creneau.getId() != null && creneau.getId().equals(demande.getCreneauId()))
                .findFirst()
                .map(creneau -> creneau.getDate() + " " + creneau.getHeureDebut() + "–" + creneau.getHeureFin())
                .orElse(null);
    }

    private String nomComplet(String animateurId) {
        return referenceDataService.listAnimateurs().stream()
                .filter(animateur -> animateur.getId().equals(animateurId))
                .findFirst()
                .map(Animateur::nomAffiche)
                .orElse(animateurId);
    }

    /* --------------------------------- SQL --------------------------------- */

    private static final String COLONNES = "id, demandeur_id, cible_id, creneau_id, stand_id, "
            + "creneau_cible_id, stand_cible_id, "
            + "motif, statut, prevalidation_ok, contraintes_violees, commentaire_admin, cree_le, "
            + "cible_decide_le, decide_le";

    private void inserer(DemandeEchange demande) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection,
                        "INSERT INTO demande_echange (edition_id, " + COLONNES + ") "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(2, demande.getId());
            ps.setString(3, demande.getDemandeurId());
            ps.setString(4, demande.getCibleId());
            ps.setLong(5, demande.getCreneauId());
            ps.setString(6, demande.getStandId());
            ps.setObject(7, demande.getCreneauCibleId());
            ps.setString(8, demande.getStandCibleId());
            ps.setString(9, demande.getMotif());
            ps.setString(10, demande.getStatut().name());
            ps.setObject(11, demande.getPrevalidationOk());
            ps.setString(12, demande.getContraintesViolees().isEmpty()
                    ? null
                    : String.join("\n", demande.getContraintesViolees()));
            ps.setString(13, demande.getCommentaireAdmin());
            ps.setTimestamp(14, Timestamp.from(demande.getCreeLe()));
            ps.setTimestamp(15, demande.getCibleDecideLe() == null ? null : Timestamp.from(demande.getCibleDecideLe()));
            ps.setTimestamp(16, demande.getDecideLe() == null ? null : Timestamp.from(demande.getDecideLe()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to store demande " + demande.getId(), e);
        }
    }

    private DemandeEchange requiredDemande(String demandeId) {
        List<DemandeEchange> demandes = list(" AND id = ?", demandeId);
        if (demandes.isEmpty()) {
            throw new BusinessError.NotFound("Demande inconnue : " + demandeId);
        }
        return demandes.get(0);
    }

    private List<DemandeEchange> list(String predicatSupplementaire, String parametre) {
        List<DemandeEchange> demandes = new ArrayList<>();
        // Only the column list and the extra predicate are concatenated, and both
        // are literals from this class's own call sites; the value that varies
        // travels as a bound parameter below.
        String sql = "SELECT " + COLONNES + " FROM demande_echange WHERE edition_id = ?"
                + predicatSupplementaire + " ORDER BY cree_le DESC, id";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = scope.prepareScoped(connection, sql)) {
            if (parametre != null) {
                ps.setString(2, parametre);
            }
            // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    demandes.add(read(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list demandes d'échange", e);
        }
        return demandes;
    }

    private static DemandeEchange read(ResultSet rs) throws SQLException {
        DemandeEchange demande = new DemandeEchange();
        demande.setId(rs.getString("id"));
        demande.setDemandeurId(rs.getString("demandeur_id"));
        demande.setCibleId(rs.getString("cible_id"));
        demande.setCreneauId(rs.getLong("creneau_id"));
        demande.setStandId(rs.getString("stand_id"));
        demande.setCreneauCibleId(rs.getObject("creneau_cible_id", Long.class));
        demande.setStandCibleId(rs.getString("stand_cible_id"));
        demande.setMotif(rs.getString("motif"));
        demande.setStatut(StatutDemandeEchange.valueOf(rs.getString("statut")));
        demande.setPrevalidationOk(rs.getObject("prevalidation_ok", Boolean.class));
        String violations = rs.getString("contraintes_violees");
        demande.setContraintesViolees(violations == null || violations.isBlank()
                ? List.of()
                : List.of(violations.split("\n")));
        demande.setCommentaireAdmin(rs.getString("commentaire_admin"));
        Timestamp creeLe = rs.getTimestamp("cree_le");
        demande.setCreeLe(creeLe == null ? null : creeLe.toInstant());
        Timestamp cibleDecideLe = rs.getTimestamp("cible_decide_le");
        demande.setCibleDecideLe(cibleDecideLe == null ? null : cibleDecideLe.toInstant());
        Timestamp decideLe = rs.getTimestamp("decide_le");
        demande.setDecideLe(decideLe == null ? null : decideLe.toInstant());
        return demande;
    }
}
