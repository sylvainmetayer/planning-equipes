package dev.sylvain.planning.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.publication.ConfirmationPlanningService;
import dev.sylvain.planning.service.publication.ConfirmationPlanningService.SyntheseConfirmations;
import dev.sylvain.planning.service.publication.PlanPublicationService;
import dev.sylvain.planning.service.publication.PlanPublicationService.ApercuPublication;
import dev.sylvain.planning.service.publication.PlanPublicationService.DestinatairePublication;
import dev.sylvain.planning.service.publication.PlanPublicationService.RapportPublication;
import dev.sylvain.planning.service.publication.PlanPublieService;
import dev.sylvain.planning.service.publication.PlanningDeliveryService;
import dev.sylvain.planning.service.publication.PublicationTraceRepository;
import dev.sylvain.planning.service.publication.PublicationTraceRepository.Destinataire;
import dev.sylvain.planning.service.publication.RelanceManuelleService;
import dev.sylvain.planning.service.publication.RelanceManuelleService.RapportRelance;
import dev.sylvain.planning.service.solve.PlanSnapshotService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;

/**
 * MCP tools over the publication of the planning ({@code PublicationResource},
 * {@code EnvoiPlanningResource}): the gesture that turns the plan an operator
 * has been reworking into the plan the animateurs read.
 *
 * <p>Without them an assistant could solve, repair and snapshot a planning
 * while being structurally unable to answer « depuis quand les animateurs
 * voient-ils autre chose que ça ? » — {@code consulter_planning} dates the last
 * solve, nothing dated the last publication.</p>
 *
 * <h2>One tool here sends mail</h2>
 *
 * <p>{@code publier_planning} (publish, resend one planning, remind; ADR
 * 0127), with its 3.x names {@code envoyer_planning_animateur} and
 * {@code relancer_animateurs} kept until 5.0, is what reaches outside the
 * application here, and it says so: {@code openWorldHint = true},
 * where every other tool declares false. A client that gates the tools which
 * leave the building has something to gate on.</p>
 *
 * <h2>What comes back</h2>
 *
 * <p>The REST views are built for a screen that may name people: recipients
 * carry {@code nomAffiche} and {@code email}, and the report of a send lists
 * the people it missed <b>by name</b>. Here a recipient is an id, an address
 * is a boolean, and a missed send is a count — the trace read back by
 * {@code consulter_publication} (vue destinataires) says who, by id, which is what the
 * other tools take as input anyway.</p>
 */
@EditionCiblee
@RefusMetier
@Journalise
@ApplicationScoped
public class PublicationMcpTools {

    private static final String ETAT = "etat";

    private static final String DESTINATAIRES = "destinataires";

    private final PlanPublicationService publicationService;

    private final PlanPublieService planPublieService;

    private final PublicationTraceRepository traceRepository;

    private final PlanningDeliveryService deliveryService;

    private final RelanceManuelleService relanceService;

    private final ConfirmationPlanningService confirmationService;

    @Inject
    PublicationMcpTools(
            PlanPublicationService publicationService,
            PlanPublieService planPublieService,
            PublicationTraceRepository traceRepository,
            PlanningDeliveryService deliveryService,
            RelanceManuelleService relanceService,
            ConfirmationPlanningService confirmationService) {
        this.publicationService = publicationService;
        this.planPublieService = planPublieService;
        this.traceRepository = traceRepository;
        this.deliveryService = deliveryService;
        this.relanceService = relanceService;
        this.confirmationService = confirmationService;
    }

    @Tool(
            name = "consulter_publication",
            description = "Lit la publication du planning, sous la vue demandée. N'envoie rien. etat (défaut) : "
                    + "quand la dernière publication est partie, si quelque chose a déjà été publié, et qui serait "
                    + "concerné par la prochaine, avec pour chaque animateur ce que son courriel lui annoncerait ; "
                    + "c'est ici que se lit la date de dernière publication, là où consulter_planning donne celle de "
                    + "la dernière résolution. destinataires : la trace d'une publication (la dernière, ou celle de "
                    + "snapshotId), qui a été prévenu, avec quel statut d'envoi et de quoi ; une liste vide veut dire "
                    + "que rien n'a jamais été publié. confirmations : les accusés de réception du planning publié "
                    + "en quatre nombres (confirmés, relancés, silencieux, échecs d'envoi) parmi les animateurs qui "
                    + "ont un poste, avec la date de la dernière publication ; un échec d'envoi est une personne qui "
                    + "n'a pas confirmé et dont le dernier courriel n'est pas parti (fiche non modifiée depuis). Les "
                    + "animateurs y sont désignés par id seul, aucune adresse n'est rendue.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    PublicationConsultationView consultPublication(
            @ToolArg(description = "etat (défaut), destinataires ou confirmations", required = false) String vue,
            @ToolArg(
                            description = "Vue destinataires : id de l'instantané publié ; omis, la dernière "
                                    + "publication",
                            required = false)
                    Long snapshotId,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        String choisie = McpArgs.choix(vue, "vue", ETAT, ETAT, DESTINATAIRES, "confirmations");
        // Refused rather than ignored: a state read as the one of that publication would mislead.
        if (snapshotId != null && !DESTINATAIRES.equals(choisie)) {
            throw new BusinessError.Invalid("snapshotId ne vaut que pour la vue destinataires");
        }
        return switch (choisie) {
            case ETAT -> new PublicationConsultationView(choisie, publicationState(edition), null, null);
            case DESTINATAIRES ->
                new PublicationConsultationView(choisie, null, listPublicationRecipients(snapshotId, edition), null);
            default -> new PublicationConsultationView(choisie, null, null, summarizeConfirmations(edition));
        };
    }

