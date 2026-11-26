package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.HorairesRecurrents.Regle;
import dev.sylvain.planning.domain.PlageStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.BusinessError;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * What a stand must satisfy before it is written, as pure functions: nothing
 * here reads the database, so each rule can be read — and tested — on its own.
 * The one rule that <i>does</i> need the referential (typologie ids must
 * exist) stays in {@link TypologieService#validateIds}.
 */
final class StandValidator {

    private StandValidator() {}

    /**
     * The checks a schedule write owes: everything but the typologie rule.
     * The opening grid writes hours, one transaction for the lot, and a stand
     * stored before issue #343 without a typologie would otherwise make the
     * whole batch fail on a field that screen cannot even show — the
     * "tolerated on read" half of the rule, applied to a write that does not
     * touch it.
     */
    static void checkSchedule(Stand stand) {
        checkEffectifs(stand);
        checkIndisponibilites(stand);
        checkOuvertures(stand);
        checkExclusiveModesPerDay(stand);
    }

    static void check(Stand stand) {
        checkTypologies(stand);
        checkEffectifs(stand);
        checkIndisponibilites(stand);
        checkOuvertures(stand);
        checkExclusiveModesPerDay(stand);
    }

    /**
     * A stand is always attached to at least one typologie (issue #343).
     * Without one, {@code Animateur.hasCompetenceFor} answers false for
     * everybody but the polyvalents: the tightest bottleneck the model can
     * produce, born from an entry nothing flagged. Refused on write only — a
     * row already stored without one stays readable and solvable, and is
     * refused at its next save with this message.
     */
    private static void checkTypologies(Stand stand) {
        if (stand.getTypologiesProposees() == null
                || stand.getTypologiesProposees().isEmpty()) {
            throw new BusinessError.Invalid(
                    "Le stand " + CoherenceAnalyzer.standLabel(stand)
                            + " ne propose aucune typologie de jeu : "
                            + "un stand est toujours rattaché à au moins une typologie. Choisissez-en une avant d'enregistrer.");
        }
    }

    private static void checkEffectifs(Stand stand) {
        if (stand.getEffectifMin() > stand.getEffectifMax()) {
            throw new BusinessError.Invalid("effectifMin (" + stand.getEffectifMin()
                    + ") cannot be greater than effectifMax (" + stand.getEffectifMax() + ")");
        }
    }

    /**
     * Every closure window must be a genuine, same-day interval — see
     * {@code PlageStand}. A {@code null} {@code heureFin} is
     * accepted and means "until closing time".
     */
    private static void checkIndisponibilites(Stand stand) {
        if (stand.getIndisponibilites() == null) {
            return;
        }
        for (PlageStand indispo : stand.getIndisponibilites()) {
            if (indispo.getDate() == null || indispo.getHeureDebut() == null) {
                throw new BusinessError.Invalid("Une indisponibilité de stand requiert une date et une heure de "
                        + "début (l'heure de fin peut être vide : jusqu'à la fermeture)");
            }
            if (indispo.getHeureFin() != null && !indispo.getHeureFin().isAfter(indispo.getHeureDebut())) {
                throw new BusinessError.Invalid("heureFin (" + indispo.getHeureFin() + ") doit être après heureDebut ("
                        + indispo.getHeureDebut()
                        + ") — une indisponibilité ne peut pas chevaucher minuit, entrez-en deux");
            }
        }
    }

    /**
     * Every opening window must be a genuine, same-day interval — see
     * {@code PlageStand}. A {@code null} {@code heureFin} is accepted and
     * means "until closing time".
     */
    private static void checkOuvertures(Stand stand) {
        if (stand.getOuvertures() == null) {
            return;
        }
        for (PlageStand ouverture : stand.getOuvertures()) {
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
     * The model's rules (ADR 0104), checked as they are sent, before any day lays them down: the data
     * each selector needs, the years a {@code date[]} column holds (V122, refused here by name rather
     * than by its CHECK as a 500), and the headcount of each window. A model kept for an edition with
     * no timeslot is otherwise never read by a check until the days that unfold it appear.
     */
    static void checkRules(Stand stand, List<Regle> regles) {
        for (Regle regle : regles) {
            switch (regle.jours()) {
                case JOURS_SEMAINE ->
                    requireSome(
                            regle.joursSemaine(),
                            "Un horaire de portée JOURS_SEMAINE requiert au moins un jour de la semaine");
                case PLAGE -> {
                    if (regle.dateDebut() == null || regle.dateFin() == null) {
                        throw new BusinessError.Invalid(
                                "Un horaire de portée PLAGE requiert une dateDebut et une dateFin");
                    }
                    if (regle.dateFin().isBefore(regle.dateDebut())) {
                        throw new BusinessError.Invalid("dateFin (" + regle.dateFin() + ") doit être après ou égale "
                                + "à dateDebut (" + regle.dateDebut() + ")");
                    }
                }
                case DATES -> {
                    requireSome(regle.dates(), "Un horaire de portée DATES requiert au moins une date");
                    for (LocalDate date : regle.dates()) {
                        if (date.getYear() < 1 || date.getYear() > 9999) {
                            throw new BusinessError.Invalid(
                                    "Un horaire de portée DATES ne prend que des dates des années 1 à 9999 (" + date
                                            + ")");
                        }
                    }
                }
                case TOUS -> {
                    // Nothing else to check: the selector carries no data of its own.
                }
            }
            regle.fenetres()
                    .forEach(fenetre -> checkEffectifFenetre(fenetre.getEffectif(), "une fenêtre horaire", stand));
        }
    }

    private static void requireSome(Collection<?> valeurs, String message) {
        if (valeurs.isEmpty()) {
            throw new BusinessError.Invalid(message);
        }
    }

    /**
     * A day can never carry both a closure and an opening window that
     * somebody typed: mixing the two modes by hand is ambiguous, so it is
     * rejected here rather than silently picking one. The plages a consigne
     * wrote are left out (ADR 0125): its band falls on stands that state their
     * openings, and that day reads as the openings minus the band.
     */
    private static void checkExclusiveModesPerDay(Stand stand) {
        // Never null through Stand's setters; checked alike to the two checks above.
        if (stand.getIndisponibilites() == null || stand.getOuvertures() == null) {
            return;
        }
        Set<LocalDate> joursFermeture = stand.getIndisponibilites().stream()
                .filter(plage -> !plage.isConsigne())
                .map(PlageStand::getDate)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<LocalDate> joursOuverture = stand.getOuvertures().stream()
                .filter(plage -> !plage.isConsigne())
                .map(PlageStand::getDate)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<LocalDate> conflits = new TreeSet<>(joursFermeture);
        conflits.retainAll(joursOuverture);
        if (!conflits.isEmpty()) {
            throw new BusinessError.Invalid("Un jour ne peut pas avoir à la fois une fermeture et une ouverture "
                    + "pour le stand " + CoherenceAnalyzer.standLabel(stand) + " : " + conflits);
        }
    }
}
