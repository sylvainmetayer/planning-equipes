package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.FenetreRepas;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.PauseAnalyzer.CoupureRepasView;
import dev.sylvain.planning.service.analyse.PauseAnalyzer.JourneeAnimateurView;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * How many people are on a meal break, when, and where — the number only the
 * planning knows, and the one the intendance asks for (issue #598).
 *
 * <p>{@link PauseAnalyzer} already answers « for whom, at the latest when, on
 * which stand », animateur by animateur and day by day: the right reading to
 * organise a relay, the wrong one to prepare sandwiches. This one aggregates
 * the very same {@code CoupureRepasView}s — so the two screens can never tell
 * two stories about the same day — into a count per half-hour and per
 * emplacement.</p>
 *
 * <p><b>People on a break, not people present.</b> The second figure is much
 * larger and answers a different question; it is deliberately out of scope
 * (issue #598).</p>
 *
 * <p><b>When</b> is where {@link PauseAnalyzer} places the break — as early as
 * the free stretch allows — not where {@code FenetreRepas.auPlusTard} says the
 * organisation would rather it fell (issue #596). The two screens then read
 * the same day the same way; the day a break is reported where it is
 * <i>preferred</i> rather than where it first fits, both move together.</p>
 *
 * <p><b>Where</b> is the emplacement of the stand the person <em>leaves</em>
 * when the break starts — they are physically there, and that is where the
 * food has to be carried. A break at the very start of a day, with nothing
 * before it, is attributed to the stand it opens onto instead. The
 * organisation of the meals itself — who prepares, who delivers, who pays —
 * stays outside the tool.</p>
 */
@ApplicationScoped
public class IntendanceRepasAnalyzer {

    /** Half an hour: the natural unit of the meal windows, and the one the intendance plans on. */
    public static final int PAS_MINUTES = 30;

    /** Shown when a stand names no emplacement — a row that must still be carried food. */
    private static final Emplacement EMPLACEMENT_INCONNU = emplacementInconnu();

    private static Emplacement emplacementInconnu() {
        Emplacement emplacement = new Emplacement();
        emplacement.setId("");
        emplacement.setNom("Emplacement non renseigné");
        return emplacement;
    }

    @Inject
    PauseAnalyzer pauseAnalyzer;

    /**
     * One emplacement of one window, on one day.
     *
     * @param personnes one count per half-hour slot of the window, in the
     *                  order of {@code FenetreIntendance.tranches}
     * @param mineurs   how many of them are minors — counted, never named, and
     *                  no birth date leaves the server (see
     *                  {@code docs/rgpd.md} §7)
     * @param total     distinct people on a break at this emplacement over the
     *                  whole window: never the sum of {@code personnes}, which
     *                  counts somebody once per half-hour their break spans
     */
    @Schema(requiredProperties = {"total", "totalMineurs"})
    public record LigneEmplacement(
            String emplacementId,
            String emplacementNom,
            List<Integer> personnes,
            List<Integer> mineurs,
            int total,
            int totalMineurs) {}

    /**
     * One meal window of one day, as a table: half-hours across, emplacements
     * down.
     *
     * @param tranches the start of each half-hour, from the window's own start
     */
    @Schema(requiredProperties = {"total", "totalMineurs"})
    public record FenetreIntendance(
            String libelle,
            LocalTime debut,
            LocalTime fin,
            List<LocalTime> tranches,
            List<LigneEmplacement> emplacements,
            int total,
            int totalMineurs) {}

    /** One day of the event, one table per window it declares. */
    public record JourneeIntendance(LocalDate date, List<FenetreIntendance> fenetres) {}

    /**
     * @param pasMinutes width of a slot, so a client never has to guess it
     * @param message    why the report is empty, when it is — a screen saying
     *                   « 0 » and a screen saying « rien n'est résolu » are not
     *                   the same screen
     */
    @Schema(requiredProperties = {"pasMinutes"})
    public record RapportIntendance(int pasMinutes, List<JourneeIntendance> journees, String message) {}

    public RapportIntendance analyze(
            PlanningEvenement planning, ParametresLegaux parametres, List<FenetreRepas> fenetres) {
        List<FenetreRepas> fenetresRepas = fenetres == null ? List.of() : fenetres;
        if (planning == null
                || planning.getPostes() == null
                || planning.getPostes().isEmpty()) {
            return new RapportIntendance(
                    PAS_MINUTES,
                    List.of(),
                    "Aucun planning résolu : lancez une résolution, l'intendance se lit sur les coupures qu'il place.");
        }
        if (fenetresRepas.isEmpty()) {
            return new RapportIntendance(
                    PAS_MINUTES,
                    List.of(),
                    "Aucune fenêtre repas déclarée : renseignez les heures de midi et du soir "
                            + "dans les paramètres légaux.");
        }

        Map<String, Emplacement> emplacements = emplacementsByStand(planning);
        Map<String, List<PosteAffectation>> parJournee = postesByAnimateurAndDay(planning);

        // date -> window label -> emplacement id -> tally
        Map<LocalDate, Map<String, Map<String, Compte>>> comptes = new LinkedHashMap<>();
        for (JourneeAnimateurView journee :
                pauseAnalyzer.analyze(planning, parametres, fenetresRepas).journees()) {
            for (CoupureRepasView coupure : journee.coupuresRepas()) {
                if (coupure.debut() == null) {
                    // The day leaves no room for the break: nobody eats here,
                    // and PauseAnalyzer already reports it as a violation.
                    continue;
                }
                Emplacement emplacement = emplacementOfCoupure(
                        parJournee.get(dayKey(journee.animateurId(), journee.date())), coupure, emplacements);
                comptes.computeIfAbsent(journee.date(), ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(coupure.libelle(), ignored -> new LinkedHashMap<>())
                        .computeIfAbsent(emplacement.getId(), ignored -> new Compte(emplacement))
                        .ajouter(coupure, journee.mineur());
            }
        }

        List<JourneeIntendance> journees = new ArrayList<>();
        for (LocalDate date : comptes.keySet().stream().sorted().toList()) {
            List<FenetreIntendance> vues = new ArrayList<>();
            for (FenetreRepas fenetre : fenetresRepas) {
                Map<String, Compte> parEmplacement = comptes.get(date).get(fenetre.libelle());
                if (parEmplacement == null || parEmplacement.isEmpty()) {
                    continue;
                }
                vues.add(fenetreView(fenetre, parEmplacement));
            }
            if (!vues.isEmpty()) {
                journees.add(new JourneeIntendance(date, List.copyOf(vues)));
            }
        }
        if (journees.isEmpty()) {
            return new RapportIntendance(
                    PAS_MINUTES,
                    List.of(),
                    "Aucune coupure repas sur ce planning : aucune journée ne traverse une fenêtre déclarée.");
        }
        return new RapportIntendance(PAS_MINUTES, List.copyOf(journees), "");
    }

    private static FenetreIntendance fenetreView(FenetreRepas fenetre, Map<String, Compte> parEmplacement) {
        List<LocalTime> tranches = tranches(fenetre);
        List<LigneEmplacement> lignes = new ArrayList<>();
        int total = 0;
        int totalMineurs = 0;
        for (Compte compte : parEmplacement.values()) {
            lignes.add(compte.ligne(tranches));
            total += compte.total;
            totalMineurs += compte.totalMineurs;
        }
        lignes.sort(Comparator.comparing(LigneEmplacement::emplacementNom, String.CASE_INSENSITIVE_ORDER));
        return new FenetreIntendance(
                fenetre.libelle(), fenetre.debut(), fenetre.fin(), tranches, List.copyOf(lignes), total, totalMineurs);
    }

    /**
     * The half-hours a window is read on. The last one may run past the
     * window's end — a window of 12:00-13:20 owes three slots, and truncating
     * the third would hide whoever eats at 13:05.
     */
    private static List<LocalTime> tranches(FenetreRepas fenetre) {
        List<LocalTime> tranches = new ArrayList<>();
        for (int minute = fenetre.debutMinutes(); minute < fenetre.finMinutes(); minute += PAS_MINUTES) {
            tranches.add(LocalTime.ofSecondOfDay(minute * 60L));
        }
        return List.copyOf(tranches);
    }

    /**
     * Where the person is when the break starts: the emplacement of the last
     * seat they hold before it, or — for a break opening their day — of the
     * first one after.
     */
    private static Emplacement emplacementOfCoupure(
            List<PosteAffectation> postes, CoupureRepasView coupure, Map<String, Emplacement> emplacements) {
        if (postes == null || postes.isEmpty()) {
            return EMPLACEMENT_INCONNU;
        }
        int debut = coupure.debut().toSecondOfDay();
        PosteAffectation avant = null;
        PosteAffectation apres = null;
        for (PosteAffectation poste : postes) {
            LocalTime fin = poste.heureFinEffectif();
            LocalTime ouverture = poste.heureDebutEffectif();
            if (fin != null
                    && fin.toSecondOfDay() <= debut
                    && (avant == null || fin.isAfter(avant.heureFinEffectif()))) {
                avant = poste;
            }
            if (ouverture != null
                    && ouverture.toSecondOfDay() >= debut
                    && (apres == null || ouverture.isBefore(apres.heureDebutEffectif()))) {
                apres = poste;
            }
        }
        PosteAffectation retenu = avant != null ? avant : apres;
        if (retenu == null || retenu.getStand() == null) {
            return EMPLACEMENT_INCONNU;
        }
        Emplacement emplacement = emplacements.get(retenu.getStand().getId());
        return emplacement == null || emplacement.getId() == null ? EMPLACEMENT_INCONNU : emplacement;
    }

    private static Map<String, List<PosteAffectation>> postesByAnimateurAndDay(PlanningEvenement planning) {
        Map<String, List<PosteAffectation>> parJournee = new LinkedHashMap<>();
        for (PosteAffectation poste : planning.getPostes()) {
            if (poste.getAnimateur() == null
                    || poste.getCreneau() == null
                    || poste.getCreneau().getDate() == null
                    || poste.heureDebutEffectif() == null) {
                continue;
            }
            parJournee
                    .computeIfAbsent(
                            dayKey(
                                    poste.getAnimateur().getId(),
                                    poste.getCreneau().getDate()),
                            ignored -> new ArrayList<>())
                    .add(poste);
        }
        return parJournee;
    }

    private static String dayKey(String animateurId, LocalDate date) {
        return animateurId + "|" + date;
    }

    /** Where each stand is set up, by stand id — read off the seats, as {@code PauseAnalyzer} does. */
    private static Map<String, Emplacement> emplacementsByStand(PlanningEvenement planning) {
        Map<String, Emplacement> parStand = new LinkedHashMap<>();
        for (PosteAffectation poste : planning.getPostes()) {
            Stand stand = poste.getStand();
            if (stand != null && stand.getId() != null && stand.getEmplacement() != null) {
                parStand.putIfAbsent(stand.getId(), stand.getEmplacement());
            }
        }
        return parStand;
    }

    /**
     * The list the intendance takes with it. One line per slot rather than a
     * cross-table: a spreadsheet pivots that in two clicks, and a flat file
     * survives a window gaining a half-hour.
     */
    public String generateCsv(RapportIntendance rapport) {
        StringBuilder csv = new StringBuilder("jour;fenetre;emplacement;tranche;personnes;dont mineurs\n");
        for (JourneeIntendance journee : rapport.journees()) {
            for (FenetreIntendance fenetre : journee.fenetres()) {
                for (LigneEmplacement ligne : fenetre.emplacements()) {
                    for (int index = 0; index < fenetre.tranches().size(); index++) {
                        csv.append(journee.date())
                                .append(';')
                                .append(echapper(fenetre.libelle()))
                                .append(';')
                                .append(echapper(ligne.emplacementNom()))
                                .append(';')
                                .append(fenetre.tranches().get(index))
                                .append(';')
                                .append(ligne.personnes().get(index))
                                .append(';')
                                .append(ligne.mineurs().get(index))
                                .append('\n');
                    }
                }
            }
        }
        return csv.toString();
    }

    private static String echapper(String valeur) {
        if (valeur == null) {
            return "";
        }
        if (valeur.contains(";") || valeur.contains("\"") || valeur.contains("\n")) {
            return "\"" + valeur.replace("\"", "\"\"") + "\"";
        }
        return valeur;
    }

    /** One emplacement's tally for one window of one day, filled break by break. */
    private static final class Compte {

        private final Emplacement emplacement;
        /** Slot index (minutes from midnight ÷ 30) → {people, of whom minors}. */
        private final Map<Integer, int[]> parTranche = new LinkedHashMap<>();

        private int total;
        private int totalMineurs;

        Compte(Emplacement emplacement) {
            this.emplacement = emplacement;
        }

        /**
         * Somebody eats here. They count once in the window's total, and once
         * in <b>every</b> half-hour their break spans — a 13:50-14:50 break is
         * two trays to carry at two different moments, not half a tray each.
         */
        void ajouter(CoupureRepasView coupure, boolean mineur) {
            total++;
            if (mineur) {
                totalMineurs++;
            }
            int debut = coupure.debut().toSecondOfDay() / 60;
            int fin = coupure.fin() == null
                    ? debut + coupure.dureeRequiseMinutes()
                    : coupure.fin().toSecondOfDay() / 60;
            for (int tranche = debut / PAS_MINUTES; tranche <= Math.max(debut, fin - 1) / PAS_MINUTES; tranche++) {
                int[] cellule = parTranche.computeIfAbsent(tranche, ignored -> new int[2]);
                cellule[0]++;
                if (mineur) {
                    cellule[1]++;
                }
            }
        }

        LigneEmplacement ligne(List<LocalTime> tranches) {
            List<Integer> personnes = new ArrayList<>(tranches.size());
            List<Integer> mineurs = new ArrayList<>(tranches.size());
            for (LocalTime tranche : tranches) {
                int[] cellule = parTranche.get(tranche.toSecondOfDay() / 60 / PAS_MINUTES);
                personnes.add(cellule == null ? 0 : cellule[0]);
                mineurs.add(cellule == null ? 0 : cellule[1]);
            }
            return new LigneEmplacement(
                    emplacement.getId(), emplacement.getNom(), personnes, mineurs, total, totalMineurs);
        }
    }
}
