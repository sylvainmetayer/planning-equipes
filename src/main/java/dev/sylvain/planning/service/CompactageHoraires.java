package dev.sylvain.planning.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeJoursHoraire;

/**
 * Turns a stand's hand-entered dated windows back into the recurring
 * {@link HoraireStand} rules they repeat — the migration path for data captured
 * before rules existed, and the reason V37 needs no data migration of its own.
 *
 * <p>On the reference 63-stand festival fixture this takes 714 dated windows
 * down to 120 rules plus 19 remaining exceptions: nearly every stand states one
 * or two patterns and repeats them across twelve days.</p>
 *
 * <p>Nothing is trusted blindly. Every candidate compaction is replayed against
 * the real créneaux and compared segment by segment with the original
 * ({@link #ecartMaximalMinutes}); a stand whose rules would not reproduce its
 * own open segments is reported and left exactly as it was. Pure and static, so
 * that check is unit-testable without a database.</p>
 */
public final class CompactageHoraires {

    /**
     * Tolerated difference, in minutes, between the open segments before and
     * after compaction.
     *
     * <p>One minute, for one specific reason: a window meant to run to a day
     * closing at midnight cannot say {@code 00:00} (a window may not cross
     * midnight), so it was written {@code 23:59}. Compaction rewrites it as
     * "until closing time", which lands on the real 00:00 and so recovers that
     * lost minute. Every stand where this happens is reported with its actual
     * deviation ({@link LigneCompactage#ecartMinutes()}) rather than silently
     * adjusted.</p>
     */
    static final int ECART_TOLERE_MINUTES = 1;

    private CompactageHoraires() {
    }

    /** What compaction would do, or did, to one stand. */
    public record LigneCompactage(
            String standId,
            int fenetresAvant,
            int reglesApres,
            int exceptionsApres,
            int ecartMinutes,
            boolean compacte,
            String raison) {
    }

    /** Overall outcome, plus one line per stand that was looked at. */
    public record RapportCompactage(
            boolean applique,
            int standsCompactes,
            int fenetresAvant,
            int fenetresApres,
            List<LigneCompactage> stands) {
    }

    /**
     * Compacts every stand of {@code stands} in place and returns what happened.
     * {@code applique} is only carried into the report — persisting is the
     * caller's business; a dry run simply throws the mutated copies away.
     */
    public static RapportCompactage compacter(List<Stand> stands, List<Creneau> creneaux, boolean applique) {
        Set<LocalDate> datesFestival = new TreeSet<>();
        Map<LocalDate, Integer> finDeJournee = new HashMap<>();
        for (Creneau creneau : creneaux) {
            if (creneau.getDate() == null || creneau.getHeureDebut() == null || creneau.getHeureFin() == null) {
                continue;
            }
            datesFestival.add(creneau.getDate());
            int fin = finEnSecondes(creneau);
            finDeJournee.merge(creneau.getDate(), fin, Math::max);
        }

        List<LigneCompactage> lignes = new ArrayList<>();
        int compactes = 0;
        int fenetresAvantTotal = 0;
        int fenetresApresTotal = 0;
        for (Stand stand : stands) {
            int fenetresAvant = stand.getIndisponibilites().size() + stand.getOuvertures().size();
            LigneCompactage ligne = compacterStand(stand, creneaux, datesFestival, finDeJournee, fenetresAvant);
            lignes.add(ligne);
            fenetresAvantTotal += fenetresAvant;
            fenetresApresTotal += ligne.reglesApres() + ligne.exceptionsApres();
            if (ligne.compacte()) {
                compactes++;
            }
        }
        return new RapportCompactage(applique, compactes, fenetresAvantTotal, fenetresApresTotal, lignes);
    }

