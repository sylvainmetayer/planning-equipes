package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.HoraireRuleOverlaps.Findings;
import dev.sylvain.planning.service.analyse.HoraireRuleOverlaps.MaskedRule;
import dev.sylvain.planning.service.analyse.HoraireRuleOverlaps.RulesOverlap;
import dev.sylvain.planning.service.analyse.HoraireRuleOverlaps.WindowsOverlap;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer.Anomaly;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer.AnomalyType;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * The findings of {@link HoraireRuleOverlaps} on one stand, worded as the
 * anomalies of the opening report. The rule is designated by what it says —
 * its days and its windows — because that is how the operator reads it on the
 * stand's form, and by its id so a screen can point at its line.
 */
final class HoraireAnomalies {

    /** How many dates a {@code DATES} rule spells out before « et n autres ». */
    private static final int CITED_DATES = 3;

    private HoraireAnomalies() {}

    static List<Anomaly> of(Stand stand, List<HoraireRuleOverlaps.EventDay> days) {
        List<HoraireStand> horaires = stand.getHoraires();
        List<LocalDate> exceptions = Stream.concat(
                        stand.getIndisponibilites().stream().map(IndisponibiliteStand::getDate),
                        stand.getOuvertures().stream().map(OuvertureStand::getDate))
                .filter(Objects::nonNull)
                .toList();
        Findings findings =
                HoraireRuleOverlaps.detect(horaires, exceptions, stand.getOuvertures(), days, stand.getEffectifMin());
        if (findings.isEmpty()) {
            return List.of();
        }
        List<Anomaly> anomalies = new ArrayList<>();
        for (RulesOverlap overlap : findings.rulesOverlaps()) {
            anomalies.add(anomaly(
                    stand,
                    AnomalyType.REGLES_CHEVAUCHANTES,
                    null,
                    rulesOverlapMessage(horaires, overlap),
                    horaires.get(overlap.otherRule()).getId()));
        }
        for (MaskedRule masked : findings.maskedRules()) {
            anomalies.add(anomaly(
                    stand,
                    AnomalyType.REGLE_MASQUEE,
                    null,
                    maskedMessage(horaires, masked),
                    horaires.get(masked.rule()).getId()));
        }
        for (WindowsOverlap overlap : findings.windowsOverlaps()) {
            anomalies.add(anomaly(
                    stand,
                    AnomalyType.FENETRES_CHEVAUCHANTES,
                    overlap.date(),
                    windowsOverlapMessage(stand, overlap),
                    overlap.rule() == null ? null : horaires.get(overlap.rule()).getId()));
        }
        return anomalies;
    }

    private static Anomaly anomaly(Stand stand, AnomalyType type, LocalDate date, String message, Long horaireId) {
        return new Anomaly(type, stand.getId(), stand.getNom(), date, null, null, message, horaireId);
    }

    private static String rulesOverlapMessage(List<HoraireStand> horaires, RulesOverlap overlap) {
        HoraireStand first = horaires.get(overlap.rule());
        String firstLabel = scope(first);
        String otherLabel = scope(horaires.get(overlap.otherRule()));
        String closure = first.getMode() == ModeHoraire.FERMETURE ? " de fermeture" : "";
        String who = firstLabel.equals(otherLabel)
                ? "Deux règles" + closure + " « " + firstLabel + " »"
                : "Les règles" + closure + " « " + firstLabel + " » et « " + otherLabel + " »";
        String when = who + " se recouvrent de " + HoraireRuleOverlaps.hour(overlap.start()) + " à "
                + HoraireRuleOverlaps.hour(overlap.end());
        if (first.getMode() == ModeHoraire.FERMETURE) {
            return when + " : la même fermeture est dite deux fois, une seule règle suffit.";
        }
        Integer effectif = overlap.effectif();
        Integer other = overlap.otherEffectif();
        if (Objects.equals(effectif, other)) {
            return when + " : la même fenêtre est dite deux fois, avec le même effectif (" + effectif
                    + "), une seule règle suffit.";
        }
        int kept = Math.max(effectif, other);
        int dropped = Math.min(effectif, other);
        return when + " : l'effectif retenu est " + kept + " (le plus haut), pas " + dropped + ".";
    }

