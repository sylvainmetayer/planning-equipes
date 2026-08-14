package dev.sylvain.planning.mcp;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Set;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.NiveauEffort;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.ReferenceDataService;
import io.quarkiverse.mcp.server.Tool;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * MCP tools for consulting the non-personal reference data: créneaux (time
 * slots) of the active groupe, and stands.
 */
@ApplicationScoped
public class ReferentielMcpTools {

    @Inject
    ReferenceDataService referenceDataService;

    @Tool(description = "Liste les créneaux du groupe de créneaux actif (jour, date, heure de début/fin).")
    List<CreneauView> lister_creneaux() {
        return referenceDataService.listCreneauxGroupeActif().stream().map(ReferentielMcpTools::toView).toList();
    }

    @Tool(description = "Liste les stands, avec leurs typologies proposées, effectifs requis, "
            + "réserve majeurs/premium et niveau d'effort.")
    List<StandView> lister_stands() {
        return referenceDataService.listStands().stream().map(ReferentielMcpTools::toView).toList();
    }

    private static CreneauView toView(Creneau creneau) {
        return new CreneauView(creneau.getId(), creneau.getJour(), creneau.getDate(),
                creneau.getHeureDebut(), creneau.getHeureFin());
    }

    private static StandView toView(Stand stand) {
        return new StandView(stand.getId(), stand.getNom(), stand.getTypologiesProposees(),
                stand.getEffectifMin(), stand.getEffectifMax(), stand.isReserveMajeurs(),
                stand.isPremium(), stand.getNiveauEffort());
    }

    public record CreneauView(Long id, int jour, LocalDate date, LocalTime heureDebut, LocalTime heureFin) {
    }

    public record StandView(String id, String nom, Set<String> typologiesProposees, int effectifMin,
            int effectifMax, boolean reserveMajeurs, boolean premium, NiveauEffort niveauEffort) {
    }
}
