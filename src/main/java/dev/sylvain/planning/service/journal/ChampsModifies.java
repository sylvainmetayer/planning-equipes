package dev.sylvain.planning.service.journal;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Stand;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * What an edit actually changed, field by field.
 *
 * <p><b>Names, never values.</b> The history says that an edit touched
 * {@code nom} and {@code email}; it does not say what they became, because a
 * name and an address are exactly what must not enter a table that outlives
 * the fiche describing them (issue #406, {@code docs/rgpd.md}).</p>
 *
 * <p>Compared against the fiche <b>as it stood before</b> rather than against
 * what the request carried, for the same reason the write-time warnings are:
 * the bulk edit sends the whole merged fiche one row at a time, so a list
 * built from the payload would claim every field changed on a write that moved
 * one. That before-image is already read by
 * {@code ReferenceDataService.writeXxx} for the warnings, so this costs
 * nothing more.</p>
 *
 * <p>Pure and static: no container, no database, unit-tested on two objects.</p>
 */
public final class ChampsModifies {

    private ChampsModifies() {}

    /** The fields of an animateur, in the order the fiche shows them. */
    private static final Map<String, Function<Animateur, Object>> ANIMATEUR = champs(map -> {
        map.put("prenom", Animateur::getPrenom);
        map.put("nom", Animateur::getNom);
        map.put("dateNaissance", Animateur::getDateNaissance);
        map.put("email", Animateur::getEmail);
        map.put("manager", Animateur::isManager);
        map.put("ninja", Animateur::isNinja);
        map.put("competences", Animateur::getCompetences);
        map.put("souhaits", Animateur::getSouhaits);
        map.put("joursIndisponibles", Animateur::getJoursIndisponibles);
    });

    private static final Map<String, Function<Stand, Object>> STAND = champs(map -> {
        map.put("nom", Stand::getNom);
        map.put("typologiesProposees", Stand::getTypologiesProposees);
        map.put("effectifMin", Stand::getEffectifMin);
        map.put("effectifMax", Stand::getEffectifMax);
        map.put("reserveMajeurs", Stand::isReserveMajeurs);
        map.put("premium", Stand::isPremium);
        map.put("famille", Stand::getFamille);
        map.put("niveauEffort", Stand::getNiveauEffort);
        map.put(
                "emplacement",
                stand -> stand.getEmplacement() == null
                        ? null
                        : stand.getEmplacement().getId());
        map.put("indisponibilites", Stand::getIndisponibilites);
        map.put("ouvertures", Stand::getOuvertures);
        map.put("horaires", Stand::getHoraires);
    });

    /**
     * {@code jour} is deliberately absent: it is derived from the edition's
     * earliest date over the whole grid ({@code Creneau.assignerJours}), never
     * stored, so comparing it would report a change on every edit read one row
     * at a time — and an edit never sets it anyway. {@code date} is the field
     * that actually moved.
     */
    private static final Map<String, Function<Creneau, Object>> CRENEAU = champs(map -> {
        map.put("date", Creneau::getDate);
        map.put("heureDebut", Creneau::getHeureDebut);
        map.put("heureFin", Creneau::getHeureFin);
        map.put("famille", Creneau::getFamille);
        map.put("couverturePause", Creneau::isCouverturePause);
    });

    public static List<String> surAnimateur(Animateur avant, Animateur apres) {
        return comparer(ANIMATEUR, avant, apres);
    }

    public static List<String> surStand(Stand avant, Stand apres) {
        return comparer(STAND, avant, apres);
    }

    public static List<String> surCreneau(Creneau avant, Creneau apres) {
        return comparer(CRENEAU, avant, apres);
    }

    /**
     * The fields whose value differs. An absent before-image — a creation, or
     * a fiche the reader could not find — yields an empty list rather than
     * « every field changed »: a creation is already described by its own
     * action, and listing its fields would say nothing more.
     */
    private static <T> List<String> comparer(Map<String, Function<T, Object>> champs, T avant, T apres) {
        if (avant == null || apres == null) {
            return List.of();
        }
        List<String> modifies = new ArrayList<>();
        champs.forEach((nom, lecture) -> {
            if (!Objects.equals(lecture.apply(avant), lecture.apply(apres))) {
                modifies.add(nom);
            }
        });
        return modifies;
    }

    /**
     * Keeps the declaration order — {@code Map.copyOf} would not, and the
     * order is the contract: the history reads « prenom, nom » the way the
     * fiche shows them, not the way a hash landed.
     */
    private static <T> Map<String, Function<T, Object>> champs(
            java.util.function.Consumer<Map<String, Function<T, Object>>> declaration) {
        Map<String, Function<T, Object>> map = new LinkedHashMap<>();
        declaration.accept(map);
        return Collections.unmodifiableMap(map);
    }
}
