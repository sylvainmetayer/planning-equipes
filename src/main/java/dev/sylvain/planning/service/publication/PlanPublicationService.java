package dev.sylvain.planning.service.publication;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.DemandeEchange;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.StatutDemandeEchange;
import dev.sylvain.planning.service.publication.PublicationDiffService.ChangementAnimateur;
import dev.sylvain.planning.service.publication.PublicationDiffService.ChangementVacation;
import dev.sylvain.planning.service.publication.PublicationDiffService.Identite;
import dev.sylvain.planning.service.publication.PublicationDiffService.Vacation;
import dev.sylvain.planning.service.publication.PublicationTraceRepository.Destinataire;
import dev.sylvain.planning.service.publication.PublicationTraceRepository.StatutEnvoi;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import dev.sylvain.planning.service.export.PlanningExportService;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.espace.DemandeEchangeService;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.solve.PlanSnapshotService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.SolverJobService;

/**
 * Publishing (issue #245): making the working plan the plan people have been
 * told about, and writing only to the people that concerns.
 *
 * <p>This replaces « envoyer à tous », which relivered 150 identical PDFs
 * whatever had moved. The action is not a warning to dismiss but a piece of
 * pending work with a count on it: « Publier — 3 personnes concernées ». The
 * count goes to zero and stays there, so there is nothing to learn to
 * ignore.</p>
 *
 * <p>Nothing here is scheduled or watched: the count is computed when someone
 * asks for it, and publishing is always a click. The guard that matters is not
 * a delay but a concurrency one — publishing while a solve runs would freeze a
 * plan about to be overwritten, so it is refused.</p>
 *
 * <p>Failures are reported, not retried and not re-queued: an animateur whose
 * mail bounced does not come back in the next count — the count measures what
 * changed, not what was delivered. The report names them, and the individual
 * resend of the timeline screen is the way back.</p>
 */
@ApplicationScoped
public class PlanPublicationService {

    /** Label of the snapshot a publication leaves behind, in the server's zone. */
    private static final DateTimeFormatter LIBELLE_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");

    @Inject
    PlanningPersistenceService persistenceService;

    @Inject
    PlanPublieService planPublieService;

    @Inject
    PlanSnapshotService snapshotService;

    @Inject
    PublicationDiffService diffService;

    @Inject
    PublicationTraceRepository traceRepository;

    @Inject
    DemandeEchangeService demandeEchangeService;

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    PlanningExportService planningExportService;

    @Inject
    MailService mailService;

    @Inject
    SolverJobService solverJobService;

    @Inject
    EditionContext editionContext;

    @Inject
    ConfirmationPlanningService confirmationService;

    /**
     * One person the next publication would write to, with the exact sentences
     * they would read.
     *
     * @param changements what moved in their own schedule, already worded
     * @param demandes    where their échange requests stand — decisions taken
     *                    since the last publication, and requests still
     *                    pending. Never enough on their own to make somebody a
     *                    recipient unless a decision was taken: nothing decided
     *                    means nothing to announce.
     */
    @Schema(requiredProperties = {"premiereDiffusion"})
    public record DestinatairePublication(String animateurId, String nomAffiche, String email,
            boolean premiereDiffusion, List<String> changements, List<String> demandes) {

        /** Everything the mail body will carry, changes first. */
        public List<String> lignes() {
            List<String> lignes = new ArrayList<>(changements);
            lignes.addAll(demandes);
            return lignes;
        }
    }

    /**
     * What the screen shows before anything is sent.
     *
     * @param jamaisPublie      no plan was ever published on this edition; the
     *                          first publication then concerns everybody
     * @param planVide          nothing is persisted to publish at all
     * @param solveEnCours      a solve is running: publishing would freeze a
     *                          plan about to be overwritten, so it is refused
     * @param dernierePublicationLe when the last publication left, {@code null}
     *                          if there has never been one
     */
    @Schema(requiredProperties = {"jamaisPublie", "nombreConcernes", "planVide", "solveEnCours"})
    public record ApercuPublication(boolean jamaisPublie, boolean planVide, boolean solveEnCours,
            Instant dernierePublicationLe, int nombreConcernes,
            List<DestinatairePublication> destinataires) {
    }

