package dev.sylvain.planning.mcp;

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
 * voient-ils autre chose que ça ? » — {@code etat_planning} dates the last
 * solve, nothing dated the last publication.</p>
 *
 * <h2>Three tools here send mail</h2>
 *
 * <p>{@code publier_planning}, {@code envoyer_planning_animateur} and
 * {@code relancer_animateurs} are the only ones in this package that reach
 * outside the application, and they say so: {@code openWorldHint = true},
 * where every other tool declares false. A client that gates the tools which
 * leave the building has something to gate on.</p>
 *
 * <h2>What comes back</h2>
 *
 * <p>The REST views are built for a screen that may name people: recipients
 * carry {@code nomAffiche} and {@code email}, and the report of a send lists
 * the people it missed <b>by name</b>. Here a recipient is an id, an address
 * is a boolean, and a missed send is a count — the trace read back by
 * {@code lister_destinataires_publication} says who, by id, which is what the
 * other tools take as input anyway.</p>
 */
@EditionCiblee
@Journalise
@ApplicationScoped
public class PublicationMcpTools {

    @Inject
    PlanPublicationService publicationService;

    @Inject
    PlanPublieService planPublieService;

    @Inject
    PublicationTraceRepository traceRepository;

    @Inject
    PlanningDeliveryService deliveryService;

    @Inject
    RelanceManuelleService relanceService;

    @Inject
    ConfirmationPlanningService confirmationService;

    @Tool(
            description = "État de la publication du planning : quand la dernière publication est partie, si "
                    + "quelque chose a déjà été publié, et qui serait concerné par la prochaine — avec, pour chaque "
                    + "animateur, ce que son courriel lui annoncerait. N'envoie rien. C'est ici que se lit la date de "
                    + "dernière publication, là où etat_planning donne celle de la dernière résolution.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    EtatPublicationView etat_publication(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        ApercuPublication apercu = publicationService.apercu();
        return new EtatPublicationView(
                apercu.jamaisPublie(),
                apercu.planVide(),
                apercu.solveEnCours(),
                apercu.dernierePublicationLe(),
                apercu.nombreConcernes(),
                apercu.destinataires().stream().map(PublicationMcpTools::toView).toList());
    }

