package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresQualite;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.solver.WalkingTime;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The tight seat-to-seat walks of a persisted plan — the read-out behind
 * {@code GET /api/planning/enchainements}, the red chevron of the Rail and the
 * « enchaînements serrés » line of the Problèmes screen.
 *
 * <p>Same arithmetic as the rule {@code trajetInsuffisantEntrePostes}, through
 * the one {@link WalkingTime}, but a wider net: this is a reading, not a
 * score, so it covers <b>every</b> pair of consecutive seats of an animateur on
 * one day, a zero gap included — the pairs the rule leaves to
 * {@code eviterChangementEmplacementEloigne} are just as unwalkable on the day.
 * Past seats stay listed too (ADR 0044): what the solver may no longer mend is
 * still what happened.</p>
 *
 * <p>A pair is listed when the gap lacks walking minutes beyond the tolerance,
 * or when that gap is also the legal break and the walk eats into it — « trajet
 * pris sur la pause ». The break rule itself is not changed: this only says
 * so.</p>
 *
 * <p>Nothing is computed for a stand without an emplacement, or an emplacement
 * without coordinates: an unknown trip is neither a short one nor a long
 * one.</p>
 */
@ApplicationScoped
public class WalkSequenceAnalyzer {

    /**
     * One walk between two consecutive seats of the same animateur, the same
     * day. Ids only: the screens join the names, and an MCP client gets
     * exactly this.
     *
     * @param animateurId       who walks
     * @param date              the day of the two timeslots — a night shift
     *                          reads on the day it starts
     * @param fromPosteId       seat left
     * @param toPosteId         seat reached
     * @param fromCreneauId     timeslot of the seat left
     * @param toCreneauId       timeslot of the seat reached
     * @param fromStandId       stand left
     * @param toStandId         stand reached
     * @param fromEmplacementId where the walk starts
     * @param toEmplacementId   where it ends
     * @param end               end of the seat left, on its effective window
     * @param start             start of the seat reached
     * @param distanceMetres    great-circle distance, rounded
     * @param walkMinutes       estimated walk, rounded up
     * @param gapMinutes        gap between the two seats, never negative
     * @param missingMinutes    what the gap lacks beyond the tolerance;
     *                          positive means the walk does not fit
     * @param walkOnBreak       the gap is the legal break and the walk leaves
     *                          less than the break of it
     */
    @Schema(requiredProperties = {"distanceMetres", "walkMinutes", "gapMinutes", "missingMinutes", "walkOnBreak"})
    public record WalkView(
            String animateurId,
            LocalDate date,
            String fromPosteId,
            String toPosteId,
            Long fromCreneauId,
            Long toCreneauId,
            String fromStandId,
            String toStandId,
            String fromEmplacementId,
            String toEmplacementId,
            LocalTime end,
            LocalTime start,
            int distanceMetres,
            int walkMinutes,
            int gapMinutes,
            int missingMinutes,
            boolean walkOnBreak) {}

    /**
     * The report: the settings it was read with, whether the edition has any
     * emplacement to walk between at all, and the tight walks.
     *
     * @param walkingSpeedKmH  the walking speed setting
     * @param detourFactor     the detour factor setting
     * @param toleranceMinutes the tolerance setting
     * @param geolocated       false when no stand of the plan sits on an
     *                         emplacement with coordinates: the list is then
     *                         empty because nothing could be computed, not
     *                         because all is well
     */
    @Schema(requiredProperties = {"walkingSpeedKmH", "detourFactor", "toleranceMinutes", "geolocated", "walks"})
    public record WalkSequenceReport(
            double walkingSpeedKmH,
            double detourFactor,
            int toleranceMinutes,
            boolean geolocated,
            List<WalkView> walks) {}

