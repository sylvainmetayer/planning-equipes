package dev.sylvain.planning.mcp;

import dev.sylvain.planning.service.journee.ChangementsJournee;
import dev.sylvain.planning.service.journee.ChangementsJournee.AnimateurLine;
import dev.sylvain.planning.service.journee.ChangementsJournee.ReferenceChangements;
import dev.sylvain.planning.service.journee.ChangementsJournee.SeatChangeType;
import dev.sylvain.planning.service.journee.ChangementsJournee.SeatLine;
import dev.sylvain.planning.service.journee.ChangementsJourneeService;
import dev.sylvain.planning.service.publication.PublicationDiffService.TypeChangement;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * MCP tool mirroring {@code ChangementsJourneeResource}: what moved on one day
 * since the last publication or since the last solve.
 *
 * <p>Ids and counts only. The screen prints names and the sentences of the
 * mail; an assistant acts on ids, and a sentence is where a name would come
 * back in.</p>
 */
@EditionCiblee
@RefusMetier
@Journalise
@ApplicationScoped
public class ChangementsJourneeMcpTools {

    @Inject
    ChangementsJourneeService changementsService;

    @Tool(
            description = "Ce qui a changé sur une journée du plan enregistré : par siège (stand, heures, "
                    + "titulaire avant et après) et par animateur (vacations gagnées, retirées, déplacées). "
                    + "reference = publication (depuis le dernier plan publié) ou resolution (depuis le plan "
                    + "d'avant la dernière résolution) ; omise, la publication s'il y en a une. "
                    + "referenceDisponible faux veut dire « rien à comparer », pas « aucun changement ».",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    ChangementsJourneeView changements_journee(
            @ToolArg(description = "Journée (AAAA-MM-JJ)") String jour,
            @ToolArg(description = "publication ou resolution", required = false) String reference,
            @ToolArg(description = EditionArg.DESCRIPTION, required = false) @EditionArg String edition) {
        ChangementsJournee changements =
                changementsService.changements(McpArgs.date(jour, "jour"), ReferenceChangements.fromParam(reference));
        return new ChangementsJourneeView(
                changements.jour(),
                changements.reference(),
                changements.referenceDisponible(),
                changements.referenceLe(),
                changements.nouveaux(),
                changements.retires(),
                changements.remplaces(),
                changements.horairesModifies(),
                changements.animateursConcernes(),
                changements.parVacation().stream()
                        .map(ChangementsJourneeMcpTools::toView)
                        .toList(),
                changements.parAnimateur().stream()
                        .map(ChangementsJourneeMcpTools::toView)
                        .toList());
    }

    private static SeatLineView toView(SeatLine ligne) {
        return new SeatLineView(
                ligne.standId(),
                ligne.heureDebut(),
                ligne.heureFin(),
                ligne.heureDebutAvant(),
                ligne.heureFinAvant(),
                ligne.avant() == null ? null : ligne.avant().animateurId(),
                ligne.apres() == null ? null : ligne.apres().animateurId(),
                ligne.type());
    }

    private static AnimateurLineView toView(AnimateurLine ligne) {
        int ajouts = 0;
        int retraits = 0;
        int deplacements = 0;
        for (var changement : ligne.changements()) {
            switch (changement.type()) {
                case AJOUT -> ajouts++;
                case RETRAIT -> retraits++;
                case DEPLACEMENT -> deplacements++;
            }
        }
        return new AnimateurLineView(ligne.animateurId(), ajouts, retraits, deplacements);
    }

    /**
     * One day's changes, by seat and by person.
     *
     * @param referenceDisponible false when there is nothing to compare to —
     *                            the counts are then zero and say nothing
     * @param horairesModifies    seats the same person keeps on other hours
     */
    public record ChangementsJourneeView(
            LocalDate jour,
            ReferenceChangements reference,
            boolean referenceDisponible,
            Instant referenceLe,
            int nouveaux,
            int retires,
            int remplaces,
            int horairesModifies,
            int animateursConcernes,
            List<SeatLineView> parVacation,
            List<AnimateurLineView> parAnimateur) {}

    /**
     * One seat whose holder or hours changed: the stand, the hours, the two
     * ids. {@code heureDebutAvant}/{@code heureFinAvant} only on a {@link
     * SeatChangeType#HORAIRES} line, the hours the reference held the seat on.
     */
    public record SeatLineView(
            String standId,
            LocalTime heureDebut,
            LocalTime heureFin,
            LocalTime heureDebutAvant,
            LocalTime heureFinAvant,
            String avantAnimateurId,
            String apresAnimateurId,
            SeatChangeType type) {}

    /**
     * One person concerned, as counts of the {@link TypeChangement}s of their
     * day: shifts gained, shifts lost, shifts moved.
     */
    public record AnimateurLineView(String animateurId, int ajouts, int retraits, int deplacements) {}
}