    private static LigneCompactage compacterStand(Stand stand, List<Creneau> creneaux, Set<LocalDate> datesFestival,
            Map<LocalDate, Integer> finDeJournee, int fenetresAvant) {
        if (!stand.getHoraires().isEmpty()) {
            return new LigneCompactage(stand.getId(), fenetresAvant, stand.getHoraires().size(), fenetresAvant, 0,
                    false, "Le stand a déjà des horaires récurrents — compactage ignoré pour ne pas les fusionner");
        }
        if (fenetresAvant == 0) {
            return new LigneCompactage(stand.getId(), 0, 0, 0, 0, false, "Aucune fenêtre datée à compacter");
        }
        if (datesFestival.isEmpty()) {
            return new LigneCompactage(stand.getId(), fenetresAvant, 0, fenetresAvant, 0, false,
                    "Aucun créneau : impossible de savoir quels jours une règle couvrirait");
        }

        Map<LocalDate, JourSaisi> parJour = regrouperParJour(stand, finDeJournee);
        // Days a rule could not speak about anyway (typically the day after a
        // midnight-crossing slot) stay dated exactly as they are.
        Map<LocalDate, JourSaisi> compactables = new LinkedHashMap<>();
        Map<LocalDate, JourSaisi> horsPerimetre = new LinkedHashMap<>();
        parJour.forEach((date, jour) -> (datesFestival.contains(date) ? compactables : horsPerimetre).put(date, jour));

        // A pattern repeated on a single day is not a pattern: leaving it dated
        // keeps the result readable instead of turning every oddity into a rule.
        Map<JourSaisi, Set<LocalDate>> groupes = new LinkedHashMap<>();
        compactables.forEach((date, jour) -> groupes.computeIfAbsent(jour, cle -> new TreeSet<>()).add(date));

        Set<LocalDate> groupeDeBase = groupeDeBase(groupes, compactables.keySet(), datesFestival);

        List<HoraireStand> regles = new ArrayList<>();
        Map<LocalDate, JourSaisi> restentDates = new LinkedHashMap<>(horsPerimetre);
        groupes.forEach((patron, dates) -> {
            if (dates.size() < 2) {
                dates.forEach(date -> restentDates.put(date, patron));
                return;
            }
            regles.add(construireRegle(patron, dates, datesFestival, dates == groupeDeBase));
        });
        if (regles.isEmpty()) {
            return new LigneCompactage(stand.getId(), fenetresAvant, 0, fenetresAvant, 0, false,
                    "Aucun motif répété : rien à factoriser");
        }

        List<IndisponibiliteStand> fermetures = new ArrayList<>();
        List<OuvertureStand> ouvertures = new ArrayList<>();
        restentDates.forEach((date, jour) -> {
            for (FenetreHoraire fenetre : jour.fenetres()) {
                if (jour.mode() == ModeHoraire.OUVERTURE) {
                    ouvertures.add(new OuvertureStand(null, date, fenetre.getHeureDebut(), fenetre.getHeureFin(),
                            jour.motif()));
                } else {
                    fermetures.add(new IndisponibiliteStand(null, date, fenetre.getHeureDebut(), fenetre.getHeureFin(),
                            jour.motif()));
                }
            }
        });

        Stand candidat = copieAvecHoraires(stand, regles, fermetures, ouvertures);
        int ecart = ecartMaximalMinutes(stand, candidat, creneaux);
        if (ecart > ECART_TOLERE_MINUTES) {
            return new LigneCompactage(stand.getId(), fenetresAvant, regles.size(),
                    fermetures.size() + ouvertures.size(), ecart, false,
                    "Les règles proposées ne reproduisent pas les mêmes segments ouverts (écart " + ecart
                            + " min) — stand laissé inchangé");
        }

        stand.setHoraires(regles);
        stand.setIndisponibilites(fermetures);
        stand.setOuvertures(ouvertures);
        stand.setFenetresEffectives(null, null);
        String raison = ecart > 0
                ? "Compacté ; " + ecart + " min récupérée(s) en fin de journée (l'ancien 23:59 devient la fermeture réelle)"
                : "Compacté à l'identique";
        return new LigneCompactage(stand.getId(), fenetresAvant, regles.size(), fermetures.size() + ouvertures.size(),
                ecart, true, raison);
    }

