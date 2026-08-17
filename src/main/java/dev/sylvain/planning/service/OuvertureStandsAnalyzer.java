package dev.sylvain.planning.service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.HoraireStandResolver.SourceHoraire;

/**
 * Read-only, stand × jour view of when each stand is <b>actually</b> open, for an
 * administrator to eyeball before launching a solve.
 *
 * <p>The point is validation, so nothing here re-derives the schedule its own
 * way: the cells are built from the very {@link PosteAffectation}s
 * {@link PlanningService#construirePostes} would hand the solver. Whatever the
 * admin sees is therefore, by construction, what the solver gets — recurring
 * horaires expanded, dated exceptions applied, windows clamped to each
 * créneau, relay-grid familles included.</p>
 *
 * <p>It also flags the three ways an opening schedule usually goes wrong in
 * practice — a stand nobody can ever staff, a window that lands outside every
 * créneau, an open stretch too short to be a real working slot. Those are what
 * turn a pretty grid into something worth reading. Pure and static, so they are
 * unit-testable without a database.</p>
 */
public final class OuvertureStandsAnalyzer {

    private OuvertureStandsAnalyzer() {
    }

    /** How much of a day's amplitude a stand covers. */
    public enum EtatOuverture {
        /** Open over the day's whole amplitude — the default for a stand with no schedule. */
        OUVERT_TOTAL,
        /** Open over part of it only. */
        OUVERT_PARTIEL,
        /** Not open at all that day. */
        FERME
    }

    public enum TypeAnomalie {
        /** The stand generates no poste at all: nobody will ever be scheduled on it. */
        STAND_JAMAIS_OUVERT,
        /** A window that overlaps no créneau of its date, so it changes nothing — usually a typo. */
        FENETRE_SANS_EFFET,
        /** An open stretch too short to be a real working slot: a data-entry artefact. */
        SEGMENT_TROP_COURT
    }

    /**
     * Below this, an open stretch is an artefact rather than a schedule: nobody
     * staffs a stand for a quarter of an hour, whereas the mis-entered windows
     * this flags produce stretches of a few minutes (historically the single
     * minute a closure ending at {@code 23:59} left on a day closing at midnight).
     *
     * <p>Deliberately <b>not</b> {@code ParametresDecoupage.dureeVacationMinMinutes}:
     * that one is the floor for slicing a long amplitude into vacations — 250 min
     * on some scenarios — and comparing against it flagged every stand
     * legitimately open for two hours. An anomaly that fires on correct data
     * stops being read.</p>
     */
    static final int DUREE_MINIMALE_EXPLOITABLE_MINUTES = 15;

    /** One festival day, and the amplitude the cells of that column are measured against. */
    public record JourAmplitude(LocalDate date, int jour, LocalTime heureDebut, LocalTime heureFin, int minutes,
            int nombreCreneaux) {
    }

    /** An open stretch, in wall-clock hours, after clamping to the créneaux. */
    public record FenetreEffective(LocalTime heureDebut, LocalTime heureFin) {
    }

    public record CelluleJour(LocalDate date, EtatOuverture etat, SourceHoraire source,
            List<FenetreEffective> fenetres, int minutesOuvertes, int minutesAmplitude, int postes) {
    }

    public record LigneStand(String standId, String nom, int effectifMin, List<CelluleJour> jours, int minutesOuvertes,
            int postes) {
    }

    public record Anomalie(TypeAnomalie type, String standId, String standNom, LocalDate date, String message) {
    }

    public record RapportOuvertures(List<JourAmplitude> jours, List<LigneStand> stands, int standsJamaisOuverts,
            int postesTotal, List<Anomalie> anomalies) {
    }

