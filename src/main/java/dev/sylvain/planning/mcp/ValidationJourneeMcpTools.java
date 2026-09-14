package dev.sylvain.planning.mcp;

import dev.sylvain.planning.domain.ValidationJournee;
import dev.sylvain.planning.service.validation.ValidationJourneeService;
import dev.sylvain.planning.service.validation.ValidationJourneeService.DemandeValidation;
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
 * MCP tools mirroring {@code ValidationJourneeResource}: where the relecture of
 * an edition has got to, and what remains to read.
 *
 * <p>An assistant walking an organiser through twelve days of relecture is the
 * very use these tools exist for — « qu'est-ce qu'il reste à relire », « cette
 * journée-là, qu'est-ce qui cloche » — and none of it was reachable without
 * them.</p>
 *
 * <p>Nothing here names a person: a validation carries the admin account it was
 * written under, which the views drop, and the prerequisites are counts.</p>
 */
@EditionCiblee
@RefusMetier
@Journalise
@ApplicationScoped
public class ValidationJourneeMcpTools {

    @Inject
    ValidationJourneeService validationService;

    @Inject
    ValidationPrerequisService prerequisService;

    @Tool(
            description = "Liste les journées relues et acceptées, et l'avancement de la relecture "
                    + "(« 3 journées sur 12 »). Une validation dit qu'un humain a relu la journée ; elle ne fige "
                    + "rien — figer, c'est verrouiller (verrouiller).",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    AvancementValidations lister_validations_journee(
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        ProgressionValidations progression = prerequisService.progression();
        return new AvancementValidations(
                progression.journees(),
                progression.journeesValidees(),
                validationService.list().stream()
                        .map(ValidationJourneeMcpTools::toView)
                        .toList());
    }

    @Tool(
            description = "Ce qu'il faut regarder avant d'accepter une journée : écarts durs, sièges vides, pauses "
                    + "sans relais, postes irremplaçables, comptés sur cette seule journée. Ne bloque rien : une "
                    + "journée peut être validée malgré un prérequis non satisfait.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    PrerequisJourneeView consulter_prerequis_validation(
            @ToolArg(description = "Journée à relire (AAAA-MM-JJ)") String jour,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
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
            description = "Marque une journée entière « relue et acceptée ». poserVerrou fige en plus la journée "
                    + "pour les prochaines résolutions — facultatif, et faux par défaut. Relire une journée déjà "
                    + "acceptée remplace la validation précédente.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    ResultatValidationView ajouter_validation_journee(
            @ToolArg(description = "Journée relue (AAAA-MM-JJ)") String jour,
            @ToolArg(description = "Commentaire de relecture, libre", required = false) String commentaire,
            @ToolArg(description = "Poser aussi un verrouillage de journée", required = false) Boolean poserVerrou,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        ValidationJourneeService.ResultatValidation resultat = validationService.accept(
                new DemandeValidation(McpArgs.date(jour, "jour"), commentaire, Boolean.TRUE.equals(poserVerrou)));
        return new ResultatValidationView(toView(resultat.validation()), resultat.verrouPose());
    }

    @Tool(
            description = "Retire une validation : la journée redevient à relire. Le verrouillage éventuellement posé "
                    + "avec elle reste en place — c'est un mécanisme distinct (deverrouiller).",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    SuppressionResult retirer_validation_journee(
            @ToolArg(description = "Id de la validation") String id,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        validationService.withdraw(id);
        return new SuppressionResult(id, true);
    }

    static ValidationView toView(ValidationJournee validation) {
        return new ValidationView(
                validation.id(),
                validation.jour(),
                validation.valideLe(),
                TextesLibres.renseigne(validation.commentaire()));
    }

    /**
     * One reading. Whether a comment was left, never what it says, and never
     * who left it: see {@link TextesLibres}.
     */
    public record ValidationView(String id, LocalDate jour, Instant valideLe, boolean commentaireRenseigne) {}

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

    /** Where the relecture stands, and every reading behind it. */
    public record AvancementValidations(int journees, int journeesValidees, List<ValidationView> validations) {}

    /** A reading, and whether the lock it was asked for was actually laid down. */
    public record ResultatValidationView(ValidationView validation, boolean verrouPose) {}
}
