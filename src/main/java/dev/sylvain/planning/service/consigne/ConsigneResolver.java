package dev.sylvain.planning.service.consigne;

import dev.sylvain.planning.domain.ConsigneEdition;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.referentiel.HoraireStandResolver;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The fourth layer of a stand's schedule: the edition's consignes, applied on
 * top of whatever {@link HoraireStandResolver} resolved (issue #4).
 *
 * <p>Nothing here touches the grid. A stand closed on a whole créneau simply
 * generates no seat on it, and a stand closed on part of one generates seats
 * for the remainder, carrying effective hours — {@code ProblemBuilder} already
 * does both. So the créneaux of the nominal day keep their ids, their seats
 * and their locks, and lifting the consigne is just not applying it.</p>
 *
 * <p>Three rules, in this order, for every stand on a date under consigne:</p>
 * <ol>
 * <li><b>the band closes every stand</b>, above its rules and its dated
 * exceptions — it is the arrêté — and it is all it closes: the stand keeps its
 * own hours outside it, so a 14h-20h afternoon becomes 18h-20h whether the
 * consigne opens the stand or not;</li>
 * <li><b>the créneaux the consigne added</b> to the grid belong to nobody's
 * usual hours: a stand is shut on them unless one of its own consigne windows
 * covers them — without this, a stand open by default would be staffed on an
 * evening nobody chose it for;</li>
 * <li><b>an opening is an extension</b>, chosen stand by stand: the stand
 * opens on its windows in addition to its own hours, never inside the band,
 * each window at the headcount typed on it, or else the highest headcount of
 * the windows the stand lost to the band, or else its minimum. Where a window
 * overlaps hours the stand already had, the higher headcount applies
 * ({@code ProfilOuverture}).</li>
 * </ol>
 *
 * <p>The day comes out stated as explicit openings — closed-by-default, in the
 * three-state vocabulary of {@link OuvertureStand} — or as a whole-day closure
 * when nothing is left open. The result lands on
 * {@link Stand#setFenetresEffectives} only, like the resolver's: the persisted
 * lists are never written. Pure and static, unit-testable on plain objects.</p>
 */
public final class ConsigneResolver {

    /** Reason carried by every window this layer adds. */
    public static final String MOTIF_CONSIGNE = "Consigne d'édition";

    private static final int MINUTES_PAR_JOUR = 24 * 60;

    private ConsigneResolver() {}

    /**
     * Applies every consigne to every stand, in place on the effective windows.
     * {@code creneaux} is read for the hours of the créneaux the consignes
     * added — the grid is what says when a created vacation runs.
     */
    public static void apply(
            Collection<Stand> stands, Collection<ConsigneEdition> consignes, Collection<Creneau> creneaux) {
        if (stands == null || consignes == null || consignes.isEmpty()) {
            return;
        }
        Map<Long, Creneau> parId = new HashMap<>();
        if (creneaux != null) {
            for (Creneau creneau : creneaux) {
                if (creneau.getId() != null) {
                    parId.put(creneau.getId(), creneau);
                }
            }
        }
        for (ConsigneEdition consigne : consignes) {
            List<int[]> plagesAjoutees = plagesAjoutees(consigne, parId);
            for (Stand stand : stands) {
                apply(stand, consigne, plagesAjoutees);
            }
        }
    }

    /** The stretches the consigne's added créneaux cover on its date, in minutes. */
    static List<int[]> plagesAjoutees(ConsigneEdition consigne, Map<Long, Creneau> creneauxParId) {
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

    /** One stand, one consigne. Package-private for the tests; {@code plagesAjoutees} in minutes of the day. */
    static void apply(Stand stand, ConsigneEdition consigne, List<int[]> plagesAjoutees) {
        LocalDate date = consigne.date();
        int[] bande = minutes(consigne.fermetureDebut(), consigne.fermetureFin());

        // 1. The day as the stand's own rules and exceptions leave it, as
        //    segments carrying a headcount (null = the stand's minimum).
        List<Segment> nominal = journeeNominale(stand, date);
        Integer herite = inheritedHeadcount(stand, nominal, bande);

        // 2. The band, then the added stretches, carved out of the stand's own hours.
        List<int[]> fermes = new ArrayList<>();
        fermes.add(bande);
        fermes.addAll(plagesAjoutees);
        List<Segment> restes = new ArrayList<>();
        for (Segment segment : nominal) {
            for (int[] reste : soustraire(segment.bornes(), fermes)) {
                restes.add(new Segment(reste, segment.effectif()));
            }
        }

        // 3. The chosen windows, outside the band, each at its own headcount —
        //    typed on that window, else inherited. A morning at 2 and an
        //    evening at 7 are two different windows of one stand.
        for (ConsigneEdition.Ouverture ouverture : consigne.openingsOf(stand.getId())) {
            if (ouverture.debut() != null) {
                Integer effectif = ouverture.effectif() != null ? ouverture.effectif() : herite;
                for (int[] morceau : soustraire(minutes(ouverture.debut(), ouverture.fin()), List.of(bande))) {
                    restes.add(new Segment(morceau, effectif));
                }
            }
        }

        land(stand, date, restes);
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

    /** Lands the day: every dated row of that date is replaced by what is left. */
    private static void land(Stand stand, LocalDate date, List<Segment> restes) {
        List<IndisponibiliteStand> fermetures = new ArrayList<>();
        for (IndisponibiliteStand fermeture : stand.getIndisponibilitesEffectives()) {
            if (!date.equals(fermeture.getDate())) {
                fermetures.add(fermeture);
            }
        }
        List<OuvertureStand> ouvertures = new ArrayList<>();
        for (OuvertureStand ouverture : stand.getOuverturesEffectives()) {
            if (!date.equals(ouverture.getDate())) {
                ouvertures.add(ouverture);
            }
        }
        if (restes.isEmpty()) {
            fermetures.add(new IndisponibiliteStand(null, date, LocalTime.MIDNIGHT, null, MOTIF_CONSIGNE));
        }
        for (Segment reste : restes) {
            ouvertures.add(new OuvertureStand(
                    null,
                    date,
                    heure(reste.bornes()[0]),
                    endOrNull(reste.bornes()[1]),
                    MOTIF_CONSIGNE,
                    reste.effectif()));
        }
        stand.setFenetresEffectives(fermetures, ouvertures);
    }

    /** One open stretch of a day, {@code [debut, fin)} in minutes, and its headcount ({@code null} = minimum). */
    record Segment(int[] bornes, Integer effectif) {

        @Override
        public boolean equals(Object other) {
            return other instanceof Segment segment
                    && Arrays.equals(bornes, segment.bornes)
                    && Objects.equals(effectif, segment.effectif);
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
     * The stand's day before the consigne, as open segments: the opening
     * windows when the day is closed-by-default, the whole day minus the
     * closures otherwise (see {@link OuvertureStand} for the three states).
     */
    static List<Segment> journeeNominale(Stand stand, LocalDate date) {
        List<Segment> segments = new ArrayList<>();
        boolean modeOuverture = false;
        for (OuvertureStand ouverture : stand.getOuverturesEffectives()) {
            if (date.equals(ouverture.getDate()) && ouverture.hasValidRange()) {
                modeOuverture = true;
                segments.add(new Segment(
                        minutes(ouverture.getHeureDebut(), ouverture.getHeureFin()), ouverture.getEffectif()));
            }
        }
        if (modeOuverture) {
            return segments;
        }
        List<int[]> fermes = new ArrayList<>();
        for (IndisponibiliteStand fermeture : stand.getIndisponibilitesEffectives()) {
            if (date.equals(fermeture.getDate()) && fermeture.hasValidRange()) {
                fermes.add(minutes(fermeture.getHeureDebut(), fermeture.getHeureFin()));
            }
        }
        for (int[] reste : soustraire(new int[] {0, MINUTES_PAR_JOUR}, fermes)) {
            segments.add(new Segment(reste, null));
        }
        return segments;
    }

    /** {@code [debut, fin)} in minutes of the day; a {@code null} end, or {@code 00:00} as an end, is midnight. */
    static int[] minutes(LocalTime debut, LocalTime fin) {
        int d = debut.toSecondOfDay() / 60;
        int f = fin == null ? MINUTES_PAR_JOUR : fin.toSecondOfDay() / 60;
        if (f == 0) {
            f = MINUTES_PAR_JOUR;
        }
        return new int[] {d, f};
    }

    private static LocalTime heure(int minutes) {
        return LocalTime.ofSecondOfDay((minutes % MINUTES_PAR_JOUR) * 60L);
    }

    /** Midnight as an end is written the way the resolver writes it: {@code null}, « until closing ». */
    private static LocalTime endOrNull(int minutes) {
        return minutes >= MINUTES_PAR_JOUR ? null : heure(minutes);
    }

    static boolean chevauchent(int[] a, int[] b) {
        return a[0] < b[1] && b[0] < a[1];
    }

    /** {@code fenetre} minus every band: the stretches left open, in order. */
    static List<int[]> soustraire(int[] fenetre, List<int[]> bandes) {
        List<int[]> restes = new ArrayList<>();
        restes.add(fenetre);
        for (int[] bande : bandes) {
            List<int[]> suivants = new ArrayList<>();
            for (int[] reste : restes) {
                if (!chevauchent(reste, bande)) {
                    suivants.add(reste);
                    continue;
                }
                if (reste[0] < bande[0]) {
                    suivants.add(new int[] {reste[0], bande[0]});
                }
                if (bande[1] < reste[1]) {
                    suivants.add(new int[] {bande[1], reste[1]});
                }
            }
            restes = suivants;
        }
        return restes;
    }
}