    private static String maskedMessage(List<HoraireStand> horaires, MaskedRule masked) {
        List<String> who = new ArrayList<>();
        for (int index : masked.maskingRules()) {
            who.add("« " + fullLabel(horaires.get(index)) + " »");
        }
        if (masked.maskedByExceptions()) {
            who.add("des exceptions datées");
        }
        boolean plural = who.size() > 1 || (who.size() == 1 && masked.maskedByExceptions());
        return "La règle « " + fullLabel(horaires.get(masked.rule())) + " » n'est appliquée à aucun jour : "
                + joinAnd(who) + (plural ? " la remplacent" : " la remplace") + " partout.";
    }

    private static String windowsOverlapMessage(Stand stand, WindowsOverlap overlap) {
        String first;
        String second;
        String where;
        if (overlap.rule() != null) {
            HoraireStand horaire = stand.getHoraires().get(overlap.rule());
            first = window(horaire.getFenetres().get(overlap.window()), overlap.effectif());
            second = window(horaire.getFenetres().get(overlap.otherWindow()), overlap.otherEffectif());
            where = "Dans la règle « " + scope(horaire) + " », ";
        } else {
            OuvertureStand a = stand.getOuvertures().get(overlap.window());
            OuvertureStand b = stand.getOuvertures().get(overlap.otherWindow());
            first = window(a.getHeureDebut(), a.getHeureFin(), overlap.effectif());
            second = window(b.getHeureDebut(), b.getHeureFin(), overlap.otherEffectif());
            where = "Le " + overlap.date() + ", les ouvertures ";
        }
        // Never null here: a window naming no headcount reads the stand's minimum.
        int kept = Math.max(overlap.effectif(), overlap.otherEffectif());
        return where + first + " et " + second + " se recouvrent : " + kept + " personne(s) de "
                + HoraireRuleOverlaps.hour(overlap.start()) + " à " + HoraireRuleOverlaps.hour(overlap.end()) + ".";
    }

    /** The days a rule applies to, as the stand form shows them. */
    static String scope(HoraireStand horaire) {
        return switch (horaire.getJours()) {
            case TOUS -> "tous les jours";
            case JOURS_SEMAINE ->
                horaire.getJoursSemaine().equals(EnumSet.allOf(DayOfWeek.class))
                        ? "du lundi au dimanche"
                        : joinAnd(horaire.getJoursSemaine().stream()
                                .map(HoraireAnomalies::weekday)
                                .toList());
            case PLAGE -> "du " + horaire.getDateDebut() + " au " + horaire.getDateFin();
            case DATES -> dates(horaire);
        };
    }

    private static String dates(HoraireStand horaire) {
        List<String> dates =
                horaire.getDates().stream().map(LocalDate::toString).toList();
        if (dates.size() == 1) {
            return "le " + dates.get(0);
        }
        if (dates.size() <= CITED_DATES) {
            return "les " + joinAnd(dates);
        }
        return "les " + String.join(", ", dates.subList(0, CITED_DATES)) + " et " + (dates.size() - CITED_DATES)
                + " autres";
    }

    /** The rule as one reads it: its days, then its windows; a closure says so. */
    static String fullLabel(HoraireStand horaire) {
        String windows = String.join(
                ", ",
                horaire.validFenetres().stream()
                        .map(fenetre -> window(fenetre, fenetre.getEffectif()))
                        .toList());
        return (horaire.getMode() == ModeHoraire.FERMETURE ? "fermé " : "") + scope(horaire) + " " + windows;
    }

    private static String window(FenetreHoraire fenetre, Integer effectif) {
        return window(fenetre.getHeureDebut(), fenetre.getHeureFin(), effectif);
    }

    private static String window(LocalTime start, LocalTime end, Integer effectif) {
        return start + "–" + (end != null ? end.toString() : "fermeture") + (effectif != null ? " @" + effectif : "");
    }

    private static String weekday(DayOfWeek day) {
        return switch (day) {
            case MONDAY -> "lundi";
            case TUESDAY -> "mardi";
            case WEDNESDAY -> "mercredi";
            case THURSDAY -> "jeudi";
            case FRIDAY -> "vendredi";
            case SATURDAY -> "samedi";
            case SUNDAY -> "dimanche";
        };
    }

    private static String joinAnd(List<String> items) {
        if (items.size() <= 1) {
            return String.join("", items);
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " et " + items.get(items.size() - 1);
    }
}
