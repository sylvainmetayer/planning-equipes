package dev.sylvain.planning.solver;

import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.ParametresQualite;
import java.math.BigDecimal;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The settings every rule actually reads, with the value this edition stored
 * and the form that changes it.
 *
 * <p>The Contraintes screen used to describe a rule without ever saying what
 * it is set to: « pas plus de jours consécutifs que le plafond réglé sur la
 * page Paramètres » left the reader to open another screen, find the right
 * tab, and trust that the field they saw was the one the sentence meant. The
 * catalogue's description is the <i>why</i>; this is the <i>how much</i>, and
 * it belongs beside it.
 *
 * <p>Two things this deliberately is not. It is <b>not the weight</b>: the
 * weight already has its own field on that screen, it is per-rule, and it
 * arbitrates between rules of the same level rather than saying what the rule
 * measures. And it is <b>not the referential data</b> a rule reads — a
 * typologie's ceiling, a stand's premium flag, an animateur's appreciations:
 * those are rows of a referential, not a setting, and the screen already
 * points at them through {@code plancher.lien} when their absence is what
 * flattens a rule.
 *
 * <p>A rule lists <b>every</b> setting it reads, not only its headline
 * threshold. {@code dureeHebdomadaireMax} is the case that makes the
 * difference visible: its ceiling is the weekly one, but what the week is
 * measured in depends on whether the organiser declares the break taken on the
 * post, and by how long. An organiser reading « 48 h » and wondering why a
 * five-day week of 10 h amplitude passes needs the other two values, not a
 * cleaner-looking line. {@code ConstraintParametersStructuralTest} is what
 * keeps the lists honest: a rule that reads a setting and does not declare it
 * fails the build.
 */
public final class ConstraintParameters {

    /** Angular route of the settings page — its two forms are tabs of it. */
    private static final String ECRAN_PARAMETRES = "/parametres";

    /** Tab holding the legal framework, the meal window and the break policy. */
    private static final String ONGLET_LEGAUX = "legaux";

    /** Tab holding « Qualité d'organisation », the organiser's own thresholds. */
    private static final String ONGLET_EDITION = "edition";

    // Setting keys named both by a reference and by the rules that read them.
    private static final String DUREE_HEBDOMADAIRE_MAX_MINUTES = "dureeHebdomadaireMaxMinutes";
    private static final String DUREE_PAUSE_MINUTES = "dureePauseMinutes";
    private static final String COUPURE_REPAS_MINUTES = "coupureRepasMinutes";
    private static final String COUPURE_REPAS_MIDI = "coupureRepasMidi";
    private static final String COUPURE_REPAS_SOIR = "coupureRepasSoir";
    private static final String JOURS_CONSECUTIFS_MAX = "joursConsecutifsMax";

    private ConstraintParameters() {}

    /**
     * One setting of one rule, ready to be shown: the label the form gives it,
     * the value this edition stored, and where to change it.
     *
     * @param libelle the field's own label on the settings form, word for word
     *                — a reader who clicks the link must recognise what they
     *                land on
     * @param valeur  the stored value, already formatted for a human (« 48 h »,
     *                « 20 min », « 8 jours »)
     * @param lien    Angular route of the form
     * @param onglet  which tab of that form, as the {@code onglet} query
     *                parameter of the Paramètres page spells it
     */
    public record ConstraintParameter(String libelle, String valeur, String lien, String onglet) {}

    /**
     * What one setting is called and where it lives, without a value: the
     * static half, which the catalogue can hold and a test can check.
     */
    private record Reference(String libelle, String onglet, Function<Contexte, String> valeur) {}

    /** Both parameter records of an edition, so a reference reads one argument. */
    private record Contexte(ParametresLegaux legaux, ParametresQualite qualite) {}

