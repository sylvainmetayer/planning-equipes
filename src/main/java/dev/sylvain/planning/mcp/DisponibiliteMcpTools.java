package dev.sylvain.planning.mcp;

import dev.sylvain.planning.domain.DeclarationDisponibilite;
import dev.sylvain.planning.domain.StatutDeclaration;
import dev.sylvain.planning.service.espace.DeclarationDisponibiliteRepository.FenetreCollecte;
import dev.sylvain.planning.service.espace.DeclarationDisponibiliteService;
import dev.sylvain.planning.service.espace.DeclarationDisponibiliteService.ConfigurationAppliquee;
import dev.sylvain.planning.service.espace.DeclarationDisponibiliteService.DeclarationAppliquee;
import dev.sylvain.planning.service.espace.EspaceAnimateurService;
import dev.sylvain.planning.service.espace.EspaceAnimateurService.DeclarationAdminView;
import dev.sylvain.planning.service.espace.TeammateRequestService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * MCP tools over the collection of availabilities
 * ({@code DeclarationDisponibiliteResource}): the window during which
 * animateurs declare their own unavailable days and wishes from their espace,
 * and the admin decisions that either write a declaration onto the fiche or
 * refuse it.
 *
 * <p>This is where an assistant preparing an edition finds out that the
 * référentiel it is about to solve is still waiting on ten declarations
 * nobody has decided — a solve run before them solves the wrong problem.</p>
 *
 * <p>A declaration names its author, so it is reported here by id only, and
 * the invitation report is counted rather than listed: it names the people it
 * could not reach.</p>
 */
@EditionCiblee
@RefusMetier
@Journalise
@ApplicationScoped
public class DisponibiliteMcpTools {

    private final DeclarationDisponibiliteService declarationService;

    private final EspaceAnimateurService espaceAnimateurService;

    private final TeammateRequestService teammateRequestService;

    @Inject
    DisponibiliteMcpTools(
            DeclarationDisponibiliteService declarationService,
            EspaceAnimateurService espaceAnimateurService,
            TeammateRequestService teammateRequestService) {
        this.declarationService = declarationService;
        this.espaceAnimateurService = espaceAnimateurService;
        this.teammateRequestService = teammateRequestService;
    }

    @Tool(
            name = "lister_demandes_covoiturage",
            description = "Liste les demandes de covoiturage (« Je viens avec… », un à trois coéquipiers) "
                    + "envoyées depuis l'onglet Covoiturage de l'espace animateur pendant la collecte, du plus "
                    + "récent au plus ancien : les membres, le statut (EN_ATTENTE, VALIDEE, ECARTEE, ANNULEE), si chacun a "
                    + "nommé les autres (confirmedByAll), les jours où leurs indisponibilités déclarées divergent, "
                    + "et l'ajustement ARRIVEE_GROUPEE créé par la validation. Une demande est distincte de la "
                    + "déclaration de disponibilités : appliquer ou refuser une déclaration ne la touche pas, et "
                    + "seule sa validation, depuis l'onglet Covoiturage de l'écran Disponibilités, crée "
                    + "l'ajustement ; l'annulation d'une arrivée groupée validée se fait aussi depuis cet onglet, "
                    + "qui prévient le groupe. Les animateurs y sont désignés par id seul ; le motif d'un écart ou "
                    + "d'une annulation, texte libre, n'est pas rendu.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    List<CarpoolRequestMcpView> listCarpoolRequests(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return teammateRequestService.list().stream()
                .map(demande -> new CarpoolRequestMcpView(
                        demande.id(),
                        demande.animateurId(),
                        demande.members().stream()
                                .map(TeammateRequestService.MemberView::animateurId)
                                .toList(),
                        demande.status(),
                        demande.confirmedByAll(),
                        demande.divergentDays(),
                        demande.contrainteId(),
                        demande.createdAt(),
                        demande.decidedAt()))
                .toList();
    }

    /** A covoiturage demand in ids: who declared it, who rides, and where it stands. */
    public record CarpoolRequestMcpView(
            String id,
            String animateurId,
            List<String> memberIds,
            String status,
            boolean confirmedByAll,
            List<LocalDate> divergentDays,
            String contrainteId,
            Instant createdAt,
            Instant decidedAt) {}

    @Tool(
            name = "lister_declarations_disponibilite",
            description = "Liste les déclarations de disponibilité envoyées par les animateurs depuis leur "
                    + "espace, de la plus récente à la plus ancienne. Chaque déclaration porte ce qui a été déclaré et, "
                    + "en regard, ce que la fiche dit aujourd'hui. Filtrable par statut : EN_ATTENTE, APPLIQUEE, "
                    + "REFUSEE. Les animateurs y sont désignés par id seul.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    List<DeclarationMcpView> listAvailabilityDeclarations(
            @ToolArg(description = "Statut pour filtrer : EN_ATTENTE, APPLIQUEE ou REFUSEE", required = false)
                    String statut,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        StatutDeclaration filtre =
                statut == null ? null : McpArgs.enumeration(StatutDeclaration.class, statut, "statut");
        return espaceAnimateurService.toDeclarationViews(declarationService.list()).stream()
                .filter(declaration -> filtre == null || filtre.name().equals(declaration.statut()))
                .map(DisponibiliteMcpTools::toView)
                .toList();
    }

