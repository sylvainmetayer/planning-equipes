package dev.sylvain.planning.service;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.ModeHoraire;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.Stand;

/**
 * What a stand must satisfy before it is written, as pure functions: nothing
 * here reads the database, so each rule can be read — and tested — on its own.
 * The one rule that <i>does</i> need the referential (typologie ids must
 * exist) stays in {@link TypologieService#validerIds}.
 */
final class StandValidator {

    private StandValidator() {
    }

    static void check(Stand stand) {
        checkEffectifs(stand);
        checkIndisponibilites(stand);
        checkOuvertures(stand);
        checkExclusiveModesPerDay(stand);
        checkHoraires(stand);
    }

    private static void checkEffectifs(Stand stand) {
        if (stand.getEffectifMin() > stand.getEffectifMax()) {
            throw new BusinessError.Invalid(
                    "effectifMin (" + stand.getEffectifMin() + ") cannot be greater than effectifMax ("
                            + stand.getEffectifMax() + ")");
        }
    }

    /**
     * Every closure window must be a genuine, same-day interval — see
     * {@code IndisponibiliteStand}. A {@code null} {@code heureFin} is
     * accepted and means "until closing time".
     */
    private static void checkIndisponibilites(Stand stand) {
        if (stand.getIndisponibilites() == null) {
            return;
        }
        for (IndisponibiliteStand indispo : stand.getIndisponibilites()) {
            if (indispo.getDate() == null || indispo.getHeureDebut() == null) {
                throw new BusinessError.Invalid("Une indisponibilité de stand requiert une date et une heure de "
                        + "début (l'heure de fin peut être vide : jusqu'à la fermeture)");
            }
            if (indispo.getHeureFin() != null && !indispo.getHeureFin().isAfter(indispo.getHeureDebut())) {
                throw new BusinessError.Invalid(
                        "heureFin (" + indispo.getHeureFin() + ") doit être après heureDebut (" + indispo.getHeureDebut()
                                + ") — une indisponibilité ne peut pas chevaucher minuit, entrez-en deux");
            }
        }
    }

    /**
     * Every opening window must be a genuine, same-day interval — see
     * {@code OuvertureStand}. A {@code null} {@code heureFin} is accepted and
     * means "until closing time".
     */
    private static void checkOuvertures(Stand stand) {
        if (stand.getOuvertures() == null) {
            return;
        }
        for (OuvertureStand ouverture : stand.getOuvertures()) {
            if (ouverture.getDate() == null || ouverture.getHeureDebut() == null) {
                throw new BusinessError.Invalid("Une ouverture de stand requiert une date et une heure de début "
                        + "(l'heure de fin peut être vide : jusqu'à la fermeture)");
            }
            if (ouverture.getHeureFin() != null && !ouverture.getHeureFin().isAfter(ouverture.getHeureDebut())) {
                throw new BusinessError.Invalid(
                        "heureFin (" + ouverture.getHeureFin() + ") doit être après heureDebut ("
                                + ouverture.getHeureDebut() + ") — une ouverture ne peut pas chevaucher minuit, "
                                + "entrez-en deux");
            }
            checkEffectifFenetre(ouverture.getEffectif(), "une ouverture", stand);
        }
    }

    /**
     * A window's effectif is optional — absent, the stand's minimum applies —
     * but never zero or negative: a window nobody should staff is a closure,
     * and is declared as one. Nor above the stand's {@code effectifMax}: seat
     * generation would then make mandatory more seats than the stand is
     * declared able to hold, and the two numbers would contradict each other
     * on every screen that shows them.
     */
    private static void checkEffectifFenetre(Integer effectif, String porteur, Stand stand) {
        if (effectif == null) {
            return;
        }
        if (effectif < 1) {
            throw new BusinessError.Invalid("effectif (" + effectif + ") doit être au moins 1 sur " + porteur
                    + " — laissez-le vide pour reprendre l'effectif minimum du stand, ou déclarez une fermeture");
        }
        if (effectif > stand.getEffectifMax()) {
            throw new BusinessError.Invalid("effectif (" + effectif + ") dépasse l'effectif maximum du stand ("
                    + stand.getEffectifMax() + ") sur " + porteur
                    + " — relevez l'effectif maximum du stand, ou baissez celui de la fenêtre");
        }
    }

