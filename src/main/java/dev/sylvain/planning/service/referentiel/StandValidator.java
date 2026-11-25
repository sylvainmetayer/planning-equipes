package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.PlageStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.BusinessError;
import java.time.LocalDate;
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
     * A day can never carry both a closure and an opening window: mixing the
     * two modes for one day is ambiguous (which one does the solver honour?),
     * so it is rejected here rather than silently picking one — see
     * {@code PlageStand}'s javadoc for the three-state rule this protects.
     */
    private static void checkExclusiveModesPerDay(Stand stand) {
        // Never null through Stand's setters; checked alike to the two checks above.
        if (stand.getIndisponibilites() == null || stand.getOuvertures() == null) {
            return;
        }
        Set<LocalDate> joursFermeture = stand.getIndisponibilites().stream()
                .map(PlageStand::getDate)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Set<LocalDate> joursOuverture = stand.getOuvertures().stream()
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