    /** One day's hand-entered statement, normalised so two identical days compare equal. */
    private record JourSaisi(ModeHoraire mode, List<FenetreHoraire> fenetres, String motif) {

        @Override
        public boolean equals(Object o) {
            // The motif is informative only: two days with the same windows are
            // the same pattern even if somebody typed a different reason.
            return o instanceof JourSaisi that && mode == that.mode && fenetres.equals(that.fenetres);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mode, fenetres);
        }
    }

    /**
     * Collapses the stand's dated windows into one {@link JourSaisi} per day,
     * sorted and de-duplicated, with any end reaching the day's closing time
     * replaced by the open-ended form — which is what lets a day closing at
     * 20:00 and a day closing at midnight fall into the same pattern.
     */
    private static Map<LocalDate, JourSaisi> regrouperParJour(Stand stand, Map<LocalDate, Integer> finDeJournee) {
        Map<LocalDate, ModeHoraire> modes = new LinkedHashMap<>();
        Map<LocalDate, Set<FenetreHoraire>> fenetres = new LinkedHashMap<>();
        Map<LocalDate, String> motifs = new LinkedHashMap<>();
        for (OuvertureStand ouverture : stand.getOuvertures()) {
            if (!ouverture.estValide()) {
                continue;
            }
            LocalDate date = ouverture.getDate();
            modes.put(date, ModeHoraire.OUVERTURE);
            fenetres.computeIfAbsent(date, cle -> new LinkedHashSet<>())
                    .add(normaliser(ouverture.getHeureDebut(), ouverture.getHeureFin(), finDeJournee.get(date)));
            motifs.putIfAbsent(date, ouverture.getMotif());
        }
        for (IndisponibiliteStand fermeture : stand.getIndisponibilites()) {
            if (!fermeture.estValide() || modes.containsKey(fermeture.getDate())) {
                // A day carrying both is rejected at write time; skip defensively
                // rather than invent a winner here.
                continue;
            }
            LocalDate date = fermeture.getDate();
            modes.put(date, ModeHoraire.FERMETURE);
            fenetres.computeIfAbsent(date, cle -> new LinkedHashSet<>())
                    .add(normaliser(fermeture.getHeureDebut(), fermeture.getHeureFin(), finDeJournee.get(date)));
            motifs.putIfAbsent(date, fermeture.getMotif());
        }
        Map<LocalDate, JourSaisi> parJour = new LinkedHashMap<>();
        modes.forEach((date, mode) -> {
            List<FenetreHoraire> triees = new ArrayList<>(fenetres.get(date));
            triees.sort(Comparator.comparing(FenetreHoraire::getHeureDebut)
                    .thenComparing(FenetreHoraire::getHeureFin, Comparator.nullsLast(Comparator.naturalOrder())));
            parJour.put(date, new JourSaisi(mode, List.copyOf(triees), motifs.get(date)));
        });
        return parJour;
    }

    /**
     * A window with its end dropped when it already reaches the day's closing
     * time — within {@link #ECART_TOLERE_MINUTES}, which is what recognises the
     * {@code 23:59} written for a day closing at midnight.
     */
    private static FenetreHoraire normaliser(LocalTime heureDebut, LocalTime heureFin, Integer finJourneeSecondes) {
        if (heureFin == null) {
            return new FenetreHoraire(heureDebut, null);
        }
        if (finJourneeSecondes == null || finJourneeSecondes > SECONDES_PAR_JOUR) {
            // The day's schedule runs past midnight into the next one, so "the
            // day's closing time" is not a same-day hour at all and an open-ended
            // window would stretch beyond what was entered. Leave it concrete;
            // the day's windows simply won't collapse with another day's.
            return new FenetreHoraire(heureDebut, heureFin);
        }
        if (heureFin.toSecondOfDay() + ECART_TOLERE_MINUTES * 60 >= finJourneeSecondes) {
            return new FenetreHoraire(heureDebut, null);
        }
        return new FenetreHoraire(heureDebut, heureFin);
    }

    /**
     * The one pattern allowed to be written as a plain "every day" rule and let
     * the others override it, or {@code null} when none is.
     *
     * <p>This is what turns "these ten dates, then those two dates" into "every
     * day, except those two" — the layering doing the work instead of two date
     * lists. It is only sound when <b>every</b> festival day is stated somewhere:
     * the days the base rule over-reaches are then all covered either by a more
     * specific rule or by a dated exception, both of which win over it. Leave one
     * day unstated and a base rule would start governing a day that was
     * deliberately left open-by-default.</p>
     *
     * <p>A {@code TOUS} rule also reaches dates <em>outside</em> the festival —
     * in practice only the day after a midnight-crossing amplitude, the one date
     * {@code HoraireStandResolver} expands beyond the créneau days. That over-reach
     * can only change anything if a créneau actually reads that date, and if it
     * does, {@link #ecartMaximalMinutes} sees it and the stand is left alone. So
     * the outcome stays correct either way; at worst a stand doesn't compact.
     * Bounding the base rule with a {@code PLAGE} instead would be more precise
     * and yet wrong: {@code PLAGE} outranks {@code JOURS_SEMAINE}, so it would
     * start winning over the very rules meant to override it.</p>
     *
     * <p>The largest pattern is picked, ties broken on the earliest date, so the
     * result doesn't depend on map iteration order.</p>
     */
    private static Set<LocalDate> groupeDeBase(Map<JourSaisi, Set<LocalDate>> groupes, Set<LocalDate> joursStates,
            Set<LocalDate> datesFestival) {
        if (!joursStates.containsAll(datesFestival)) {
            return null;
        }
        return groupes.values().stream()
                .filter(dates -> dates.size() >= 2)
                .max(Comparator.<Set<LocalDate>>comparingInt(Set::size)
                        .thenComparing(dates -> dates.iterator().next(), Comparator.reverseOrder()))
                .orElse(null);
    }

    /**
     * The cheapest selector that covers exactly {@code dates} and not one day
     * more — unless this is the base pattern ({@link #groupeDeBase}), which gets
     * {@code TOUS} and relies on the other rules being more specific.
     *
     * <p>Exactness matters for every other pattern: a {@code JOURS_SEMAINE}
     * selector that also caught a day belonging to another pattern would silently
     * restate that day, and at equal specificity nothing would arbitrate.</p>
     */
    private static HoraireStand construireRegle(JourSaisi patron, Set<LocalDate> dates, Set<LocalDate> datesFestival,
            boolean base) {
        HoraireStand regle = new HoraireStand();
        regle.setMode(patron.mode());
        regle.setFenetres(patron.fenetres());
        regle.setMotif(patron.motif());
        if (base || dates.equals(datesFestival)) {
            regle.setJours(TypeJoursHoraire.TOUS);
            return regle;
        }
        Set<DayOfWeek> joursSemaine = EnumSet.noneOf(DayOfWeek.class);
        dates.forEach(date -> joursSemaine.add(date.getDayOfWeek()));
        Set<LocalDate> memesJoursSemaine = new TreeSet<>(datesFestival);
        memesJoursSemaine.removeIf(date -> !joursSemaine.contains(date.getDayOfWeek()));
        if (memesJoursSemaine.equals(dates)) {
            regle.setJours(TypeJoursHoraire.JOURS_SEMAINE);
            regle.setJoursSemaine(joursSemaine);
            return regle;
        }
        LocalDate premiere = dates.iterator().next();
        LocalDate derniere = dates.stream().max(LocalDate::compareTo).orElse(premiere);
        Set<LocalDate> memePlage = new TreeSet<>(datesFestival);
        memePlage.removeIf(date -> date.isBefore(premiere) || date.isAfter(derniere));
        if (memePlage.equals(dates)) {
            regle.setJours(TypeJoursHoraire.PLAGE);
            regle.setDateDebut(premiere);
            regle.setDateFin(derniere);
            return regle;
        }
        regle.setJours(TypeJoursHoraire.DATES);
        regle.setDates(dates);
        return regle;
    }

    /**
     * How many minutes of opening {@code avant} and {@code apres} disagree on,
     * at worst, over any single créneau of {@code creneaux}.
     *
     * <p>This is the safety net of the whole operation, and it is deliberately
     * expressed on the observable outcome (which minutes of a créneau a stand is
     * open for, hence which postes get generated) rather than on the rules
     * themselves: a compaction is correct exactly when the solver cannot tell the
     * difference.</p>
     *
     * <p>Measured as the symmetric difference of the <b>covered minutes</b>, not
     * by pairing segments index by index. That matters for the one discrepancy
     * this operation does introduce: a stand absent all day used to be written
     * "closed 10:00-23:59" on a day closing at midnight, which left a single
     * open minute behind — and therefore a one-minute poste. Rewritten as "closed
     * from 10:00 to closing", the stand generates no poste at all, so the segment
     * <i>count</i> drops from one to zero while the actual disagreement is the one
     * minute that never should have been staffable. Comparing counts would reject
     * exactly the stands this rewrite helps most.</p>
     */
    static int ecartMaximalMinutes(Stand avant, Stand apres, List<Creneau> creneaux) {
        Stand referenceAvant = copieAvecHoraires(avant, List.of(), avant.getIndisponibilites(), avant.getOuvertures());
        HoraireStandResolver.appliquer(List.of(referenceAvant), creneaux);
        HoraireStandResolver.appliquer(List.of(apres), creneaux);
        int ecart = 0;
        for (Creneau creneau : creneaux) {
            boolean[] ouvertAvant = minutesOuvertes(creneau, referenceAvant);
            boolean[] ouvertApres = minutesOuvertes(creneau, apres);
            int desaccord = 0;
            for (int minute = 0; minute < ouvertAvant.length; minute++) {
                if (ouvertAvant[minute] != ouvertApres[minute]) {
                    desaccord++;
                }
            }
            ecart = Math.max(ecart, desaccord);
        }
        return ecart;
    }

    /** One flag per minute of {@code creneau}: is {@code stand} open then? */
    private static boolean[] minutesOuvertes(Creneau creneau, Stand stand) {
        boolean[] ouvert = new boolean[Math.max(0, creneau.getDureeMinutes())];
        for (int[] segment : creneau.segmentsOuvertsMinutes(stand)) {
            for (int minute = Math.max(0, segment[0]); minute < Math.min(ouvert.length, segment[1]); minute++) {
                ouvert[minute] = true;
            }
        }
        return ouvert;
    }

    /**
     * A stand carrying the same identity and effectifs but different windows —
     * enough for {@link Creneau#segmentsOuvertsMinutes(Stand)}, which is all the
     * equivalence check reads. Not a general-purpose copy: keeping it to what
     * the check needs is what stops it drifting as {@link Stand} grows fields.
     */
    private static Stand copieAvecHoraires(Stand modele, List<HoraireStand> horaires,
            List<IndisponibiliteStand> fermetures, List<OuvertureStand> ouvertures) {
        Stand copie = new Stand();
        copie.setId(modele.getId());
        copie.setNom(modele.getNom());
        copie.setHoraires(new ArrayList<>(horaires));
        copie.setIndisponibilites(new ArrayList<>(fermetures));
        copie.setOuvertures(new ArrayList<>(ouvertures));
        return copie;
    }

    private static final int SECONDES_PAR_JOUR = 24 * 3600;

    /** A créneau's end in seconds since its start day's midnight, {@code 00:00} counting as 24:00. */
    private static int finEnSecondes(Creneau creneau) {
        int fin = creneau.getHeureFin().toSecondOfDay();
        return fin > creneau.getHeureDebut().toSecondOfDay() ? fin : fin + SECONDES_PAR_JOUR;
    }
}