    private static final Map<String, Reference> REFERENCES = Map.ofEntries(
            Map.entry(
                    DUREE_HEBDOMADAIRE_MAX_MINUTES,
                    new Reference(
                            "Durée hebdomadaire maximale, majeurs",
                            ONGLET_LEGAUX,
                            contexte -> duree(contexte.legaux().getDureeHebdomadaireMaxMinutes()))),
            Map.entry(
                    "dureeHebdomadaireMaxMineurMinutes",
                    new Reference(
                            "Durée hebdomadaire maximale, mineurs",
                            ONGLET_LEGAUX,
                            contexte -> duree(contexte.legaux().getDureeHebdomadaireMaxMineurMinutes()))),
            Map.entry(
                    DUREE_PAUSE_MINUTES,
                    new Reference(
                            "Durée de la pause légale",
                            ONGLET_LEGAUX,
                            contexte -> duree(contexte.legaux().getDureePauseMinutes()))),
            Map.entry(
                    COUPURE_REPAS_MINUTES,
                    new Reference(
                            "Durée de la coupure repas",
                            ONGLET_LEGAUX,
                            contexte -> duree(contexte.legaux().getCoupureRepasMinutes()))),
            Map.entry(
                    COUPURE_REPAS_MIDI,
                    new Reference(
                            "Fenêtre du midi",
                            ONGLET_LEGAUX,
                            contexte -> plage(
                                    contexte.legaux().getCoupureRepasMidiDebut(),
                                    contexte.legaux().getCoupureRepasMidiFin()))),
            Map.entry(
                    COUPURE_REPAS_SOIR,
                    new Reference(
                            "Fenêtre du soir",
                            ONGLET_LEGAUX,
                            contexte -> plage(
                                    contexte.legaux().getCoupureRepasSoirDebut(),
                                    contexte.legaux().getCoupureRepasSoirFin()))),
            Map.entry(
                    "maxEmplacementsDistinctsParJour",
                    new Reference(
                            "Emplacements distincts par jour",
                            ONGLET_EDITION,
                            contexte -> count(contexte.qualite().maxEmplacementsDistinctsParJour()))),
            Map.entry(
                    "typologiesDistinctesMax",
                    new Reference(
                            "Typologies distinctes par animateur",
                            ONGLET_EDITION,
                            contexte -> count(contexte.qualite().typologiesDistinctesMax()))),
            Map.entry(
                    JOURS_CONSECUTIFS_MAX,
                    new Reference(
                            "Jours travaillés d'affilée",
                            ONGLET_EDITION,
                            contexte -> jours(contexte.qualite().joursConsecutifsMax()))),
            Map.entry(
                    "vitesseMarcheKmH",
                    new Reference(
                            "Vitesse de marche",
                            ONGLET_EDITION,
                            contexte -> decimal(contexte.qualite().vitesseMarcheKmH()) + " km/h")),
            Map.entry(
                    "facteurDetour",
                    new Reference(
                            "Facteur de détour",
                            ONGLET_EDITION,
                            contexte -> decimal(contexte.qualite().facteurDetour()))),
            Map.entry(
                    "toleranceTrajetMinutes",
                    new Reference(
                            "Tolérance de trajet",
                            ONGLET_EDITION,
                            contexte -> duree(contexte.qualite().toleranceTrajetMinutes()))),
            Map.entry(
                    "heureServiceTardif",
                    new Reference(
                            "Heure d'un service tardif",
                            ONGLET_EDITION,
                            contexte -> heure(contexte.qualite().heureServiceTardif()))),
            Map.entry(
                    "heureServiceMatinal",
                    new Reference(
                            "Heure d'un service matinal",
                            ONGLET_EDITION,
                            contexte -> heure(contexte.qualite().heureServiceMatinal()))),
            Map.entry(
                    "reposSouhaiteApresServiceTardifMinutes",
                    new Reference(
                            "Repos souhaité après un service tardif",
                            ONGLET_EDITION,
                            contexte -> duree(contexte.qualite().reposSouhaiteApresServiceTardifMinutes()))));

