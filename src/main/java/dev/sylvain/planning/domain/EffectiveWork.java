package dev.sylvain.planning.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Travail effectif: the amplitude of a set of seats, less the legal breaks the
 * days they make up owe (ADR 0048).
 *
 * <p>Amplitude and travail effectif used to be two readings of the same
 * planning that the application held at once and never reconciled: the daily
 * and weekly caps deducted breaks, the Heures screen, the Équité screen, the
 * Besoin floor and the KPI counted the whole span, and an organiser comparing
 * two numbers that differ by half an hour a day had nothing to tell them apart.
 * There is one quantity now, and this class is where it is computed — once, so
 * that no two screens can drift.</p>
 *
 * <p>A break is due <b>per day and per animateur</b>, because a stretch never
 * crosses midnight here ({@code LegalConstraints.reposQuotidienMinimal} is what
 * watches a chain over midnight). Callers that report per week therefore group
 * by day first and add the days up, never the other way round: deducting from a
 * week's total would owe breaks on a stretch nobody ever worked.</p>
 */
public final class EffectiveWork {

    private EffectiveWork() {}

    /** One animateur-day: the seats they hold, and the date they hold them on. */
    private record Jour(String animateurId, LocalDate date) {}

    /**
     * Minutes of travail effectif over these seats: the sum of their effective
     * durations, less the breaks each animateur-day owes. Seats with no
     * animateur, no créneau, no date or no start time are skipped — they carry
     * no day to owe a break on, and their amplitude is nobody's.
     */
    public static int minutes(Collection<PosteAffectation> postes, ParametresLegaux parametres) {
        int total = 0;
        for (Map.Entry<Jour, List<PosteAffectation>> jour :
                byAnimateurAndDay(postes).entrySet()) {
            total += minutesOfDay(jour.getValue(), parametres);
        }
        return total;
    }

    /**
     * The same, per animateur: what the Heures screen, the Équité screen and
     * the KPI each put on their own line. The map keeps the order the seats
     * came in, so a caller iterating it reads its roster in its own order.
     */
    public static Map<String, Integer> minutesPerAnimateur(
            Collection<PosteAffectation> postes, ParametresLegaux parametres) {
        Map<String, Integer> parAnimateur = new LinkedHashMap<>();
        for (Map.Entry<Jour, List<PosteAffectation>> jour :
                byAnimateurAndDay(postes).entrySet()) {
            parAnimateur.merge(jour.getKey().animateurId(), minutesOfDay(jour.getValue(), parametres), Integer::sum);
        }
        return parAnimateur;
    }

    /**
     * Minutes of legal break each animateur owes over these seats, day by day —
     * for a caller that already holds their amplitude and only needs what to
     * take off it.
     */
    public static Map<String, Integer> breakMinutesPerAnimateur(
            Collection<PosteAffectation> postes, ParametresLegaux parametres) {
        Map<String, Integer> parAnimateur = new LinkedHashMap<>();
        for (Map.Entry<Jour, List<PosteAffectation>> jour :
                byAnimateurAndDay(postes).entrySet()) {
            parAnimateur.merge(jour.getKey().animateurId(), breakMinutes(jour.getValue(), parametres), Integer::sum);
        }
        return parAnimateur;
    }

    /**
     * Minutes of legal break one animateur-day owes — the quantity every hours
     * read-out takes off its amplitude. The seats must all belong to the same
     * animateur and date.
     */
    public static int breakMinutes(List<PosteAffectation> postesDuJour, ParametresLegaux parametres) {
        int minutes = 0;
        for (PauseSurPoste pause : PauseSurPoste.dues(postesDuJour, parametres)) {
            minutes += pause.dureeMinutes();
        }
        return minutes;
    }

    private static int minutesOfDay(List<PosteAffectation> postesDuJour, ParametresLegaux parametres) {
        int amplitude = 0;
        for (PosteAffectation poste : postesDuJour) {
            amplitude += poste.getDureeEffectiveMinutes();
        }
        return amplitude - breakMinutes(postesDuJour, parametres);
    }

    private static Map<Jour, List<PosteAffectation>> byAnimateurAndDay(Collection<PosteAffectation> postes) {
        Map<Jour, List<PosteAffectation>> parJour = new LinkedHashMap<>();
        for (PosteAffectation poste : postes) {
            if (poste.getAnimateur() == null
                    || poste.getCreneau() == null
                    || poste.getCreneau().getDate() == null
                    || poste.heureDebutEffectif() == null) {
                continue;
            }
            parJour.computeIfAbsent(
                            new Jour(
                                    poste.getAnimateur().getId(),
                                    poste.getCreneau().getDate()),
                            jour -> new ArrayList<>())
                    .add(poste);
        }
        return parJour;
    }
}
