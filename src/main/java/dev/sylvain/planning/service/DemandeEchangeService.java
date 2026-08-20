package dev.sylvain.planning.service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.DemandeEchange;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.StatutDemandeEchange;
import dev.sylvain.planning.domain.TypeVerrouillage;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.PlanningService.EchangeSimulation;
import dev.sylvain.planning.service.PlanningService.ViolationDure;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * The demande d'échange lifecycle (issue #165): submission with hard-constraint
 * prevalidation from the espace animateur, and the admin decisions — accept
 * (apply the swap exactly as simulated, pin it, notify) or refuse (touch
 * nothing, notify).
 *
 * <p>Carries its own {@code demande_echange} SQL, like
 * {@link PlanSnapshotService}: every statement is edition-scoped through
 * {@code prepareScoped}, same convention as {@link ReferenceDataRepository}.</p>
 */
@ApplicationScoped
public class DemandeEchangeService {

    @Inject
    DataSource dataSource;

    @Inject
    EditionContext editionContext;

    @Inject
    PlanningService planningService;

    @Inject
    PlanningPersistenceService persistenceService;

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    MailService mailService;

    /** One demande as typed in the espace animateur, before any validation. */
    public record NouvelleDemande(Long creneauId, String standId, String cibleId, String motif) {
    }

    /* ------------------------------ Animateur ------------------------------ */

    /**
     * Submits a batch of demandes for {@code demandeurId}. Each one is
     * prevalidated against the hard constraints (see
     * {@link PlanningService#simulerEchange}) but stored <b>whatever the
     * verdict</b> — the animateur is simply told which ones are infeasible in
     * the current planning. One admin notification per batch.
     *
     * @throws IllegalArgumentException when a demande does not reference one of
     *                                  the demandeur's own seats or a known colleague
     */
    public List<DemandeEchange> soumettre(String demandeurId, List<NouvelleDemande> nouvelles) {
        verifierFoireOuverte();
        if (nouvelles == null || nouvelles.isEmpty()) {
            return List.of();
        }
        PlanningFestival planning = persistenceService.loadPersistedPlanning();
        PlanningPersistenceService.PlanningResolution resolution = persistenceService.loadResolution();
        List<DemandeEchange> demandes = new ArrayList<>();
        for (NouvelleDemande nouvelle : nouvelles) {
            demandes.add(construireDemande(demandeurId, nouvelle, planning, resolution));
        }
        for (DemandeEchange demande : demandes) {
            inserer(demande);
        }
        mailService.notifierNouvellesDemandes(nomComplet(demandeurId), demandes);
        return demandes;
    }

    private DemandeEchange construireDemande(String demandeurId, NouvelleDemande nouvelle,
            PlanningFestival planning, PlanningPersistenceService.PlanningResolution resolution) {
        if (nouvelle.creneauId() == null || nouvelle.standId() == null || nouvelle.standId().isBlank()) {
            throw new IllegalArgumentException("Créneau ou stand manquant sur une demande");
        }
        if (nouvelle.cibleId() == null || nouvelle.cibleId().isBlank()) {
            throw new IllegalArgumentException("Animateur avec qui échanger manquant sur une demande");
        }
        if (nouvelle.cibleId().equals(demandeurId)) {
            throw new IllegalArgumentException("Impossible d'échanger un créneau avec soi-même");
        }
        // Throws on an unknown seat/cible — the UI only offers the animateur's
        // own seats, so this only rejects stale or hand-crafted payloads.
        EchangeSimulation simulation = planningService.simulerEchange(planning,
                demandeurId, nouvelle.cibleId(), nouvelle.creneauId(), nouvelle.standId());

        DemandeEchange demande = new DemandeEchange();
        demande.setId(UUID.randomUUID().toString());
        demande.setDemandeurId(demandeurId);
        demande.setCibleId(nouvelle.cibleId());
        demande.setCreneauId(nouvelle.creneauId());
        demande.setStandId(nouvelle.standId());
        demande.setMotif(nouvelle.motif());
        demande.setStatut(StatutDemandeEchange.PROPOSEE);
        demande.setPrevalidationOk(!simulation.casseContrainteDure());
        demande.setContraintesViolees(simulation.nouvellesViolationsDures().stream()
                .map(ViolationDure::description)
                .toList());
        demande.setCreeLe(Instant.now());
        return demande;
    }

