package dev.sylvain.planning.mcp;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import dev.sylvain.planning.domain.DemandeEchange;
import dev.sylvain.planning.domain.StatutDemandeEchange;
import dev.sylvain.planning.service.espace.DemandeEchangeService;
import dev.sylvain.planning.service.espace.DemandeEchangeService.FenetreFoire;
import dev.sylvain.planning.service.espace.EspaceAnimateurService;
import dev.sylvain.planning.service.espace.EspaceAnimateurService.DemandeEchangeView;
import dev.sylvain.planning.service.solve.PlanningWhatIf.EchangeSimulation;
import dev.sylvain.planning.service.solve.PlanningWhatIf.HardViolation;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * MCP tools over the swap requests animateurs send from their espace
 * ({@code DemandeEchangeResource}): the « foire aux échanges », its window,
 * and the admin decisions that apply a swap or leave the planning alone.
 *
 * <p>Accepting one rewrites the persisted plan and pins the result — the same
 * kind of write as {@code affecter_poste}, reached from the other end. An
 * assistant that could solve and repair but not decide a demande would leave
 * the operator to do by hand exactly the arbitration it is best placed to
 * document.</p>
 *
 * <p>A demande names two people and its prevalidation quotes constraint
 * violations worded for the web UI: ids only here, and the violation lines go
 * through {@link AnonymisationViolations} like everywhere else.</p>
 */
@EditionCiblee
@Journalise
@ApplicationScoped
public class EchangeMcpTools {

    @Inject
    DemandeEchangeService demandeEchangeService;

    @Inject
    EspaceAnimateurService espaceAnimateurService;

    @Tool(description = "Liste les demandes d'échange de l'édition, de la plus récente à la plus ancienne. "
            + "Filtrable par statut : EN_ATTENTE_CIBLE (le collègue visé n'a pas encore répondu), PROPOSEE "
            + "(en attente de décision de l'organisation), ACCEPTEE, REFUSEE, ANNULEE. Les animateurs y sont "
            + "désignés par id seul.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    List<DemandeView> lister_demandes_echange(
            @ToolArg(description = "Statut pour filtrer, par exemple PROPOSEE", required = false) String statut,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        StatutDemandeEchange filtre = statut == null
                ? null
                : McpArgs.enumeration(StatutDemandeEchange.class, statut, "statut");
        return espaceAnimateurService.toViews(demandeEchangeService.list()).stream()
                .filter(demande -> filtre == null || filtre.name().equals(demande.statut()))
                .map(EchangeMcpTools::toView)
                .toList();
    }

    @Tool(description = "Consulte la fenêtre de la foire aux échanges : l'interrupteur, ses dates éventuelles, "
            + "et si elle accepte quelque chose aujourd'hui. Une foire fermée rend les espaces animateurs "
            + "consultables mais non modifiables.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    FoireView consulter_foire_echanges(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return foireView();
    }

    @Tool(description = "Ouvre ou ferme la foire aux échanges, et la borne éventuellement par des dates. "
            + "L'interrupteur est le maître : une fenêtre datée dont l'interrupteur est éteint n'accepte rien. "
            + "N'envoie aucun courriel.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    FoireView configurer_foire_echanges(
            @ToolArg(description = "Foire ouverte ou fermée") boolean ouverte,
            @ToolArg(description = "Début de la foire (AAAA-MM-JJ)", required = false) String debut,
            @ToolArg(description = "Fin de la foire (AAAA-MM-JJ)", required = false) String fin,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        demandeEchangeService.openFoire(new FenetreFoire(ouverte,
                McpArgs.date(debut, "debut"), McpArgs.date(fin, "fin")));
        return foireView();
    }

    @Tool(description = "Chiffre l'impact d'une demande d'échange sur le planning persisté d'aujourd'hui : "
            + "score avant et après, delta, et contraintes dures qu'elle casserait. Recalculé à la demande — la "
            + "prévalidation stockée ne décrit que le planning du moment où la demande a été envoyée. Ne "
            + "persiste rien.",
            annotations = @Tool.Annotations(readOnlyHint = true, destructiveHint = false,
                    idempotentHint = true, openWorldHint = false))
    ImpactEchangeView analyser_impact_echange(
            @ToolArg(description = "Id de la demande") String id,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        EchangeSimulation simulation = demandeEchangeService.impact(id);
        return new ImpactEchangeView(simulation.posteDemandeurId(), simulation.posteCibleId(),
                simulation.echangeCroise(), simulation.standCibleId(),
                String.valueOf(simulation.scoreAvant()), String.valueOf(simulation.scoreApres()),
                String.valueOf(simulation.delta()), simulation.casseContrainteDure(),
                simulation.nouvellesViolationsDures().stream().map(EchangeMcpTools::toView).toList());
    }