    @Tool(
            description = "Publie le planning persisté : il est capturé comme instantané publié, puis chaque "
                    + "animateur concerné reçoit son planning par courriel. ENVOIE DES COURRIELS. Refusé si une "
                    + "résolution est en cours, s'il n'y a rien de résolu à publier, ou si personne n'est concerné — "
                    + "consulter etat_publication d'abord. Les personnes sans adresse et les échecs d'envoi sont "
                    + "comptés ici et détaillés par id par lister_destinataires_publication.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = true))
    RapportPublicationView publier_planning(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        RapportPublication rapport = publicationService.publier();
        return new RapportPublicationView(
                rapport.snapshotId(),
                rapport.publieLe(),
                rapport.envoyes(),
                rapport.sansEmail().size(),
                rapport.echecs().size());
    }

    @Tool(
            description = "Trace d'une publication : qui a été prévenu, avec quel statut d'envoi et de quoi il a "
                    + "été informé. Par défaut la dernière publication ; une liste vide veut dire que rien n'a jamais "
                    + "été publié. Les animateurs y sont désignés par id seul.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    List<DestinataireView> lister_destinataires_publication(
            @ToolArg(description = "Id de l'instantané publié ; omis, la dernière publication", required = false)
                    Long snapshotId,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        if (snapshotId != null) {
            return toViews(traceRepository.bySnapshot(snapshotId));
        }
        PlanSnapshotService.SnapshotMeta derniere = planPublieService.lastPublication();
        return derniere == null ? List.of() : toViews(traceRepository.bySnapshot(derniere.id()));
    }

    @Tool(
            description = "Renvoie à un animateur son planning tel qu'il a été publié — pas le planning de "
                    + "travail en cours. ENVOIE UN COURRIEL. Échoue si rien n'a jamais été publié, si l'id est inconnu "
                    + "ou si la fiche ne porte pas d'adresse.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = true))
    EnvoiView envoyer_planning_animateur(
            @ToolArg(description = "Id de l'animateur") String animateurId,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        PlanningDeliveryService.DeliveryReport compteRendu;
        try {
            compteRendu = deliveryService.sendToOneAnimateur(animateurId);
        } catch (BusinessError.Invalid e) {
            // The service words one of its two refusals for a screen that
            // already shows the fiche: « Prénom Nom n'a pas d'adresse e-mail ».
            // That sentence carries no id, so nothing could anonymise it after
            // the fact — it is replaced, not rewritten. The privacy rule holds
            // on the failure path too.
            throw new BusinessError.Invalid(
                    planPublieService.jamaisPublie()
                            ? "Le planning n'a pas encore été publié : il n'y a rien à renvoyer."
                            : "L'animateur " + animateurId + " n'a pas d'adresse e-mail sur sa fiche.");
        }
        // echecs() carries the address it could not reach; the id is what the
        // caller can act on, and the log holds the rest.
        return new EnvoiView(
                compteRendu.envoyes() == 1,
                compteRendu.echecs().isEmpty() ? null : "L'envoi à l'animateur " + animateurId + " a échoué");
    }

    @Tool(
            description = "Relance maintenant les animateurs désignés qui n'ont pas accusé réception de leur "
                    + "planning publié, sans attendre la relance automatique de nuit. ENVOIE UN COURRIEL à chacun "
                    + "d'eux. Même message que la nuit, même règle : personne ne reçoit deux fois la relance d'une "
                    + "même publication, par la nuit ou à la main — les personnes déjà relancées, déjà confirmées, "
                    + "sans adresse ou sans poste sont rendues par id dans le compte rendu au lieu d'être écrites. "
                    + "Refusé si rien n'a jamais été publié ou si un id est inconnu. Consulter synthese_confirmations "
                    + "ou lister_animateurs d'abord.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = true))
    RapportRelanceView relancer_animateurs(
            @ToolArg(description = "Ids des animateurs à relancer") List<String> animateurIds,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        RapportRelance rapport = relanceService.relancer(animateurIds);
        return new RapportRelanceView(
                rapport.envoyes(),
                rapport.dejaConfirmes(),
                rapport.sansEmail(),
                rapport.dejaRelancesPourCettePublication(),
                rapport.echecs(),
                rapport.sansPoste());
    }

    @Tool(
            description = "Accusés de réception du planning publié en trois nombres — confirmés, relancés, "
                    + "silencieux — parmi les animateurs qui ont un poste sur ce planning, avec la date de la "
                    + "dernière publication. N'envoie rien. jamaisPublie vrai veut dire que la question n'a encore "
                    + "été posée à personne.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    SyntheseConfirmationsView synthese_confirmations(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        SyntheseConfirmations synthese = confirmationService.synthese();
        return new SyntheseConfirmationsView(
                synthese.confirmes(),
                synthese.relances(),
                synthese.silencieux(),
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
                        destinataire.changements()))
                .toList();
    }

    static DestinatairePublicationView toView(DestinatairePublication destinataire) {
        return new DestinatairePublicationView(
                destinataire.animateurId(),
                destinataire.email() != null && !destinataire.email().isBlank(),
                destinataire.premiereDiffusion(),
                destinataire.changements(),
                destinataire.demandes());
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
            List<String> demandes) {}

    /**
     * One line of a publication's trace.
     *
     * @param statut     ENVOYE, SANS_EMAIL ou ECHEC — the counts of
     *                   {@code publier_planning}, named by id
     * @param changements what that person was told, exactly as their mail
     *                    worded it
     */
    public record DestinataireView(
            long snapshotId, String animateurId, String statut, Instant envoyeLe, List<String> changements) {}

    /**
     * @param sansAdresse how many concerned people have no address on their
     *                    fiche; {@code lister_destinataires_publication} names
     *                    them by id
     */
    public record RapportPublicationView(long snapshotId, Instant publieLe, int envoyes, int sansAdresse, int echecs) {}

    public record EnvoiView(boolean envoye, String echec) {}

    /**
     * Who a manual reminder reached, by id, and who it left alone and why —
     * the REST report already carries ids only, so nothing is withheld here.
     *
     * @param dejaRelancesPourCettePublication refused by the one-reminder
     *                                         rule: the night or an earlier
     *                                         hand already wrote to them about
     *                                         this publication
     */
    public record RapportRelanceView(
            List<String> envoyes,
            List<String> dejaConfirmes,
            List<String> sansEmail,
            List<String> dejaRelancesPourCettePublication,
            List<String> echecs,
            List<String> sansPoste) {}

    /**
     * @param dernierePublicationLe the publication the answers are about;
     *                              {@code null} when nothing was ever published
     */
    public record SyntheseConfirmationsView(
            int confirmes, int relances, int silencieux, Instant dernierePublicationLe, boolean jamaisPublie) {}
}
