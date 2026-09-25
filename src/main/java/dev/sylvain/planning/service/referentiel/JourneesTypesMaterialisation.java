package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.JourneeType;
import dev.sylvain.planning.domain.VacationType;
import dev.sylvain.planning.service.BusinessError;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * How a calendar of day templates becomes créneaux, and how créneaux become
 * templates again — pure functions, no database, so the rules are unit tested
 * on lists.
 *
 * <p><b>The diff is on the natural key</b> {@code (date, heureDebut, heureFin)}
 * — the same key {@code CreneauGridService} calls a duplicate — so that "the
 * same créneau" means one thing in the whole product. A créneau the template
 * still names keeps its id, its seats and its locks; only its relay flag can be
 * updated in place. A créneau the template does not name, on a date the
 * template governs, is removed with its seats. A date the calendar does not
 * assign is never touched.</p>
 */
public final class JourneesTypesMaterialisation {

    private JourneesTypesMaterialisation() {}

    /** One date governed by one template. */
    public record Affectation(LocalDate date, Long journeeTypeId) {}

    /**
     * What applying the calendar would do, créneau by créneau.
     *
     * @param conserves   already exactly as the template says: untouched
     * @param misAJour    same hours, relay flag to flip: updated in place, id kept
     * @param aCreer      named by the template, absent from the grid
     * @param aSupprimer  on a governed date, not named by its template
     * @param datesEnEcart the governed dates where at least one of the three lists is non-empty
     */
    public record Plan(
            List<Creneau> conserves,
            List<Creneau> misAJour,
            List<Creneau> aCreer,
            List<Creneau> aSupprimer,
            List<LocalDate> datesEnEcart) {

        public boolean isEmpty() {
            return misAJour.isEmpty() && aCreer.isEmpty() && aSupprimer.isEmpty();
        }
    }

    /**
     * Plans the materialisation of {@code calendrier} over {@code existants}.
     * A calendar naming a template that does not exist is a data error, refused
     * rather than silently skipped: the screen never produces one, and an
     * assistant that did needs to hear it.
     */
    public static Plan planifier(
            List<JourneeType> journeesTypes, List<Affectation> calendrier, List<Creneau> existants) {
        Map<Long, JourneeType> parId = new LinkedHashMap<>();
        for (JourneeType journeeType : journeesTypes) {
            parId.put(journeeType.getId(), journeeType);
        }
        Map<LocalDate, List<Creneau>> existantsParDate = new TreeMap<>();
        for (Creneau creneau : existants) {
            if (creneau.getDate() != null) {
                existantsParDate
                        .computeIfAbsent(creneau.getDate(), date -> new ArrayList<>())
                        .add(creneau);
            }
        }

        List<Creneau> conserves = new ArrayList<>();
        List<Creneau> misAJour = new ArrayList<>();
        List<Creneau> aCreer = new ArrayList<>();
        List<Creneau> aSupprimer = new ArrayList<>();
        List<LocalDate> datesEnEcart = new ArrayList<>();

        List<Affectation> tri = new ArrayList<>(calendrier);
        tri.sort(Comparator.comparing(Affectation::date));
        for (Affectation affectation : tri) {
            JourneeType journeeType = parId.get(affectation.journeeTypeId());
            if (journeeType == null) {
                throw new BusinessError.Invalid("Le " + affectation.date()
                        + " est affecté à une journée type inconnue : " + affectation.journeeTypeId());
            }
            int avant = misAJour.size() + aCreer.size() + aSupprimer.size();
            Plan duJour = planDay(
                    affectation.date(), journeeType, existantsParDate.getOrDefault(affectation.date(), List.of()));
            conserves.addAll(duJour.conserves());
            misAJour.addAll(duJour.misAJour());
            aCreer.addAll(duJour.aCreer());
            aSupprimer.addAll(duJour.aSupprimer());
            if (misAJour.size() + aCreer.size() + aSupprimer.size() > avant) {
                datesEnEcart.add(affectation.date());
            }
        }
        return new Plan(conserves, misAJour, aCreer, aSupprimer, datesEnEcart);
    }

