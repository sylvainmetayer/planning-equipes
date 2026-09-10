package dev.sylvain.planning.service.solve;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.LocalDate;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PlafondsLegauxMajeurs;
import dev.sylvain.planning.domain.PlafondsLegauxMineurs;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import dev.sylvain.planning.service.referentiel.JoursEvenement;

/**
 * Real scale of the problem the next solve will build — the figures of the
 * « Volumétrie du problème » card of the Solveur screen and of the MCP tool
 * of the same name.
 *
 * <p>Computed on the very problem {@link PlanningService#buildFromReferenceData()}
 * builds for a solve ({@code postes.size()} is Timefold's entity count,
 * {@code animateurs.size()} its value count), so it never drifts from what the
 * solver logs report, unlike a stands × créneaux guess would. All-zero rather
 * than an error when the reference data is not loaded yet: this feeds a
 * read-only card, not a solve.</p>
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

    /**
     * @param animateurCount       Timefold's value count
     * @param posteCount           Timefold's entity count, one per seat to fill
     * @param contrainteAdHocCount the ad hoc rules layered on top
     * @param hoursToFill          sum of the effective duration of every seat
     * @param hoursAvailable       legal ceiling of what the animateurs may work
     */
    @Schema(requiredProperties = {"animateurCount", "contrainteAdHocCount", "hoursAvailable", "hoursToFill", "posteCount"})
    public record ProblemScale(int animateurCount, int posteCount, int contrainteAdHocCount,
            double hoursToFill, double hoursAvailable) {

        static final ProblemScale VIDE = new ProblemScale(0, 0, 0, 0, 0);

        /**
         * The figures of {@code evenement}, as {@link #compute()} reports them.
         * The event's days are those its seats fall on: a day with a timeslot
         * but nothing to staff needs nobody, and counting it would only inflate
         * the ceiling the seats are compared against.
         */
        public static ProblemScale of(PlanningEvenement evenement) {
            JoursEvenement jours = JoursEvenement.of(evenement.getPostes().stream()
                    .map(PosteAffectation::getCreneau)
                    .filter(java.util.Objects::nonNull)
                    .toList());
            return new ProblemScale(evenement.getAnimateurs().size(), evenement.getPostes().size(),
                    evenement.getContraintesAdHoc().size(),
                    hoursToFill(evenement.getPostes()),
                    hoursAvailable(evenement.getAnimateurs(), jours, parametresLegaux(evenement)));
        }

        private static ParametresLegaux parametresLegaux(PlanningEvenement evenement) {
            List<ParametresLegaux> parametres = evenement.getParametresLegaux();
            return parametres == null || parametres.isEmpty() ? new ParametresLegaux() : parametres.get(0);
        }

        static double hoursToFill(List<PosteAffectation> postes) {
            return postes.stream().mapToLong(PosteAffectation::getDureeEffectiveMinutes).sum() / 60.0;
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
                    parSemaine.computeIfAbsent(semaineIso(jour), semaine -> new ArrayList<>()).add(jour);
                }
            }
            long minutes = 0;
            for (List<LocalDate> joursSemaine : parSemaine.values()) {
                long quotidien = 0;
                int travaillables = Math.min(joursSemaine.size(), PlafondsLegauxMajeurs.JOURS_TRAVAILLES_MAX_PAR_SEMAINE);
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

    /** The figures of the current edition; all zero when it has no reference data yet. */
    public ProblemScale compute() {
        try {
            return ProblemScale.of(planningService.buildFromReferenceData());
        } catch (IllegalStateException e) {
            return ProblemScale.VIDE;
        }
    }
}
