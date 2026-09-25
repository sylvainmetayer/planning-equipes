package dev.sylvain.planning.service.export;

import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.PauseAnalyzer;
import java.awt.Color;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * One animateur's planning, arranged the way the document reads it rather than
 * the way the solver produced it: the days of the event in a row, the stands
 * grouped by place, the team-mates gathered, the crowded shifts counted.
 *
 * <p>Pure data built from plain objects, so the three questions the document
 * answers — what does my festival look like, where do I go, who am I with —
 * are unit-tested without reading the bytes of a PDF. Both layouts (the
 * booklet and the folded sheet) render <b>this</b>, which is what keeps them
 * saying the same thing.</p>
 */
record AnimateurPlanningView(
        String nom,
        LocalDate premierJour,
        LocalDate dernierJour,
        int joursTotal,
        int joursTravailles,
        int joursRepos,
        int creneaux,
        int standsDistincts,
        int minutesTravaillees,
        List<Jour> jours,
        List<Lieu> lieux,
        List<Coequipier> coequipiers,
        List<EquipeNombreuse> equipesNombreuses,
        List<LieuJours> daysByLieu,
        List<TypologiePalette.Entree> legende,
        int amplitudeDebutMinutes,
        int amplitudeFinMinutes) {

    /**
     * Beyond eight people on the same stand and the same window, the document
     * prints how many rather than who: the festival's set-up and take-down
     * gather a hundred names nobody reads, and the question they answer is
     * « are we many », not « who exactly ».
     */
    static final int SEUIL_NOMS = 8;

    private static final DateTimeFormatter JOUR_COURT = DateTimeFormatter.ofPattern("EEE d", Locale.FRENCH);

    /** A day of the event as the animateur lives it: their shifts, or « Repos ». */
    record Jour(
            int numero, LocalDate date, boolean repos, String note, List<Vacation> vacations, int minutesTravaillees) {

        boolean sousConsigne() {
            return note != null && !note.isBlank();
        }
    }

    /**
     * One shift, with its <b>effective</b> hours — those the animateur covers,
     * not the wider window of the créneau.
     *
     * @param notes  what falls under the shift: the meal break, the legal one
     * @param effectif how many hold that stand on that window, this animateur included
     */
    record Vacation(
            LocalTime debut,
            LocalTime fin,
            int dureeMinutes,
            int debutMinutes,
            int finMinutes,
            String standNom,
            String typologieLibelle,
            Color couleur,
            String lieu,
            String urlLieu,
            List<String> coequipiers,
            int effectif,
            List<String> notes) {

        /** Whether the names are printed, or only how many people are there. */
        boolean nomme() {
            return coequipiers.size() < SEUIL_NOMS;
        }

        String equipeTexte() {
            if (coequipiers.isEmpty()) {
                return "Seul(e) sur ce stand";
            }
            return nomme() ? "Avec " + String.join(", ", coequipiers) : "Avec " + coequipiers.size() + " personnes";
        }
    }

    /** The stands of one place, as page one lists them: « créneaux × heures » each. */
    record Lieu(String nom, List<StandUsage> stands) {}

    record StandUsage(String nom, String typologieLibelle, Color couleur, int creneaux, int minutes) {}

    /** Somebody met on a stand, and when — the most frequent first. */
    record Coequipier(String nom, int fois, List<String> moments) {}

    /** A shift too crowded to name: the set-up, the take-down, a podium. */
    record EquipeNombreuse(String standNom, LocalDate date, LocalTime debut, LocalTime fin, int effectif) {}

    /** One place and the days spent there. */
    record LieuJours(String nom, List<LocalDate> dates) {}

    boolean vide() {
        return jours.isEmpty();
    }

    /** « Du lundi 14 au mardi 29 septembre 2026 · 16 jours, dont 3 de repos ». */
    String periode() {
        if (premierJour == null || dernierJour == null) {
            return "";
        }
        String texte = PdfTheme.formatPeriode(premierJour, dernierJour) + " · " + joursTotal
                + (joursTotal > 1 ? " jours" : " jour");
        return joursRepos > 0 ? texte + ", dont " + joursRepos + " de repos" : texte;
    }

    static String jourCourt(LocalDate date) {
        return JOUR_COURT.format(date);
    }

    /**
     * Assembles the view from what the export service already reads: the
     * animateur's seats in chronological order, their rest days, their breaks,
     * the team-mates of each seat and what a consigne says of a date.
     */
    static AnimateurPlanningView build(
            String nom,
            List<PosteAffectation> postes,
            Map<String, List<String>> teammatesByPoste,
            List<PlanningExportService.JourRepos> joursRepos,
            List<PauseAnalyzer.PauseAnimateurView> pauses,
            List<PauseAnalyzer.CoupureAnimateurView> coupures,
            Map<LocalDate, String> notes,
            TypologiePalette palette) {
        Map<LocalDate, String> consignes = notes == null ? Map.of() : notes;
        Map<PosteAffectation, List<PauseAnalyzer.CoupureAnimateurView>> coupuresParPoste =
                ancrerCoupures(postes, coupures, pauses);

        Map<LocalDate, Jour> parDate = new TreeMap<>();
        Map<LocalDate, List<Vacation>> vacationsParDate = new LinkedHashMap<>();
        Map<LocalDate, Integer> numeroParDate = new LinkedHashMap<>();
        for (PosteAffectation poste : postes) {
            if (poste.getCreneau() == null || poste.getCreneau().getDate() == null) {
                continue;
            }
            LocalDate date = poste.getCreneau().getDate();
            numeroParDate.putIfAbsent(date, poste.getCreneau().getJour());
            vacationsParDate
                    .computeIfAbsent(date, ignored -> new ArrayList<>())
                    .add(vacation(poste, teammatesByPoste, pauses, coupures, coupuresParPoste, palette));
        }
        for (Map.Entry<LocalDate, List<Vacation>> entree : vacationsParDate.entrySet()) {
            List<Vacation> vacations = entree.getValue();
            vacations.sort(Comparator.comparingInt(Vacation::debutMinutes).thenComparing(Vacation::standNom));
            int minutes = vacations.stream().mapToInt(Vacation::dureeMinutes).sum();
            parDate.put(
                    entree.getKey(),
                    new Jour(
                            numeroParDate.get(entree.getKey()),
                            entree.getKey(),
                            false,
                            consignes.get(entree.getKey()),
                            List.copyOf(vacations),
                            minutes));
        }
        for (PlanningExportService.JourRepos repos : joursRepos) {
            parDate.putIfAbsent(
                    repos.date(),
                    new Jour(repos.jour(), repos.date(), true, consignes.get(repos.date()), List.of(), 0));
        }
        List<Jour> jours = List.copyOf(parDate.values());

        int minutes = jours.stream().mapToInt(Jour::minutesTravaillees).sum();
        int joursTravailles = (int) jours.stream().filter(jour -> !jour.repos()).count();
        int nombreRepos = jours.size() - joursTravailles;

        List<Stand> stands = PosteStatistics.distinctStands(postes);
        return new AnimateurPlanningView(
                nom,
                jours.isEmpty() ? null : jours.get(0).date(),
                jours.isEmpty() ? null : jours.get(jours.size() - 1).date(),
                jours.size(),
                joursTravailles,
                nombreRepos,
                PosteStatistics.distinctCreneauCount(postes),
                stands.size(),
                minutes,
                jours,
                lieux(postes, palette),
                coequipiers(jours),
                equipesNombreuses(jours),
                daysByLieu(jours),
                palette.legende(stands),
                amplitudeDebut(jours),
                amplitudeFin(jours));
    }

    private static Vacation vacation(
            PosteAffectation poste,
            Map<String, List<String>> teammatesByPoste,
            List<PauseAnalyzer.PauseAnimateurView> pauses,
            List<PauseAnalyzer.CoupureAnimateurView> coupures,
            Map<PosteAffectation, List<PauseAnalyzer.CoupureAnimateurView>> coupuresParPoste,
            TypologiePalette palette) {
        Stand stand = poste.getStand();
        List<String> equipe = teammatesByPoste.getOrDefault(poste.getId(), List.of());
        LocalTime debut = poste.heureDebutEffectif();
        int debutMinutes = debut == null ? 0 : debut.toSecondOfDay() / 60;
        return new Vacation(
                debut,
                poste.heureFinEffectif(),
                poste.getDureeEffectiveMinutes(),
                debutMinutes,
                debutMinutes + poste.getDureeEffectiveMinutes(),
                stand == null ? "—" : stand.getNom(),
                palette.libelleDuStand(stand),
                palette.couleurDuStand(stand),
                lieuNom(stand),
                urlLieu(stand),
                List.copyOf(equipe),
                equipe.size() + 1,
                notesSousVacation(poste, pauses, coupures, coupuresParPoste));
    }

    /**
     * The lines printed under a shift, in the order the animateur reads them:
     * the legal break of the shift it falls in, then the meal break anchored
     * on it. Same wording as the espace animateur, deliberately — the two
     * must not tell the same moment in two ways.
     */
    private static List<String> notesSousVacation(
            PosteAffectation poste,
            List<PauseAnalyzer.PauseAnimateurView> pauses,
            List<PauseAnalyzer.CoupureAnimateurView> coupures,
            Map<PosteAffectation, List<PauseAnalyzer.CoupureAnimateurView>> coupuresParPoste) {
        List<String> lignes = new ArrayList<>();
        for (PauseAnalyzer.PauseAnimateurView pause : pauses) {
            if (pause.fallsInside(poste)) {
                lignes.add(pauseText(pause, coupureQuiCouvre(coupures, pause)));
            }
        }
        for (PauseAnalyzer.CoupureAnimateurView coupure : coupuresParPoste.getOrDefault(poste, List.of())) {
            lignes.add(coupureText(coupure));
        }
        return List.copyOf(lignes);
    }

    /** « Repas de 13:00 à 14:00 (60 min) », the meal break of issue #598. */
    private static String coupureText(PauseAnalyzer.CoupureAnimateurView coupure) {
        return "Repas de " + coupure.debut().format(PdfTheme.TIME_FORMAT) + " à "
                + coupure.fin().format(PdfTheme.TIME_FORMAT) + " (" + coupure.dureeMinutes() + " min)";
    }

    private static String pauseText(
            PauseAnalyzer.PauseAnimateurView pause, PauseAnalyzer.CoupureAnimateurView coupure) {
        String moment = coupure == null
                ? "Pause de " + pause.debut().format(PdfTheme.TIME_FORMAT) + " à "
                        + pause.fin().format(PdfTheme.TIME_FORMAT) + " (" + pause.dureeMinutes() + " min)"
                : "Repas de " + coupure.debut().format(PdfTheme.TIME_FORMAT) + " à "
                        + coupure.fin().format(PdfTheme.TIME_FORMAT) + " (" + coupure.dureeMinutes()
                        + " min), pause légale comprise";
        return moment
                + (pause.relaisDisponible()
                        ? ", en relais avec l'équipe du stand"
                        : " — personne d'autre sur le stand : demandez le relais à l'organisation");
    }

    /**
     * The meal break covering this legal one, or {@code null}. The two are
     * different objects — art. L3121-16 owes twenty minutes at the sixth hour,
     * the meal break is the rule the organisation gives itself — but when the
     * legal one falls inside the meal one, printing both makes the animateur
     * read two obligations where there is one moment (issue #598).
     */
    private static PauseAnalyzer.CoupureAnimateurView coupureQuiCouvre(
            List<PauseAnalyzer.CoupureAnimateurView> coupures, PauseAnalyzer.PauseAnimateurView pause) {
        return coupures.stream()
                .filter(coupure -> coupure.couvre(pause))
                .findFirst()
                .orElse(null);
    }

    /**
     * Each meal break hung off the shift it follows — the last one of its day
     * that ends before it, or, for a break opening the day, the first one
     * after.
     *
     * <p>A break a legal one already carries is dropped here rather than
     * printed twice: {@link #pauseText} then says both in one line.</p>
     */
    private static Map<PosteAffectation, List<PauseAnalyzer.CoupureAnimateurView>> ancrerCoupures(
            List<PosteAffectation> postes,
            List<PauseAnalyzer.CoupureAnimateurView> coupures,
            List<PauseAnalyzer.PauseAnimateurView> pauses) {
        Map<PosteAffectation, List<PauseAnalyzer.CoupureAnimateurView>> parPoste = new LinkedHashMap<>();
        for (PauseAnalyzer.CoupureAnimateurView coupure : coupures) {
            PosteAffectation retenu = pauses.stream().anyMatch(coupure::couvre) ? null : ancre(postes, coupure);
            if (retenu != null) {
                parPoste.computeIfAbsent(retenu, ignored -> new ArrayList<>()).add(coupure);
            }
        }
        return parPoste;
    }

    /**
     * The shift a meal break hangs off: the last one of its day ending before
     * it, else the first one after, {@code null} on a day with no shift.
     */
    private static PosteAffectation ancre(List<PosteAffectation> postes, PauseAnalyzer.CoupureAnimateurView coupure) {
        PosteAffectation ancre = null;
        PosteAffectation suivant = null;
        for (PosteAffectation poste : postes) {
            LocalDate date =
                    poste.getCreneau() == null ? null : poste.getCreneau().getDate();
            if (!coupure.date().equals(date) || poste.heureFinEffectif() == null) {
                continue;
            }
            if (!poste.heureFinEffectif().isAfter(coupure.debut())) {
                ancre = poste;
            } else if (suivant == null) {
                suivant = poste;
            }
        }
        return ancre != null ? ancre : suivant;
    }

    /**
     * The stands grouped by place, in first-appearance order — « vous êtes
     * neuf jours sur treize au même endroit » is the sentence the animateur
     * cannot read off a chronological list.
     */
    private static List<Lieu> lieux(List<PosteAffectation> postes, TypologiePalette palette) {
        Map<String, Map<String, int[]>> parLieu = new LinkedHashMap<>();
        Map<String, Stand> standsParId = new LinkedHashMap<>();
        for (PosteAffectation poste : postes) {
            Stand stand = poste.getStand();
            if (stand == null) {
                continue;
            }
            standsParId.putIfAbsent(stand.getId(), stand);
            int[] compte = parLieu.computeIfAbsent(lieuNom(stand), ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(stand.getId(), ignored -> new int[2]);
            compte[0]++;
            compte[1] += poste.getDureeEffectiveMinutes();
        }
        List<Lieu> lieux = new ArrayList<>();
        for (Map.Entry<String, Map<String, int[]>> entree : parLieu.entrySet()) {
            List<StandUsage> usages = new ArrayList<>();
            for (Map.Entry<String, int[]> stand : entree.getValue().entrySet()) {
                Stand objet = standsParId.get(stand.getKey());
                usages.add(new StandUsage(
                        objet.getNom(),
                        palette.libelleDuStand(objet),
                        palette.couleurDuStand(objet),
                        stand.getValue()[0],
                        stand.getValue()[1]));
            }
            usages.sort(Comparator.comparing(StandUsage::nom, String.CASE_INSENSITIVE_ORDER));
            lieux.add(new Lieu(entree.getKey(), List.copyOf(usages)));
        }
        // « Lieu non précisé » last: it is where the set-up and take-down land,
        // and they are not a place somebody has to find.
        lieux.sort(Comparator.comparing(lieu -> LIEU_INCONNU.equals(lieu.nom()) ? 1 : 0));
        return List.copyOf(lieux);
    }

    static final String LIEU_INCONNU = "Lieu non précisé";

    static String lieuNom(Stand stand) {
        Emplacement emplacement = stand == null ? null : stand.getEmplacement();
        return emplacement == null
                        || emplacement.getNom() == null
                        || emplacement.getNom().isBlank()
                ? LIEU_INCONNU
                : emplacement.getNom();
    }

    static String urlLieu(Stand stand) {
        Emplacement emplacement = stand == null ? null : stand.getEmplacement();
        if (emplacement == null || !emplacement.isGeocoded()) {
            return null;
        }
        double lat = emplacement.getLatitude();
        double lon = emplacement.getLongitude();
        return "https://www.openstreetmap.org/?mlat=" + lat + "&mlon=" + lon + "#map=18/" + lat + "/" + lon;
    }

    /**
     * Who the animateur meets, gathered and counted: the same person met five
     * times is one line saying five, where the chronological pages say their
     * name five times without ever adding up.
     */
    private static List<Coequipier> coequipiers(List<Jour> jours) {
        Map<String, List<String>> moments = new LinkedHashMap<>();
        for (Jour jour : jours) {
            for (Vacation vacation : jour.vacations()) {
                if (!vacation.nomme()) {
                    continue;
                }
                for (String coequipier : vacation.coequipiers()) {
                    moments.computeIfAbsent(coequipier, ignored -> new ArrayList<>())
                            .add(jourCourt(jour.date()) + " " + vacation.standNom());
                }
            }
        }
        return moments.entrySet().stream()
                .map(entree ->
                        new Coequipier(entree.getKey(), entree.getValue().size(), List.copyOf(entree.getValue())))
                .sorted(Comparator.comparingInt(Coequipier::fois)
                        .reversed()
                        .thenComparing(Coequipier::nom, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** The crowded shifts, in chronological order: how many, and when. */
    private static List<EquipeNombreuse> equipesNombreuses(List<Jour> jours) {
        List<EquipeNombreuse> equipes = new ArrayList<>();
        for (Jour jour : jours) {
            for (Vacation vacation : jour.vacations()) {
                if (!vacation.nomme()) {
                    equipes.add(new EquipeNombreuse(
                            vacation.standNom(), jour.date(), vacation.debut(), vacation.fin(), vacation.effectif()));
                }
            }
        }
        return List.copyOf(equipes);
    }

    /** Each place and the dates spent there — « neuf jours au même endroit », said as dates. */
    private static List<LieuJours> daysByLieu(List<Jour> jours) {
        Map<String, Set<LocalDate>> parLieu = new LinkedHashMap<>();
        for (Jour jour : jours) {
            for (Vacation vacation : jour.vacations()) {
                parLieu.computeIfAbsent(vacation.lieu(), ignored -> new LinkedHashSet<>())
                        .add(jour.date());
            }
        }
        return parLieu.entrySet().stream()
                .map(entree -> new LieuJours(entree.getKey(), List.copyOf(entree.getValue())))
                .sorted(Comparator.comparingInt((LieuJours lieu) -> lieu.dates().size())
                        .reversed()
                        .thenComparing(LieuJours::nom, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** Where the timeline's axis starts: the earliest shift of the event, on the hour below. */
    private static int amplitudeDebut(List<Jour> jours) {
        int debut = jours.stream()
                .flatMap(jour -> jour.vacations().stream())
                .mapToInt(Vacation::debutMinutes)
                .min()
                .orElse(9 * 60);
        return Math.max(0, (debut / 60) * 60);
    }

    /** And where it ends: the latest one, on the hour above, at least four hours after the start. */
    private static int amplitudeFin(List<Jour> jours) {
        int fin = jours.stream()
                .flatMap(jour -> jour.vacations().stream())
                .mapToInt(Vacation::finMinutes)
                .max()
                .orElse(20 * 60);
        int arrondi = (int) (Math.ceil(fin / 60.0) * 60);
        return Math.max(arrondi, amplitudeDebut(jours) + 4 * 60);
    }
}