    /** The plan of one calendar date against the créneaux it already has; no drift dates. */
    private static Plan planDay(LocalDate date, JourneeType journeeType, List<Creneau> existantsDuJour) {
        List<Creneau> conserves = new ArrayList<>();
        List<Creneau> misAJour = new ArrayList<>();
        List<Creneau> aCreer = new ArrayList<>();
        List<Creneau> aSupprimer = new ArrayList<>();
        Map<String, Creneau> duJour = new LinkedHashMap<>();
        for (Creneau creneau : existantsDuJour) {
            // A duplicate slot (same hours twice) is the grid's DOUBLON
            // error: the first row stands for the key, the others go.
            if (duJour.putIfAbsent(keyOf(creneau), creneau) != null) {
                aSupprimer.add(creneau);
            }
        }
        for (VacationType vacation : journeeType.getVacations()) {
            Creneau present = duJour.remove(vacation.key());
            if (present == null) {
                aCreer.add(vacation.toCreneau(date));
            } else if (present.isCouverturePause() != vacation.couverturePause()) {
                Creneau corrige = copie(present);
                corrige.setCouverturePause(vacation.couverturePause());
                misAJour.add(corrige);
            } else {
                conserves.add(present);
            }
        }
        aSupprimer.addAll(duJour.values());
        return new Plan(conserves, misAJour, aCreer, aSupprimer, List.of());
    }

    /** The templates a grid implies, and the calendar that maps its dates onto them. */
    public record Reconnaissance(List<JourneeType> journeesTypes, List<Affectation> calendrier) {}

    /**
     * Reads the day templates back out of a grid: every date whose créneaux
     * carry the same hours and relay flags is the same kind of day. Templates
     * are numbered in the order their first date comes — « Journée type 1 » is
     * the opening day's shape — and get no id: the caller persists them.
     */
    public static Reconnaissance reconnaitre(List<Creneau> creneaux) {
        Map<LocalDate, List<Creneau>> parDate = new TreeMap<>();
        for (Creneau creneau : creneaux) {
            if (creneau.getDate() != null && creneau.getHeureDebut() != null && creneau.getHeureFin() != null) {
                parDate.computeIfAbsent(creneau.getDate(), date -> new ArrayList<>())
                        .add(creneau);
            }
        }
        Map<String, JourneeType> parSignature = new LinkedHashMap<>();
        Map<String, Long> rangParSignature = new LinkedHashMap<>();
        List<Affectation> calendrier = new ArrayList<>();
        for (Map.Entry<LocalDate, List<Creneau>> jour : parDate.entrySet()) {
            List<VacationType> vacations = new ArrayList<>();
            Map<String, Boolean> vus = new LinkedHashMap<>();
            List<Creneau> tries = new ArrayList<>(jour.getValue());
            tries.sort(Comparator.comparing(Creneau::getHeureDebut).thenComparing(Creneau::getHeureFin));
            for (Creneau creneau : tries) {
                if (vus.putIfAbsent(keyOf(creneau), Boolean.TRUE) == null) {
                    vacations.add(new VacationType(
                            creneau.getHeureDebut(), creneau.getHeureFin(), creneau.isCouverturePause()));
                }
            }
            JourneeType candidat = new JourneeType(null, null, vacations);
            String signature = candidat.signature();
            JourneeType journeeType = parSignature.get(signature);
            if (journeeType == null) {
                long rang = parSignature.size() + 1L;
                candidat.setNom("Journée type " + rang);
                // A provisional id, only so the calendar can name its template
                // before anything is persisted; the repository reassigns it.
                candidat.setId(-rang);
                parSignature.put(signature, candidat);
                rangParSignature.put(signature, rang);
                journeeType = candidat;
            }
            calendrier.add(new Affectation(jour.getKey(), journeeType.getId()));
        }
        return new Reconnaissance(new ArrayList<>(parSignature.values()), calendrier);
    }

    static String keyOf(Creneau creneau) {
        return creneau.getHeureDebut() + "→" + creneau.getHeureFin();
    }

    private static Creneau copie(Creneau source) {
        Creneau creneau = new Creneau(
                source.getId(), source.getJour(), source.getDate(), source.getHeureDebut(), source.getHeureFin());
        creneau.setCouverturePause(source.isCouverturePause());
        creneau.setModifieLe(source.getModifieLe());
        return creneau;
    }
}