    /**
     * What the screen shows once the mails have left.
     *
     * @param sansEmail names of concerned people with no address on their fiche
     * @param echecs    names of concerned people whose mail did not leave
     */
    @Schema(requiredProperties = {"envoyes", "snapshotId"})
    public record RapportPublication(long snapshotId, Instant publieLe, int envoyes,
            List<String> sansEmail, List<String> echecs) {
    }

    /* ------------------------------- Preview ------------------------------- */

    /** Who would be written to, and what they would read. Reads only; sends nothing. */
    public ApercuPublication apercu() {
        PlanningEvenement courant = persistenceService.loadPersistedPlanning();
        PlanningEvenement publie = planPublieService.planPublie();
        boolean jamaisPublie = planPublieService.jamaisPublie();
        PlanSnapshotService.SnapshotMeta derniere = planPublieService.lastPublication();

        Map<String, Identite> identites = identites();
        List<ChangementAnimateur> changements = diffService.comparer(
                PublicationDiffService.vacationsByAnimateur(publie),
                PublicationDiffService.vacationsByAnimateur(courant),
                identites,
                jamaisPublie);

        List<DestinatairePublication> destinataires = assembler(changements, identites);
        return new ApercuPublication(
                jamaisPublie,
                courant.getPostes().stream().noneMatch(poste -> poste.getAnimateur() != null),
                solveRunning(),
                derniere == null ? null : derniere.publieLe(),
                destinataires.size(),
                destinataires);
    }

    /**
     * Joins the schedule changes with the échange decisions still to announce.
     * A decision makes somebody a recipient on its own — a refusal changes
     * nothing in the plan, and staying silent about it would leave them
     * waiting for an answer that already exists.
     */
    private List<DestinatairePublication> assembler(List<ChangementAnimateur> changements,
            Map<String, Identite> identites) {
        Map<String, List<String>> decisions = decisionsByAnimateur();
        Map<String, List<String>> enCours = pendingByAnimateur();

        Map<String, DestinatairePublication> byAnimateur = new LinkedHashMap<>();
        for (ChangementAnimateur changement : changements) {
            List<String> lignesDemandes = new ArrayList<>(decisions.getOrDefault(changement.animateurId(), List.of()));
            lignesDemandes.addAll(enCours.getOrDefault(changement.animateurId(), List.of()));
            byAnimateur.put(changement.animateurId(), new DestinatairePublication(
                    changement.animateurId(), changement.nomAffiche(), changement.email(),
                    changement.premiereDiffusion(),
                    changement.changements().stream().map(ChangementVacation::libelle).toList(),
                    lignesDemandes));
        }
        for (Map.Entry<String, List<String>> entree : decisions.entrySet()) {
            if (byAnimateur.containsKey(entree.getKey())) {
                continue;
            }
            Identite identite = identites.get(entree.getKey());
            if (identite == null) {
                continue;
            }
            List<String> lignesDemandes = new ArrayList<>(entree.getValue());
            lignesDemandes.addAll(enCours.getOrDefault(entree.getKey(), List.of()));
            byAnimateur.put(entree.getKey(), new DestinatairePublication(
                    entree.getKey(), identite.nomAffiche(), identite.email(), false,
                    List.of(), lignesDemandes));
        }
        List<DestinatairePublication> destinataires = new ArrayList<>(byAnimateur.values());
        destinataires.sort((gauche, droite) -> String.CASE_INSENSITIVE_ORDER
                .compare(gauche.nomAffiche(), droite.nomAffiche()));
        return List.copyOf(destinataires);
    }

    /* ------------------------------ Publication ---------------------------- */

