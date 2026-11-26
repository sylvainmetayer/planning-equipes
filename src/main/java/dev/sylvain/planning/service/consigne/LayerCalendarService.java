package dev.sylvain.planning.service.consigne;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.consigne.LayerCalendar.OpeningLayers;
import dev.sylvain.planning.service.referentiel.CreneauService;
import dev.sylvain.planning.service.referentiel.StandService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.List;

/**
 * The combined calendar of the Ouvertures page: stands × timeslots × consignes,
 * layer by layer, before any solve (see {@link LayerCalendar}). Reads the
 * referential only, never a plan, so it answers on an edition never solved.
 */
@ApplicationScoped
public class LayerCalendarService {

    private final StandService stands;

    private final CreneauService creneaux;

    private final ConsigneRepository consignes;

    @Inject
    public LayerCalendarService(StandService stands, CreneauService creneaux, ConsigneRepository consignes) {
        this.stands = stands;
        this.creneaux = creneaux;
        this.consignes = consignes;
    }

    /**
     * The calendar over the days of {@code [du, au]} carrying a timeslot, both
     * bounds optional; {@code 400} on an inverted range.
     *
     * <p>The span itself is not capped: only the event days inside it are
     * computed, so its cost is bounded by the edition's grid whatever the
     * calendar distance between the bounds — the screen pages by event days,
     * and seven of them may sit months apart.</p>
     */
    public OpeningLayers build(LocalDate du, LocalDate au) {
        if (du != null && au != null && au.isBefore(du)) {
            throw new BusinessError.Invalid("La fin (" + au + ") précède le début (" + du + ").");
        }
        // One read of the grid for both resolutions: the nominal and the
        // effective day must be cut from the same timeslots.
        List<Creneau> grille = creneaux.list();
        // The nominal day, the consigne's plages left out: what the calendar
        // shows next to the effective one, never a source of seats.
        List<Stand> nominaux = stands.list();
        stands.resolveOwnHours(nominaux);
        List<Stand> effectifs = stands.list();
        stands.resolve(effectifs, grille);
        return LayerCalendar.build(nominaux, effectifs, consignes.list(), grille, du, au);
    }
}
