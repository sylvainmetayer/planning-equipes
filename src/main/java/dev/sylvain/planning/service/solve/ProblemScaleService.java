package dev.sylvain.planning.service.solve;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PlafondsLegauxMajeurs;
import dev.sylvain.planning.domain.PlafondsLegauxMineurs;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.referentiel.JoursEvenement;
import dev.sylvain.planning.service.referentiel.ReferenceData;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Real scale of the problem the next solve will build — the figures of the
 * « Volumétrie du problème » card of the Solveur screen and of the MCP tool
 * of the same name.
 *
 * <p>Computed on the very seats {@link PlanningService#buildSeatsFromReferenceData()}
 * builds for a solve ({@code postes.size()} is Timefold's entity count,
 * {@code animateurs.size()} its value count), so it never drifts from what the
 * solver logs report, unlike a stands × créneaux guess would. Seats only, not
 * the whole problem: that one refuses an edition without animateur, and the
 * seat count is known as soon as the stands and timeslots are (issue #416) —
 * an edition with no animateur yet reports its seats and zero hours
 * available, and one with no stand or no timeslot simply reports zero. Never
 * an error: this feeds a read-only card, not a solve.</p>
 *
 * <p>The two hour figures put the counts in perspective. <b>Hours to fill</b>
 * is the sum of every seat's effective duration — a seat narrowed by a stand
 * closure counts only the time actually staffed, the same basis as the Heures
 * screen. <b>Hours available</b> is what the animateurs may legally work over
 * the event: per animateur and per ISO week, the days carrying a timeslot on
 * which they are not unavailable, six at most, each capped by the daily
 * ceiling of their age on that day, the whole capped by the weekly ceiling of
 * the edition's {@link ParametresLegaux}. It is a ceiling, not a forecast:
 * competences, rest between shifts and the pause rule all take from it, which
 * is why a fill ratio close to one is already a warning.</p>
 */
@ApplicationScoped
public class ProblemScaleService {

    @Inject
    PlanningService planningService;

    @Inject
    ReferenceData referenceDataService;

    /**
     * @param animateurCount       Timefold's value count
     * @param posteCount           Timefold's entity count, one per seat to fill
     * @param contrainteAdHocCount the ad hoc rules layered on top
     * @param hoursToFill          sum of the effective duration of every seat
     * @param hoursAvailable       legal ceiling of what the animateurs may work
     */
    @Schema(
            requiredProperties = {
                "animateurCount",
                "contrainteAdHocCount",
                "hoursAvailable",
                "hoursToFill",
                "posteCount"
            })
    public record ProblemScale(
            int animateurCount, int posteCount, int contrainteAdHocCount, double hoursToFill, double hoursAvailable) {

        /** The figures of a problem built by hand — the plain-Java harness of the tests. */
        public static ProblemScale of(PlanningEvenement evenement) {
            return of(
                    evenement.getPostes(),
                    evenement.getAnimateurs(),
                    evenement.getContraintesAdHoc().size(),
                    parametresLegaux(evenement));
        }

        /**
         * The figures {@link #compute()} reports. The event's days are those
         * the seats fall on: a day with a timeslot but nothing to staff needs
         * nobody, and counting it would only inflate the ceiling the seats are
         * compared against.
         */
        public static ProblemScale of(
                List<PosteAffectation> postes,
                List<Animateur> animateurs,
                int contrainteAdHocCount,
                ParametresLegaux legaux) {
            JoursEvenement jours = JoursEvenement.of(postes.stream()
                    .map(PosteAffectation::getCreneau)
                    .filter(java.util.Objects::nonNull)
                    .toList());
            return new ProblemScale(
                    animateurs.size(),
                    postes.size(),
                    contrainteAdHocCount,
                    hoursToFill(postes),
                    hoursAvailable(animateurs, jours, legaux));
        }

        private static ParametresLegaux parametresLegaux(PlanningEvenement evenement) {
            List<ParametresLegaux> parametres = evenement.getParametresLegaux();
            return parametres == null || parametres.isEmpty() ? new ParametresLegaux() : parametres.get(0);
        }

        static double hoursToFill(List<PosteAffectation> postes) {
            return postes.stream()
                            .mapToLong(PosteAffectation::getDureeEffectiveMinutes)
                            .sum()
                    / 60.0;
        }

        static double hoursAvailable(List<Animateur> animateurs, JoursEvenement jours, ParametresLegaux legaux) {
            long minutes = 0;
            for (Animateur animateur : animateurs) {
                minutes += minutesAvailable(animateur, jours, legaux);
            }
            return minutes / 60.0;
        }

        /**
         * The legal ceiling of one animateur over the event. Week by week, as
         * the weekly limit is: the days of that week they can come, six at
         * most, each worth the daily ceiling of their age on that day.
         */
        private static long minutesAvailable(Animateur animateur, JoursEvenement jours, ParametresLegaux legaux) {
            Map<String, List<LocalDate>> parSemaine = new LinkedHashMap<>();
            for (LocalDate jour : jours.jours()) {
                if (!animateur.isIndisponibleOn(jour)) {
                    parSemaine
                            .computeIfAbsent(semaineIso(jour), semaine -> new ArrayList<>())
                            .add(jour);
                }
            }
            long minutes = 0;
            for (List<LocalDate> joursSemaine : parSemaine.values()) {
                long quotidien = 0;
                int travaillables =
                        Math.min(joursSemaine.size(), PlafondsLegauxMajeurs.JOURS_TRAVAILLES_MAX_PAR_SEMAINE);
                for (LocalDate jour : joursSemaine.subList(0, travaillables)) {
                    quotidien += dailyCeilingMinutes(animateur, jour);
                }
                int hebdomadaire = animateur.isMineurOn(joursSemaine.get(0))
                        ? legaux.getDureeHebdomadaireMaxMineurMinutes()
                        : legaux.getDureeHebdomadaireMaxMinutes();
                minutes += Math.min(quotidien, hebdomadaire);
            }
            return minutes;
        }

        private static int dailyCeilingMinutes(Animateur animateur, LocalDate jour) {
            if (animateur.isMineurOn(jour)) {
                return PlafondsLegauxMineurs.dureeQuotidienneMaxMinutes(animateur.isUnder16On(jour));
            }
            return PlafondsLegauxMajeurs.DUREE_QUOTIDIENNE_MAX_MINUTES;
        }

        private static String semaineIso(LocalDate jour) {
            return jour.get(IsoFields.WEEK_BASED_YEAR) + "-W" + jour.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        }
    }

    /** The figures of the current edition; zero wherever the referential is still empty. */
    public ProblemScale compute() {
        ProblemBuilder.Seats seats = planningService.buildSeatsFromReferenceData();
        return ProblemScale.of(
                seats.postes(),
                referenceDataService.listAnimateurs(),
                referenceDataService.snapshotContraintes().size(),
                referenceDataService.getParametresLegaux());
    }
}