    /**
     * Publishes: snapshots the working plan as the published one, then writes
     * to the people it concerns and to nobody else.
     *
     * <p>The snapshot is taken <b>before</b> the mails leave, so what was
     * announced and what is stored as published cannot diverge: a send that
     * fails mid-way leaves a coherent published plan and a trace naming who was
     * missed.</p>
     *
     * @throws BusinessError.Conflict while a solve is running, when there is
     *         nothing persisted to publish, or when nobody is concerned — the
     *         last one being the point of the feature, not an error to work
     *         around
     */
    public RapportPublication publier() {
        ApercuPublication apercu = apercu();
        if (apercu.solveEnCours()) {
            throw new BusinessError.Conflict(
                    "Un solve est en cours : publier maintenant figerait un plan sur le point d'être réécrit.");
        }
        if (apercu.planVide()) {
            throw new BusinessError.Conflict("Aucun planning résolu à publier.");
        }
        if (apercu.destinataires().isEmpty()) {
            throw new BusinessError.Conflict(
                    "Personne n'est concerné : le planning publié est déjà à jour.");
        }

        PlanSnapshotService.SnapshotMeta meta = snapshotService.capturePubliee(
                "Publication du " + LIBELLE_FORMAT.format(ZonedDateTime.now()));
        if (meta == null) {
            throw new BusinessError.Conflict("Aucun planning résolu à publier.");
        }

        PlanningEvenement planning = persistenceService.loadPersistedPlanning();
        Instant envoyeLe = Instant.now();
        List<Destinataire> trace = new ArrayList<>();
        List<String> sansEmail = new ArrayList<>();
        List<String> echecs = new ArrayList<>();
        int envoyes = 0;
        for (DestinatairePublication destinataire : apercu.destinataires()) {
            StatutEnvoi statut = send(planning, destinataire);
            switch (statut) {
                case ENVOYE -> envoyes++;
                case SANS_EMAIL -> sansEmail.add(destinataire.nomAffiche());
                case ECHEC -> echecs.add(destinataire.nomAffiche());
            }
            trace.add(new Destinataire(meta.id(), destinataire.animateurId(), destinataire.nomAffiche(),
                    destinataire.email(), statut, envoyeLe, destinataire.lignes()));
        }
        traceRepository.record(meta.id(), trace);
        demandeEchangeService.markAsCommunicated(decisionsAnnoncees(), envoyeLe);
        // Back to NON_VU for the people whose own schedule moved, and for
        // nobody else (issue #293): a recipient who is only being told that an
        // échange was decided reads the same days as before, and asking them to
        // re-confirm an unchanged planning is how a confirmation button becomes
        // a reflex instead of an answer.
        confirmationService.reset(apercu.destinataires().stream()
                .filter(destinataire -> !destinataire.changements().isEmpty())
                .map(DestinatairePublication::animateurId)
                .toList());

        return new RapportPublication(meta.id(), meta.publieLe(), envoyes,
                List.copyOf(sansEmail), List.copyOf(echecs));
    }

    private StatutEnvoi send(PlanningEvenement planning, DestinatairePublication destinataire) {
        if (destinataire.email() == null || destinataire.email().isBlank()) {
            return StatutEnvoi.SANS_EMAIL;
        }
        try {
            byte[] pdf = planningExportService.exportAnimateurPdfPublie(planning, destinataire.animateurId());
            Animateur animateur = animateur(destinataire.animateurId());
            mailService.sendPlanningPublie(
                    destinataire.email(),
                    animateur == null ? null : animateur.getPrenom(),
                    planningExportService.lienEspaceAnimateur(planning, destinataire.animateurId()),
                    pdf,
                    PlanningExportService.planningFileName(destinataire.nomAffiche(), "pdf"),
                    destinataire.premiereDiffusion(),
                    destinataire.changements(),
                    destinataire.demandes());
            return StatutEnvoi.ENVOYE;
        } catch (RuntimeException e) {
            Log.errorf(e, "Failed to mail the published planning of animateur %s", destinataire.animateurId());
            return StatutEnvoi.ECHEC;
        }
    }

    /* -------------------------------- Helpers ------------------------------ */

