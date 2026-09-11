package dev.sylvain.planning.service.publication;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.StatutConfirmation;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanSnapshotService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * « J'ai lu et je serai là » (issue #293): the one thing an animateur answers
 * back, and the only signal the organisation has that the planning it sent was
 * actually read.
 *
 * <p>It is an answer <b>to the published plan</b>, never to the working one —
 * confirming a plan nobody was told about would mean nothing. Publishing again
 * therefore sends back to {@link StatutConfirmation#NON_VU} exactly the people
 * whose own schedule moved (see {@link PlanPublicationService}): somebody whose
 * days did not budge has already answered the question they are being asked,
 * and asking again would train everyone to click without reading.</p>
 */
@ApplicationScoped
public class ConfirmationPlanningService {

    @Inject
    ConfirmationPlanningRepository repository;

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    PlanPublieService planPublieService;

    /**
     * One animateur's answer, as the admin table shows it.
     *
     * @param affecte holds at least one seat in the published plan — the only
     *                people the question is even asked of; the others show as
     *                "sans objet" rather than as silent
     */
    @Schema(requiredProperties = {"affecte"})
    public record ConfirmationView(
            String animateurId,
            String nomAffiche,
            String statut,
            boolean affecte,
            Instant confirmeLe,
            Instant relanceLe) {}

    /** What the espace reads back after the click: its own new state, and nothing about anybody else. */
    public record AccuseReception(String statut, Instant confirmeLe) {}

    /**
     * The edition's answers in three numbers (issue #504), counted among the
     * people who hold a seat in the published plan — the only ones the
     * question is asked of — next to the date they were asked.
     *
     * <p>Read by the head of the Animateurs page and by « État de l'édition »;
     * it is one read, not the whole table summed on the client, so both
     * screens show the same figures at the same moment.</p>
     *
     * @param dernierePublicationLe when the plan the answers are about left,
     *                              {@code null} while nothing was ever published
     * @param jamaisPublie          true before the first publication: the three
     *                              counts are then all zero, and mean nothing
     */
    @Schema(requiredProperties = {"confirmes", "relances", "silencieux", "jamaisPublie"})
    public record SyntheseConfirmations(
            int confirmes, int relances, int silencieux, Instant dernierePublicationLe, boolean jamaisPublie) {}

    /**
     * Records the animateur's own click. Idempotent: clicking twice keeps the
     * first date.
     *
     * <p>Refused for somebody who holds no seat in the published plan, and that
     * refusal is the server's job rather than the interface's. The espace is
     * public, so hiding the button is a courtesy, not a guarantee — and an
     * answer that was accepted, stored, then shown as « — » on the admin screen
     * (because {@code affecte} is false) is worse than a refusal: the person
     * believes they have answered, and nobody sees it.</p>
     */
    public AccuseReception confirmer(String animateurId) {
        if (planPublieService.jamaisPublie()) {
            throw new BusinessError.Conflict("Aucun planning n'a encore été communiqué : il n'y a rien à confirmer.");
        }
        if (!assignedAnimateurs().contains(animateurId)) {
            throw new BusinessError.Conflict(
                    "Vous n'avez aucun poste sur le planning publié : il n'y a rien à confirmer.");
        }
        repository.confirmer(animateurId, Instant.now());
        return stored(animateurId)
                .map(confirmation -> new AccuseReception(confirmation.statut().name(), confirmation.confirmeLe()))
                .orElseThrow(() -> new BusinessError.NotFound("Animateur inconnu : " + animateurId));
    }

    /**
     * One animateur's stored answer, or empty while they have none — read
     * straight from the table, without loading the published plan, because the
     * espace has already loaded it and does not need the {@code affecte} flag.
     *
     * <p>A single-row query, not a filter over {@link #byAnimateur()}: this sits
     * on the espace's home payload, so every animateur opening their space
     * would otherwise read the whole edition's confirmations to learn about
     * their own.</p>
     */
    public Optional<ConfirmationPlanningRepository.Confirmation> stored(String animateurId) {
        return repository.byId(animateurId);
    }

    /** The whole edition's answers, one line per animateur, sorted by display name. */
    public List<ConfirmationView> byAnimateur() {
        Map<String, ConfirmationPlanningRepository.Confirmation> stockees = repository.byAnimateur();
        Collection<String> affectes = assignedAnimateurs();
        List<ConfirmationView> vues = new ArrayList<>();
        for (Animateur animateur : referenceDataService.listAnimateurs()) {
            ConfirmationPlanningRepository.Confirmation stored = stockees.get(animateur.getId());
            vues.add(new ConfirmationView(
                    animateur.getId(),
                    animateur.nomAffiche(),
                    (stored == null ? StatutConfirmation.NON_VU : stored.statut()).name(),
                    affectes.contains(animateur.getId()),
                    stored == null ? null : stored.confirmeLe(),
                    stored == null ? null : stored.relanceLe()));
        }
        vues.sort(Comparator.comparing(ConfirmationView::nomAffiche, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(vues);
    }

    /** The three counts of the head of the page, over the people the published plan gives a seat to. */
    public SyntheseConfirmations synthese() {
        PlanSnapshotService.SnapshotMeta publication = planPublieService.lastPublication();
        if (publication == null) {
            return new SyntheseConfirmations(0, 0, 0, null, true);
        }
        Map<String, ConfirmationPlanningRepository.Confirmation> stockees = repository.byAnimateur();
        int confirmes = 0;
        int relances = 0;
        int silencieux = 0;
        for (String animateurId : assignedAnimateurs()) {
            ConfirmationPlanningRepository.Confirmation stored = stockees.get(animateurId);
            StatutConfirmation statut = stored == null ? StatutConfirmation.NON_VU : stored.statut();
            switch (statut) {
                case CONFIRME -> confirmes++;
                case RELANCE -> relances++;
                case NON_VU -> silencieux++;
            }
        }
        return new SyntheseConfirmations(confirmes, relances, silencieux, publication.publieLe(), false);
    }

    /**
     * Ids still at NON_VU among the people the published plan actually gives
     * seats to — what the automatic reminder (issue #299) writes to.
     *
     * <p>Two exclusions, and both are the point: somebody with no seat has
     * nothing to confirm, and somebody already at RELANCE has had their
     * reminder — escalating after several of them is deliberately another
     * feature.</p>
     */
    public List<String> unconfirmed() {
        Map<String, ConfirmationPlanningRepository.Confirmation> stockees = repository.byAnimateur();
        List<String> attendus = new ArrayList<>();
        for (String animateurId : assignedAnimateurs()) {
            if (!stockees.containsKey(animateurId)) {
                attendus.add(animateurId);
            }
        }
        return List.copyOf(attendus);
    }

    /** Marks the reminder as sent, so the next run leaves that person alone. */
    public void recordReminder(String animateurId, Instant relanceLe) {
        repository.recordReminder(animateurId, relanceLe);
    }

    /**
     * Back to NON_VU for the people a republication actually moves. Called by
     * {@link PlanPublicationService} with the ids whose {@code changements} are
     * non-empty — never with the whole roster.
     */
    public void reset(Collection<String> animateurIds) {
        repository.reset(animateurIds);
    }

    /** Animateur ids holding at least one seat in the published plan. */
    private Collection<String> assignedAnimateurs() {
        return PublicationDiffService.vacationsByAnimateur(planPublieService.planPublie())
                .keySet();
    }
}