    /**
     * Builds the report. {@code stands} must already be resolved
     * ({@code listStandsResolus}); {@code creneaux} are the active group's, the
     * ones a solve would actually run on.
     */
    public static RapportOuvertures analyser(List<Stand> stands, List<Creneau> creneaux) {
        Map<LocalDate, List<Creneau>> creneauxParJour = new TreeMap<>();
        for (Creneau creneau : creneaux) {
            if (creneau.getDate() != null && creneau.getHeureDebut() != null && creneau.getHeureFin() != null) {
                creneauxParJour.computeIfAbsent(creneau.getDate(), cle -> new ArrayList<>()).add(creneau);
            }
        }
        List<JourAmplitude> jours = new ArrayList<>();
        Map<LocalDate, Integer> amplitudeParJour = new LinkedHashMap<>();
        creneauxParJour.forEach((date, duJour) -> {
            List<int[]> couverture = fusionner(duJour.stream()
                    .map(OuvertureStandsAnalyzer::intervalle)
                    .toList());
            int minutes = couverture.stream().mapToInt(borne -> borne[1] - borne[0]).sum();
            amplitudeParJour.put(date, minutes);
            int debut = couverture.get(0)[0];
            int fin = couverture.get(couverture.size() - 1)[1];
            jours.add(new JourAmplitude(date, duJour.get(0).getJour(), minuteEnHeure(debut), minuteEnHeure(fin),
                    minutes, duJour.size()));
        });

        // Les postes que le solveur recevrait, groupés par stand puis par jour :
        // c'est la source unique de vérité de cet écran.
        Map<String, Map<LocalDate, List<PosteAffectation>>> postesParStandEtJour = new LinkedHashMap<>();
        for (PosteAffectation poste : PlanningService.construirePostes(stands, creneaux)) {
            if (poste.getStand() == null || poste.getCreneau() == null || poste.getCreneau().getDate() == null) {
                continue;
            }
            postesParStandEtJour
                    .computeIfAbsent(poste.getStand().getId(), cle -> new TreeMap<>())
                    .computeIfAbsent(poste.getCreneau().getDate(), cle -> new ArrayList<>())
                    .add(poste);
        }

        List<LigneStand> lignes = new ArrayList<>();
        List<Anomalie> anomalies = new ArrayList<>();
        int postesTotal = 0;
        int jamaisOuverts = 0;
        for (Stand stand : stands) {
            Map<LocalDate, List<PosteAffectation>> parJour =
                    postesParStandEtJour.getOrDefault(stand.getId(), Map.of());
            List<CelluleJour> cellules = new ArrayList<>();
            int minutesStand = 0;
            int postesStand = 0;
            for (JourAmplitude jour : jours) {
                List<PosteAffectation> postes = parJour.getOrDefault(jour.date(), List.of());
                CelluleJour cellule = cellule(stand, jour, postes, amplitudeParJour.get(jour.date()));
                cellules.add(cellule);
                minutesStand += cellule.minutesOuvertes();
                postesStand += cellule.postes();
                anomalies.addAll(anomaliesDuJour(stand, cellule));
            }
            if (postesStand == 0) {
                jamaisOuverts++;
                anomalies.add(new Anomalie(TypeAnomalie.STAND_JAMAIS_OUVERT, stand.getId(), stand.getNom(), null,
                        "Le stand n'est ouvert aucun jour du groupe de créneaux actif : aucun poste ne sera à pourvoir."));
            }
            anomalies.addAll(fenetresSansEffet(stand, creneauxParJour));
            postesTotal += postesStand;
            lignes.add(new LigneStand(stand.getId(), stand.getNom(), stand.getEffectifMin(), cellules, minutesStand,
                    postesStand));
        }
        anomalies.sort(Comparator.comparing((Anomalie anomalie) -> anomalie.type().ordinal())
                .thenComparing(Anomalie::standId)
                .thenComparing(anomalie -> anomalie.date() != null ? anomalie.date() : LocalDate.MIN));
        return new RapportOuvertures(jours, lignes, jamaisOuverts, postesTotal, anomalies);
    }

    /**
     * One cell, read off the day's postes: their effective windows merged (a day
     * sliced into overlapping vacations must show one continuous stretch, not one
     * band per vacation), and the poste count as it stands.
     */
    private static CelluleJour cellule(Stand stand, JourAmplitude jour, List<PosteAffectation> postes,
            int minutesAmplitude) {
        SourceHoraire source = HoraireStandResolver.sourceDuJour(stand, jour.date());
        if (postes.isEmpty()) {
            return new CelluleJour(jour.date(), EtatOuverture.FERME, source, List.of(), 0, minutesAmplitude, 0);
        }
        List<int[]> fenetres = fusionner(postes.stream()
                .map(poste -> intervalle(poste.heureDebutEffectif(), poste.heureFinEffectif()))
                .toList());
        int minutesOuvertes = fenetres.stream().mapToInt(borne -> borne[1] - borne[0]).sum();
        EtatOuverture etat = minutesOuvertes >= minutesAmplitude ? EtatOuverture.OUVERT_TOTAL
                : EtatOuverture.OUVERT_PARTIEL;
        List<FenetreEffective> effectives = fenetres.stream()
                .map(borne -> new FenetreEffective(minuteEnHeure(borne[0]), minuteEnHeure(borne[1])))
                .toList();
        return new CelluleJour(jour.date(), etat, source, effectives, minutesOuvertes, minutesAmplitude,
                postes.size());
    }