    @Tool(
            name = "publier_planning",
            description = "Publie le planning persisté : il est capturé comme instantané publié, puis chaque "
                    + "animateur concerné reçoit son planning par courriel. ENVOIE DES COURRIELS à toute la liste. "
                    + "Refusé hors de l'édition active (voir lister_editions), si une résolution est en cours, "
                    + "s'il n'y a rien de résolu à publier, ou si personne n'est concerné — consulter_publication "
                    + "d'abord. Les personnes sans adresse et les échecs d'envoi sont comptés ici et détaillés par "
                    + "id par consulter_publication (vue destinataires). Tout le monde de la liste est prévenu : on "
                    + "ne peut plus en exclure. Pour écrire à une seule personne, envoyer_planning_animateur ; pour "
                    + "relancer les silencieux, envoyer_relance_animateurs.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = true))
    RapportPublicationView publishPlanning(@ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        RapportPublication rapport = publicationService.publier();
        return new RapportPublicationView(
                rapport.snapshotId(),
                rapport.publieLe(),
                rapport.envoyes(),
                rapport.sansEmail().size(),
                rapport.echecs().size(),
                rapport.differes().size());
    }

    @Deprecated
    @Tool(
            name = "etat_publication",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser consulter_publication (vue etat). "
                    + "État de la publication du planning : quand la dernière publication est partie, si "
                    + "quelque chose a déjà été publié, et qui serait concerné par la prochaine — avec, pour chaque "
                    + "animateur, ce que son courriel lui annoncerait. N'envoie rien. C'est ici que se lit la date de "
                    + "dernière publication, là où consulter_planning donne celle de la dernière résolution.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    EtatPublicationView publicationState(@ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        ApercuPublication apercu = publicationService.apercu();
        return new EtatPublicationView(
                apercu.jamaisPublie(),
                apercu.planVide(),
                apercu.solveEnCours(),
                apercu.dernierePublicationLe(),
                apercu.nombreConcernes(),
                apercu.destinataires().stream().map(PublicationMcpTools::toView).toList());
    }

    @Deprecated
    @Tool(
            name = "lister_destinataires_publication",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser consulter_publication (vue destinataires). "
                    + "Trace d'une publication : qui a été prévenu, avec quel statut d'envoi et de quoi il a "
                    + "été informé. Par défaut la dernière publication ; une liste vide veut dire que rien n'a jamais "
                    + "été publié. Les animateurs y sont désignés par id seul.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    List<DestinataireView> listPublicationRecipients(
            @ToolArg(description = "Id de l'instantané publié ; omis, la dernière publication", required = false)
                    Long snapshotId,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        if (snapshotId != null) {
            return toViews(traceRepository.bySnapshot(snapshotId));
        }
        PlanSnapshotService.SnapshotMeta derniere = planPublieService.lastPublication();
        return derniere == null ? List.of() : toViews(traceRepository.bySnapshot(derniere.id()));
    }

    @Tool(
            name = "envoyer_planning_animateur",
            description = "Renvoie à UN animateur son planning tel qu'il a été publié — pas le planning de "
                    + "travail en cours : après une réaffectation, seul publier_planning envoie le nouveau. ENVOIE UN "
                    + "COURRIEL. Refusé hors de l'édition active. Échoue si rien n'a jamais été publié, si l'id est inconnu "
                    + "ou si la fiche ne porte pas d'adresse.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = true))
    EnvoiView sendAnimateurPlanning(
            @ToolArg(description = "Id de l'animateur") String animateurId,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        // No try/catch around the refusals any more: the service words them by
        // id, and @RefusMetier carries them to the caller for every tool of
        // the package (issue #529). This one used to rewrite « Prénom Nom n'a
        // pas d'adresse » by hand, which is exactly the per-tool workaround a
        // transverse treatment makes unnecessary.
        PlanningDeliveryService.DeliveryReport compteRendu = deliveryService.sendToOneAnimateur(animateurId);
        // echecs() carries the address it could not reach; the id is what the
        // caller can act on, and the log holds the rest.
        return new EnvoiView(
                compteRendu.envoyes() == 1,
                compteRendu.echecs().isEmpty() ? null : "L'envoi à l'animateur " + animateurId + " a échoué");
    }

    @Tool(
            name = "envoyer_relance_animateurs",
            description = "Relance maintenant les animateurs désignés qui n'ont pas accusé réception de leur "
                    + "planning publié, sans attendre la relance automatique de nuit, qui ne part que de l'édition "
                    + "active — tout comme celle-ci, refusée sur une autre édition. ENVOIE UN COURRIEL à chacun "
                    + "d'eux. Même message que la nuit, même règle : personne ne reçoit deux fois la relance d'une "
                    + "même publication, par la nuit ou à la main — les personnes déjà relancées, déjà confirmées, "
                    + "sans adresse ou sans poste sont rendues par id dans le compte rendu au lieu d'être écrites, "
                    + "comme celles dont l'adresse a été refusée par le serveur de messagerie au dernier envoi tant "
                    + "que leur fiche n'a pas été modifiée depuis (adresseRefusee). "
                    + "Refusé si rien n'a jamais été publié ou si un id est inconnu. Voir consulter_publication (vue "
                    + "confirmations) ou lister_animateurs d'abord.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = true))
    RapportRelanceView sendReminders(
            @ToolArg(description = "Ids des animateurs à relancer") List<String> animateurIds,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return remind(animateurIds);
    }

    /** Shared by the tool and its former name: never another @Tool, whose interceptor would journal twice. */
    private RapportRelanceView remind(List<String> animateurIds) {
        RapportRelance rapport = relanceService.relancer(animateurIds);
        return new RapportRelanceView(
                rapport.envoyes(),
                rapport.dejaConfirmes(),
                rapport.sansEmail(),
                rapport.dejaRelancesPourCettePublication(),
                rapport.echecs(),
                rapport.sansPoste(),
                rapport.adresseRefusee());
    }

    @Deprecated
    @Tool(
            name = "relancer_animateurs",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser envoyer_relance_animateurs. "
                    + "Relance maintenant les animateurs désignés qui n'ont pas accusé réception de leur "
                    + "planning publié, sans attendre la relance automatique de nuit, qui ne part que de l'édition "
                    + "active — tout comme celle-ci, refusée sur une autre édition. ENVOIE UN COURRIEL à chacun "
                    + "d'eux. Même message que la nuit, même règle : personne ne reçoit deux fois la relance d'une "
                    + "même publication, par la nuit ou à la main — les personnes déjà relancées, déjà confirmées, "
                    + "sans adresse ou sans poste sont rendues par id dans le compte rendu au lieu d'être écrites, "
                    + "comme celles dont l'adresse a été refusée par le serveur de messagerie au dernier envoi tant "
                    + "que leur fiche n'a pas été modifiée depuis (adresseRefusee). "
                    + "Refusé si rien n'a jamais été publié ou si un id est inconnu. Voir consulter_publication (vue "
                    + "confirmations) ou lister_animateurs d'abord.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = true))
    RapportRelanceView remindAnimateurs(
            @ToolArg(description = "Ids des animateurs à relancer") List<String> animateurIds,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return remind(animateurIds);
    }

    @Deprecated
    @Tool(
            name = "synthese_confirmations",
            description = "Ancien nom, gardé jusqu'en 5.0 : utiliser consulter_publication (vue confirmations). "
                    + "Accusés de réception du planning publié en quatre nombres — confirmés, relancés, "
                    + "silencieux, échecs d'envoi — parmi les animateurs qui ont un poste sur ce planning, avec la "
                    + "date de la dernière publication. Un échec d'envoi est une personne qui n'a pas confirmé et "
                    + "dont le dernier courriel n'est pas parti (fiche non modifiée depuis) : elle n'est pas "
                    + "silencieuse, elle n'a pas été jointe. N'envoie rien et ne donne aucune adresse. jamaisPublie "
                    + "vrai veut dire que la question n'a encore été posée à personne.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    SyntheseConfirmationsView summarizeConfirmations(
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        SyntheseConfirmations synthese = confirmationService.synthese();
        return new SyntheseConfirmationsView(
                synthese.confirmes(),
                synthese.relances(),
                synthese.silencieux(),
                synthese.echecsEnvoi(),
                synthese.dernierePublicationLe(),
                synthese.jamaisPublie());
    }

    /* -------------------------------- Views -------------------------------- */

    static List<DestinataireView> toViews(List<Destinataire> trace) {
        return trace.stream()
                .map(destinataire -> new DestinataireView(
                        destinataire.snapshotId(),
                        destinataire.animateurId(),
                        destinataire.statut().name(),
                        destinataire.envoyeLe(),
                        // Both halves, in the order the mail read them: this
                        // view answers « de quoi a-t-il été informé », where
                        // the trace keeps them apart because they age
                        // differently (see Destinataire).
                        destinataire.lignes()))
                .toList();
    }

    static DestinatairePublicationView toView(DestinatairePublication destinataire) {
        return new DestinatairePublicationView(
                destinataire.animateurId(),
                destinataire.email() != null && !destinataire.email().isBlank(),
                destinataire.premiereDiffusion(),
                destinataire.changements(),
                destinataire.demandes(),
                destinataire.mineur(),
                destinataire.reporte());
    }

    /**
     * @param dernierePublicationLe when the last publication left; {@code null}
     *                              when nothing was ever published
     * @param nombreConcernes       how many people the next publication would
     *                              write to — zero meaning the published plan
     *                              is already up to date
     */
    public record EtatPublicationView(
            boolean jamaisPublie,
            boolean planVide,
            boolean solveEnCours,
            Instant dernierePublicationLe,
            int nombreConcernes,
            List<DestinatairePublicationView> destinataires) {}

    /**
     * @param adresseConnue whether the fiche carries an address at all — the
     *                      address itself never leaves over MCP
     */
    public record DestinatairePublicationView(
            String animateurId,
            boolean adresseConnue,
            boolean premiereDiffusion,
            List<String> changements,
            List<String> demandes,
            boolean mineur,
            boolean reporte) {}

    /**
     * One line of a publication's trace.
     *
     * @param statut     ENVOYE, SANS_EMAIL, ECHEC ou EXCLU — the counts of
     *                   {@code publier_planning}, named by id
     * @param changements what that person was told, exactly as their mail
     *                    worded it
     */
    public record DestinataireView(
            long snapshotId, String animateurId, String statut, Instant envoyeLe, List<String> changements) {}

    /** What {@code consulter_publication} answers: the view asked, the other two left out. */
    public record PublicationConsultationView(
            String vue,
            @JsonInclude(JsonInclude.Include.NON_NULL) EtatPublicationView etat,
            @JsonInclude(JsonInclude.Include.NON_NULL) List<DestinataireView> destinataires,
            @JsonInclude(JsonInclude.Include.NON_NULL) SyntheseConfirmationsView confirmations) {}

    /**
     * @param sansAdresse how many concerned people have no address on their
     *                    fiche; {@code consulter_publication} (vue destinataires)
     *                    names them by id
     * @param differes    how many were deliberately left out of this send;
     *                    the same view names them by id, with the statut EXCLU
     */
    public record RapportPublicationView(
            long snapshotId, Instant publieLe, int envoyes, int sansAdresse, int echecs, int differes) {}

    public record EnvoiView(boolean envoye, String echec) {}

    /**
     * Who a manual reminder reached, by id, and who it left alone and why —
     * the REST report already carries ids only, so nothing is withheld here.
     *
     * @param dejaRelancesPourCettePublication refused by the one-reminder
     *                                         rule: the night or an earlier
     *                                         hand already wrote to them about
     *                                         this publication
     * @param adresseRefusee                   not attempted: the relay refused
     *                                         their address at the last send,
     *                                         and the fiche has not been edited
     *                                         since
     */
    public record RapportRelanceView(
            List<String> envoyes,
            List<String> dejaConfirmes,
            List<String> sansEmail,
            List<String> dejaRelancesPourCettePublication,
            List<String> echecs,
            List<String> sansPoste,
            List<String> adresseRefusee) {}

    /**
     * @param dernierePublicationLe the publication the answers are about;
     *                              {@code null} when nothing was ever published
     * @param echecsEnvoi           not confirmed, and their last mail failed —
     *                              counted in neither relances nor silencieux
     */
    public record SyntheseConfirmationsView(
            int confirmes,
            int relances,
            int silencieux,
            int echecsEnvoi,
            Instant dernierePublicationLe,
            boolean jamaisPublie) {}
}