    public WalkSequenceReport analyze(PlanningEvenement planning, ParametresQualite qualite, ParametresLegaux legaux) {
        // Every seat with known hours splits the day, located or not: the rule
        // pairs only seats with nothing in between, whatever sits there, and
        // the reading must pair the same ones. A pair lacking coordinates on
        // either side is skipped in read(), never bridged.
        List<PosteAffectation> postes = planning == null || planning.getPostes() == null
                ? List.of()
                : planning.getPostes().stream()
                        .filter(WalkSequenceAnalyzer::usable)
                        .toList();
        boolean geolocalises = postes.stream().anyMatch(poste -> hasCoordinates(emplacementOf(poste)));
        List<WalkView> enchainements = new ArrayList<>();
        if (geolocalises) {
            for (List<PosteAffectation> journee : byAnimateurAndDay(postes)) {
                List<PosteAffectation> triee = journee.stream()
                        .sorted(Comparator.comparing(WalkSequenceAnalyzer::start))
                        .toList();
                for (int i = 1; i < triee.size(); i++) {
                    read(triee.get(i - 1), triee.get(i), qualite, legaux).ifPresent(enchainements::add);
                }
            }
        }
        enchainements.sort(Comparator.comparing(WalkView::date)
                .thenComparing(WalkView::end)
                .thenComparing(WalkView::animateurId));
        return new WalkSequenceReport(
                qualite.vitesseMarcheKmH(),
                qualite.facteurDetour(),
                qualite.toleranceTrajetMinutes(),
                geolocalises,
                List.copyOf(enchainements));
    }

    private static Optional<WalkView> read(
            PosteAffectation precedent, PosteAffectation suivant, ParametresQualite qualite, ParametresLegaux legaux) {
        Emplacement depart = emplacementOf(precedent);
        Emplacement arrivee = emplacementOf(suivant);
        if (!hasCoordinates(depart) || !hasCoordinates(arrivee) || depart.equals(arrivee)) {
            return Optional.empty();
        }
        long ecart = Duration.between(end(precedent), start(suivant)).toMinutes();
        if (ecart < 0) {
            // Two overlapping seats are a legal breach, not a walk.
            return Optional.empty();
        }
        double metres = depart.distanceMetresTo(arrivee);
        int trajet = WalkingTime.minutes(metres, qualite.vitesseMarcheKmH(), qualite.facteurDetour());
        int manquantes = WalkingTime.missingMinutes(trajet, ecart, qualite.toleranceTrajetMinutes());
        boolean surLaPause = legaux != null && onTheBreak(precedent, ecart, trajet, legaux);
        if (manquantes == 0 && !surLaPause) {
            return Optional.empty();
        }
        return Optional.of(new WalkView(
                precedent.getAnimateur().getId(),
                precedent.getCreneau().getDate(),
                precedent.getId(),
                suivant.getId(),
                precedent.getCreneau().getId(),
                suivant.getCreneau().getId(),
                precedent.getStand().getId(),
                suivant.getStand().getId(),
                depart.getId(),
                arrivee.getId(),
                end(precedent).toLocalTime(),
                start(suivant).toLocalTime(),
                (int) Math.round(metres),
                trajet,
                (int) ecart,
                manquantes,
                surLaPause));
    }

    /**
     * The gap is long enough to be the legal break — the break analysis reads
     * a shorter hole as part of one stretch — and the walk leaves less than a
     * break of it.
     */
    private static boolean onTheBreak(PosteAffectation precedent, long ecart, int trajet, ParametresLegaux legaux) {
        boolean mineur =
                precedent.getAnimateur().isMineurOn(precedent.getCreneau().getDate());
        int pause = legaux.dureePauseMinutes(mineur);
        return trajet > 0 && ecart >= pause && ecart - trajet < pause;
    }

    private static Collection<List<PosteAffectation>> byAnimateurAndDay(List<PosteAffectation> postes) {
        Map<String, List<PosteAffectation>> groupes = new LinkedHashMap<>();
        for (PosteAffectation poste : postes) {
            String cle = poste.getAnimateur().getId() + '|' + poste.getCreneau().getJour();
            groupes.computeIfAbsent(cle, k -> new ArrayList<>()).add(poste);
        }
        return groupes.values();
    }

    private static boolean usable(PosteAffectation poste) {
        return poste.getAnimateur() != null
                && poste.getCreneau() != null
                && poste.getCreneau().getDate() != null
                && poste.heureDebutEffectif() != null
                && poste.heureFinEffectif() != null;
    }

    private static Emplacement emplacementOf(PosteAffectation poste) {
        return poste.getStand() == null ? null : poste.getStand().getEmplacement();
    }

    private static boolean hasCoordinates(Emplacement emplacement) {
        return emplacement != null && emplacement.getLatitude() != null && emplacement.getLongitude() != null;
    }

    private static LocalDateTime start(PosteAffectation poste) {
        return LocalDateTime.of(poste.getCreneau().getDate(), poste.heureDebutEffectif());
    }

    private static LocalDateTime end(PosteAffectation poste) {
        return start(poste).plusMinutes(poste.getDureeEffectiveMinutes());
    }
}
