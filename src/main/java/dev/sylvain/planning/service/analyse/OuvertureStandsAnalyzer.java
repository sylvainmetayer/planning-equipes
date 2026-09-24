package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.referentiel.HoraireStandResolver;
import dev.sylvain.planning.service.referentiel.HoraireStandResolver.SourceHoraire;
import dev.sylvain.planning.service.solve.ProblemBuilder;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Read-only, stand × jour view of when each stand is <b>actually</b> open, for an
 * administrator to eyeball before launching a solve.
 *
 * <p>The point is validation, so nothing here re-derives the schedule its own
 * way: the cells are built from the very {@link PosteAffectation}s
 * {@link ProblemBuilder#buildPostes} would hand the solver. Whatever the
 * admin sees is therefore, by construction, what the solver gets — recurring
 * horaires expanded, dated exceptions applied, windows clamped to each
 * créneau, relay-grid families included.</p>
 *
 * <p>It also flags the three ways an opening schedule usually goes wrong in
 * practice — a stand nobody can ever staff, a window that lands outside every
 * créneau, an open stretch too short to be a real working slot. Those are what
 * turn a pretty grid into something worth reading. Pure and static, so they are
 * unit-testable without a database.</p>
 *
 * <p>Three more, for information only, describe how a stand's rules are
 * written rather than what the solver gets: rules merged on a same day whose
 * windows overlap, a rule no day ever reads, windows of one rule overlapping at
 * different headcounts ({@link HoraireRuleOverlaps}). The resolver settles all
 * three deterministically; the report is where they stop being silent.</p>
 */
public final class OuvertureStandsAnalyzer {

    private static final int MINUTES_PER_DAY = 24 * 60;

    private OuvertureStandsAnalyzer() {}

    /** How much of a day's amplitude a stand covers. */
    public enum EtatOuverture {
        /** Open over the day's whole amplitude — the default for a stand with no schedule. */
        OUVERT_TOTAL,
        /** Open over part of it only. */
        OUVERT_PARTIEL,
        /** Not open at all that day. */
        FERME
    }

    public enum AnomalyType {
        /** The stand generates no poste at all: nobody will ever be scheduled on it. */
        STAND_JAMAIS_OUVERT,
        /** A window that overlaps no créneau of its date, so it changes nothing — usually a typo. */
        FENETRE_SANS_EFFET,
        /** An open stretch too short to be a real working slot: a data-entry artefact. */
        SEGMENT_TROP_COURT,
        /**
         * Two rules of one scope and one mode decide a same day and their windows
         * overlap: the resolver merges them and keeps the highest headcount, so
         * the lower one the operator may think they typed counts for nothing.
         * For information — a background rule plus a peak rule is legitimate.
         */
        REGLES_CHEVAUCHANTES,
        /**
         * A rule covering days of the edition decides none of them: a more
         * specific rule or a dated exception wins on every one. It serves no purpose.
         */
        REGLE_MASQUEE,
        /**
         * Two windows of one rule, or of one day's dated openings, overlap at
         * different headcounts: the highest one is kept on the overlap.
         */
        FENETRES_CHEVAUCHANTES;

        /**
         * The anomalies that describe how the rules are written rather than a
         * schedule the solver would misread: they are reported for information,
         * and never hold the edition back on their own.
         */
        public boolean isInformational() {
            return this == REGLES_CHEVAUCHANTES || this == REGLE_MASQUEE || this == FENETRES_CHEVAUCHANTES;
        }
    }

    /**
     * Below this, an open stretch is an artefact rather than a schedule: nobody
     * staffs a stand for a quarter of an hour, whereas the mis-entered windows
     * this flags produce stretches of a few minutes (historically the single
     * minute a closure ending at {@code 23:59} left on a day closing at midnight).
     *
     * <p>Deliberately <b>not</b> a vacation-length setting: the floor the
     * découpage used when it sliced a long amplitude ran to 250 min on some
     * scenarios, and comparing against it flagged every stand legitimately open
     * for two hours. An anomaly that fires on correct data stops being read —
     * which is why this stays a constant of what a stand opening can plausibly
     * be, not a knob.</p>
     */
    public static final int DUREE_MINIMALE_EXPLOITABLE_MINUTES = 15;

    /**
     * One column of the entry grid: a <em>tranche</em> of a créneau — the
     * whole créneau when no stand cuts it, else one stretch between two
     * boundaries some stand's windows draw inside it (4 people from 14:00 to
     * 19:00 then 2 until 20:00 gives the créneau 14-20 two columns, 14-19 and
     * 19-20, for every stand). The columns follow the need, the créneaux stay
     * the solver's; nothing is stored, the boundaries are read off the
     * windows on every report. Ordered by créneau (start time, then id) and
     * tranche. {@code id} is the créneau's, {@code null} for a créneau not
     * written yet — the recurrence preview validates a grid holding the rows a
     * rule <em>would</em> add.
     *
     * @param tranche the column's rank inside its créneau, from 0
     */
    @Schema(requiredProperties = {"couverturePause", "tranche"})
    public record ColonneCreneau(
            Long id, int tranche, LocalTime heureDebut, LocalTime heureFin, boolean couverturePause) {

        /** A créneau in one piece. */
        public ColonneCreneau(Long id, LocalTime heureDebut, LocalTime heureFin, boolean couverturePause) {
            this(id, 0, heureDebut, heureFin, couverturePause);
        }
    }

    /** One event day, the amplitude the cells of that column are measured against, and its créneaux. */
    @Schema(requiredProperties = {"jour", "minutes", "nombreCreneaux"})
    public record JourAmplitude(
            LocalDate date,
            int jour,
            LocalTime heureDebut,
            LocalTime heureFin,
            int minutes,
            int nombreCreneaux,
            List<ColonneCreneau> creneaux) {}

    /** One open stretch of a cell, in wall-clock hours, with the headcount it asks for. */
    @Schema(requiredProperties = {"effectif", "heureDebut", "heureFin"})
    public record SegmentCellule(LocalTime heureDebut, LocalTime heureFin, int effectif) {}

    /**
     * What one stand does on one créneau, as the entry grid shows it: the
     * headcount when the stand is open on the whole créneau at one headcount,
     * {@code null} when closed. {@code partiel} flags a stand open on part of
     * the créneau only, or at a headcount that changes during it — a shape
     * the grid cannot hold in one integer; {@code effectif} is then the
     * highest one, and {@code segments} says what the cell really holds. A
     * save keeps those segments as long as the cell is not retyped
     * ({@link dev.sylvain.planning.service.referentiel.GrilleHorairesStands}).
     */
    /**
     * @param tranche     the column's rank inside its créneau ({@link ColonneCreneau#tranche()})
     * @param segments    the open stretches of the cell, empty when closed;
     *                    one stretch spanning the column when the cell is not
     *                    partial
     */
    @Schema(requiredProperties = {"partiel", "segments", "tranche"})
    public record CelluleCreneau(
            Long creneauId, int tranche, Integer effectif, boolean partiel, List<SegmentCellule> segments) {

        /** A cell of a créneau in one piece, without its stretches. */
        public CelluleCreneau(Long creneauId, Integer effectif, boolean partiel) {
            this(creneauId, 0, effectif, partiel, List.of());
        }
    }

    /** An open stretch, in wall-clock hours, after clamping to the créneaux. */
    public record FenetreEffective(LocalTime heureDebut, LocalTime heureFin) {}

    @Schema(requiredProperties = {"minutesAmplitude", "minutesOuvertes", "postes"})
    public record CelluleJour(
            LocalDate date,
            EtatOuverture etat,
            SourceHoraire source,
            List<FenetreEffective> fenetres,
            int minutesOuvertes,
            int minutesAmplitude,
            int postes,
            List<CelluleCreneau> creneaux) {}

    /**
     * @param modifieLe the stand's stamp as this grid read it, echoed back by
     *                  the save as its precondition (issue #362)
     */
    @Schema(requiredProperties = {"effectifMin", "minutesOuvertes", "postes"})
    public record LigneStand(
            String standId,
            String nom,
            int effectifMin,
            List<CelluleJour> jours,
            int minutesOuvertes,
            int postes,
            Instant modifieLe) {}

    /**
     * @param heureDebut with {@code heureFin}, the window a {@link AnomalyType#FENETRE_SANS_EFFET} names —
     *                   so the day timeline can draw it where it falls; {@code null} on the other types
     * @param heureFin   {@code null} on an open-ended window (« jusqu'à la fermeture »)
     * @param horaireId  the rule a {@link AnomalyType#REGLE_MASQUEE}, {@link AnomalyType#REGLES_CHEVAUCHANTES}
     *                   (the later of the two) or {@link AnomalyType#FENETRES_CHEVAUCHANTES} is about, so a
     *                   screen can point at its line; {@code null} on the other types, on dated openings
     *                   and on a rule not saved yet
     */
    public record Anomaly(
            AnomalyType type,
            String standId,
            String standNom,
            LocalDate date,
            LocalTime heureDebut,
            LocalTime heureFin,
            String message,
            Long horaireId) {

        public Anomaly(AnomalyType type, String standId, String standNom, LocalDate date, String message) {
            this(type, standId, standNom, date, null, null, message, null);
        }

        public Anomaly(
                AnomalyType type,
                String standId,
                String standNom,
                LocalDate date,
                LocalTime heureDebut,
                LocalTime heureFin,
                String message) {
            this(type, standId, standNom, date, heureDebut, heureFin, message, null);
        }
    }

    @Schema(requiredProperties = {"postesTotal", "standsJamaisOuverts"})
    public record RapportOuvertures(
            List<JourAmplitude> jours,
            List<LigneStand> stands,
            int standsJamaisOuverts,
            int postesTotal,
            List<Anomaly> anomalies) {}

    /**
     * Builds the report. {@code stands} must already be resolved
     * ({@code listSolvedStands}); {@code creneaux} are the active group's, the
     * ones a solve would actually run on.
     */
    public static RapportOuvertures analyze(List<Stand> stands, List<Creneau> creneaux) {
        Map<LocalDate, List<Creneau>> creneauxParJour = creneauxByDay(creneaux);
        List<JourAmplitude> jours = new ArrayList<>();
        Map<LocalDate, Integer> amplitudeParJour = new LinkedHashMap<>();
        Map<LocalDate, List<TrancheCreneau>> colonnesParJour = new LinkedHashMap<>();
        creneauxParJour.forEach((date, duJour) -> {
            List<int[]> couverture = merge(
                    duJour.stream().map(OuvertureStandsAnalyzer::intervalle).toList());
            int minutes =
                    couverture.stream().mapToInt(borne -> borne[1] - borne[0]).sum();
            amplitudeParJour.put(date, minutes);
            int debut = couverture.get(0)[0];
            int fin = couverture.get(couverture.size() - 1)[1];
            // Same order as the day's list, which creneauxByDay sorted, each
            // créneau cut into its tranches: the cells of a row are read back
            // by position.
            List<TrancheCreneau> colonnes = new ArrayList<>();
            for (Creneau creneau : duJour) {
                colonnes.addAll(columnsOf(creneau, stands));
            }
            colonnesParJour.put(date, colonnes);
            jours.add(new JourAmplitude(
                    date,
                    duJour.get(0).getJour(),
                    minuteToTime(debut),
                    minuteToTime(fin),
                    minutes,
                    duJour.size(),
                    colonnes.stream().map(TrancheCreneau::colonne).toList()));
        });

        // The seats the solver would receive, grouped by stand then by day:
        // this is the single source of truth of that screen.
        Map<String, Map<LocalDate, List<PosteAffectation>>> postesParStandEtJour = new LinkedHashMap<>();
        for (PosteAffectation poste : ProblemBuilder.buildPostes(stands, creneaux)) {
            if (poste.getStand() == null
                    || poste.getCreneau() == null
                    || poste.getCreneau().getDate() == null) {
                continue;
            }
            postesParStandEtJour
                    .computeIfAbsent(poste.getStand().getId(), key -> new TreeMap<>())
                    .computeIfAbsent(poste.getCreneau().getDate(), key -> new ArrayList<>())
                    .add(poste);
        }

        List<LigneStand> lignes = new ArrayList<>();
        List<Anomaly> anomalies = new ArrayList<>();
        int postesTotal = 0;
        int jamaisOuverts = 0;
        List<HoraireRuleOverlaps.EventDay> eventDays = eventDays(creneaux);
        for (Stand stand : stands) {
            Map<LocalDate, List<PosteAffectation>> parJour = postesParStandEtJour.getOrDefault(stand.getId(), Map.of());
            List<CelluleJour> cellules = new ArrayList<>();
            int minutesStand = 0;
            int postesStand = 0;
            for (JourAmplitude jour : jours) {
                List<PosteAffectation> postes = parJour.getOrDefault(jour.date(), List.of());
                CelluleJour cellule = cellule(
                        stand, jour, postes, amplitudeParJour.get(jour.date()), colonnesParJour.get(jour.date()));
                cellules.add(cellule);
                minutesStand += cellule.minutesOuvertes();
                postesStand += cellule.postes();
                anomalies.addAll(anomaliesOfDay(stand, cellule));
            }
            if (postesStand == 0) {
                jamaisOuverts++;
                anomalies.add(
                        new Anomaly(
                                AnomalyType.STAND_JAMAIS_OUVERT,
                                stand.getId(),
                                stand.getNom(),
                                null,
                                "Le stand n'est ouvert aucun jour du groupe de créneaux actif : aucun poste ne sera à pourvoir."));
            }
            anomalies.addAll(fenetresWithoutEffect(stand, creneauxParJour));
            anomalies.addAll(HoraireAnomalies.of(stand, eventDays));
            postesTotal += postesStand;
            lignes.add(new LigneStand(
                    stand.getId(),
                    stand.getNom(),
                    stand.getEffectifMin(),
                    cellules,
                    minutesStand,
                    postesStand,
                    stand.getModifieLe()));
        }
        anomalies.sort(
                Comparator.comparing((Anomaly anomalie) -> anomalie.type().ordinal())
                        .thenComparing(Anomaly::standId)
                        .thenComparing(anomalie -> anomalie.date() != null ? anomalie.date() : LocalDate.MIN));
        return new RapportOuvertures(jours, lignes, jamaisOuverts, postesTotal, anomalies);
    }

    /**
     * One cell, read off the day's postes: their effective windows merged (a day
     * sliced into overlapping vacations must show one continuous stretch, not one
     * band per vacation), and the poste count as it stands.
     */
    private static CelluleJour cellule(
            Stand stand,
            JourAmplitude jour,
            List<PosteAffectation> postes,
            int minutesAmplitude,
            List<TrancheCreneau> colonnes) {
        SourceHoraire source = HoraireStandResolver.sourceOfDay(stand, jour.date());
        List<CelluleCreneau> parCreneau = new ArrayList<>();
        for (TrancheCreneau colonne : colonnes) {
            parCreneau.add(celluleCreneau(stand, colonne.creneau(), colonne.colonne()));
        }
        if (postes.isEmpty()) {
            return new CelluleJour(
                    jour.date(), EtatOuverture.FERME, source, List.of(), 0, minutesAmplitude, 0, parCreneau);
        }
        List<int[]> fenetres = merge(postes.stream()
                .map(poste -> intervalle(poste.heureDebutEffectif(), poste.heureFinEffectif()))
                .toList());
        int minutesOuvertes =
                fenetres.stream().mapToInt(borne -> borne[1] - borne[0]).sum();
        EtatOuverture etat =
                minutesOuvertes >= minutesAmplitude ? EtatOuverture.OUVERT_TOTAL : EtatOuverture.OUVERT_PARTIEL;
        List<FenetreEffective> effectives = fenetres.stream()
                .map(borne -> new FenetreEffective(minuteToTime(borne[0]), minuteToTime(borne[1])))
                .toList();
        return new CelluleJour(
                jour.date(), etat, source, effectives, minutesOuvertes, minutesAmplitude, postes.size(), parCreneau);
    }

    /** A column with the créneau it is a tranche of: the cells are read off the créneau's segments, clipped to the column. */
    private record TrancheCreneau(Creneau creneau, ColonneCreneau colonne) {}

    /**
     * The columns of one créneau: its tranches, cut wherever a window of a
     * stand starts or ends strictly inside it. Every boundary counts, so a
     * cell is never partial on a stand's own boundaries; a stand
     * with an odd boundary (10:07) cuts the column for everyone, which is the
     * price of showing the need as it is rather than flattening it.
     */
    private static List<TrancheCreneau> columnsOf(Creneau creneau, List<Stand> stands) {
        int duree = creneau.getDureeMinutes();
        TreeSet<Integer> bornes = new TreeSet<>();
        bornes.add(0);
        bornes.add(duree);
        for (Stand stand : stands) {
            for (Creneau.SegmentOuvert segment : creneau.segmentsOuverts(stand)) {
                if (segment.debutMinutes() > 0 && segment.debutMinutes() < duree) {
                    bornes.add(segment.debutMinutes());
                }
                if (segment.finMinutes() > 0 && segment.finMinutes() < duree) {
                    bornes.add(segment.finMinutes());
                }
            }
        }
        int debutCreneau = creneau.getHeureDebut().toSecondOfDay() / 60;
        List<Integer> tries = new ArrayList<>(bornes);
        List<TrancheCreneau> colonnes = new ArrayList<>();
        for (int index = 0; index + 1 < tries.size(); index++) {
            colonnes.add(new TrancheCreneau(
                    creneau,
                    new ColonneCreneau(
                            creneau.getId(),
                            index,
                            minuteToTime(debutCreneau + tries.get(index)),
                            minuteToTime(debutCreneau + tries.get(index + 1)),
                            creneau.isCouverturePause())));
        }
        return colonnes;
    }

    /** A column's bounds in minutes from its créneau's start. */
    public static int[] boundsWithin(Creneau creneau, LocalTime heureDebut, LocalTime heureFin) {
        int debutCreneau = creneau.getHeureDebut().toSecondOfDay() / 60;
        int debut = heureDebut.toSecondOfDay() / 60;
        int fin = heureFin.toSecondOfDay() / 60;
        if (debut < debutCreneau) {
            debut += 24 * 60;
        }
        if (fin <= debut) {
            fin += 24 * 60;
        }
        return new int[] {debut - debutCreneau, fin - debutCreneau};
    }

    /**
     * The grid cell of one column, read off the same open segments seat
     * generation reads ({@link Creneau#segmentsOuverts}), clipped to the
     * column: the configured headcount, not the seats — a break-covering
     * créneau halves the seats, and the organiser types what the stand needs,
     * not what the solver gets.
     */
    private static CelluleCreneau celluleCreneau(Stand stand, Creneau creneau, ColonneCreneau colonne) {
        int[] bornes = boundsWithin(creneau, colonne.heureDebut(), colonne.heureFin());
        int debutCreneau = creneau.getHeureDebut().toSecondOfDay() / 60;
        List<SegmentCellule> lus = new ArrayList<>();
        int effectif = 0;
        int couvert = 0;
        for (Creneau.SegmentOuvert segment : creneau.segmentsOuverts(stand)) {
            int debut = Math.max(bornes[0], segment.debutMinutes());
            int fin = Math.min(bornes[1], segment.finMinutes());
            if (debut >= fin) {
                continue;
            }
            lus.add(new SegmentCellule(
                    minuteToTime(debutCreneau + debut), minuteToTime(debutCreneau + fin), segment.effectif()));
            effectif = Math.max(effectif, segment.effectif());
            couvert += fin - debut;
        }
        if (lus.isEmpty()) {
            return new CelluleCreneau(colonne.id(), colonne.tranche(), null, false, List.of());
        }
        boolean entier = lus.size() == 1 && couvert == bornes[1] - bornes[0];
        return new CelluleCreneau(colonne.id(), colonne.tranche(), effectif, !entier, lus);
    }

    /**
     * An open stretch of a few minutes is the signature of a mis-entered window —
     * historically a closure ending at {@code 23:59} on a day closing at
     * midnight, which left exactly one staffable minute behind.
     */
    private static List<Anomaly> anomaliesOfDay(Stand stand, CelluleJour cellule) {
        if (cellule.fenetres().isEmpty()) {
            return List.of();
        }
        List<Anomaly> anomalies = new ArrayList<>();
        for (FenetreEffective fenetre : cellule.fenetres()) {
            int minutes = intervalle(fenetre.heureDebut(), fenetre.heureFin())[1]
                    - intervalle(fenetre.heureDebut(), fenetre.heureFin())[0];
            if (minutes < DUREE_MINIMALE_EXPLOITABLE_MINUTES) {
                anomalies.add(new Anomaly(
                        AnomalyType.SEGMENT_TROP_COURT,
                        stand.getId(),
                        stand.getNom(),
                        cellule.date(),
                        "Ouvert seulement " + minutes + " min (" + fenetre.heureDebut() + "–"
                                + fenetre.heureFin() + ") : trop court pour être un vrai créneau de travail, "
                                + "la saisie de ce jour est probablement à revoir."));
            }
        }
        return anomalies;
    }

    /**
     * The well-formed créneaux, grouped by date in calendar order, each day's
     * sorted by start time then by id — an unsaved créneau, which the recurrence
     * preview builds without one, last.
     *
     * <p>The sort is here and nowhere else: the columns of a day and the cells
     * of a row are read back by position, so both sides must walk the same
     * list.</p>
     */
    public static Map<LocalDate, List<Creneau>> creneauxByDay(List<Creneau> creneaux) {
        Map<LocalDate, List<Creneau>> creneauxParJour = new TreeMap<>();
        for (Creneau creneau : creneaux) {
            if (creneau.getDate() != null && creneau.getHeureDebut() != null && creneau.getHeureFin() != null) {
                creneauxParJour
                        .computeIfAbsent(creneau.getDate(), key -> new ArrayList<>())
                        .add(creneau);
            }
        }
        Comparator<Creneau> ordreDuJour = Comparator.comparing(Creneau::getHeureDebut)
                .thenComparing(Creneau::getId, Comparator.nullsLast(Comparator.naturalOrder()));
        creneauxParJour.values().forEach(duJour -> duJour.sort(ordreDuJour));
        return creneauxParJour;
    }

    /**
     * Windows that overlap no créneau of their own date: they were entered, they
     * validate, and they change nothing — the classic "j'ai saisi 14 h-16 h sur un
     * jour qui ferme à midi". Read on the effective lists, so a rule that expands
     * onto such a day is caught too. Shared with {@link CoherenceAnalyzer}, which
     * says it at write time rather than on the review screen.
     */
    public static List<Anomaly> fenetresWithoutEffect(Stand stand, Map<LocalDate, List<Creneau>> creneauxParJour) {
        List<Anomaly> anomalies = new ArrayList<>();
        TreeSet<String> dejaVues = new TreeSet<>();
        for (OuvertureStand ouverture : stand.getOuverturesEffectives()) {
            reportIfWithoutEffect(
                    stand,
                    creneauxParJour,
                    anomalies,
                    dejaVues,
                    ouverture.getDate(),
                    ouverture.getHeureDebut(),
                    ouverture.getHeureFin(),
                    "L'ouverture");
        }
        for (IndisponibiliteStand fermeture : stand.getIndisponibilitesEffectives()) {
            reportIfWithoutEffect(
                    stand,
                    creneauxParJour,
                    anomalies,
                    dejaVues,
                    fermeture.getDate(),
                    fermeture.getHeureDebut(),
                    fermeture.getHeureFin(),
                    "La fermeture");
        }
        return anomalies;
    }

    private static void reportIfWithoutEffect(
            Stand stand,
            Map<LocalDate, List<Creneau>> creneauxParJour,
            List<Anomaly> anomalies,
            TreeSet<String> dejaVues,
            LocalDate date,
            LocalTime heureDebut,
            LocalTime heureFin,
            String libelle) {
        if (date == null || heureDebut == null) {
            return;
        }
        // An open-ended window (null heureFin) stops at the end of the timeslot
        // being evaluated: it therefore has an effect as soon as a timeslot
        // starts after its own start, or encloses it.
        List<Creneau> duJour = creneauxParJour.get(date);
        if (duJour == null) {
            // No timeslot that day: the window can only aim at the morning
            // after a timeslot crossing midnight, which is legitimate.
            return;
        }
        int debut = heureDebut.toSecondOfDay() / 60;
        int fin = heureFin != null ? intervalle(heureDebut, heureFin)[1] : 24 * 60;
        boolean utile = duJour.stream().anyMatch(creneau -> {
            int[] borne = intervalle(creneau);
            return debut < borne[1] && borne[0] < fin;
        });
        if (utile) {
            return;
        }
        String key = date + "#" + heureDebut + "#" + heureFin;
        if (!dejaVues.add(key)) {
            return;
        }
        anomalies.add(new Anomaly(
                AnomalyType.FENETRE_SANS_EFFET,
                stand.getId(),
                stand.getNom(),
                date,
                heureDebut,
                heureFin,
                libelle + " de " + heureDebut + " à "
                        + (heureFin != null ? heureFin.toString() : "la fermeture")
                        + " ne recoupe aucun créneau de ce jour : elle ne change rien."));
    }

    /**
     * The edition's days as {@link HoraireRuleOverlaps} reads them: the days
     * the resolver resolves ({@link HoraireStandResolver#datesConcernees}) —
     * every date carrying a timeslot, and the morning after one that crosses
     * midnight — each with the end of its opening span: the latest end among
     * the timeslots of that date, capped at midnight, or where a timeslot of
     * the eve that crossed midnight stops.
     */
    public static List<HoraireRuleOverlaps.EventDay> eventDays(Collection<Creneau> creneaux) {
        List<Creneau> complets = creneaux.stream()
                .filter(creneau ->
                        creneau.getDate() != null && creneau.getHeureDebut() != null && creneau.getHeureFin() != null)
                .toList();
        Map<LocalDate, Integer> ends = new TreeMap<>();
        for (LocalDate date : HoraireStandResolver.datesConcernees(complets)) {
            ends.put(date, 0);
        }
        for (Creneau creneau : complets) {
            int end = intervalle(creneau)[1];
            ends.merge(creneau.getDate(), Math.min(end, MINUTES_PER_DAY), Math::max);
            if (end > MINUTES_PER_DAY) {
                ends.merge(creneau.getDate().plusDays(1), end - MINUTES_PER_DAY, Math::max);
            }
        }
        List<HoraireRuleOverlaps.EventDay> days = new ArrayList<>();
        ends.forEach((date, end) -> days.add(new HoraireRuleOverlaps.EventDay(date, end)));
        return days;
    }

    /** A créneau as {@code [debut, fin]} minutes from its start day's midnight; midnight-crossing ends past 24 h. */
    private static int[] intervalle(Creneau creneau) {
        return intervalle(creneau.getHeureDebut(), creneau.getHeureFin());
    }

    private static int[] intervalle(LocalTime heureDebut, LocalTime heureFin) {
        int debut = heureDebut.toSecondOfDay() / 60;
        int fin = heureFin.toSecondOfDay() / 60;
        return new int[] {debut, fin > debut ? fin : fin + 24 * 60};
    }

    /** Merges overlapping or touching minute intervals, sorted. */
    private static List<int[]> merge(List<int[]> intervalles) {
        List<int[]> tries = new ArrayList<>(intervalles);
        tries.sort(Comparator.comparingInt(borne -> borne[0]));
        List<int[]> fusionnes = new ArrayList<>();
        for (int[] borne : tries) {
            if (!fusionnes.isEmpty() && borne[0] <= fusionnes.get(fusionnes.size() - 1)[1]) {
                int[] dernier = fusionnes.get(fusionnes.size() - 1);
                dernier[1] = Math.max(dernier[1], borne[1]);
            } else {
                fusionnes.add(borne.clone());
            }
        }
        return fusionnes;
    }

    /** Minutes since midnight back to a wall-clock time; {@code 24:00} wraps to {@code 00:00}. */
    private static LocalTime minuteToTime(int minutes) {
        return LocalTime.ofSecondOfDay((minutes % (24 * 60)) * 60L);
    }
}
