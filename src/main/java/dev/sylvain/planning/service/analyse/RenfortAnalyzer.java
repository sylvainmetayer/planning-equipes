package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Where the bonus hours are: the renforts a stand declares above the staffing
 * its windows ask for (issue #505, ADR 0046), in hours, stand by stand and day
 * by day.
 *
 * <p>The « Renforts » screen answers one question the rest of the application
 * does not: <b>if the budget shrinks, what do I cut, and what does cutting it
 * actually save?</b> The Ouvertures grid says a stand carries a margin, the
 * solver page says how many hours the event owes — neither says that three
 * stands hold two thirds of the bonus, nor that half of it was never staffed.
 *
 * <h2>Two figures, two sources, on purpose</h2>
 *
 * <ul>
 * <li><b>Hours opened</b> — the renfort seats a solve would build from the
 * reference data as it stands <em>now</em>. This is the capacity the
 * administrator declared and the one they can trim, by lowering a stand's
 * {@code effectifMax}.</li>
 * <li><b>Hours staffed</b> — the renfort seats somebody holds in the
 * <em>persisted</em> plan. This is what the bonus really cost on the last
 * solve.</li>
 * </ul>
 *
 * <p>They are read from two different things, and the gap between them is the
 * point: a stand opening forty bonus hours of which four were ever staffed is
 * a margin that costs nothing to remove, while one staffed to the brim is a
 * cut somebody will feel. A grid edited since the last solve can make the
 * second figure exceed the first on a stand; that is a stale plan saying so,
 * not an error, and the screen shows both rather than reconciling them behind
 * the reader's back.</p>
 *
 * <p>Hours, not seats: a renfort on a four-hour shift and one on a one-hour
 * relay are not the same decision, and the budget is counted in hours. The
 * duration is the seat's <b>effective</b> one, so a renfort narrowed by a
 * stand closure counts only the time actually staffed — the same basis as the
 * Heures screen and as the solver's volumetry.</p>
 */
@ApplicationScoped
public class RenfortAnalyzer {

    /** Renfort hours of one stand on one day. */
    @Schema(requiredProperties = {"date", "heuresOuvertes", "heuresPourvues"})
    public record CelluleRenfort(LocalDate date, double heuresOuvertes, double heuresPourvues) {}

    /**
     * One stand's bonus hours over the event.
     *
     * @param emplacementNom the location the stand sits on, {@code null} when
     *                       it has none — carried so the reading can be
     *                       grouped by site when the budget is
     * @param jours          one cell per day the stand opens a renfort on or
     *                       staffs one, earliest first; a day with neither is
     *                       left out rather than sent as a zero
     */
    @Schema(requiredProperties = {"heuresOuvertes", "heuresPourvues", "jours", "nom", "standId"})
    public record LigneRenfort(
            String standId,
            String nom,
            String emplacementNom,
            double heuresOuvertes,
            double heuresPourvues,
            List<CelluleRenfort> jours) {}

    /**
     * @param jours           every day at least one stand has something to say
     *                        about, earliest first — the columns of the grid
     * @param stands          the rows, the stand opening the most bonus hours
     *                        first, so the lever with the most to give is read
     *                        before the others
     * @param heuresOuvertes  bonus hours the edition declares
     * @param heuresPourvues  bonus hours the persisted plan staffs
     * @param heuresDues      hours the edition owes, renforts excluded — what
     *                        the bonus is put in perspective against
     * @param planEnregistre  whether a plan was persisted at all; without one
     *                        every {@code heuresPourvues} is zero because
     *                        nothing was solved, not because nothing was taken
     * @param message         what an empty report means, worded for the screen
     */
    @Schema(
            requiredProperties = {"heuresDues", "heuresOuvertes", "heuresPourvues", "jours", "planEnregistre", "stands"
            })
    public record RapportRenforts(
            List<LocalDate> jours,
            List<LigneRenfort> stands,
            double heuresOuvertes,
            double heuresPourvues,
            double heuresDues,
            boolean planEnregistre,
            String message) {}

    /**
     * @param seats    the seats a solve would build from the reference data as
     *                 it stands — where the declared capacity is read
     * @param persiste the seats of the persisted plan — where the staffed
     *                 renforts are read; empty when nothing was solved yet
     */
    public RapportRenforts analyze(List<PosteAffectation> seats, List<PosteAffectation> persiste) {
        Map<String, Stand> standsById = new LinkedHashMap<>();
        Map<String, Map<LocalDate, double[]>> parStand = new LinkedHashMap<>();
        double heuresDues = 0;
        for (PosteAffectation poste : seats) {
            if (poste.getStand() == null) {
                continue;
            }
            if (!poste.isOptionnel()) {
                heuresDues += poste.getDureeEffectiveMinutes() / 60.0;
                continue;
            }
            standsById.putIfAbsent(poste.getStand().getId(), poste.getStand());
            cell(parStand, poste)[0] += poste.getDureeEffectiveMinutes() / 60.0;
        }
        for (PosteAffectation poste : persiste) {
            if (poste.getStand() == null || !poste.isOptionnel() || poste.getAnimateur() == null) {
                continue;
            }
            standsById.putIfAbsent(poste.getStand().getId(), poste.getStand());
            cell(parStand, poste)[1] += poste.getDureeEffectiveMinutes() / 60.0;
        }

        List<LigneRenfort> lignes = new ArrayList<>();
        List<LocalDate> jours = new ArrayList<>();
        double ouvertes = 0;
        double pourvues = 0;
        for (Map.Entry<String, Map<LocalDate, double[]>> entry : parStand.entrySet()) {
            Stand stand = standsById.get(entry.getKey());
            List<CelluleRenfort> cellules = new ArrayList<>();
            double standOuvertes = 0;
            double standPourvues = 0;
            for (Map.Entry<LocalDate, double[]> jour : entry.getValue().entrySet()) {
                double[] heures = jour.getValue();
                if (heures[0] == 0 && heures[1] == 0) {
                    continue;
                }
                cellules.add(new CelluleRenfort(jour.getKey(), arrondi(heures[0]), arrondi(heures[1])));
                standOuvertes += heures[0];
                standPourvues += heures[1];
                if (jour.getKey() != null && !jours.contains(jour.getKey())) {
                    jours.add(jour.getKey());
                }
            }
            if (cellules.isEmpty()) {
                continue;
            }
            lignes.add(new LigneRenfort(
                    entry.getKey(),
                    stand == null ? entry.getKey() : stand.getNom(),
                    stand == null || stand.getEmplacement() == null
                            ? null
                            : stand.getEmplacement().getNom(),
                    arrondi(standOuvertes),
                    arrondi(standPourvues),
                    cellules));
            ouvertes += standOuvertes;
            pourvues += standPourvues;
        }
        jours.sort(Comparator.naturalOrder());
        // The stand with the most to give first, then by name so two stands
        // opening the same margin keep a stable order between two reads.
        lignes.sort(Comparator.comparingDouble(LigneRenfort::heuresOuvertes)
                .reversed()
                .thenComparing(LigneRenfort::nom, Comparator.nullsLast(Comparator.naturalOrder())));
        return new RapportRenforts(
                jours,
                lignes,
                arrondi(ouvertes),
                arrondi(pourvues),
                arrondi(heuresDues),
                !persiste.isEmpty(),
                lignes.isEmpty() ? message(seats) : null);
    }

    private static double[] cell(Map<String, Map<LocalDate, double[]>> parStand, PosteAffectation poste) {
        LocalDate date = poste.getCreneau() == null ? null : poste.getCreneau().getDate();
        return parStand.computeIfAbsent(
                        poste.getStand().getId(), id -> new TreeMap<>(Comparator.nullsLast(Comparator.naturalOrder())))
                .computeIfAbsent(date, jour -> new double[2]);
    }

    /** Why the report is empty, told apart so the screen does not say « aucun renfort » to an edition with no stand. */
    private static String message(List<PosteAffectation> seats) {
        if (seats.isEmpty()) {
            return "Aucun siège à pourvoir : l'édition n'a pas encore de stand ou de créneau.";
        }
        return "Aucun renfort : aucun stand ne déclare d'effectif maximum au-dessus de ce que ses fenêtres demandent.";
    }

    /** Hours to the nearest hundredth: the screen reads them, nothing computes on them again. */
    private static double arrondi(double heures) {
        return Math.round(heures * 100.0) / 100.0;
    }
}