    /**
     * An open stretch of a few minutes is the signature of a mis-entered window —
     * historically a closure ending at {@code 23:59} on a day closing at
     * midnight, which left exactly one staffable minute behind.
     */
    private static List<Anomalie> anomaliesDuJour(Stand stand, CelluleJour cellule) {
        if (cellule.fenetres().isEmpty()) {
            return List.of();
        }
        List<Anomalie> anomalies = new ArrayList<>();
        for (FenetreEffective fenetre : cellule.fenetres()) {
            int minutes = intervalle(fenetre.heureDebut(), fenetre.heureFin())[1]
                    - intervalle(fenetre.heureDebut(), fenetre.heureFin())[0];
            if (minutes < DUREE_MINIMALE_EXPLOITABLE_MINUTES) {
                anomalies.add(new Anomalie(TypeAnomalie.SEGMENT_TROP_COURT, stand.getId(), stand.getNom(),
                        cellule.date(), "Ouvert seulement " + minutes + " min (" + fenetre.heureDebut() + "–"
                                + fenetre.heureFin() + ") : trop court pour être un vrai créneau de travail, "
                                + "la saisie de ce jour est probablement à revoir."));
            }
        }
        return anomalies;
    }

    /**
     * Windows that overlap no créneau of their own date: they were entered, they
     * validate, and they change nothing — the classic "j'ai saisi 14 h-16 h sur un
     * jour qui ferme à midi". Read on the effective lists, so a rule that expands
     * onto such a day is caught too.
     */
    private static List<Anomalie> fenetresSansEffet(Stand stand, Map<LocalDate, List<Creneau>> creneauxParJour) {
        List<Anomalie> anomalies = new ArrayList<>();
        TreeSet<String> dejaVues = new TreeSet<>();
        for (OuvertureStand ouverture : stand.getOuverturesEffectives()) {
            signalerSiSansEffet(stand, creneauxParJour, anomalies, dejaVues, ouverture.getDate(),
                    ouverture.getHeureDebut(), ouverture.getHeureFin(), "ouverture");
        }
        for (IndisponibiliteStand fermeture : stand.getIndisponibilitesEffectives()) {
            signalerSiSansEffet(stand, creneauxParJour, anomalies, dejaVues, fermeture.getDate(),
                    fermeture.getHeureDebut(), fermeture.getHeureFin(), "fermeture");
        }
        return anomalies;
    }

    private static void signalerSiSansEffet(Stand stand, Map<LocalDate, List<Creneau>> creneauxParJour,
            List<Anomalie> anomalies, TreeSet<String> dejaVues, LocalDate date, LocalTime heureDebut,
            LocalTime heureFin, String libelle) {
        if (date == null || heureDebut == null) {
            return;
        }
        // Une fenêtre ouverte (heureFin nulle) s'arrête à la fin du créneau
        // évalué : elle a donc un effet dès qu'un créneau commence après son
        // début, ou l'englobe.
        List<Creneau> duJour = creneauxParJour.get(date);
        if (duJour == null) {
            // Aucun créneau ce jour-là : la fenêtre ne peut viser que le
            // lendemain d'un créneau traversant minuit, cas légitime.
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
        String cle = date + "#" + heureDebut + "#" + heureFin;
        if (!dejaVues.add(cle)) {
            return;
        }
        anomalies.add(new Anomalie(TypeAnomalie.FENETRE_SANS_EFFET, stand.getId(), stand.getNom(), date,
                "La " + libelle + " de " + heureDebut + " à "
                        + (heureFin != null ? heureFin.toString() : "la fermeture")
                        + " ne recoupe aucun créneau de ce jour : elle ne change rien."));
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
    private static List<int[]> fusionner(List<int[]> intervalles) {
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
    private static LocalTime minuteEnHeure(int minutes) {
        return LocalTime.ofSecondOfDay((minutes % (24 * 60)) * 60L);
    }
}
