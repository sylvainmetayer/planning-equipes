package dev.sylvain.planning.service.publication;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.service.notification.JournalNotificationsRepository;
import dev.sylvain.planning.service.publication.EnvoiPlanningRepository.CauseEchec;
import dev.sylvain.planning.service.publication.EnvoiPlanningRepository.Envoi;
import dev.sylvain.planning.service.publication.EnvoiPlanningRepository.NatureEnvoi;
import dev.sylvain.planning.service.publication.PlanPublicationService.DestinatairePublication;
import dev.sylvain.planning.service.publication.PublicationTraceRepository.StatutEnvoi;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanSnapshotService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * « Qui a reçu quelle version » — the permanent table of the Diffuser screen:
 * one line per animateur of the edition, whatever the next publication
 * concerns, saying which published version they were last told about, whether
 * that mail reached them, where the night's reminders stand and whether they
 * acknowledged it.
 *
 * <p>Nothing new is computed here: every column reads a store that already
 * answers one question — the marker of the plan each person was told about,
 * the delivery ledger, the scheduled notifications' ledger, the
 * acknowledgements, and the publication preview for who is still to be told.
 * This service only joins them per person, so the screen does not have to
 * make five calls and join them itself.</p>
 */
@ApplicationScoped
public class EtatEnvoisService {

    private final ReferenceDataService referenceDataService;

    private final PlanPublicationService publicationService;

    private final PlanSnapshotService snapshotService;

    private final NotifiedPlanRepository notifiedPlans;

    private final EnvoiPlanningRepository envois;

    private final JournalNotificationsRepository notifications;

    private final ConfirmationPlanningService confirmationService;

    @Inject
    public EtatEnvoisService(
            ReferenceDataService referenceDataService,
            PlanPublicationService publicationService,
            PlanSnapshotService snapshotService,
            NotifiedPlanRepository notifiedPlans,
            EnvoiPlanningRepository envois,
            JournalNotificationsRepository notifications,
            ConfirmationPlanningService confirmationService) {
        this.referenceDataService = referenceDataService;
        this.publicationService = publicationService;
        this.snapshotService = snapshotService;
        this.notifiedPlans = notifiedPlans;
        this.envois = envois;
        this.notifications = notifications;
        this.confirmationService = confirmationService;
    }

    /**
     * One published version: its snapshot, its rank among the edition's
     * publications (« v3 ») and when it left.
     */
    @Schema(requiredProperties = {"snapshotId", "numero"})
    public record VersionPubliee(long snapshotId, int numero, Instant publieLe) {}

    /**
     * One person.
     *
     * @param email          an address is on their fiche — the address itself
     *                       stays on the fiche
     * @param affecte        the published plan gives them a seat: only then is
     *                       an acknowledgement expected of them
     * @param version        the version they were last told about, {@code null}
     *                       when they were never told anything
     * @param envoi          the outcome of their latest planning mail, a
     *                       publication or a resend, {@code null} when none left
     * @param rappelVeilleLe the night's J-1 reminder, sent or not — see
     *                       {@code rappelVeilleEchec}
     * @param relanceLe      the latest reminder of the silent, by hand or by the night
     * @param confirmation   {@code NON_VU}, {@code CONFIRME} or {@code RELANCE}
     * @param aPrevenir      the next publication would write to them
     * @param differe        a publication deferred them and nothing has reached them since
     * @param joursAPrevenir the days their pending changes belong to
     */
    @Schema(
            requiredProperties = {
                "animateurId",
                "nomAffiche",
                "email",
                "affecte",
                "rappelVeilleEchec",
                "relanceEchec",
                "confirmation",
                "aPrevenir",
                "differe",
                "joursAPrevenir"
            })
    public record LigneEnvoi(
            String animateurId,
            String nomAffiche,
            boolean email,
            boolean affecte,
            VersionPubliee version,
            DernierEnvoi envoi,
            Instant rappelVeilleLe,
            boolean rappelVeilleEchec,
            Instant relanceLe,
            boolean relanceEchec,
            String confirmation,
            Instant confirmeLe,
            boolean aPrevenir,
            boolean differe,
            List<LocalDate> joursAPrevenir) {}

    /** The latest planning mail of one person: what it was, how it went, and when. */
    @Schema(requiredProperties = {"nature", "statut", "envoyeLe"})
    public record DernierEnvoi(
            NatureEnvoi nature, StatutEnvoi statut, CauseEchec cause, Instant envoyeLe, Integer numero) {}

    /**
     * The whole table.
     *
     * @param derniereVersion      the last publication, {@code null} before the first one
     * @param relancesAutomatiques the night's sends are armed on this edition —
     *                             off by default, which the screen says with the
     *                             way to turn them on
     * @param nombreConcernes      how many people the next publication would write to
     */
    @Schema(requiredProperties = {"relancesAutomatiques", "nombreConcernes", "personnes"})
    public record EtatEnvois(
            VersionPubliee derniereVersion,
            boolean relancesAutomatiques,
            int nombreConcernes,
            List<LigneEnvoi> personnes) {}