    /**
     * Which settings each rule reads. A rule absent from this map reads none —
     * the ordinary case: most rules measure the plan against the referential
     * or against an article of the Code du travail, neither of which has a
     * field to fill.
     *
     * <p>The order inside a list is the reading order, not the declaration
     * order of the record: the threshold the rule is named after comes first,
     * and what changes the measure follows.
     */
    private static final Map<String, List<String>> PAR_CONTRAINTE = Map.ofEntries(
            // The daily caps themselves are the Code du travail's (10 h, and the
            // minors' own), never a field — what an organiser sets is how much
            // of the day is not work.
            Map.entry("dureeQuotidienneMaxMajeur", List.of(DUREE_PAUSE_MINUTES)),
            Map.entry("dureeQuotidienneMaxMineur", List.of(DUREE_PAUSE_MINUTES)),
            Map.entry("dureeHebdomadaireMax", List.of(DUREE_HEBDOMADAIRE_MAX_MINUTES, DUREE_PAUSE_MINUTES)),
            Map.entry("dureeHebdomadaireMaxDeuxSemaines", List.of(DUREE_HEBDOMADAIRE_MAX_MINUTES, DUREE_PAUSE_MINUTES)),
            Map.entry("dureeHebdomadaireMaxMineur", List.of("dureeHebdomadaireMaxMineurMinutes", DUREE_PAUSE_MINUTES)),
            Map.entry("travailContinuMaxMajeur", List.of(DUREE_PAUSE_MINUTES)),
            Map.entry("travailContinuMaxMineur", List.of(DUREE_PAUSE_MINUTES)),
            Map.entry(
                    "coupureRepasObligatoire", List.of(COUPURE_REPAS_MINUTES, COUPURE_REPAS_MIDI, COUPURE_REPAS_SOIR)),
            Map.entry(
                    "coupureRepasPlacementPrefere",
                    List.of(COUPURE_REPAS_MINUTES, COUPURE_REPAS_MIDI, COUPURE_REPAS_SOIR)),
            Map.entry("limiterEmplacementsParJour", List.of("maxEmplacementsDistinctsParJour")),
            Map.entry(
                    "trajetInsuffisantEntrePostes",
                    List.of("vitesseMarcheKmH", "facteurDetour", "toleranceTrajetMinutes")),
            Map.entry("limiterTypologiesDistinctesParAnimateur", List.of("typologiesDistinctesMax")),
            Map.entry("maxJoursConsecutifsTravailles", List.of(JOURS_CONSECUTIFS_MAX)),
            Map.entry("maxJoursConsecutifsTravaillesDur", List.of(JOURS_CONSECUTIFS_MAX)),
            Map.entry(
                    "eviterFermeturePuisOuverture",
                    List.of("heureServiceTardif", "heureServiceMatinal", "reposSouhaiteApresServiceTardifMinutes")));

    /**
     * The settings {@code name} reads, valued against this edition. Empty for
     * a rule that reads none, and for a name the catalogue does not carry.
     */
    public static List<ConstraintParameter> of(String name, ParametresLegaux legaux, ParametresQualite qualite) {
        List<String> cles = PAR_CONTRAINTE.get(name);
        if (cles == null || legaux == null || qualite == null) {
            return List.of();
        }
        Contexte contexte = new Contexte(legaux, qualite);
        return cles.stream()
                .map(REFERENCES::get)
                .map(reference -> new ConstraintParameter(
                        reference.libelle(), reference.valeur().apply(contexte), ECRAN_PARAMETRES, reference.onglet()))
                .toList();
    }

    /** The setting keys each rule declares — read by the structural test. */
    public static Map<String, List<String>> declarations() {
        return PAR_CONTRAINTE;
    }

    /** Every setting key the references know, so a declaration cannot name a field nobody fills. */
    public static Set<String> keys() {
        return REFERENCES.keySet();
    }

    /** « 20 min », « 48 h », « 1 h 30 » — never « 2880 minutes », which nobody reads as a week. */
    private static String duree(int minutes) {
        if (minutes < 60) {
            return minutes + " min";
        }
        int heures = minutes / 60;
        int reste = minutes % 60;
        return reste == 0 ? heures + " h" : heures + " h " + String.format("%02d", reste);
    }

    private static String heure(LocalTime heure) {
        return heure == null ? "non réglée" : heure.toString();
    }

    private static String plage(LocalTime debut, LocalTime fin) {
        return debut == null || fin == null ? "non réglée" : debut + " – " + fin;
    }

    /** « 1,3 », « 4 » — a French decimal comma, and no trailing zero. */
    private static String decimal(double valeur) {
        BigDecimal nombre = BigDecimal.valueOf(valeur).stripTrailingZeros();
        return (nombre.scale() < 0 ? nombre.setScale(0) : nombre)
                .toPlainString()
                .replace('.', ',');
    }

    private static String count(int valeur) {
        return String.valueOf(valeur);
    }

    private static String jours(int valeur) {
        return valeur + (valeur > 1 ? " jours" : " jour");
    }
}
