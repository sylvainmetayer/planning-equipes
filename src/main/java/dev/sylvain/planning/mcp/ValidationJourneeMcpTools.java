package dev.sylvain.planning.mcp;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.sylvain.planning.domain.DayState;
import dev.sylvain.planning.service.validation.ValidationJourneeService;
import dev.sylvain.planning.service.validation.ValidationJourneeService.DayStateView;
import dev.sylvain.planning.service.validation.ValidationPrerequisService;
import dev.sylvain.planning.service.validation.ValidationPrerequisService.PrerequisJournee;
import dev.sylvain.planning.service.validation.ValidationPrerequisService.ProgressionValidations;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * MCP tools for the state of a day — à relire, relue, figée (ADR 0121) — and
 * what to check before reading one: where the relecture of an edition has
 * got to, and what remains to read.
 *
 * <p>An assistant walking an organiser through twelve days of relecture is the
 * very use these tools exist for — « qu'est-ce qu'il reste à relire », « cette
 * journée-là, qu'est-ce qui cloche » — and none of it was reachable without
 * them. The 3.x tools that listed, added and withdrew readings are gone (the
 * table of renamed tools says where they went); the REST routes behind them
 * stay as aliases until 5.0.</p>
 *
 * <p>Nothing here names a person: the account a reading was written under is
 * dropped, the comment reduced to whether there is one, and the prerequisites
 * are counts.</p>
 */
@EditionCiblee
@RefusMetier
@Journalise
@ApplicationScoped
public class ValidationJourneeMcpTools {

    private final ValidationJourneeService validationService;

    private final ValidationPrerequisService prerequisService;

    @Inject
    ValidationJourneeMcpTools(ValidationJourneeService validationService, ValidationPrerequisService prerequisService) {
        this.validationService = validationService;
        this.prerequisService = prerequisService;
    }

    @Tool(
            name = "consulter_etats_journee",
            description = "Liste l'état de chaque journée de l'édition (à relire, relue, figée) et l'avancement de "
                    + "la relecture (« 3 journées sur 12 ») : une journée compte quand elle est relue ou figée. "
                    + "Figée, c'est le verrou de journée : le calcul n'y touche plus. Relue, c'est une relecture "
                    + "sans verrou, que le calcul retire s'il recalcule la journée.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    EtatsJourneeView getJourneeStates(@ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        ProgressionValidations progression = prerequisService.progression();
        return new EtatsJourneeView(
                progression.journees(),
                progression.journeesValidees(),
                validationService.states().stream()
                        .map(ValidationJourneeMcpTools::toView)
                        .toList());
    }

    @Tool(
            name = "consulter_prerequis_validation",
            description = "Ce qu'il faut regarder avant d'accepter une journée : écarts durs, sièges vides, pauses "
                    + "sans relais, postes irremplaçables, comptés sur cette seule journée. Ne bloque rien : une "
                    + "journée peut être validée malgré un prérequis non satisfait.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    PrerequisJourneeView getValidationPrerequisites(
            @ToolArg(description = "Journée à relire (AAAA-MM-JJ)") String jour,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        PrerequisJournee lu = prerequisService.prerequis(McpArgs.date(jour, "jour"));
        return new PrerequisJourneeView(
                lu.jour(),
                lu.validee(),
                lu.validationId(),
                lu.valideeLe(),
                TextesLibres.renseigne(lu.commentaire()),
                lu.prerequis(),
                lu.tousSatisfaits());
    }

    @Tool(
            name = "modifier_etat_journee",
            description = "Change l'état d'une journée entière : A_RELIRE, RELUE ou FIGEE. Seul FIGEE pose le "
                    + "verrou de journée (le calcul n'y touchera plus) ; RELUE et A_RELIRE le retirent. "
                    + "Le commentaire de relecture est facultatif ; figer sans commentaire garde celui de la "
                    + "relecture existante, A_RELIRE retire la relecture. Idempotent. Pendant une résolution de "
                    + "l'édition, l'état est enregistré mais la réponse porte RESOLUTION_EN_COURS : la résolution "
                    + "en cours ne le voit pas, et à son atterrissage elle retirera la relecture d'une journée "
                    + "non figée qu'elle déplace.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    @WarnsWhileSolving
    EtatJourneeView updateJourneeState(
            @ToolArg(description = "Journée (AAAA-MM-JJ)") String jour,
            @ToolArg(description = "A_RELIRE, RELUE ou FIGEE") String etat,
            @ToolArg(description = "Commentaire de relecture, libre", required = false) String commentaire,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return toView(validationService.setState(
                McpArgs.date(jour, "jour"), McpArgs.enumeration(DayState.class, etat, "etat"), commentaire));
    }

    static EtatJourneeView toView(DayStateView state) {
        return new EtatJourneeView(
                state.jour(), state.etat(), state.valideLe(), TextesLibres.renseigne(state.commentaire()), List.of());
    }

    /**
     * One day and where it stands. Whether a comment was left, never what it
     * says, and never who left it: see {@link TextesLibres}. Carries
     * {@code RESOLUTION_EN_COURS} alone, when a solve holds the edition and the
     * answer is a write's; left out when empty.
     */
    public record EtatJourneeView(
            LocalDate jour,
            DayState etat,
            Instant valideLe,
            boolean commentaireRenseigne,
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> avertissements)
            implements WarningCarrier<EtatJourneeView> {

        @Override
        public EtatJourneeView withWarning(String code) {
            return new EtatJourneeView(
                    jour, etat, valideLe, commentaireRenseigne, WarningCodes.with(avertissements, code));
        }
    }

    /**
     * What to check before accepting a day. Same figures as the screen's, with
     * the reviewer's comment reduced to whether there is one: see
     * {@link TextesLibres}.
     */
    public record PrerequisJourneeView(
            LocalDate jour,
            boolean validee,
            String validationId,
            Instant valideeLe,
            boolean commentaireRenseigne,
            List<ValidationPrerequisService.Prerequis> prerequis,
            boolean tousSatisfaits) {}

    /** Where the relecture stands, and the state of every day of the grid. */
    public record EtatsJourneeView(int journees, int journeesValidees, List<EtatJourneeView> etats) {}
}