    /** The demandeur withdraws one of their own, still-pending demandes. */
    public void annuler(String demandeurId, String demandeId) {
        verifierFoireOuverte();
        // Not prepareScoped: the SET clause claims placeholder 1, so the
        // edition_id predicate is bound explicitly.
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "UPDATE demande_echange SET statut = 'ANNULEE', decide_le = ? "
                                + "WHERE edition_id = ? AND id = ? AND demandeur_id = ? AND statut = 'PROPOSEE'")) {
            ps.setTimestamp(1, Timestamp.from(Instant.now()));
            ps.setString(2, editionContext.editionIdCourant());
            ps.setString(3, demandeId);
            ps.setString(4, demandeurId);
            if (ps.executeUpdate() == 0) {
                throw new IllegalArgumentException("Demande introuvable ou déjà décidée");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to cancel demande " + demandeId, e);
        }
    }

    public List<DemandeEchange> listerPourDemandeur(String demandeurId) {
        return lister(" AND demandeur_id = ?", demandeurId);
    }

    /* --------------------------- Foire open/close --------------------------- */

    /**
     * True when animateurs may submit and withdraw demandes. Open by default:
     * the row only exists once the admin has decided something.
     */
    public boolean estFoireOuverte() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = prepareScoped(connection,
                        "SELECT foire_ouverte FROM parametres_echange WHERE edition_id = ?");
                ResultSet rs = ps.executeQuery()) {
            return !rs.next() || rs.getBoolean("foire_ouverte");
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the foire state", e);
        }
    }

    /**
     * Admin decision: opens or closes the foire for the current edition. The
     * closure is enforced server-side ({@link #soumettre}, {@link #annuler}),
     * not merely hidden in the interface.
     */
    public void ouvrirFoire(boolean ouverte) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = prepareScoped(connection,
                        "INSERT INTO parametres_echange (edition_id, foire_ouverte) VALUES (?, ?) "
                                + "ON CONFLICT (edition_id) DO UPDATE SET foire_ouverte = EXCLUDED.foire_ouverte")) {
            ps.setBoolean(2, ouverte);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to store the foire state", e);
        }
    }

    private void verifierFoireOuverte() {
        if (!estFoireOuverte()) {
            throw new IllegalArgumentException(
                    "La foire au planning est fermée : les demandes d'échange ne sont plus ouvertes");
        }
    }

    /* -------------------------------- Admin -------------------------------- */

    public List<DemandeEchange> lister() {
        return lister("", null);
    }

    /**
     * Re-simulates one pending demande against the <b>current</b> persisted
     * planning — the situation may have changed since submission, so the admin
     * screen always shows a fresh impact, never the stored one.
     */
    public EchangeSimulation impact(String demandeId) {
        DemandeEchange demande = demandeRequise(demandeId);
        PlanningFestival planning = persistenceService.loadPersistedPlanning();
        return planningService.simulerEchange(planning,
                demande.getDemandeurId(), demande.getCibleId(), demande.getCreneauId(), demande.getStandId());
    }

    /**
     * Accepts a pending demande: applies the swap to the persisted planning
     * exactly as just re-simulated, pins both animateurs on the créneau with
     * {@link TypeVerrouillage#ANIMATEUR_CRENEAU} locks (a future regeneration
     * keeps the échange), marks the demande and notifies the demandeur. The
     * solver is <b>not</b> relaunched — regeneration stays an explicit admin
     * action.
     */
    public DemandeEchange accepter(String demandeId, String commentaire) {
        DemandeEchange demande = demandeRequise(demandeId);
        exigerEnAttente(demande);
        PlanningFestival planning = persistenceService.loadPersistedPlanning();
        EchangeSimulation simulation = planningService.simulerEchange(planning,
                demande.getDemandeurId(), demande.getCibleId(), demande.getCreneauId(), demande.getStandId());
        persistenceService.appliquerEchange(demande.getCreneauId(), demande.getStandId(),
                demande.getDemandeurId(), demande.getCibleId(), simulation.standCibleId());
        poserVerrouillages(demande);
        marquerDecision(demande, StatutDemandeEchange.ACCEPTEE, commentaire);
        notifierDemandeur(demande);
        return demande;
    }

    /** Refuses a pending demande: the planning is untouched, the demandeur is told why. */
    public DemandeEchange refuser(String demandeId, String commentaire) {
        DemandeEchange demande = demandeRequise(demandeId);
        exigerEnAttente(demande);
        marquerDecision(demande, StatutDemandeEchange.REFUSEE, commentaire);
        notifierDemandeur(demande);
        return demande;
    }

    private static void exigerEnAttente(DemandeEchange demande) {
        if (demande.getStatut() != StatutDemandeEchange.PROPOSEE) {
            throw new IllegalArgumentException("La demande n'est plus en attente (statut "
                    + demande.getStatut() + ")");
        }
    }

    /**
     * One lock per animateur on the échanged créneau: what each of them holds
     * there after the swap is frozen, the rest of their planning stays free.
     */
    private void poserVerrouillages(DemandeEchange demande) {
        for (String animateurId : List.of(demande.getDemandeurId(), demande.getCibleId())) {
            VerrouillagePlanning verrouillage = new VerrouillagePlanning(null, TypeVerrouillage.ANIMATEUR_CRENEAU);
            verrouillage.setAnimateurId(animateurId);
            verrouillage.setCreneauId(demande.getCreneauId());
            verrouillage.setRaison("Échange validé (demande " + demande.getId() + ")");
            referenceDataService.createVerrouillage(verrouillage);
        }
    }

    private void marquerDecision(DemandeEchange demande, StatutDemandeEchange statut, String commentaire) {
        Instant decideLe = Instant.now();
        // Not prepareScoped for the same reason as annuler: SET comes first.
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = connection.prepareStatement(
                        "UPDATE demande_echange SET statut = ?, commentaire_admin = ?, decide_le = ? "
                                + "WHERE edition_id = ? AND id = ? AND statut = 'PROPOSEE'")) {
            ps.setString(1, statut.name());
            ps.setString(2, commentaire == null || commentaire.isBlank() ? null : commentaire);
            ps.setTimestamp(3, Timestamp.from(decideLe));
            ps.setString(4, editionContext.editionIdCourant());
            ps.setString(5, demande.getId());
            if (ps.executeUpdate() == 0) {
                throw new IllegalArgumentException("La demande n'est plus en attente");
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
            mailService.notifierDecision(demandeur.getEmail(), demande, libelleCreneau(demande));
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
                .map(animateur -> animateur.getPrenom() + " " + animateur.getNom())
                .orElse(animateurId);
    }

    /* --------------------------------- SQL --------------------------------- */

    private static final String COLONNES = "id, demandeur_id, cible_id, creneau_id, stand_id, "
            + "motif, statut, prevalidation_ok, contraintes_violees, commentaire_admin, cree_le, decide_le";

    private void inserer(DemandeEchange demande) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = prepareScoped(connection,
                        "INSERT INTO demande_echange (edition_id, " + COLONNES + ") "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(2, demande.getId());
            ps.setString(3, demande.getDemandeurId());
            ps.setString(4, demande.getCibleId());
            ps.setLong(5, demande.getCreneauId());
            ps.setString(6, demande.getStandId());
            ps.setString(7, demande.getMotif());
            ps.setString(8, demande.getStatut().name());
            ps.setObject(9, demande.getPrevalidationOk());
            ps.setString(10, demande.getContraintesViolees().isEmpty()
                    ? null
                    : String.join("\n", demande.getContraintesViolees()));
            ps.setString(11, demande.getCommentaireAdmin());
            ps.setTimestamp(12, Timestamp.from(demande.getCreeLe()));
            ps.setTimestamp(13, demande.getDecideLe() == null ? null : Timestamp.from(demande.getDecideLe()));
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to store demande " + demande.getId(), e);
        }
    }

    private DemandeEchange demandeRequise(String demandeId) {
        List<DemandeEchange> demandes = lister(" AND id = ?", demandeId);
        if (demandes.isEmpty()) {
            throw new IllegalArgumentException("Demande inconnue : " + demandeId);
        }
        return demandes.get(0);
    }

    private List<DemandeEchange> lister(String predicatSupplementaire, String parametre) {
        List<DemandeEchange> demandes = new ArrayList<>();
        String sql = "SELECT " + COLONNES + " FROM demande_echange WHERE edition_id = ?"
                + predicatSupplementaire + " ORDER BY cree_le DESC, id";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement ps = prepareScoped(connection, sql)) {
            if (parametre != null) {
                ps.setString(2, parametre);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    demandes.add(lire(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list demandes d'échange", e);
        }
        return demandes;
    }

    private static DemandeEchange lire(ResultSet rs) throws SQLException {
        DemandeEchange demande = new DemandeEchange();
        demande.setId(rs.getString("id"));
        demande.setDemandeurId(rs.getString("demandeur_id"));
        demande.setCibleId(rs.getString("cible_id"));
        demande.setCreneauId(rs.getLong("creneau_id"));
        demande.setStandId(rs.getString("stand_id"));
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
        Timestamp decideLe = rs.getTimestamp("decide_le");
        demande.setDecideLe(decideLe == null ? null : decideLe.toInstant());
        return demande;
    }

    /** Same convention as {@link ReferenceDataRepository}: edition bound to placeholder 1. */
    private PreparedStatement prepareScoped(Connection connection, String sql) throws SQLException {
        PreparedStatement ps = connection.prepareStatement(sql);
        try {
            ps.setString(1, editionContext.editionIdCourant());
            return ps;
        } catch (SQLException | RuntimeException e) {
            ps.close();
            throw e;
        }
    }
}
