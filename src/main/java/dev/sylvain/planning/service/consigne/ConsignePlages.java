package dev.sylvain.planning.service.consigne;

import dev.sylvain.planning.domain.ConsigneEdition;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlageStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.referentiel.HoraireStandResolver;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The plages a consigne writes on each stand of its date (ADR 0125), marked
 * {@link PlageStand#isConsigne()} so that lifting it removes exactly them.
 * There is no layer above the plages any more: what the consigne does to a
 * stand is these rows, read by {@link HoraireStandResolver} like any other.
 *
 * <p>The three rules of ADR 0043 hold, written instead of applied:</p>
 * <ol>
 * <li><b>the band closes every stand</b>: a closure over the band, on every
 * stand;</li>
 * <li><b>the créneaux the consigne added</b> belong to nobody's usual hours: a
 * closure over each of them;</li>
 * <li><b>an opening is an extension</b>: an opening per window, outside the
 * band, at the headcount typed on it, or else the highest headcount of the
 * stretches the band takes from the stand, or else its minimum.</li>
 * </ol>
 *
 * <p>The resolver reads these plages above the stand's own: the closures take
 * their stretch out of the stand's own hours and never out of the consigne's
 * openings, which are added whatever the stand's own plages say there — a stand that closes at 17h every day still
 * reopens 18h-22h when the consigne says so.</p>
 *
 * <p>The headcount a window inherits is computed when the consigne is laid
 * and written on the plage: laying the consigne again recomputes it. Pure and
 * static, unit-testable on plain objects.</p>
 */
public final class ConsignePlages {

    /** Reason carried by every plage a consigne writes. */
    public static final String MOTIF_CONSIGNE = "Consigne d'édition";

    private static final int MINUTES_PAR_JOUR = 24 * 60;

    private ConsignePlages() {}

    /**
     * The plages {@code consigne} writes on each of {@code stands}, by stand id. The stands are read on
     * their own hours (the plages a consigne wrote left out); {@code creneaux} gives the hours of the
     * créneaux the consigne added — the grid is what says when a created vacation runs.
     */
    public static Map<String, List<PlageStand>> of(
            Collection<Stand> stands, ConsigneEdition consigne, Collection<Creneau> creneaux) {
        List<int[]> ajoutees = addedStretches(consigne, byId(creneaux));
        Map<String, List<PlageStand>> parStand = new LinkedHashMap<>();
        for (Stand stand : stands) {
            Stand propre = ownHoursOn(stand, Set.of(consigne.date()));
            parStand.put(stand.getId(), of(propre, consigne, ajoutees));
        }
        return parStand;
    }

    /**
     * Lays {@code consignes} on the stands' effective plages only, as if they had been written — what the
     * previews and the indicators read before (or without) any write. The stored lists are not touched,
     * and a stand's plages from an earlier laying of the same dates are left out first.
     */
    public static void apply(
            Collection<Stand> stands, Collection<ConsigneEdition> consignes, Collection<Creneau> creneaux) {
        if (stands == null || consignes == null || consignes.isEmpty()) {
            return;
        }
        Map<Long, Creneau> parId = byId(creneaux);
        Set<LocalDate> dates = new HashSet<>();
        consignes.forEach(consigne -> dates.add(consigne.date()));
        for (Stand stand : stands) {
            Stand propre = ownHoursOn(stand, dates);
            List<PlageStand> plages = new ArrayList<>(propre.getPlages());
            for (ConsigneEdition consigne : consignes) {
                plages.addAll(of(propre, consigne, addedStretches(consigne, parId)));
            }
            List<PlageStand> effectives = HoraireStandResolver.effective(plages);
            stand.setPlagesEffectives(effectives != null ? effectives : plages);
        }
    }

    /**
     * A copy of {@code stand} without the plages a consigne wrote on {@code dates}, resolved: the day a
     * consigne of those dates departs from.
     */
    static Stand ownHoursOn(Stand stand, Set<LocalDate> dates) {
        Stand propre = new Stand(
                stand.getId(),
                stand.getNom(),
                stand.getTypologiesProposees(),
                stand.getEffectifMin(),
                stand.getEffectifMax(),
                stand.isReserveMajeurs());
        propre.setPlages(new ArrayList<>(stand.getPlages().stream()
                .filter(plage -> !(plage.isConsigne() && dates.contains(plage.getDate())))
                .toList()));
        HoraireStandResolver.apply(propre, dates);
        return propre;
    }

    /** One stand, one consigne; {@code propre} resolved on its own hours, {@code ajoutees} in minutes of the day. */
    static List<PlageStand> of(Stand propre, ConsigneEdition consigne, List<int[]> ajoutees) {
        LocalDate date = consigne.date();
        int[] bande = minutes(consigne.fermetureDebut(), consigne.fermetureFin());
        List<PlageStand> plages = new ArrayList<>();
        plages.add(marked(PlageStand.fermeture(
                null,
                date,
                consigne.fermetureDebut(),
                ConsigneEdition.openEnd(consigne.fermetureFin()),
                MOTIF_CONSIGNE)));

        List<ConsigneEdition.Ouverture> fenetres = consigne.openingsOf(propre.getId()).stream()
                .filter(ouverture -> ouverture.debut() != null)
                .toList();
        // Above the stand's own hours, a closure only carves those: the windows still open over it.
        for (int[] ajoutee : ajoutees) {
            plages.add(marked(PlageStand.fermeture(
                    null,
                    date,
                    HoraireStandResolver.time(ajoutee[0]),
                    HoraireStandResolver.endOrNull(ajoutee[1]),
                    MOTIF_CONSIGNE)));
        }
        List<Segment> nominale = journeeNominale(propre, date);
        Integer herite = inheritedHeadcount(propre, nominale, bande);
        if (herite != null && herite == propre.getEffectifMin()) {
            herite = null; // the stand's minimum, which an opening without a headcount already reads
        }
        for (ConsigneEdition.Ouverture ouverture : fenetres) {
            Integer effectif = ouverture.effectif() != null ? ouverture.effectif() : herite;
            // Never inside the band: the window is written as what it leaves outside it.
            for (int[] morceau : soustraire(minutes(ouverture.debut(), ouverture.fin()), List.of(bande))) {
                plages.add(marked(PlageStand.ouverture(
                        null,
                        date,
                        HoraireStandResolver.time(morceau[0]),
                        HoraireStandResolver.endOrNull(morceau[1]),
                        MOTIF_CONSIGNE,
                        effectif)));
            }
        }
        return plages;
    }

    private static PlageStand marked(PlageStand plage) {
        plage.setConsigne(true);
        return plage;
    }

    private static Map<Long, Creneau> byId(Collection<Creneau> creneaux) {
        Map<Long, Creneau> parId = new HashMap<>();
        if (creneaux != null) {
            for (Creneau creneau : creneaux) {
                if (creneau.getId() != null) {
                    parId.put(creneau.getId(), creneau);
                }
            }
        }
        return parId;
    }

    /** The stretches the consigne's added créneaux cover on its date, in minutes. */
    static List<int[]> addedStretches(ConsigneEdition consigne, Map<Long, Creneau> creneauxParId) {
        List<int[]> plages = new ArrayList<>();
        for (Long id : consigne.creneauxAjoutes()) {
            Creneau creneau = creneauxParId.get(id);
            if (creneau == null
                    || !consigne.date().equals(creneau.getDate())
                    || creneau.getHeureDebut() == null
                    || creneau.getHeureFin() == null) {
                continue;
            }
            int debut = creneau.getHeureDebut().toSecondOfDay() / 60;
            int fin = creneau.getHeureFin().toSecondOfDay() / 60;
            if (fin <= debut) {
                fin = MINUTES_PAR_JOUR; // crosses midnight: the part on this date runs to it
            }
            plages.add(new int[] {debut, fin});
        }
        return plages;
    }

    /** The headcount to inherit: the highest one the band takes away, {@code null} when it takes none. */
    private static Integer inheritedHeadcount(Stand stand, List<Segment> nominal, int[] bande) {
        Integer herite = null;
        for (Segment segment : nominal) {
            if (chevauchent(segment.bornes(), bande)) {
                int effectif = segment.effectif() != null ? segment.effectif() : stand.getEffectifMin();
                herite = herite == null ? effectif : Math.max(herite, effectif);
            }
        }
        return herite;
    }

    /** One open stretch of a day, {@code [debut, fin)} in minutes, and its headcount ({@code null} = minimum). */
    record Segment(int[] bornes, Integer effectif) {

        @Override
        public boolean equals(Object other) {
            return other instanceof Segment(int[] autresBornes, Integer autreEffectif)
                    && Arrays.equals(bornes, autresBornes)
                    && Objects.equals(effectif, autreEffectif);
        }

        @Override
        public int hashCode() {
            return 31 * Arrays.hashCode(bornes) + Objects.hashCode(effectif);
        }

        @Override
        public String toString() {
            return "Segment[bornes=" + Arrays.toString(bornes) + ", effectif=" + effectif + "]";
        }
    }

    /**
     * The stand's resolved day, as open segments: the opening windows when the day is
     * closed-by-default, the whole day minus the closures otherwise (see {@link PlageStand}).
     */
    static List<Segment> journeeNominale(Stand stand, LocalDate date) {
        List<Segment> segments = new ArrayList<>();
        boolean modeOuverture = false;
        for (PlageStand ouverture : stand.getOuverturesEffectives()) {
            if (date.equals(ouverture.getDate()) && ouverture.hasValidRange()) {
                modeOuverture = true;
                segments.add(new Segment(
                        minutes(ouverture.getHeureDebut(), ouverture.getHeureFin()), ouverture.getEffectif()));
            }
        }
        if (modeOuverture) {
            return segments;
        }
        List<int[]> fermes = stand.getIndisponibilitesEffectives().stream()
                .filter(fermeture -> date.equals(fermeture.getDate()) && fermeture.hasValidRange())
                .map(fermeture -> minutes(fermeture.getHeureDebut(), fermeture.getHeureFin()))
                .toList();
        segments.addAll(soustraire(new int[] {0, MINUTES_PAR_JOUR}, fermes).stream()
                .map(reste -> new Segment(reste, null))
                .toList());
        return segments;
    }

    /** {@code [debut, fin)} in minutes of the day; a {@code null} end, or {@code 00:00} as an end, is midnight. */
    static int[] minutes(LocalTime debut, LocalTime fin) {
        return HoraireStandResolver.minutes(debut, fin);
    }

    static boolean chevauchent(int[] a, int[] b) {
        return HoraireStandResolver.overlap(a, b);
    }

    /** {@code fenetre} minus every band: the stretches left open, in order. */
    static List<int[]> soustraire(int[] fenetre, List<int[]> bandes) {
        return HoraireStandResolver.subtract(fenetre, bandes);
    }
}