    /**
     * Recurring rules must each be self-consistent (a selector with the data it
     * needs, at least one usable window), and the set of them must not leave the
     * resolver an arbitrary choice to make.
     *
     * <p>That second part is the interesting one: two rules of the <b>same</b>
     * day selector, whose day sets intersect, but with opposite
     * {@link ModeHoraire}, would give a day both "closed except…" and "open
     * only…" at the same specificity. There is no non-arbitrary winner, so it is
     * rejected here — exactly as {@link #checkExclusiveModesPerDay} does for
     * the dated exceptions. Two rules of <i>different</i> specificity are fine
     * and expected ("open 14:00→closing every day, closed all day on the 14th"):
     * the more specific one simply wins.</p>
     */
    private static void checkHoraires(Stand stand) {
        List<HoraireStand> horaires = stand.getHoraires();
        if (horaires == null || horaires.isEmpty()) {
            return;
        }
        for (HoraireStand horaire : horaires) {
            checkHoraire(horaire, stand);
        }
        for (int i = 0; i < horaires.size(); i++) {
            for (int j = i + 1; j < horaires.size(); j++) {
                HoraireStand a = horaires.get(i);
                HoraireStand b = horaires.get(j);
                if (a.getMode() != b.getMode() && a.daysOverlapWith(b)) {
                    throw new BusinessError.Invalid("Deux horaires de même portée (" + a.getJours()
                            + ") portant sur les mêmes jours ne peuvent pas être l'un une ouverture et l'autre une "
                            + "fermeture pour le stand " + stand.getId()
                            + " — utilisez une portée plus précise pour celui qui doit primer");
                }
            }
        }
    }

    private static void checkHoraire(HoraireStand horaire, Stand stand) {
        if (horaire.getFenetres().isEmpty()) {
            throw new BusinessError.Invalid("Un horaire de stand requiert au moins une fenêtre horaire");
        }
        for (FenetreHoraire fenetre : horaire.getFenetres()) {
            if (fenetre.getHeureDebut() == null) {
                throw new BusinessError.Invalid("Une fenêtre horaire requiert une heure de début "
                        + "(l'heure de fin peut être vide : jusqu'à la fermeture)");
            }
            if (fenetre.getHeureFin() != null && !fenetre.getHeureFin().isAfter(fenetre.getHeureDebut())) {
                throw new BusinessError.Invalid("heureFin (" + fenetre.getHeureFin() + ") doit être après heureDebut ("
                        + fenetre.getHeureDebut() + ") — une fenêtre horaire ne peut pas chevaucher minuit, "
                        + "entrez-en deux");
            }
            checkEffectifFenetre(fenetre.getEffectif(), "une fenêtre horaire", stand);
        }
        switch (horaire.getJours()) {
            case JOURS_SEMAINE -> {
                if (horaire.getJoursSemaine().isEmpty()) {
                    throw new BusinessError.Invalid(
                            "Un horaire de portée JOURS_SEMAINE requiert au moins un jour de la semaine");
                }
            }
            case PLAGE -> {
                if (horaire.getDateDebut() == null || horaire.getDateFin() == null) {
                    throw new BusinessError.Invalid(
                            "Un horaire de portée PLAGE requiert une dateDebut et une dateFin");
                }
                if (horaire.getDateFin().isBefore(horaire.getDateDebut())) {
                    throw new BusinessError.Invalid("dateFin (" + horaire.getDateFin() + ") doit être après ou égale "
                            + "à dateDebut (" + horaire.getDateDebut() + ")");
                }
            }
            case DATES -> {
                if (horaire.getDates().isEmpty()) {
                    throw new BusinessError.Invalid("Un horaire de portée DATES requiert au moins une date");
                }
            }
            case TOUS -> {
                // Nothing else to check: the selector carries no data of its own.
            }
        }
    }

    /**
     * A day can never carry both a closure and an opening window: mixing the
     * two modes for one day is ambiguous (which one does the solver honour?),
     * so it is rejected here rather than silently picking one — see
     * {@code OuvertureStand}'s javadoc for the three-state rule this protects.
     */
    private static void checkExclusiveModesPerDay(Stand stand) {
        Set<LocalDate> joursFermeture = stand.getIndisponibilites().stream()
                .map(IndisponibiliteStand::getDate)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<LocalDate> joursOuverture = stand.getOuvertures().stream()
                .map(OuvertureStand::getDate)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<LocalDate> conflits = new TreeSet<>(joursFermeture);
        conflits.retainAll(joursOuverture);
        if (!conflits.isEmpty()) {
            throw new BusinessError.Invalid("Un jour ne peut pas avoir à la fois une fermeture et une ouverture "
                    + "pour le stand " + stand.getId() + " : " + conflits);
        }
    }
}
