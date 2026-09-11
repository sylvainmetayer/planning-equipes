package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.BusinessError;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The opening schedule of a stand written the way the organiser's own
 * spreadsheet writes it: one integer per créneau, empty for closed — and
 * turned back into the rules and exceptions the rest of the application
 * reads.
 *
 * <p>The grid is the whole schedule of a stand: a save replaces its recurring
 * horaires and dated exceptions in full, so what the grid shows after a save
 * is exactly what was typed, and typing the same grid twice writes the same
 * thing twice. The conversion goes through dated windows first — one per run
 * of créneaux open at one headcount — then through {@link HoraireCompaction},
 * which folds the repeated days into rules where it can prove the result
 * equivalent, and leaves the odd days dated otherwise.</p>
 *
 * <p><b>The grid never destroys what it cannot show.</b> A cell holds one
 * integer, but a stand may be open on part of a créneau only, or at two
 * headcounts during it (4 people from 14:00 to 19:00, then 2 until 20:00 —
 * the shape the reference workbook cuts its columns on). Such a cell reads as
 * its highest headcount and is flagged partial. Saving it <em>unchanged</em>
 * keeps the underlying segments as they are; only a cell whose value was
 * typed differently is rewritten to the whole créneau at that value. Flattening
 * every partial cell onto its créneau is a separate, explicit request
 * ({@code aplatir}), never the side effect of saving a neighbour.</p>
 *
 * <p>The stand's bounds follow the cells: {@code effectifMin} is the smallest
 * headcount typed, {@code effectifMax} the largest, which is what the source
 * workbook already did for every stand of the reference event. A window then
 * names its headcount only when it differs from the minimum — the shape the
 * form and the exports treat as the common case.</p>
 *
 * <p>Pure and static: the persistence is {@code StandService}'s business. The
 * stand handed in must carry its <em>effective</em> windows
 * ({@link Stand#getOuverturesEffectives()}): the segments a partial cell
 * keeps are read off them before anything is rewritten.</p>
 */
public final class GrilleHorairesStands {

    private GrilleHorairesStands() {}

    /** One stand of a submitted grid: its cells, one per créneau of the edition. */
    /**
     * @param modifieLe the stand's {@code modifie_le} as the grid read it, sent
     *                  back as the write's precondition (issue #362). This
     *                  screen rewrites a stand's whole schedule, so it is the
     *                  gesture that overwrites the most — it is checked like a
     *                  fiche, and {@code null} means "no precondition" here too.
     * @param aplatir   {@code true} to rewrite every cell onto its whole
     *                  créneau at the typed headcount, partial ones included —
     *                  the explicit "align the windows on the créneaux"
     *                  gesture. Absent or {@code false}, a partial cell saved
     *                  unchanged keeps its segments.
     */
    public record SaisieStand(String standId, Instant modifieLe, List<SaisieCellule> cellules, boolean aplatir) {}

    /** The headcount typed under one créneau; {@code null} or absent means closed. */
    @Schema(requiredProperties = {"creneauId"})
    public record SaisieCellule(long creneauId, Integer effectif) {}

    /** What the conversion did to one stand — the compaction's own line, plus the bounds it derived. */
    @Schema(requiredProperties = {"compacte", "effectifMax", "effectifMin", "exceptions", "regles"})
    public record LigneGrille(
            String standId,
            int regles,
            int exceptions,
            int effectifMin,
            int effectifMax,
            boolean compacte,
            String raison) {}

    /**
     * Rewrites {@code stand}'s schedule from {@code cellules}, in place, a
     * partial cell saved unchanged keeping its segments.
     *
     * @throws BusinessError.Invalid on a créneau id the edition does not have, or a headcount below one
     */
    public static LigneGrille apply(Stand stand, List<Creneau> creneaux, List<SaisieCellule> cellules) {
        return apply(stand, creneaux, cellules, false);
    }

    /**
     * Rewrites {@code stand}'s schedule from {@code cellules}, in place.
     *
     * @param aplatir {@code true} to write every cell onto its whole créneau at
     *                the typed headcount, even one the server reported partial
     *                and whose value did not change
     * @throws BusinessError.Invalid on a créneau id the edition does not have, or a headcount below one
     */
    public static LigneGrille apply(
            Stand stand, List<Creneau> creneaux, List<SaisieCellule> cellules, boolean aplatir) {
        Map<Long, Integer> parCreneau = new HashMap<>();
        Map<Long, Creneau> connus = new HashMap<>();
        for (Creneau creneau : creneaux) {
            if (creneau.getId() != null
                    && creneau.getDate() != null
                    && creneau.getHeureDebut() != null
                    && creneau.getHeureFin() != null) {
                connus.put(creneau.getId(), creneau);
            }
        }
        for (SaisieCellule cellule : cellules) {
            if (!connus.containsKey(cellule.creneauId())) {
                throw new BusinessError.Invalid(
                        "Créneau inconnu dans la grille du stand " + stand.getId() + " : " + cellule.creneauId());
            }
            if (cellule.effectif() != null && cellule.effectif() < 1) {
                throw new BusinessError.Invalid("Effectif " + cellule.effectif() + " sur le stand " + stand.getId()
                        + " : laissez la case vide pour fermer le stand sur ce créneau");
            }
            if (cellule.effectif() != null) {
                parCreneau.put(cellule.creneauId(), cellule.effectif());
            }
        }

        // What each cell becomes, in minutes from its day's midnight: the whole
        // créneau at the typed headcount, or — for a partial cell whose value
        // did not change — the segments the stand already had there. Read
        // before the rewrite below empties the effective windows.
        Map<Long, List<int[]>> segmentsParCreneau = new HashMap<>();
        for (Creneau creneau : connus.values()) {
            Integer effectif = parCreneau.get(creneau.getId());
            if (effectif == null) {
                continue;
            }
            int debut = creneau.getHeureDebut().toSecondOfDay() / 60;
            List<Creneau.SegmentOuvert> actuels = aplatir ? List.of() : creneau.segmentsOuverts(stand);
            if (conserve(creneau, actuels, effectif)) {
                segmentsParCreneau.put(
                        creneau.getId(),
                        actuels.stream()
                                .map(segment -> new int[] {
                                    debut + segment.debutMinutes(), debut + segment.finMinutes(), segment.effectif()
                                })
                                .toList());
            } else {
                segmentsParCreneau.put(creneau.getId(), List.of(new int[] {debut, finMinutes(creneau), effectif}));
            }
        }

        IntSummaryStatistics effectifs = segmentsParCreneau.values().stream()
                .flatMap(List::stream)
                .mapToInt(segment -> segment[2])
                .summaryStatistics();
        int effectifMin = effectifs.getCount() > 0 ? effectifs.getMin() : stand.getEffectifMin();
        int effectifMax = effectifs.getCount() > 0 ? effectifs.getMax() : stand.getEffectifMax();
        stand.setEffectifMin(effectifMin);
        stand.setEffectifMax(effectifMax);

        Map<LocalDate, List<Creneau>> parJour = new TreeMap<>();
        connus.values()
                .forEach(creneau -> parJour.computeIfAbsent(creneau.getDate(), key -> new ArrayList<>())
                        .add(creneau));
        List<OuvertureStand> ouvertures = new ArrayList<>();
        List<IndisponibiliteStand> fermetures = new ArrayList<>();
        parJour.forEach((date, duJour) -> {
            List<Fenetre> fenetres = dayWindows(duJour, segmentsParCreneau);
            if (fenetres.isEmpty()) {
                // Nothing stated about a day means open all day: a closed day
                // must say so.
                fermetures.add(new IndisponibiliteStand(null, date, LocalTime.MIDNIGHT, null, null));
                return;
            }
            for (Fenetre fenetre : fenetres) {
                Integer effectif = fenetre.effectif == effectifMin ? null : fenetre.effectif;
                ouvertures.add(new OuvertureStand(null, date, fenetre.debut, fenetre.fin, null, effectif));
            }
        });
        stand.setHoraires(new ArrayList<>());
        stand.setOuvertures(ouvertures);
        stand.setIndisponibilites(fermetures);
        stand.setFenetresEffectives(null, null);

        HoraireCompaction.LigneCompactage compactage = HoraireCompaction.compact(List.of(stand), creneaux, true)
                .stands()
                .get(0);
        return new LigneGrille(
                stand.getId(),
                stand.getHoraires().size(),
                stand.getOuvertures().size() + stand.getIndisponibilites().size(),
                effectifMin,
                effectifMax,
                compactage.compacte(),
                compactage.raison());
    }

    /**
     * Whether a typed cell leaves the stand's current segments alone: the cell
     * is partial — open on part of the créneau, or at more than one headcount
     * — and the value typed is the one the grid showed for it, its highest
     * headcount. Typing that same number back is not a decision to flatten;
     * {@code aplatir} is.
     */
    private static boolean conserve(Creneau creneau, List<Creneau.SegmentOuvert> actuels, int effectif) {
        if (actuels.isEmpty()) {
            return false;
        }
        boolean entier = actuels.size() == 1
                && actuels.get(0).debutMinutes() == 0
                && actuels.get(0).finMinutes() == creneau.getDureeMinutes();
        int affiche =
                actuels.stream().mapToInt(Creneau.SegmentOuvert::effectif).max().orElse(0);
        return !entier && affiche == effectif;
    }

    /** A run of créneaux open at one headcount; {@code fin} is {@code null} for "until closing". */
    private record Fenetre(LocalTime debut, LocalTime fin, int effectif) {}

    /**
     * The open windows of one day: consecutive segments at the same headcount
     * merge into one window, a change of headcount cuts a new one — exactly
     * where the workbook cuts its own. A window reaching the day's last end,
     * midnight included, is left open-ended: that is how one rule serves a day
     * closing at 20:00 and a day closing at midnight alike.
     */
    private static List<Fenetre> dayWindows(List<Creneau> duJour, Map<Long, List<int[]>> segmentsParCreneau) {
        List<Creneau> tries = new ArrayList<>(duJour);
        tries.sort(Comparator.comparing(Creneau::getHeureDebut).thenComparing(Creneau::getId));
        int finJournee =
                tries.stream().mapToInt(GrilleHorairesStands::finMinutes).max().orElse(0);
        List<int[]> courses = new ArrayList<>();
        for (Creneau creneau : tries) {
            for (int[] segment : segmentsParCreneau.getOrDefault(creneau.getId(), List.of())) {
                int[] derniere = courses.isEmpty() ? null : courses.get(courses.size() - 1);
                if (derniere != null && derniere[2] == segment[2] && segment[0] <= derniere[1]) {
                    derniere[1] = Math.max(derniere[1], segment[1]);
                } else {
                    courses.add(new int[] {segment[0], segment[1], segment[2]});
                }
            }
        }
        List<Fenetre> fenetres = new ArrayList<>();
        for (int[] course : courses) {
            LocalTime debut = LocalTime.ofSecondOfDay(course[0] * 60L);
            LocalTime fin =
                    course[1] >= finJournee || course[1] >= 24 * 60 ? null : LocalTime.ofSecondOfDay(course[1] * 60L);
            fenetres.add(new Fenetre(debut, fin, course[2]));
        }
        return fenetres;
    }

    /** A créneau's end in minutes since its day's midnight; {@code 00:00} counts as 24:00. */
    private static int finMinutes(Creneau creneau) {
        int debut = creneau.getHeureDebut().toSecondOfDay() / 60;
        int fin = creneau.getHeureFin().toSecondOfDay() / 60;
        return fin > debut ? fin : fin + 24 * 60;
    }
}