    @Tool(
            name = "consulter_collecte_disponibilites",
            description = "Consulte la fenêtre de collecte des disponibilités : ouverte ou non, et ses dates si "
                    + "elle en porte. Fermée tant que personne ne l'a ouverte — un formulaire ouvert sur Internet ne "
                    + "s'ouvre pas par omission.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    CollecteView getAvailabilityCollection(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return toView(declarationService.fenetre(), null);
    }

    @Tool(
            name = "configurer_collecte_disponibilites",
            description = "Ouvre ou ferme la collecte des disponibilités, et la borne éventuellement par des "
                    + "dates. prevenirAnimateurs ENVOIE UN COURRIEL à chaque animateur avec le lien de son espace : "
                    + "c'est une décision par ouverture, pas un réglage, et personne n'est invité à une collecte qu'on "
                    + "ferme. L'invitation est comptée, jamais nominative.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = true))
    CollecteView configureAvailabilityCollection(
            @ToolArg(description = "Collecte ouverte ou fermée") boolean ouverte,
            @ToolArg(description = "Début de la collecte (AAAA-MM-JJ)", required = false) String debut,
            @ToolArg(description = "Fin de la collecte (AAAA-MM-JJ)", required = false) String fin,
            @ToolArg(description = "Envoyer maintenant l'invitation à déclarer (courriels)", required = false)
                    Boolean prevenirAnimateurs,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        ConfigurationAppliquee appliquee = declarationService.configure(
                new FenetreCollecte(ouverte, McpArgs.date(debut, "debut"), McpArgs.date(fin, "fin")),
                Boolean.TRUE.equals(prevenirAnimateurs));
        return toView(appliquee.fenetre(), appliquee.invitation());
    }

    @Tool(
            name = "appliquer_declaration_disponibilite",
            description = "Applique une déclaration en attente sur la fiche de l'animateur : jours indisponibles "
                    + "et souhaits déclarés y remplacent ceux qui s'y trouvaient. Tout ou rien, comme dans l'interface. "
                    + "Les données de référence sont marquées modifiées : le planning déjà résolu devient périmé. "
                    + "Rend les avertissements de cohérence en codes, comme modifier_animateur.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = false))
    DeclarationAppliqueeMcpView applyAvailabilityDeclaration(
            @ToolArg(description = "Id de la déclaration") String id,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        DeclarationAppliquee appliquee = declarationService.apply(id);
        return new DeclarationAppliqueeMcpView(
                view(appliquee.declaration()), WarningCodes.of(appliquee.avertissements()));
    }

    @Tool(
            name = "refuser_declaration_disponibilite",
            description = "Refuse une déclaration en attente : le référentiel n'est pas touché. Le commentaire "
                    + "est ce que l'animateur lira, et il peut renvoyer une version corrigée tant que la collecte est "
                    + "ouverte.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = false))
    DeclarationMcpView refuseAvailabilityDeclaration(
            @ToolArg(description = "Id de la déclaration") String id,
            @ToolArg(description = "Commentaire pour l'animateur", required = false) String commentaire,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return view(declarationService.refuse(id, commentaire));
    }

    /* -------------------------------- Views -------------------------------- */

    private DeclarationMcpView view(DeclarationDisponibilite declaration) {
        return toView(
                espaceAnimateurService.toDeclarationViews(List.of(declaration)).get(0));
    }

    static DeclarationMcpView toView(DeclarationAdminView declaration) {
        return new DeclarationMcpView(
                declaration.id(),
                declaration.animateurId(),
                declaration.statut(),
                declaration.joursIndisponibles(),
                declaration.souhaits(),
                TextesLibres.renseigne(declaration.commentaire()),
                TextesLibres.renseigne(declaration.commentaireAdmin()),
                declaration.creeLe(),
                declaration.decideLe(),
                declaration.joursActuels());
    }

    static CollecteView toView(FenetreCollecte fenetre, DeclarationDisponibiliteService.InvitationReport invitation) {
        return new CollecteView(
                fenetre.ouverte(),
                fenetre.debut(),
                fenetre.fin(),
                invitation == null
                        ? null
                        : new InvitationView(
                                invitation.envoyes(),
                                invitation.sansEmail().size(),
                                invitation.echecs().size()));
    }

    /** The applied declaration and the coherence warnings, in codes (see {@link WarningCodes}). */
    public record DeclarationAppliqueeMcpView(DeclarationMcpView declaration, List<String> avertissements) {}

    /**
     * One declaration, by id. Whether each comment was written, never what it
     * says: see {@link TextesLibres}.
     *
     * @param souhaits      ids of the wished typologies, as the tools name them
     * @param joursActuels  what the fiche says today, to compare with what was
     *                      declared before applying anything
     */
    public record DeclarationMcpView(
            String id,
            String animateurId,
            String statut,
            List<LocalDate> joursIndisponibles,
            List<String> souhaits,
            boolean commentaireRenseigne,
            boolean commentaireAdminRenseigne,
            Instant creeLe,
            Instant decideLe,
            List<LocalDate> joursActuels) {}

    /**
     * @param invitation what the invitation mails did, when this call sent
     *                   them; {@code null} otherwise
     */
    public record CollecteView(boolean ouverte, LocalDate debut, LocalDate fin, InvitationView invitation) {}

    /**
     * @param sansAdresse how many animateurs have no address on their fiche —
     *                    counted, never named
     */
    public record InvitationView(int envoyes, int sansAdresse, int echecs) {}
}
