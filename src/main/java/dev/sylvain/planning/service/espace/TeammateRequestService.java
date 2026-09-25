package dev.sylvain.planning.service.espace;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.DeclarationDisponibilite;
import dev.sylvain.planning.domain.DemandeCoequipier;
import dev.sylvain.planning.domain.NatureCoequipier;
import dev.sylvain.planning.domain.StatutDeclaration;
import dev.sylvain.planning.domain.StatutDemandeCoequipier;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.RateLimitVerdict;
import dev.sylvain.planning.service.ReferenceDataChangeTracker;
import dev.sylvain.planning.service.analyse.GroupedArrivalAnalyzer;
import dev.sylvain.planning.service.notification.Notification;
import dev.sylvain.planning.service.referentiel.Avertissement;
import dev.sylvain.planning.service.referentiel.DivergentDays;
import dev.sylvain.planning.service.referentiel.JoursEvenement;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.referentiel.WrittenContrainteAdHoc;
import dev.sylvain.planning.service.solve.SolverJobService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Event;
import jakarta.inject.Inject;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The teammates an animateur names from the Covoiturage tab of their espace —
 * « Je viens avec… » — from their request to the admin's decision.
 *
 * <p><b>Requested by the animateur, validated by the admin</b>, and apart from
 * the availability declaration: its own submission, open while the collection
 * window is, and its own decision — applying or refusing a declaration never
 * touches it, and the other way round. Only {@link #validate} creates
 * the {@link TypeContrainteAdHoc#ARRIVEE_GROUPEE} exception the solver reads,
 * through the same write path as the Ajustements manuels screen — so a group
 * holding two incompatible animateurs is refused there, with the same
 * sentence.</p>
 *
 * <p>Every sentence this class writes names an animateur by id alone: the
 * refusals and the warnings reach screens whose client keeps a log. A decision
 * is told to the people it concerns by a best-effort {@link Notification}: a
 * mail that does not leave never undoes it.</p>
 */
@ApplicationScoped
public class TeammateRequestService {

    private final TeammateDeclarationRepository repository;

    private final DeclarationDisponibiliteRepository declarations;

    private final ReferenceDataService referenceDataService;

    /** The collection window: a request is open exactly when a declaration is. */
    private final DeclarationDisponibiliteService declarationService;

    private final DeclarationRateLimiter rateLimiter;

    private final ApplicationLinks links;

    /** Cancelling deletes an exception the solver reads: the plan goes stale. */
    private final ReferenceDataChangeTracker changeTracker;

    /** Fired as facts; {@code NotificationDispatcher} owns the delivery and its failures. */
    private final Event<Notification> notifications;

    /** A cancellation is refused while a solve holds the edition, like the other writes it would race. */
    private final SolverJobService solverJobs;

    /** The single wording of a request sent while the window is closed. */
    public static final String COLLECTION_CLOSED =
            "La collecte des disponibilités est fermée : votre demande de covoiturage ne peut plus être envoyée";

    /** The single wording of a cancellation aimed at a group that is no longer validated. */
    public static final String NOT_VALIDATED = "Cette arrivée groupée n'est plus validée : il n'y a rien à annuler.";

    /** The single wording of a request sent while a validated group holds the animateur. */
    public static final String GROUP_VALIDATED =
            "Votre arrivée groupée a été validée par l'organisation : pour la modifier ou la retirer, "
                    + "adressez-vous à elle.";

    @Inject
    public TeammateRequestService(
            TeammateDeclarationRepository repository,
            DeclarationDisponibiliteRepository declarations,
            ReferenceDataService referenceDataService,
            DeclarationDisponibiliteService declarationService,
            DeclarationRateLimiter rateLimiter,
            ApplicationLinks links,
            ReferenceDataChangeTracker changeTracker,
            Event<Notification> notifications,
            SolverJobService solverJobs) {
        this.repository = repository;
        this.declarations = declarations;
        this.referenceDataService = referenceDataService;
        this.declarationService = declarationService;
        this.rateLimiter = rateLimiter;
        this.links = links;
        this.changeTracker = changeTracker;
        this.notifications = notifications;
        this.solverJobs = solverJobs;
    }

    /* ------------------------------- Animateur ------------------------------- */

    /**
     * The teammates {@code ids}, checked: at most three, never the declarant,
     * each one an animateur of the edition. Blank and repeated entries are
     * dropped rather than refused — a picker sends what it shows.
     */
    public List<String> check(String animateurId, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        List<String> retenus = ids.stream()
                .filter(id -> id != null && !id.isBlank())
                .map(String::trim)
                .distinct()
                .toList();
        if (retenus.contains(animateurId)) {
            throw new BusinessError.Invalid("On ne vient pas avec soi-même : retirez-vous de la liste.");
        }
        if (retenus.size() > DemandeCoequipier.COEQUIPIERS_MAX) {
            throw new BusinessError.Invalid(
                    "Trois coéquipiers au plus : au-delà, c'est une navette et non plus une voiture.");
        }
        Set<String> connus = referenceDataService.listAnimateurs().stream()
                .map(Animateur::getId)
                .collect(Collectors.toSet());
        retenus.stream().filter(id -> !connus.contains(id)).findFirst().ifPresent(id -> {
            throw new BusinessError.Invalid("Coéquipier inconnu : " + id);
        });
        return retenus;
    }

    /**
     * What the Covoiturage tab of the espace sends.
     *
     * @param teammateIds up to three teammates, by id; empty withdraws the
     *                    pending request
     */
    public record NewCarpoolRequest(List<String> teammateIds) {}

    /**
     * Records the covoiturage {@code animateurId} asks for from their espace,
     * replacing the pending one; an empty list withdraws it. A request set
     * aside earlier stays as it was decided: this one is a new request.
     *
     * @throws BusinessError.Invalid   when the collection window is closed, or
     *                                 the teammates break a rule of
     *                                 {@link #check}
     * @throws BusinessError.Conflict  when a validated grouped arrival already
     *                                 holds the animateur — the organisation's
     *                                 to change, never the espace's
     * @throws DeclarationDisponibiliteService.TooManyRequests past the
     *                                 espace's ceiling of writes
     */
    public void request(String animateurId, List<String> ids) {
        if (!declarationService.isCollecteOuverte()) {
            throw new BusinessError.Invalid(COLLECTION_CLOSED);
        }
        RateLimitVerdict verdict = rateLimiter.submitCarpool(animateurId);
        if (!verdict.autorise()) {
            throw new DeclarationDisponibiliteService.TooManyRequests(verdict.secondsBeforeNextTry());
        }
        if (validatedGroupOf(animateurId).isPresent()) {
            throw new BusinessError.Conflict(GROUP_VALIDATED);
        }
        List<String> coequipiers = check(animateurId, ids);
        if (coequipiers.isEmpty()) {
            repository.deletePending(animateurId, NatureCoequipier.COVOITURAGE);
            return;
        }
        repository.replacePending(new DemandeCoequipier(
                UUID.randomUUID().toString(),
                animateurId,
                NatureCoequipier.COVOITURAGE,
                coequipiers,
                StatutDemandeCoequipier.EN_ATTENTE,
                null,
                Instant.now(),
                null,
                null));
    }

    /**
     * The car an animateur rides in, or asked for, as the espace shows it.
     *
     * @param teammateIds the others in the car, by id
     * @param status      {@code VALIDEE} when a grouped arrival holds it,
     *                    {@code EN_ATTENTE} while it is a request,
     *                    {@code ECARTEE} when the last request was set aside,
     *                    {@code ANNULEE} when the last decision cancelled the
     *                    grouped arrival they rode in
     * @param reason      why it was set aside or cancelled, as the admin typed
     *                    it for the animateur; {@code null} otherwise
     * @param decidedAt   when the organisation decided, {@code null} while
     *                    pending or when a grouped arrival written by hand
     *                    holds the animateur
     */
    public record CarpoolState(
            List<String> teammateIds, StatutDemandeCoequipier status, String reason, Instant decidedAt) {}

    /**
     * The validated grouped arrival holding the animateur when there is one —
     * the exception itself, not the demand that led to it: an exception the
     * organisation removed frees the animateur, one it wrote by hand binds
     * them all the same —, else their pending request, else the latest
     * decision about them when it set their request aside or cancelled the
     * car they rode in, so the espace can say so and why; {@code null} when
     * none of those.
     *
     * <p>A cancellation reaches every member, the declarant and the
     * teammates alike, so the demands naming the animateur count as well as
     * their own; a set-aside concerns the one who asked alone.</p>
     */
    public CarpoolState stateOf(String animateurId) {
        Optional<ContrainteAdHoc> groupe = validatedGroupOf(animateurId);
        List<DemandeCoequipier> covoiturages = repository.list().stream()
                .filter(demande -> demande.nature() == NatureCoequipier.COVOITURAGE)
                .toList();
        List<DemandeCoequipier> siennes = covoiturages.stream()
                .filter(demande -> demande.animateurId().equals(animateurId))
                .toList();
        if (groupe.isPresent()) {
            Instant decideLe = covoiturages.stream()
                    .filter(demande -> groupe.get().getId().equals(demande.contrainteId())
                            && demande.members().contains(animateurId))
                    .map(DemandeCoequipier::decideLe)
                    .findFirst()
                    .orElse(null);
            return new CarpoolState(
                    GroupedArrivalAnalyzer.memberIds(groupe.get()).stream()
                            .filter(membre -> !membre.equals(animateurId))
                            .toList(),
                    StatutDemandeCoequipier.VALIDEE,
                    null,
                    decideLe);
        }
        Optional<DemandeCoequipier> enAttente = siennes.stream()
                .filter(demande -> demande.statut() == StatutDemandeCoequipier.EN_ATTENTE)
                .findFirst();
        if (enAttente.isPresent()) {
            return new CarpoolState(enAttente.get().coequipiers(), StatutDemandeCoequipier.EN_ATTENTE, null, null);
        }
        // Only the latest decision speaks. A validated one whose exception was
        // removed since, by an import or a reset, frees the animateur — no state.
        return covoiturages.stream()
                .filter(demande -> demande.decideLe() != null)
                .filter(demande -> demande.animateurId().equals(animateurId)
                        || (demande.statut() != StatutDemandeCoequipier.ECARTEE
                                && demande.members().contains(animateurId)))
                .max(Comparator.comparing(DemandeCoequipier::decideLe))
                .filter(demande -> demande.statut() == StatutDemandeCoequipier.ECARTEE
                        || demande.statut() == StatutDemandeCoequipier.ANNULEE)
                .map(demande -> new CarpoolState(
                        demande.members().stream()
                                .filter(membre -> !membre.equals(animateurId))
                                .toList(),
                        demande.statut(),
                        demande.motif(),
                        demande.decideLe()))
                .orElse(null);
    }

    /** The first grouped arrival of the edition that names the animateur. */
    private Optional<ContrainteAdHoc> validatedGroupOf(String animateurId) {
        return GroupedArrivalAnalyzer.groups(referenceDataService.listContraintesAdHoc()).stream()
                .filter(groupe -> GroupedArrivalAnalyzer.memberIds(groupe).contains(animateurId))
                .findFirst();
    }

    /** A grouped arrival whose members are exactly {@code membres}, whoever declared it. */
    private Optional<ContrainteAdHoc> groupOf(Set<String> membres) {
        return GroupedArrivalAnalyzer.groups(referenceDataService.listContraintesAdHoc()).stream()
                .filter(groupe -> new HashSet<>(GroupedArrivalAnalyzer.memberIds(groupe)).equals(membres))
                .findFirst();
    }

    /* --------------------------------- Admin --------------------------------- */

    /** A member of a group, named for the admin screen. */
    public record MemberView(String animateurId, String fullName) {}

    /**
     * One demand as the Disponibilités screen shows it.
     *
     * @param status         {@code EN_ATTENTE}, {@code VALIDEE},
     *                       {@code ECARTEE} or {@code ANNULEE}
     * @param members        the declarant first, then the teammates they named
     * @param confirmedByAll every other member declared the very same group
     * @param divergentDays  days on which the members' declared
     *                       unavailabilities disagree — the grouped arrival
     *                       cannot hold there, whatever the solver does
     * @param contrainteId   the {@code ARRIVEE_GROUPEE} exception the
     *                       validation created or joined
     * @param reason         why it was set aside or cancelled, as the admin
     *                       typed it for the animateur; {@code null} when none
     *                       was given
     */
    @Schema(requiredProperties = {"confirmedByAll", "divergentDayCount"})
    public record TeammateRequestView(
            String id,
            String animateurId,
            String nature,
            String status,
            List<MemberView> members,
            boolean confirmedByAll,
            int divergentDayCount,
            List<LocalDate> divergentDays,
            String contrainteId,
            Instant createdAt,
            Instant decidedAt,
            String reason) {}

    /** Every covoiturage demand of the edition, most recent first. */
    public List<TeammateRequestView> list() {
        List<DemandeCoequipier> demandes = repository.list().stream()
                .filter(demande -> demande.nature() == NatureCoequipier.COVOITURAGE)
                .toList();
        Map<String, Animateur> animateurs = referenceDataService.listAnimateurs().stream()
                .collect(Collectors.toMap(Animateur::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        Map<String, Set<LocalDate>> indisponibilites = declaredUnavailability(animateurs);
        Set<LocalDate> joursEvenement = new HashSet<>(
                JoursEvenement.of(referenceDataService.listCreneaux()).jours());
        return demandes.stream()
                .map(demande -> view(demande, demandes, animateurs, indisponibilites, joursEvenement))
                .toList();
    }

    public TeammateRequestView view(DemandeCoequipier demande) {
        return list().stream()
                .filter(vue -> vue.id().equals(demande.id()))
                .findFirst()
                .orElseThrow(() -> new BusinessError.NotFound("Demande inconnue : " + demande.id()));
    }

    /** The validated demand, the view of it, and what the written exception raised. */
    public record ValidatedCarpool(TeammateRequestView request, List<Avertissement> avertissements) {}

    /**
     * Turns one pending demand into an {@code ARRIVEE_GROUPEE} exception naming
     * the declarant and their teammates. The exception is written first, through
     * the ad hoc write path — which refuses it when two members are declared
     * incompatible — and the demand is only marked once it exists; the pending
     * demands of the other members naming the very same group are marked with
     * it, so one car is validated once. A grouped arrival with exactly these
     * members that already exists — validated from another member's demand,
     * or written by hand — is joined rather than doubled: two identical
     * exceptions would charge every misaligned day twice.
     */
    public ValidatedCarpool validate(String id) {
        DemandeCoequipier demande = pendingOrFail(id);
        Map<String, Animateur> animateurs = referenceDataService.listAnimateurs().stream()
                .collect(Collectors.toMap(Animateur::getId, Function.identity()));
        List<Animateur> membres = new ArrayList<>();
        for (String membre : demande.members()) {
            Animateur animateur = animateurs.get(membre);
            if (animateur == null) {
                throw new BusinessError.Invalid(
                        "Un membre de ce covoiturage n'existe plus : " + membre + ". Écartez la demande.");
            }
            membres.add(animateur);
        }
        Set<String> groupe = new HashSet<>(demande.members());
        Optional<ContrainteAdHoc> existante = groupOf(groupe);
        String contrainteId;
        List<Avertissement> avertissements;
        if (existante.isPresent()) {
            contrainteId = existante.get().getId();
            avertissements = List.of();
        } else {
            // No id: the application draws the ajustement's own, like any other.
            ContrainteAdHoc contrainte = new ContrainteAdHoc(null, TypeContrainteAdHoc.ARRIVEE_GROUPEE);
            contrainte.setAnimateursConcernes(membres);
            contrainte.setRaison("Covoiturage demandé depuis l'espace par " + demande.animateurId());
            contrainte.setCreeParUtilisateurId("collecte");
            WrittenContrainteAdHoc ecrite = referenceDataService.writeContrainteAdHoc(contrainte);
            contrainteId = ecrite.contrainte().getId();
            avertissements = ecrite.avertissements();
        }

        Timestamp decideLe = Timestamp.from(Instant.now());
        if (repository.decide(id, StatutDemandeCoequipier.VALIDEE, contrainteId, decideLe, null) == 0) {
            if (existante.isEmpty()) {
                referenceDataService.deleteContrainteAdHoc(contrainteId);
            }
            throw new BusinessError.Conflict("Cette demande a déjà été traitée");
        }
        repository.list().stream()
                .filter(autre -> !autre.id().equals(id)
                        && autre.nature() == NatureCoequipier.COVOITURAGE
                        && autre.statut() == StatutDemandeCoequipier.EN_ATTENTE
                        && new HashSet<>(autre.members()).equals(groupe))
                .forEach(autre ->
                        repository.decide(autre.id(), StatutDemandeCoequipier.VALIDEE, contrainteId, decideLe, null));
        membres.forEach(membre -> notifications.fire(new Notification.CarpoolValidated(
                membre.getEmail(),
                membre.getPrenom(),
                membres.stream()
                        .filter(autre -> !autre.getId().equals(membre.getId()))
                        .map(Animateur::nomAffiche)
                        .toList(),
                links.espaceCovoiturage(membre.getAccessToken()).orElse(null))));
        return new ValidatedCarpool(view(repository.byId(id).orElseThrow()), avertissements);
    }

    /**
     * Sets one pending demand aside: nothing is written but its statut and the
     * reason, which the animateur reads in their espace — and then free to send
     * a new request. The one who asked is told, best-effort.
     *
     * @param reason optional, at most {@link DemandeCoequipier#MOTIF_MAX}
     *               characters; blank is none
     */
    public TeammateRequestView setAside(String id, String reason) {
        String motif = checkedReason(reason);
        DemandeCoequipier demande = pendingOrFail(id);
        if (repository.decide(id, StatutDemandeCoequipier.ECARTEE, null, Timestamp.from(Instant.now()), motif) == 0) {
            throw new BusinessError.Conflict("Cette demande a déjà été traitée");
        }
        referenceDataService.listAnimateurs().stream()
                .filter(animateur -> animateur.getId().equals(demande.animateurId()))
                .findFirst()
                .ifPresent(animateur -> notifications.fire(new Notification.CarpoolSetAside(
                        animateur.getEmail(),
                        animateur.getPrenom(),
                        motif,
                        links.espaceCovoiturage(animateur.getAccessToken()).orElse(null))));
        return view(repository.byId(id).orElseThrow());
    }

    /**
     * Cancels the validated grouped arrival demand {@code id} belongs to: the
     * {@code ARRIVEE_GROUPEE} exception is deleted and every demand validated
     * against it becomes {@code ANNULEE} with the reason, in one transaction.
     * Every member is told, best-effort, with what is left to them: a new
     * request while the collection window is open, the organisation
     * otherwise. A request made after this one is an ordinary new request.
     *
     * <p>Refused while a solve holds the edition ({@link
     * SolverJobService#refuseIfSolving}): the running solve still scores the
     * grouped arrival it started with, and the group would be told of a
     * change the plan it is about to receive does not reflect.</p>
     *
     * @param reason optional, at most {@link DemandeCoequipier#MOTIF_MAX}
     *               characters; blank is none — never journalled
     * @throws BusinessError.NotFound when no demand of the edition has that id
     * @throws BusinessError.Conflict when the demand is not validated, or its
     *                                exception is already gone
     * @throws SolverJobService.SolverBusyException while a solve holds the edition
     */
    public TeammateRequestView cancel(String id, String reason) {
        solverJobs.refuseIfSolving();
        String motif = checkedReason(reason);
        DemandeCoequipier demande =
                repository.byId(id).orElseThrow(() -> new BusinessError.NotFound("Demande inconnue : " + id));
        if (demande.statut() != StatutDemandeCoequipier.VALIDEE || demande.contrainteId() == null) {
            throw new BusinessError.Conflict(NOT_VALIDATED);
        }
        ContrainteAdHoc groupe = referenceDataService.listContraintesAdHoc().stream()
                .filter(contrainte -> contrainte.getId().equals(demande.contrainteId()))
                .findFirst()
                .orElseThrow(() -> new BusinessError.Conflict(NOT_VALIDATED));
        List<String> membreIds = GroupedArrivalAnalyzer.memberIds(groupe);
        if (repository.cancelGroup(groupe.getId(), Timestamp.from(Instant.now()), motif) == 0) {
            throw new BusinessError.Conflict(NOT_VALIDATED);
        }
        changeTracker.markModified();

        boolean collecteOuverte = declarationService.isCollecteOuverte();
        Map<String, Animateur> animateurs = referenceDataService.listAnimateurs().stream()
                .collect(Collectors.toMap(Animateur::getId, Function.identity()));
        List<Animateur> membres =
                membreIds.stream().map(animateurs::get).filter(Objects::nonNull).toList();
        membres.forEach(membre -> notifications.fire(new Notification.CarpoolCancelled(
                membre.getEmail(),
                membre.getPrenom(),
                membres.stream()
                        .filter(autre -> !autre.getId().equals(membre.getId()))
                        .map(Animateur::nomAffiche)
                        .toList(),
                motif,
                collecteOuverte,
                links.espaceCovoiturage(membre.getAccessToken()).orElse(null))));
        return view(repository.byId(id).orElseThrow());
    }

    /** The reason an admin types for the animateur: blank is none, and a sentence at most. */
    private static String checkedReason(String reason) {
        String motif = reason == null || reason.isBlank() ? null : reason.strip();
        if (motif != null && motif.length() > DemandeCoequipier.MOTIF_MAX) {
            throw new BusinessError.Invalid(
                    "Motif trop long : " + DemandeCoequipier.MOTIF_MAX + " caractères au plus.");
        }
        return motif;
    }

    private DemandeCoequipier pendingOrFail(String id) {
        DemandeCoequipier demande =
                repository.byId(id).orElseThrow(() -> new BusinessError.NotFound("Demande inconnue : " + id));
        if (demande.statut() != StatutDemandeCoequipier.EN_ATTENTE) {
            throw new BusinessError.Invalid("Cette demande a déjà été traitée");
        }
        return demande;
    }

    private static TeammateRequestView view(
            DemandeCoequipier demande,
            List<DemandeCoequipier> toutes,
            Map<String, Animateur> animateurs,
            Map<String, Set<LocalDate>> indisponibilites,
            Set<LocalDate> joursEvenement) {
        List<String> membres = demande.members();
        List<Set<LocalDate>> jours = membres.stream()
                .map(membre -> indisponibilites.getOrDefault(membre, Set.of()))
                .toList();
        List<LocalDate> divergents = DivergentDays.of(jours, joursEvenement);
        return new TeammateRequestView(
                demande.id(),
                demande.animateurId(),
                demande.nature().name(),
                demande.statut().name(),
                membres.stream()
                        .map(membre -> new MemberView(
                                membre,
                                animateurs.containsKey(membre)
                                        ? animateurs.get(membre).nomAffiche()
                                        : membre))
                        .toList(),
                confirmedByAll(demande, toutes),
                divergents.size(),
                divergents,
                demande.contrainteId(),
                demande.creeLe(),
                demande.decideLe(),
                demande.motif());
    }

    /**
     * True when every other member stands behind the same group: their latest
     * covoiturage demand that was neither set aside nor cancelled names
     * exactly these people.
     */
    static boolean confirmedByAll(DemandeCoequipier demande, List<DemandeCoequipier> toutes) {
        Set<String> groupe = new HashSet<>(demande.members());
        for (String membre : demande.coequipiers()) {
            DemandeCoequipier sienne = toutes.stream()
                    .filter(autre -> autre.animateurId().equals(membre)
                            && autre.nature() == demande.nature()
                            && autre.statut() != StatutDemandeCoequipier.ECARTEE
                            && autre.statut() != StatutDemandeCoequipier.ANNULEE)
                    .findFirst()
                    .orElse(null);
            if (sienne == null || !new HashSet<>(sienne.members()).equals(groupe)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Each animateur's declared unavailable days: those of their pending
     * declaration when they have one — that is what they are saying now — and
     * those of their fiche otherwise.
     */
    private Map<String, Set<LocalDate>> declaredUnavailability(Map<String, Animateur> animateurs) {
        Map<String, Set<LocalDate>> jours = new LinkedHashMap<>();
        animateurs.forEach((id, animateur) -> jours.put(id, new TreeSet<>(animateur.getJoursIndisponibles())));
        for (DeclarationDisponibilite declaration : declarations.list()) {
            if (declaration.getStatut() == StatutDeclaration.EN_ATTENTE) {
                jours.put(declaration.getAnimateurId(), new LinkedHashSet<>(declaration.getJoursIndisponibles()));
            }
        }
        return jours;
    }
}