    /** Reads the table; sends nothing. */
    public EtatEnvois etat() {
        List<VersionPubliee> versions = versions();
        Map<Long, VersionPubliee> parSnapshot = new HashMap<>();
        versions.forEach(version -> parSnapshot.put(version.snapshotId(), version));

        PlanPublicationService.ApercuPublication apercu = publicationService.apercu();
        Map<String, DestinatairePublication> aPrevenir = new HashMap<>();
        for (DestinatairePublication destinataire : apercu.destinataires()) {
            aPrevenir.put(destinataire.animateurId(), destinataire);
        }
        Map<String, Long> marqueurs = notifiedPlans.byAnimateur();
        Map<String, Envoi> derniers = envois.latestByAnimateur();
        Map<String, Instant> rappels = notifications.lastByAnimateur(JournalNotificationsRepository.Type.RAPPEL_VEILLE);
        Map<String, Instant> rappelsEchoues =
                notifications.lastByAnimateur(JournalNotificationsRepository.Type.RAPPEL_VEILLE_INJOIGNABLE);
        Map<String, Instant> relancesEchouees =
                notifications.lastByAnimateur(JournalNotificationsRepository.Type.RELANCE_INJOIGNABLE);
        Map<String, ConfirmationPlanningService.ConfirmationView> confirmations = new HashMap<>();
        for (ConfirmationPlanningService.ConfirmationView vue : confirmationService.byAnimateur()) {
            confirmations.put(vue.animateurId(), vue);
        }

        List<LigneEnvoi> lignes = new ArrayList<>();
        for (Animateur animateur : referenceDataService.listAnimateurs()) {
            String id = animateur.getId();
            DestinatairePublication destinataire = aPrevenir.get(id);
            ConfirmationPlanningService.ConfirmationView confirmation = confirmations.get(id);
            Envoi envoi = derniers.get(id);
            Instant rappel = latest(rappels.get(id), rappelsEchoues.get(id));
            Instant relanceEchouee = relancesEchouees.get(id);
            Instant relance = latest(confirmation == null ? null : confirmation.relanceLe(), relanceEchouee);
            Long marqueur = marqueurs.get(id);
            lignes.add(new LigneEnvoi(
                    id,
                    animateur.nomAffiche(),
                    animateur.getEmail() != null && !animateur.getEmail().isBlank(),
                    confirmation != null && confirmation.affecte(),
                    marqueur == null ? null : parSnapshot.get(marqueur),
                    envoi == null ? null : dernierEnvoi(envoi, parSnapshot),
                    rappel,
                    rappel != null && rappel.equals(rappelsEchoues.get(id)),
                    relance,
                    relance != null && relance.equals(relanceEchouee),
                    confirmation == null ? "NON_VU" : confirmation.statut(),
                    confirmation == null ? null : confirmation.confirmeLe(),
                    destinataire != null,
                    destinataire != null && destinataire.reporte(),
                    destinataire == null ? List.of() : destinataire.jours()));
        }
        lignes.sort(Comparator.comparing(LigneEnvoi::nomAffiche, String.CASE_INSENSITIVE_ORDER));
        return new EtatEnvois(
                versions.isEmpty() ? null : versions.getLast(),
                referenceDataService.getParametresNotifications().actives(),
                apercu.nombreConcernes(),
                List.copyOf(lignes));
    }

    /** The edition's publications, oldest first, numbered from 1. */
    private List<VersionPubliee> versions() {
        List<PlanSnapshotService.SnapshotMeta> publiees = snapshotService.list().stream()
                .filter(meta -> meta.publieLe() != null)
                .sorted(Comparator.comparing(PlanSnapshotService.SnapshotMeta::publieLe)
                        .thenComparingLong(PlanSnapshotService.SnapshotMeta::id))
                .toList();
        List<VersionPubliee> versions = new ArrayList<>();
        for (int rang = 0; rang < publiees.size(); rang++) {
            PlanSnapshotService.SnapshotMeta meta = publiees.get(rang);
            versions.add(new VersionPubliee(meta.id(), rang + 1, meta.publieLe()));
        }
        return versions;
    }

    private static DernierEnvoi dernierEnvoi(Envoi envoi, Map<Long, VersionPubliee> parSnapshot) {
        VersionPubliee version = envoi.snapshotId() == null ? null : parSnapshot.get(envoi.snapshotId());
        return new DernierEnvoi(
                envoi.nature(),
                envoi.statut(),
                envoi.cause(),
                envoi.envoyeLe(),
                version == null ? null : version.numero());
    }

    private static Instant latest(Instant gauche, Instant droite) {
        if (gauche == null) {
            return droite;
        }
        if (droite == null) {
            return gauche;
        }
        return gauche.isAfter(droite) ? gauche : droite;
    }
}
