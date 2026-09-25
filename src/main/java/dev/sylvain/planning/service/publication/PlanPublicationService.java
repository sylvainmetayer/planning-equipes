package dev.sylvain.planning.service.publication;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.DemandeEchange;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.StatutDemandeEchange;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.espace.DemandeEchangeService;
import dev.sylvain.planning.service.export.PlanningExportService;
import dev.sylvain.planning.service.publication.PublicationDiffService.ChangementAnimateur;
import dev.sylvain.planning.service.publication.PublicationDiffService.ChangementVacation;
import dev.sylvain.planning.service.publication.PublicationDiffService.Identite;
import dev.sylvain.planning.service.publication.PublicationDiffService.Vacation;
import dev.sylvain.planning.service.publication.PublicationTraceRepository.Destinataire;
import dev.sylvain.planning.service.publication.PublicationTraceRepository.StatutEnvoi;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanSnapshotService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.solve.SolverJobService;
import dev.sylvain.planning.service.validation.ValidationPrerequisService;
import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

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
    dev.sylvain.planning.service.consigne.ConsigneService consigneService;

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    PlanningExportService planningExportService;

    @Inject
    MailService mailService;

    @Inject
    SolverJobService solverJobService;

    @Inject
    ValidationPrerequisService prerequisService;

    @Inject
    EditionContext editionContext;

    @Inject
    ConfirmationPlanningService confirmationService;

    @Inject
    NotifiedPlanRepository notifiedPlans;

    @Inject
    dev.sylvain.planning.service.journal.JournalActionService journal;

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
     * @param ajouts      how many seats are new to them, of the sentences
     *                    above — counted here rather than parsed from the
     *                    wording, so the screen can sort on the size of a
     *                    change without reading French (issue #503)
     * @param retraits    how many they no longer hold
     * @param deplacements how many moved stand or hours
     * @param mineur      every one of their changes is the same vacation
     *                    sliding on the same stand by at most
     *                    {@link PublicationDiffService#DECALAGE_MINEUR}, and
     *                    no échange decision is waiting to be announced:
     *                    « ces trois-là ne bougent que de dix minutes »
     * @param reporte     they were deferred by a previous publication and are
     *                    still owed a message — the écart below is counted
     *                    from what they really received, not from the last
     *                    plan published
     * @param confirmation where their « j'ai lu » stands on the plan they were
     *                    last sent, {@code null} when they never answered
     * @param confirmeLe  when they answered it
     */
    @Schema(requiredProperties = {"premiereDiffusion", "ajouts", "retraits", "deplacements", "mineur", "reporte"})
    public record DestinatairePublication(
            String animateurId,
            String nomAffiche,
            String email,
            boolean premiereDiffusion,
            List<String> changements,
            List<String> demandes,
            int ajouts,
            int retraits,
            int deplacements,
            boolean mineur,
            boolean reporte,
            String confirmation,
            Instant confirmeLe) {}

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
     * @param journeesNonValidees days of the edition nobody has marked « relu et
     *                          accepté ». Said, never enforced: publishing an
     *                          unreviewed day is an ordinary thing to do — what
     *                          is not ordinary is doing it without knowing
     */
    @Schema(requiredProperties = {"jamaisPublie", "journeesNonValidees", "nombreConcernes", "planVide", "solveEnCours"})
    public record ApercuPublication(
            boolean jamaisPublie,
            boolean planVide,
            boolean solveEnCours,
            Instant dernierePublicationLe,
            int nombreConcernes,
            int journeesNonValidees,
            List<DestinatairePublication> destinataires) {}

    /**
     * What the screen shows once the mails have left.
     *
     * @param sansEmail names of concerned people with no address on their fiche
     * @param echecs    names of concerned people whose mail did not leave
     * @param differes  names of the people the admin took out of this send.
     *                  They keep the reference they really received, so they
     *                  come back in the next count with the écart accumulated
     *                  since — nothing is lost by deferring somebody
     */
    @Schema(requiredProperties = {"envoyes", "snapshotId"})
    public record RapportPublication(
            long snapshotId,
            Instant publieLe,
            int envoyes,
            List<String> sansEmail,
            List<String> echecs,
            List<String> differes) {}

    /* ------------------------------- Preview ------------------------------- */

    /** Who would be written to, and what they would read. Reads only; sends nothing. */
    public ApercuPublication apercu() {
        PlanningEvenement courant = persistenceService.loadPersistedPlanning();
        boolean jamaisPublie = planPublieService.jamaisPublie();
        PlanSnapshotService.SnapshotMeta derniere = planPublieService.lastPublication();

        Map<String, Identite> identites = identites();
        Map<String, Long> marqueurs = notifiedPlans.byAnimateur();
        List<ChangementAnimateur> changements = diffService.comparer(
                vacationsNotifiees(marqueurs, identites.keySet()),
                PublicationDiffService.vacationsByAnimateur(courant),
                identites,
                jamaisPublie);

        List<DestinatairePublication> destinataires =
                assembler(changements, identites, traceRepository.deferred(), confirmationService.storedByAnimateur());
        ValidationPrerequisService.ProgressionValidations relecture = prerequisService.progression();
        return new ApercuPublication(
                jamaisPublie,
                courant.getPostes().stream().noneMatch(poste -> poste.getAnimateur() != null),
                solveRunning(),
                derniere == null ? null : derniere.publieLe(),
                destinataires.size(),
                relecture.journees() - relecture.journeesValidees(),
                destinataires);
    }

    /**
     * What each person was really told, seat by seat: their own schedule in
     * the snapshot their marker names, and nothing for somebody who has never
     * been told anything (issue #503).
     *
     * <p>One reference per person rather than one for everybody, because
     * somebody can be deferred — and comparing a deferred person to a plan
     * they never received is exactly how their écart disappeared without a
     * word. Each distinct snapshot is loaded once: an ordinary publication
     * leaves everybody on the same marker, so this is one read, and it grows
     * only by the number of people actually carried over.</p>
     *
     * <p>An animateur whose fiche is gone is left out, as everywhere else in
     * this service: there is nobody left to write to.</p>
     */
    private Map<String, List<Vacation>> vacationsNotifiees(Map<String, Long> marqueurs, Set<String> connus) {
        List<Long> references = marqueurs.entrySet().stream()
                .filter(marqueur -> connus.contains(marqueur.getKey()))
                .map(Map.Entry::getValue)
                .distinct()
                .toList();
        // Read in one go: each reference used to assemble a whole plan of its
        // own, re-reading the animateurs, the créneaux, the stands and every
        // horaire — once per distinct marker.
        Map<Long, Optional<Map<String, List<Vacation>>>> parSnapshot =
                planPublieService.vacationsBySnapshot(references);
        Map<String, List<Vacation>> notifiees = new LinkedHashMap<>();
        for (Map.Entry<String, Long> marqueur : marqueurs.entrySet()) {
            if (!connus.contains(marqueur.getKey())) {
                continue;
            }
            // A marker naming a snapshot that is gone leaves no entry at all:
            // an empty list would claim this person was told they had nothing,
            // where the truth is that nothing is left to compare against.
            parSnapshot
                    .getOrDefault(marqueur.getValue(), Optional.empty())
                    .ifPresent(vacations ->
                            notifiees.put(marqueur.getKey(), vacations.getOrDefault(marqueur.getKey(), List.of())));
        }
        return notifiees;
    }

    /**
     * Joins the schedule changes with the échange decisions still to announce.
     * A decision makes somebody a recipient on its own — a refusal changes
     * nothing in the plan, and staying silent about it would leave them
     * waiting for an answer that already exists.
     */
    private List<DestinatairePublication> assembler(
            List<ChangementAnimateur> changements,
            Map<String, Identite> identites,
            Set<String> differes,
            Map<String, ConfirmationPlanningRepository.Confirmation> confirmations) {
        Map<String, List<String>> decisions = decisionsByAnimateur();
        Map<String, List<String>> enCours = pendingByAnimateur();

        Map<String, DestinatairePublication> byAnimateur = new LinkedHashMap<>();
        for (ChangementAnimateur changement : changements) {
            List<String> lignesDemandes = new ArrayList<>(decisions.getOrDefault(changement.animateurId(), List.of()));
            lignesDemandes.addAll(enCours.getOrDefault(changement.animateurId(), List.of()));
            byAnimateur.put(
                    changement.animateurId(),
                    destinataire(
                            changement.animateurId(),
                            new Identite(changement.nomAffiche(), changement.email()),
                            changement.premiereDiffusion(),
                            changement.changements(),
                            lignesDemandes,
                            differes,
                            confirmations));
        }
        for (Map.Entry<String, List<String>> entree : decisions.entrySet()) {
            Identite identite = identites.get(entree.getKey());
            if (byAnimateur.containsKey(entree.getKey()) || identite == null) {
                continue;
            }
            List<String> lignesDemandes = new ArrayList<>(entree.getValue());
            lignesDemandes.addAll(enCours.getOrDefault(entree.getKey(), List.of()));
            byAnimateur.put(
                    entree.getKey(),
                    destinataire(entree.getKey(), identite, false, List.of(), lignesDemandes, differes, confirmations));
        }
        List<DestinatairePublication> destinataires = new ArrayList<>(byAnimateur.values());
        destinataires.sort(
                (gauche, droite) -> String.CASE_INSENSITIVE_ORDER.compare(gauche.nomAffiche(), droite.nomAffiche()));
        return List.copyOf(destinataires);
    }

    /**
     * One row of the review table: the sentences, plus everything the screen
     * needs to sort and filter them without reading French (issue #503).
     *
     * <p>A recipient is « mineur » only when <b>every</b> one of their changes
     * is one — one added seat among three slides is still a seat they do not
     * know they hold — and only when nothing else is waiting to be announced:
     * an échange decision is an answer somebody is waiting for, whatever moved
     * in the plan.</p>
     */
    private DestinatairePublication destinataire(
            String animateurId,
            Identite identite,
            boolean premiereDiffusion,
            List<ChangementVacation> changements,
            List<String> demandes,
            Set<String> differes,
            Map<String, ConfirmationPlanningRepository.Confirmation> confirmations) {
        int ajouts = countOf(changements, PublicationDiffService.TypeChangement.AJOUT);
        int retraits = countOf(changements, PublicationDiffService.TypeChangement.RETRAIT);
        int deplacements = countOf(changements, PublicationDiffService.TypeChangement.DEPLACEMENT);
        boolean mineur = !changements.isEmpty()
                && demandes.isEmpty()
                && !premiereDiffusion
                && changements.stream().allMatch(PublicationDiffService::isMinor);
        ConfirmationPlanningRepository.Confirmation confirmation = confirmations.get(animateurId);
        return new DestinatairePublication(
                animateurId,
                identite.nomAffiche(),
                identite.email(),
                premiereDiffusion,
                changements.stream().map(ChangementVacation::libelle).toList(),
                demandes,
                ajouts,
                retraits,
                deplacements,
                mineur,
                differes.contains(animateurId),
                confirmation == null ? null : confirmation.statut().name(),
                confirmation == null ? null : confirmation.confirmeLe());
    }

    private static int countOf(List<ChangementVacation> changements, PublicationDiffService.TypeChangement type) {
        return (int) changements.stream()
                .filter(changement -> changement.type() == type)
                .count();
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
     * <p><b>Excluding somebody defers their message, it does not drop it</b>
     * (issue #503). The capture still happens for everybody — the espace
     * follows the published plan, and leaving one person's espace behind would
     * need a second published plan, which is exactly the thing ADR 0011
     * removed. What stays behind is their <b>marker</b>: the plan they were
     * told about does not move, so the next publication compares their
     * schedule to what they really received and they come back in the count,
     * with the écart accumulated since.</p>
     *
     * @param exclusions animateur ids the admin took out of this send; ids
     *                   that are not recipients are ignored rather than
     *                   refused — the screen may have been read before the
     *                   last change
     * @throws BusinessError.Conflict while a solve is running, when there is
     *         nothing persisted to publish, when nobody is concerned — that
     *         last one being the point of the feature, not an error to work
     *         around — or when every single recipient was excluded
     */
    public RapportPublication publier() {
        return publier(List.of());
    }

    /**
     * Same, with the people the admin took out of this send — see the
     * {@code exclusions} parameter below.
     */
    public RapportPublication publier(List<String> exclusions) {
        ApercuPublication apercu = apercu();
        if (apercu.solveEnCours()) {
            throw new BusinessError.Conflict(
                    "Un solve est en cours : publier maintenant figerait un plan sur le point d'être réécrit.");
        }
        if (apercu.planVide()) {
            throw new BusinessError.Conflict("Aucun planning résolu à publier.");
        }
        if (apercu.destinataires().isEmpty()) {
            throw new BusinessError.Conflict("Personne n'est concerné : le planning publié est déjà à jour.");
        }
        Set<String> demandes = exclusions == null ? Set.of() : new LinkedHashSet<>(exclusions);
        List<DestinatairePublication> differes = apercu.destinataires().stream()
                .filter(destinataire -> demandes.contains(destinataire.animateurId()))
                .toList();
        List<DestinatairePublication> retenus = apercu.destinataires().stream()
                .filter(destinataire -> !demandes.contains(destinataire.animateurId()))
                .toList();
        if (retenus.isEmpty()) {
            throw new BusinessError.Conflict(
                    "Tous les destinataires sont exclus : cette publication ne préviendrait personne.");
        }

        // The plan each person's diff was read against, kept before the
        // capture makes the working plan the published one: somebody the band
        // emptied on a date holds a seat there only in their reference, and
        // still has to read why. Per person, not global (issue #503): a
        // deferred recipient is compared to the snapshot they really received,
        // and reading the global one here would drop the consigne sentence on
        // exactly the dates only their own reference knows about.
        Map<String, List<Vacation>> referencesParAnimateur = vacationsNotifiees(
                notifiedPlans.byAnimateur(),
                apercu.destinataires().stream()
                        .map(DestinatairePublication::animateurId)
                        .collect(Collectors.toSet()));
        PlanningEvenement reference = planPublieService.planPublie();
        PlanSnapshotService.SnapshotMeta meta = snapshotService.capturePubliee(
                "Publication du " + LIBELLE_FORMAT.format(ZonedDateTime.now(ZoneId.systemDefault())));
        if (meta == null) {
            throw new BusinessError.Conflict("Aucun planning résolu à publier.");
        }

        PlanningEvenement planning = persistenceService.loadPersistedPlanning();
        Instant envoyeLe = Instant.now();
        List<Destinataire> trace = new ArrayList<>();
        List<String> sansEmail = new ArrayList<>();
        List<String> echecs = new ArrayList<>();
        int envoyes = 0;
        for (DestinatairePublication destinataire : retenus) {
            StatutEnvoi statut = send(planning, reference, referencesParAnimateur, destinataire);
            switch (statut) {
                case ENVOYE -> envoyes++;
                case SANS_EMAIL -> sansEmail.add(destinataire.nomAffiche());
                case ECHEC -> echecs.add(destinataire.nomAffiche());
                case EXCLU -> {
                    // Never an outcome of a send: excluded recipients are traced apart, unsent.
                }
            }
            trace.add(new Destinataire(
                    meta.id(),
                    destinataire.animateurId(),
                    destinataire.nomAffiche(),
                    destinataire.email(),
                    statut,
                    envoyeLe,
                    destinataire.changements(),
                    destinataire.demandes(),
                    destinataire.premiereDiffusion()));
        }
        for (DestinatairePublication differe : differes) {
            trace.add(new Destinataire(
                    meta.id(),
                    differe.animateurId(),
                    differe.nomAffiche(),
                    differe.email(),
                    StatutEnvoi.EXCLU,
                    envoyeLe,
                    differe.changements(),
                    differe.demandes(),
                    differe.premiereDiffusion()));
            journal.recordAdminAction("PUBLICATION_DIFFEREE", differe.animateurId());
        }
        traceRepository.recordRecipients(meta.id(), trace);
        // The marker moves for the people this publication addressed, and for
        // them only. Somebody it had nothing to say to was told nothing, so
        // claiming they know this plan would turn their next message from
        // « voici votre planning » into « votre planning a changé » — and
        // somebody deferred keeps theirs, which is what brings them back in
        // the next count with their own écart rather than with nothing.
        notifiedPlans.mark(
                retenus.stream().map(DestinatairePublication::animateurId).toList(), meta.id());
        demandeEchangeService.markAsCommunicated(
                decisionsAnnoncees(retenus.stream()
                        .map(DestinatairePublication::animateurId)
                        .collect(Collectors.toSet())),
                envoyeLe);
        // Back to NON_VU for the people whose own schedule moved, and for
        // nobody else (issue #293): a recipient who is only being told that an
        // échange was decided reads the same days as before, and asking them to
        // re-confirm an unchanged planning is how a confirmation button becomes
        // a reflex instead of an answer.
        confirmationService.reset(retenus.stream()
                .filter(destinataire -> !destinataire.changements().isEmpty())
                .map(DestinatairePublication::animateurId)
                .toList());

        return new RapportPublication(
                meta.id(),
                meta.publieLe(),
                envoyes,
                List.copyOf(sansEmail),
                List.copyOf(echecs),
                differes.stream().map(DestinatairePublication::nomAffiche).toList());
    }

    private StatutEnvoi send(
            PlanningEvenement planning,
            PlanningEvenement reference,
            Map<String, List<Vacation>> referencesParAnimateur,
            DestinatairePublication destinataire) {
        if (destinataire.email() == null || destinataire.email().isBlank()) {
            return StatutEnvoi.SANS_EMAIL;
        }
        try {
            byte[] pdf = planningExportService.exportAnimateurPdfPublie(planning, destinataire.animateurId());
            Animateur animateur = animateur(destinataire.animateurId());
            mailService.sendPlanningPublie(
                    destinataire.email(),
                    pdf,
                    PlanningExportService.planningFileName(destinataire.nomAffiche(), "pdf"),
                    new MailService.PlanningPublie(
                            animateur == null ? null : animateur.getPrenom(),
                            planningExportService.lienEspaceAnimateur(planning, destinataire.animateurId()),
                            destinataire.premiereDiffusion(),
                            destinataire.changements(),
                            destinataire.demandes(),
                            consigneService.lignesJourneesModifiees(datesConcernees(
                                    planning,
                                    reference,
                                    referencesParAnimateur.get(destinataire.animateurId()),
                                    destinataire.animateurId()))));
            return StatutEnvoi.ENVOYE;
        } catch (RuntimeException e) {
            Log.errorf(e, "Failed to mail the published planning of animateur %s", destinataire.animateurId());
            return StatutEnvoi.ECHEC;
        }
    }

    /**
     * The dates {@code animateurId} holds a seat on in either plan — the one
     * being published or the one they were last told about. A consigne on a
     * date the band emptied for them shows in the second only: they read a
     * bare « vacation retirée » otherwise, with nothing saying an arrêté
     * decided it.
     *
     * @param notifiees the vacations this person was last sent, when a marker
     *                  names the snapshot they received (issue #503). Their
     *                  own reference, which a deferred recipient's differs
     *                  from; {@code null} falls back on the global published
     *                  plan, which is what the marker means when it is absent
     */
    static List<java.time.LocalDate> datesConcernees(
            PlanningEvenement planning, PlanningEvenement reference, List<Vacation> notifiees, String animateurId) {
        Stream<java.time.LocalDate> lues = notifiees == null
                ? daysOf(reference, animateurId)
                : notifiees.stream().map(Vacation::date);
        return Stream.concat(daysOf(planning, animateurId), lues)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    private static Stream<java.time.LocalDate> daysOf(PlanningEvenement planning, String animateurId) {
        if (planning == null) {
            return Stream.empty();
        }
        return planning.getPostes().stream()
                .filter(poste -> poste.getAnimateur() != null
                        && animateurId.equals(poste.getAnimateur().getId())
                        && poste.getCreneau() != null
                        && poste.getCreneau().getDate() != null)
                .map(poste -> poste.getCreneau().getDate());
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

    /**
     * Ids of the decisions this publication is about to announce — only those
     * it has a line for, <b>and</b> only those belonging to somebody it is
     * actually writing to. An id marked communicated without a line ever having
     * been written would be a decision nobody was told about, and nothing would
     * ever bring it back; a deferred person's decision is exactly that case
     * (issue #503), so it waits for the publication that carries their
     * message.
     */
    private Set<String> decisionsAnnoncees(Set<String> destinataires) {
        Set<String> ids = new LinkedHashSet<>();
        for (DemandeEchange demande : demandeEchangeService.decisionsNonCommuniquees()) {
            if (libelleDecision(demande) != null && destinataires.contains(demande.getDemandeurId())) {
                ids.add(demande.getId());
            }
        }
        return ids;
    }

    /** Wording of the decisions taken since the last publication, per demandeur. */
    private Map<String, List<String>> decisionsByAnimateur() {
        Map<String, List<String>> lignes = new LinkedHashMap<>();
        for (DemandeEchange demande : demandeEchangeService.decisionsNonCommuniquees()) {
            String ligne = libelleDecision(demande);
            if (ligne != null) {
                lignes.computeIfAbsent(demande.getDemandeurId(), unused -> new ArrayList<>())
                        .add(ligne);
            }
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

    /**
     * The wording of one decision, {@code null} for a statut this publication
     * has nothing to say about.
     *
     * <p>« Everything that is not accepted is a refusal » is what announced an
     * annulation — the demandeur's own withdrawal — as a refusal by the
     * organisation (issue #540). The statuts are named one by one now, so a
     * fifth one added tomorrow says nothing rather than saying the wrong
     * thing.</p>
     */
    private String libelleDecision(DemandeEchange demande) {
        String verdict =
                switch (demande.getStatut()) {
                    case ACCEPTEE -> " a été acceptée : elle est prise en compte dans ce planning.";
                    case REFUSEE -> " a été refusée : votre planning reste inchangé sur ce point.";
                    case ANNULEE, REFUSEE_CIBLE, EN_ATTENTE_CIBLE, PROPOSEE -> null;
                };
        if (verdict == null) {
            return null;
        }
        String creneau = libelleCreneau(demande);
        StringBuilder ligne =
                new StringBuilder("Votre demande d'échange").append(creneau == null ? "" : " (" + creneau + ")");
        ligne.append(verdict);
        if (demande.getCommentaireAdmin() != null
                && !demande.getCommentaireAdmin().isBlank()) {
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
                .map(creneau -> diffService.libelleVacation(new Vacation(
                        creneau.getDate(),
                        creneau.getHeureDebut(),
                        creneau.getHeureFin(),
                        demande.getStandId(),
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

    /* --------------------------------- CSV --------------------------------- */

    /**
     * The review table as a file, for the reading that happens away from the
     * screen (issue #503): « on passe la liste en réunion, on décide qui on
     * appelle avant d'envoyer ».
     *
     * <p>Headers are the field names of the API, not French labels: this file
     * is read next to the JSON it comes from, and a column called
     * {@code deplacements} is the same thing in both. The sentences travel in
     * one cell, separated by {@code |}, because one line per person is what
     * makes the file sortable in a spreadsheet — which is the whole point of
     * exporting it.</p>
     */
    public static String generateCsv(ApercuPublication apercu) {
        StringBuilder csv =
                new StringBuilder("animateurId;nomAffiche;email;premiereDiffusion;reporte;ajouts;retraits;deplacements;"
                        + "mineur;confirmation;changements;demandes\n");
        for (DestinatairePublication destinataire : apercu.destinataires()) {
            csv.append(escape(destinataire.animateurId()))
                    .append(';')
                    .append(escape(destinataire.nomAffiche()))
                    .append(';')
                    .append(escape(destinataire.email()))
                    .append(';')
                    .append(destinataire.premiereDiffusion())
                    .append(';')
                    .append(destinataire.reporte())
                    .append(';')
                    .append(destinataire.ajouts())
                    .append(';')
                    .append(destinataire.retraits())
                    .append(';')
                    .append(destinataire.deplacements())
                    .append(';')
                    .append(destinataire.mineur())
                    .append(';')
                    .append(escape(destinataire.confirmation()))
                    .append(';')
                    .append(escape(String.join(" | ", destinataire.changements())))
                    .append(';')
                    .append(escape(String.join(" | ", destinataire.demandes())))
                    .append('\n');
        }
        return csv.toString();
    }

    private static String escape(String valeur) {
        if (valeur == null) {
            return "";
        }
        if (valeur.contains(";") || valeur.contains("\"") || valeur.contains("\n")) {
            return "\"" + valeur.replace("\"", "\"\"") + "\"";
        }
        return valeur;
    }
}