    private boolean solveRunning() {
        Optional<SolverJobService.SolverJob> actif = solverJobService.findActive();
        return actif.isPresent()
                && editionContext.editionIdCourant().equals(actif.get().getEditionId());
    }

    private Map<String, Identite> identites() {
        Map<String, Identite> identites = new LinkedHashMap<>();
        for (Animateur animateur : referenceDataService.listAnimateurs()) {
            identites.put(animateur.getId(), new Identite(animateur.nomAffiche(), animateur.getEmail()));
        }
        return identites;
    }

    private Animateur animateur(String animateurId) {
        return referenceDataService.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(animateurId))
                .findFirst()
                .orElse(null);
    }

    /** Ids of the decisions this publication is about to announce. */
    private Set<String> decisionsAnnoncees() {
        Set<String> ids = new LinkedHashSet<>();
        for (DemandeEchange demande : demandeEchangeService.decisionsNonCommuniquees()) {
            ids.add(demande.getId());
        }
        return ids;
    }

    /** Wording of the decisions taken since the last publication, per demandeur. */
    private Map<String, List<String>> decisionsByAnimateur() {
        Map<String, List<String>> lignes = new LinkedHashMap<>();
        for (DemandeEchange demande : demandeEchangeService.decisionsNonCommuniquees()) {
            lignes.computeIfAbsent(demande.getDemandeurId(), unused -> new ArrayList<>())
                    .add(libelleDecision(demande));
        }
        return lignes;
    }

    /** Wording of the requests still waiting, per demandeur. */
    private Map<String, List<String>> pendingByAnimateur() {
        Map<String, List<String>> lignes = new LinkedHashMap<>();
        for (DemandeEchange demande : demandeEchangeService.pendingDemandes()) {
            lignes.computeIfAbsent(demande.getDemandeurId(), unused -> new ArrayList<>())
                    .add(libellePendingDemande(demande));
        }
        return lignes;
    }

    private String libelleDecision(DemandeEchange demande) {
        String creneau = libelleCreneau(demande);
        StringBuilder ligne = new StringBuilder("Votre demande d'échange")
                .append(creneau == null ? "" : " (" + creneau + ")");
        if (demande.getStatut() == StatutDemandeEchange.ACCEPTEE) {
            ligne.append(" a été acceptée : elle est prise en compte dans ce planning.");
        } else {
            ligne.append(" a été refusée : votre planning reste inchangé sur ce point.");
        }
        if (demande.getCommentaireAdmin() != null && !demande.getCommentaireAdmin().isBlank()) {
            ligne.append(" Commentaire de l'organisation : ").append(demande.getCommentaireAdmin());
        }
        return ligne.toString();
    }

    private String libellePendingDemande(DemandeEchange demande) {
        String creneau = libelleCreneau(demande);
        return "Votre demande d'échange" + (creneau == null ? "" : " (" + creneau + ")")
                + (demande.getStatut() == StatutDemandeEchange.EN_ATTENTE_CIBLE
                        ? " attend l'accord du collègue concerné."
                        : " est en attente de décision de l'organisation.");
    }

    /**
     * The créneau of a demande, worded like the change lines are — same
     * sentence shape for the same object, whichever section of the mail it
     * appears in.
     */
    private String libelleCreneau(DemandeEchange demande) {
        return referenceDataService.listCreneaux().stream()
                .filter(creneau -> creneau.getId() != null && creneau.getId().equals(demande.getCreneauId()))
                .findFirst()
                .map(creneau -> diffService.libelleVacation(new Vacation(creneau.getDate(),
                        creneau.getHeureDebut(), creneau.getHeureFin(), demande.getStandId(),
                        nomStand(demande.getStandId()))))
                .orElse(null);
    }

    private String nomStand(String standId) {
        return referenceDataService.listStands().stream()
                .filter(stand -> stand.getId().equals(standId))
                .findFirst()
                .map(stand -> stand.getNom())
                .orElse(standId);
    }
}