    @Tool(description = "Accepte une demande d'échange : l'échange est appliqué au planning persisté tel qu'il "
            + "a été simulé, puis figé par des verrouillages ANIMATEUR_CRENEAU pour qu'une résolution ne le "
            + "défasse pas. Vérifier analyser_impact_echange d'abord : une demande acceptable à l'envoi peut "
            + "casser une contrainte dure sur le planning d'aujourd'hui.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false,
                    idempotentHint = false, openWorldHint = false))
    DemandeView accepter_demande_echange(
            @ToolArg(description = "Id de la demande") String id,
            @ToolArg(description = "Commentaire pour le demandeur", required = false) String commentaire,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return view(demandeEchangeService.accept(id, commentaire));
    }

    @Tool(description = "Refuse une demande d'échange : le planning n'est pas touché. Le commentaire est ce "
            + "que le demandeur lira à la prochaine publication.",
            annotations = @Tool.Annotations(readOnlyHint = false, destructiveHint = false,
                    idempotentHint = false, openWorldHint = false))
    DemandeView refuser_demande_echange(
            @ToolArg(description = "Id de la demande") String id,
            @ToolArg(description = "Commentaire pour le demandeur", required = false) String commentaire,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        return view(demandeEchangeService.refuse(id, commentaire));
    }

    /* -------------------------------- Views -------------------------------- */

    private FoireView foireView() {
        FenetreFoire fenetre = demandeEchangeService.fenetre();
        return new FoireView(fenetre.ouverte(), fenetre.debut(), fenetre.fin(),
                demandeEchangeService.isFoireOpen());
    }

    private DemandeView view(DemandeEchange demande) {
        return toView(espaceAnimateurService.toViews(List.of(demande)).get(0));
    }

    static DemandeView toView(DemandeEchangeView demande) {
        return new DemandeView(demande.id(), demande.statut(), demande.demandeurId(), demande.cibleId(),
                demande.creneauId(), demande.date(), demande.heureDebut(), demande.heureFin(),
                demande.standId(), demande.creneauCibleId(), demande.dateCible(), demande.heureDebutCible(),
                demande.heureFinCible(), demande.standCibleId(), demande.motif(), demande.prevalidationOk(),
                AnonymisationViolations.anonymiser(demande.contraintesViolees()),
                demande.commentaireAdmin(), demande.creeLe(), demande.cibleDecideLe(), demande.decideLe());
    }

    static ViolationHardView toView(HardViolation violation) {
        return new ViolationHardView(violation.name(),
                AnonymisationViolations.anonymiser(violation.description()),
                violation.matchesSupplementaires());
    }

    /**
     * One swap request, by ids.
     *
     * @param cibleId          the colleague the demande is aimed at, when it is
     *                         a directed swap; {@code null} for a seat given up
     * @param creneauCibleId   the colleague's seat wanted in return — directed
     *                         swaps only, {@code null} otherwise
     * @param prevalidationOk  what the check said <b>at submission time</b>;
     *                         {@code analyser_impact_echange} re-answers it
     *                         against today's plan
     */
    public record DemandeView(String id, String statut, String demandeurId, String cibleId,
            Long creneauId, LocalDate date, LocalTime heureDebut, LocalTime heureFin, String standId,
            Long creneauCibleId, LocalDate dateCible, LocalTime heureDebutCible, LocalTime heureFinCible,
            String standCibleId, String motif, Boolean prevalidationOk, List<String> contraintesViolees,
            String commentaireAdmin, Instant creeLe, Instant cibleDecideLe, Instant decideLe) {
    }

    /**
     * @param ouverteAujourdhui the switch <b>and</b> today's date against the
     *                          bounds — a foire switched on for next week
     *                          accepts nothing today
     */
    public record FoireView(boolean ouverte, LocalDate debut, LocalDate fin, boolean ouverteAujourdhui) {
    }

    /** Scores are rendered as text ({@code "0hard/-12medium/…"}), as everywhere else in this package. */
    public record ImpactEchangeView(String posteDemandeurId, String posteCibleId, boolean echangeCroise,
            String standCibleId, String scoreAvant, String scoreApres, String delta,
            boolean casseContrainteDure, List<ViolationHardView> nouvellesViolationsDures) {
    }

    public record ViolationHardView(String contrainte, String description, int matchesSupplementaires) {
    }
}
