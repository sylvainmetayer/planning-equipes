package dev.sylvain.planning.solver;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Business-facing catalogue of every constraint enforced by
 * {@link PlanningConstraintProvider}. The {@code name} of each entry must match
 * the id passed to {@code asConstraint(...)}, so a score analysis can be
 * merged with its human-readable description on the constraints screen.
 */
public final class ConstraintCatalog {

    public enum Niveau {
        HARD,
        MEDIUM,
        SOFT
    }

    /** The one category whose rules are dosed rather than switched off — see {@link ConstraintDefinition#dosable()}. */
    public static final String CATEGORIE_QUALITE = "Qualité d'organisation";

    /**
     * The meal break, and the first « organisation rule » held hard. No article
     * of the Code du travail requires lunch — L3121-16 covers only the twenty
     * minutes owed at the sixth hour, which {@code travailContinuMaxMajeur}
     * already carries. This is the rule of the staffing workbook, kept hard by
     * choice and protected all the same: without it the solver returns a
     * ten-hour unbroken day scoring zero hard (issue #438).
     */
    public static final String CATEGORIE_ORGANISATION_REPAS = "Organisation (repas)";

    /**
     * Categories whose rules found the plan in law, in the organiser's own
     * safety policy for young workers, or in the meal rule the event is built
     * on. Switching one off lets the solver return a plan scoring zero hard
     * that nonetheless breaks the Code du travail — or holds a ten-hour day
     * with no meal break — so the UI asks for a confirmation naming the rule
     * and what founds it, and the Contraintes screen keeps showing what is
     * off. See {@code docs/contraintes.md}.
     */
    public static final Set<String> CATEGORIES_PROTEGEES =
            Set.of("Légal (mineurs)", "Légal (temps de travail)", "Sécurité (mineurs)", CATEGORIE_ORGANISATION_REPAS);

    /**
     * The two categories whose rules are founded on an article of the Code du
     * travail, and only those. The other protected categories —
     * « Sécurité (mineurs) », « Organisation (repas) » — are rules the
     * organiser sets: switching one off engages them just as much, but it does
     * not make the plan unlawful, and the confirmation must not say it does.
     */
    public static final Set<String> CATEGORIES_LEGALES = Set.of("Légal (mineurs)", "Légal (temps de travail)");

    /**
     * The rules the catalogue ships <b>switched off</b>: they exist, they are
     * described, an organiser can turn them on, but no edition enforces them
     * unless someone asks. Every other rule is active until an edition says
     * otherwise — the convention the {@code constraint_toggle} table was born
     * with.
     *
     * <p>A rule lands here when it is a policy rather than an obligation, and
     * when the organisation this deployment serves fills it outside the plan.
     * {@code mineurNecessiteEncadrementMajeur} is the case that created the
     * mechanism: no article of the Code du travail requires an adult beside a
     * young worker, and the organiser's managers — never planned — provide
     * that supervision themselves (issue #595, ADR 0041). The legal framework
     * for minors does not move an inch: {@code travailDeNuitInterditPourMineur},
     * {@code standReserveAuxMajeurs}, the duration caps and the rest stay
     * active.</p>
     *
     * <p>{@code maxJoursConsecutifsTravaillesDur} is the second: the hard form
     * of the six-day ceiling, which no article of the Code du travail
     * requires. The medium form is the default; an organisation that wants the
     * run blocked rather than dosed turns this one on. Same mechanism, no
     * migration.</p>
     */
    public static final Set<String> DESACTIVEES_PAR_DEFAUT =
            Set.of("mineurNecessiteEncadrementMajeur", "maxJoursConsecutifsTravaillesDur");

    /**
     * What to do about a rule in default, rule by rule.
     *
     * <p>The pivot of issue #496 says <b>where</b> the breaches concentrate;
     * « 210 écarts ici » is a measurement, not a next step, and a reader who
     * cannot act on a screen stops opening it. This is the next step: the
     * lever that actually moves that rule, in the organiser's own terms —
     * hire, open a stand later, vet somebody, lower a weight.</p>
     *
     * <p>Only the rules whose lever is specific are listed. The others fall
     * back on {@link ConstraintDefinition#remediation()}, which answers by
     * category rather than inventing a false precision: what a legal rule
     * needs is never « change a setting ».</p>
     */
    private static final Map<String, String> REMEDIATIONS = Map.ofEntries(
            Map.entry(
                    "posteDoitEtrePourvu",
                    "Il manque du monde sur ces places : ajoutez des animateurs disponibles ce jour-là, "
                            + "réduisez l'effectif minimum du stand, ou fermez le créneau."),
            Map.entry(
                    "animateurDisponible",
                    "Le plan pose quelqu'un un jour qu'il a déclaré indisponible : corrigez la fiche de "
                            + "l'animateur si la déclaration a changé, sinon laissez le solveur placer "
                            + "quelqu'un d'autre."),
            Map.entry(
                    "plafondCreneauxParTypologie",
                    "Le plafond de la typologie est atteint : montez-le sur l'écran Typologies, ou faites "
                            + "apprécier cette typologie par d'autres animateurs pour élargir le vivier."),
            Map.entry(
                    "standComplexeAvecReferent",
                    "Aucun animateur confirmé sur ces créneaux : montez le niveau d'un animateur sur la "
                            + "typologie du stand, ou retirez l'exigence de référent au stand."),
            Map.entry(
                    "equilibrerCharge",
                    "La charge est inégale : cherchez qui est très au-dessus sur l'écran Heures, et ouvrez "
                            + "des disponibilités ailleurs — ou baissez le poids si l'écart vous convient."),
            Map.entry(
                    "experienceRequisePourStandsPremium",
                    "Un stand premium est tenu sans animateur expérimenté : appréciez davantage "
                            + "d'animateurs sur sa typologie, ou retirez le drapeau premium au stand."),
            Map.entry(
                    "souhaitsIncompatibles",
                    "Les souhaits se contredisent ou sont hors d'atteinte : c'est une règle souple, "
                            + "regardez les souhaits concernés sur les fiches, ou baissez son poids."),
            Map.entry(
                    "appreciationIncompatible",
                    "Le plan place des animateurs sur des typologies qu'ils n'apprécient pas : complétez "
                            + "les appréciations, ou acceptez l'écart en baissant le poids."),
            Map.entry(
                    "coupureRepasObligatoire",
                    "La journée ne laisse pas la place au repas : coupez la journée en deux vacations, "
                            + "élargissez la fenêtre repas dans les paramètres légaux, ou raccourcissez le "
                            + "créneau."),
            Map.entry(
                    "limiterTypologiesDistinctesParAnimateur",
                    "Trop de typologies différentes pour une même personne : relevez le plafond dans les "
                            + "paramètres légaux, ou baissez le poids de la règle."),
            Map.entry(
                    "stabiliteDuPlanPublie",
                    "Le plan s'écarte de ce qui a été publié : chaque écart est une vacation à "
                            + "re-annoncer. Verrouillez ce qui doit tenir, ou montez le poids de la règle."),
            Map.entry(
                    "limiterEmplacementsParJour",
                    "Trop d'allers-retours entre emplacements dans la journée : relevez le plafond dans "
                            + "les paramètres légaux, ou baissez le poids."),
            Map.entry(
                    "maxJoursConsecutifsTravailles",
                    "Trop de jours d'affilée : ouvrez des disponibilités sur d'autres personnes pour "
                            + "couvrir ces journées, ou baissez le poids de la règle si la série vous "
                            + "convient. Le plafond de six jours n'est pas réglable — aucun paramètre "
                            + "légal ne le porte."),
            Map.entry(
                    "maxJoursConsecutifsTravaillesDur",
                    "Trop de jours d'affilée, et cette édition tient la règle en dur : ouvrez des "
                            + "disponibilités sur d'autres personnes pour couvrir ces journées. Si la série "
                            + "doit rester possible, désactivez maxJoursConsecutifsTravaillesDur depuis "
                            + "l'écran Contraintes — maxJoursConsecutifsTravailles continue alors de la "
                            + "pénaliser sans bloquer le plan."));

    /** Read by the Contraintes screen when a rule has no lever of its own. */
    private static final Map<String, String> REMEDIATIONS_PAR_CATEGORIE = Map.of(
            "Légal (mineurs)",
            "Une règle légale ne se règle pas : il faut changer le plan. Retirez le mineur de ces "
                    + "créneaux, ou raccourcissez-les.",
            "Légal (temps de travail)",
            "Une règle légale ne se règle pas : il faut changer le plan. Ajoutez du monde pour "
                    + "alléger ces journées, ou raccourcissez les vacations.",
            "Sécurité (mineurs)",
            "Règle de sécurité posée par l'organisateur : ajoutez un majeur sur ces créneaux, ou "
                    + "assumez l'encadrement hors planning et laissez la règle éteinte.",
            CATEGORIE_ORGANISATION_REPAS,
            "Réglez la fenêtre repas et sa durée dans les paramètres légaux, ou découpez les "
                    + "journées trop longues en deux vacations.",
            "Affectation",
            "Le plan ne peut pas tenir en l'état : ajoutez des animateurs disponibles, ou "
                    + "allégez ce que les stands demandent.",
            "Verrouillage",
            "C'est un verrou posé à la main : levez-le depuis l'écran Verrouillages s'il n'a plus " + "lieu d'être.",
            "Ajustement manuel",
            "C'est une contrainte ad hoc écrite à la main : revoyez-la ou supprimez-la depuis "
                    + "l'écran Ajustements manuels.");

    /** The fallback of the fallback: a rule of a category nobody wrote a lever for. */
    private static final String REMEDIATION_PAR_DEFAUT =
            "Cette règle arbitre du confort : baissez son poids si l'écart vous convient, montez-le si "
                    + "elle compte plus que les autres du même niveau.";

    public record ConstraintDefinition(String name, Niveau niveau, String categorie, String description) {

        /**
         * What an organiser can actually do about this rule being in default —
         * the lever of {@link #REMEDIATIONS}, the one of its category, or the
         * generic one. Never empty: a screen that says « 210 écarts » and
         * nothing else is a screen nobody opens twice.
         */
        public String remediation() {
            String propre = REMEDIATIONS.get(name);
            if (propre != null) {
                return propre;
            }
            return REMEDIATIONS_PAR_CATEGORIE.getOrDefault(categorie, REMEDIATION_PAR_DEFAUT);
        }

        /** True when disabling this rule needs the confirmation described on {@link #CATEGORIES_PROTEGEES}. */
        public boolean protegee() {
            return CATEGORIES_PROTEGEES.contains(categorie);
        }

        /**
         * True when an article of the Code du travail founds this rule — see
         * {@link #CATEGORIES_LEGALES}. A protected rule that is <i>not</i>
         * founded in law is no less binding on the organiser; it is simply not
         * the law, and the confirmation asked before switching it off says so
         * in its own words.
         */
        public boolean legale() {
            return CATEGORIES_LEGALES.contains(categorie);
        }

        /**
         * True for the rules that genuinely vary from one organiser to the
         * next: the MEDIUM ones of « Qualité d'organisation ». They arbitrate
         * comfort against comfort — continuity on a premium stand against a
         * balanced workload, wishes against experience — and every organiser
         * ranks those differently. They are meant to be <b>dosed</b>: a rule
         * nobody cares about goes to 1 and gets outvoted, the one that matters
         * goes up. Switching them off is possible and rarely what is wanted.
         */
        public boolean dosable() {
            return niveau == Niveau.MEDIUM && CATEGORIE_QUALITE.equals(categorie);
        }

        /**
         * False for the rules of {@link #DESACTIVEES_PAR_DEFAUT}: an edition
         * that never touched this rule does not enforce it. The Contraintes
         * screen shows it as off, and turning it on is what stores a toggle —
         * the mirror image of what a toggle does for every other rule.
         */
        public boolean activeByDefault() {
            return !DESACTIVEES_PAR_DEFAUT.contains(name);
        }
    }

    private static final List<ConstraintDefinition> DEFINITIONS = List.of(
            new ConstraintDefinition(
                    "posteDoitEtrePourvu",
                    Niveau.HARD,
                    "Affectation",
                    "Chaque place ouverte sur un stand doit être pourvue par un animateur."),
            new ConstraintDefinition(
                    "animateurDisponible",
                    Niveau.HARD,
                    "Affectation",
                    "Un animateur ne peut pas être affecté un jour qu'il a déclaré indisponible."),
            new ConstraintDefinition(
                    "pasDeChevauchementHoraire",
                    Niveau.HARD,
                    "Affectation",
                    "Un animateur ne peut pas tenir deux postes dont les créneaux se chevauchent dans le temps "
                            + "(y compris deux créneaux distincts qui se recouvrent, et pas seulement deux postes "
                            + "sur le même créneau)."),
            new ConstraintDefinition(
                    "plafondCreneauxParTypologie",
                    Niveau.HARD,
                    "Affectation",
                    "Sur une typologie qui porte un plafond, un animateur ne tient pas plus que ce nombre de "
                            + "créneaux sur l'ensemble de l'édition. Un poste compte pour chaque typologie que son "
                            + "stand propose. Une typologie sans plafond n'impose rien."),
            new ConstraintDefinition(
                    "standReserveAuxMajeurs",
                    Niveau.HARD,
                    "Légal (mineurs)",
                    "Les stands réservés aux majeurs ne peuvent accueillir aucun mineur."),
            // Reclassified under "Sécurité (mineurs)": no article of the Code du travail requires an
            // adult next to a young worker on their seat. It is a safety policy of the organiser, kept
            // as a HARD constraint by choice. See docs/contraintes.md.
            new ConstraintDefinition(
                    "mineurNecessiteEncadrementMajeur",
                    Niveau.HARD,
                    "Sécurité (mineurs)",
                    "Éteinte par défaut. Un mineur doit toujours être accompagné d'au moins un majeur sur le "
                            + "même stand et le même créneau. Règle de sécurité posée par l'organisateur, pas une "
                            + "obligation du Code du travail : l'organisateur de l'évènement la remplit par ses "
                            + "managers, qui ne sont pas planifiés, et ne la demande donc pas au solveur. Une "
                            + "organisation sans encadrant hors planning l'allume depuis l'écran Contraintes."),
            new ConstraintDefinition(
                    "travailDeNuitInterditPourMineur",
                    Niveau.HARD,
                    "Légal (mineurs)",
                    "Un mineur ne peut pas être affecté sur un créneau qui empiète sur sa nuit légale : "
                            + "20 h-6 h avant 16 ans, 22 h-6 h de 16 à 18 ans "
                            + "(Code du travail art. L3163-1)."),
            new ConstraintDefinition(
                    "dureeQuotidienneMaxMineur",
                    Niveau.HARD,
                    "Légal (mineurs)",
                    "Un mineur ne peut pas dépasser 8 heures de travail effectif sur une même journée "
                            + "(Code du travail art. L3162-1), ramenées à 7 heures avant 16 ans "
                            + "(art. D4153-3). Les pauses dues sont déduites : une pause, relayée ou prise "
                            + "comme un trou, est du repos et non du travail effectif."),
            new ConstraintDefinition(
                    "travailInterditJourFerieMineur",
                    Niveau.HARD,
                    "Légal (mineurs)",
                    "Un mineur ne peut pas travailler un jour férié légal "
                            + "(Code du travail art. L3164-6, liste de l'art. L3133-1). Aucune dérogation "
                            + "sectorielle n'est appliquée : celle de l'art. R3164-2 reste à instruire."),
            new ConstraintDefinition(
                    "reposHebdomadaireMineur",
                    Niveau.HARD,
                    "Légal (mineurs)",
                    "Un mineur bénéficie de deux jours de repos consécutifs à l'intérieur de chaque semaine "
                            + "civile, du lundi 0 h au dimanche 24 h (Code du travail art. L3164-2 et L3121-35) : un "
                            + "dimanche et le lundi qui le suit sont chacun un jour de repos de leur semaine, mais "
                            + "ne forment la paire d'aucune des deux. Les "
                            + "dérogations conventionnelles supposent un accord étendu ou une autorisation de "
                            + "l'inspection du travail : elles ne sont pas présumées."),
            new ConstraintDefinition(
                    "travailContinuMaxMineur",
                    Niveau.HARD,
                    "Légal (mineurs)",
                    "Aucune période de travail ininterrompue de plus de 4 h 30 pour un mineur : au-delà, chaque "
                            + "pause due doit être prise, soit comme un trou dans la grille, soit relayée par un "
                            + "collègue du même stand tenant une place pendant toute la pause. Une pause que "
                            + "personne ne peut prendre est un écart dur (Code du travail art. L3162-3). La durée "
                            + "est celle paramétrée, portée à 30 minutes au minimum pour un mineur : ce plancher "
                            + "est d'ordre public."),
            new ConstraintDefinition(
                    "dureeHebdomadaireMax",
                    Niveau.HARD,
                    "Légal (temps de travail)",
                    "Aucun animateur majeur (tous payés, manager ou non) ne peut dépasser la durée hebdomadaire de "
                            + "travail effectif maximale paramétrée (48 h par défaut, Code du travail art. L3121-20, "
                            + "d'ordre public). Les pauses dues sont déduites, comme au plafond quotidien."),
            new ConstraintDefinition(
                    "dureeHebdomadaireMaxDeuxSemaines",
                    Niveau.HARD,
                    "Légal (temps de travail)",
                    "Un animateur majeur ne peut pas atteindre la durée hebdomadaire maximale sur deux semaines ISO "
                            + "consécutives : 48 h une semaine puis 48 h la suivante est refusé, 47 h puis 48 h reste "
                            + "permis. Forme courte et opérationnelle de la moyenne de 44 h sur douze semaines "
                            + "(Code du travail art. L3121-22) — la seule qui ait un sens sur un événement de quinze "
                            + "jours. Le seuil est celui du paramètre de durée hebdomadaire maximale, jamais une "
                            + "seconde constante. Une semaine se juge pleine en travail effectif : les pauses "
                            + "dues en sont déduites."),
            new ConstraintDefinition(
                    "dureeHebdomadaireMaxMineur",
                    Niveau.HARD,
                    "Légal (mineurs)",
                    "Un mineur ne peut pas dépasser 35 heures de travail effectif par semaine "
                            + "(Code du travail art. L3162-1 ; art. D4153-3 pour les 14 à moins de 16 ans employés "
                            + "pendant les vacances scolaires). Les pauses dues sont déduites, comme au "
                            + "plafond quotidien."),
            new ConstraintDefinition(
                    "dureeQuotidienneMaxMajeur",
                    Niveau.HARD,
                    "Légal (temps de travail)",
                    "Un animateur majeur ne peut pas dépasser 10 heures de travail effectif sur une même journée "
                            + "(Code du travail art. L3121-18). Les pauses dues sont déduites : une pause "
                            + "relayée est du repos, pas du travail effectif (art. L3121-1)."),
            new ConstraintDefinition(
                    "reposQuotidienMinimal",
                    Niveau.HARD,
                    "Légal (temps de travail)",
                    "Entre deux journées travaillées, tout animateur bénéficie d'un repos quotidien minimal : "
                            + "11 h pour un majeur (art. L3131-1), 12 h pour un mineur et 14 h avant 16 ans "
                            + "(art. L3164-1)."),
            new ConstraintDefinition(
                    "maxJoursTravaillesParSemaine",
                    Niveau.HARD,
                    "Légal (temps de travail)",
                    "Aucun animateur ne peut travailler plus de six jours dans la même semaine "
                            + "(Code du travail art. L3132-1)."),
            new ConstraintDefinition(
                    "reposHebdomadaireMinimal",
                    Niveau.HARD,
                    "Légal (temps de travail)",
                    "Chaque animateur bénéficie, dans chaque semaine, d'un repos hebdomadaire de 35 heures "
                            + "consécutives : 24 heures (art. L3132-2) auxquelles s'ajoutent les 11 heures de repos "
                            + "quotidien (art. L3131-1). Un repos à cheval sur le lundi compte en entier "
                            + "pour la semaine où il tombe."),
            new ConstraintDefinition(
                    "travailContinuMaxMajeur",
                    Niveau.HARD,
                    "Légal (temps de travail)",
                    "Aucune période de travail ininterrompue de plus de 6 heures pour un majeur : au-delà, chaque "
                            + "pause due doit être prise, soit comme un trou dans la grille d'au moins la durée "
                            + "paramétrée, soit relayée par un collègue du même stand tenant une place pendant "
                            + "toute la pause. Une pause que personne ne peut prendre est un écart dur (Code du "
                            + "travail art. L3121-16, au minimum 20 minutes). C'est aussi ce qui autorise la "
                            + "déduction de la pause des plafonds quotidien et hebdomadaire : dans un plan sans "
                            + "écart dur, toute pause déduite a bien été prise."),
            new ConstraintDefinition(
                    "coupureRepasObligatoire",
                    Niveau.HARD,
                    CATEGORIE_ORGANISATION_REPAS,
                    "Qui travaille de part et d'autre d'une fenêtre repas doit disposer, entièrement dans cette "
                            + "fenêtre, d'une coupure libre de la durée paramétrée (60 min par défaut, midi "
                            + "12 h-14 h et soir 19 h-21 h). Commencer sa journée à l'ouverture de la fenêtre, ou "
                            + "la terminer à sa fermeture, ne doit rien : on a mangé avant, ou on mangera après. "
                            + "Une journée à cheval sur les deux fenêtres doit deux coupures. Ce n'est pas une "
                            + "obligation du Code du travail — la seule pause qu'il impose est celle de 20 minutes "
                            + "à la sixième heure (art. L3121-16), portée par travailContinuMaxMajeur — mais la "
                            + "règle d'organisation de l'événement, tenue en dur par choix. La pause légale et "
                            + "la coupure repas restent deux choses distinctes : la coupure d'une heure ne tient "
                            + "pas lieu de relais, et un relais ne tient pas lieu de repas — même si un trou "
                            + "d'une heure coupe bien la séquence de six heures au passage."),
            new ConstraintDefinition(
                    "coupureRepasPlacementPrefere",
                    Niveau.SOFT,
                    "Préférences",
                    "Entre deux coupures repas possibles dans la même fenêtre, préférer celle vers laquelle la "
                            + "fenêtre penche : le midi la plus tard — 13 h-14 h plutôt que 12 h-13 h, les stands "
                            + "viennent d'ouvrir — et le soir la plus tôt, pour rouvrir ensuite. La couverture des "
                            + "stands, elle, est dure : c'est son arbitrage avec cette préférence qui répartit la "
                            + "rotation."),
            new ConstraintDefinition(
                    "indisponibiliteForcee",
                    Niveau.HARD,
                    "Contraintes ad hoc",
                    "Indisponibilité posée manuellement par l'administrateur : l'animateur ne doit jamais être affecté sur le périmètre visé."),
            new ConstraintDefinition(
                    "incompatibiliteAdHoc",
                    Niveau.HARD,
                    "Contraintes ad hoc",
                    "Deux animateurs déclarés incompatibles ne doivent jamais travailler sur le même créneau."),
            new ConstraintDefinition(
                    "affectationForcee",
                    Niveau.HARD,
                    "Contraintes ad hoc",
                    "Affectation imposée par l'administrateur : l'animateur doit être présent sur le créneau ou le stand visé."),
            new ConstraintDefinition(
                    "affiniteAdHoc",
                    Niveau.SOFT,
                    "Contraintes ad hoc",
                    "Paire d'animateurs à privilégier : chaque créneau où les deux sont affectés au même stand est "
                            + "récompensé. Contrainte souple : elle favorise la co-affectation quand c'est possible, "
                            + "sans jamais la forcer."),
            new ConstraintDefinition(
                    "animateurVerrouilleFige",
                    Niveau.HARD,
                    "Verrouillage du planning",
                    "Le planning d'un animateur verrouillé ne bouge plus : ses postes validés sont figés et "
                            + "le solveur ne peut plus lui en attribuer de nouveaux."),
            new ConstraintDefinition(
                    "animateurVerrouilleCreneauFige",
                    Niveau.HARD,
                    "Verrouillage du planning",
                    "Un échange validé est figé sur son créneau : ce que chacun des deux animateurs y tient "
                            + "après l'échange ne bouge plus, sans geler le reste de leur planning."),
            new ConstraintDefinition(
                    "standComplexeAvecReferent",
                    Niveau.MEDIUM,
                    "Qualité d'organisation",
                    "Chaque stand devrait compter au moins un référent sur chaque créneau."),
            new ConstraintDefinition(
                    "equilibrerCharge",
                    Niveau.MEDIUM,
                    "Qualité d'organisation",
                    "La charge de travail doit être répartie équitablement entre les animateurs."),
            new ConstraintDefinition(
                    "stabiliteDuPlanPublie",
                    Niveau.MEDIUM,
                    "Qualité d'organisation",
                    "Une fois un planning publié, chaque personne déplacée d'un siège qu'elle tenait dans le plan "
                            + "publié coûte : le solveur ne bouscule les gens déjà prévenus que si le gain vaut "
                            + "le dérangement. Muette tant que rien n'a été publié ; une vacation que le plan "
                            + "publié n'avait pas — autre jour, autres heures ou autre stand — reste libre."),
            new ConstraintDefinition(
                    "repartitionMineursParCreneau",
                    Niveau.MEDIUM,
                    "Qualité d'organisation",
                    "Sur un créneau, un stand ne devrait pas compter plus de mineurs que de majeurs."),
            new ConstraintDefinition(
                    "experienceRequisePourStandsPremium",
                    Niveau.MEDIUM,
                    "Qualité d'organisation",
                    "Un stand premium ne devrait pas être tenu par un animateur débutant sur sa typologie."),
            new ConstraintDefinition(
                    "eviterRoulementStandsPremium",
                    Niveau.MEDIUM,
                    "Qualité d'organisation",
                    "Sur un stand premium, limiter le nombre d'animateurs différents qui s'y relaient au-delà d'un "
                            + "équipage : on privilégie la continuité."),
            new ConstraintDefinition(
                    "eviterChangementEmplacementEloigne",
                    Niveau.MEDIUM,
                    "Qualité d'organisation",
                    "Entre deux créneaux consécutifs, éviter de faire basculer un animateur vers un stand dont "
                            + "l'emplacement est éloigné (> 300 m à vol d'oiseau) de celui du créneau précédent."),
            new ConstraintDefinition(
                    "limiterEmplacementsParJour",
                    Niveau.MEDIUM,
                    "Qualité d'organisation",
                    "Sur une même journée, limiter le nombre d'emplacements distincts visités par un animateur "
                            + "(plafond réglable, 3 par défaut) : au-delà, la journée est dispersée quelles que "
                            + "soient les distances."),
            new ConstraintDefinition(
                    "eviterEnchainementStandsEpuisants",
                    Niveau.MEDIUM,
                    "Qualité d'organisation",
                    "Entre deux créneaux consécutifs, éviter d'enchaîner un animateur sur deux stands physiquement "
                            + "épuisants sans repos ni stand plus facile entre les deux."),
            new ConstraintDefinition(
                    "eviterFermeturePuisOuverture",
                    Niveau.MEDIUM,
                    CATEGORIE_QUALITE,
                    "Après une vacation qui finit tard (22 h par défaut), éviter une reprise matinale le lendemain "
                            + "(10 h par défaut) : on souhaite alors 12 h de repos plutôt que le minimum légal. "
                            + "Préférence d'organisation, pas une obligation du Code du travail : seules les minutes "
                            + "au-dessus du repos quotidien légal sont comptées ici, celles en dessous restent "
                            + "l'affaire de reposQuotidienMinimal, qui les tient en dur. La règle est donc muette "
                            + "quand la loi exige déjà autant (un mineur, 12 h ; avant 16 ans, 14 h)."),
            new ConstraintDefinition(
                    "appreciationIncompatible",
                    Niveau.MEDIUM,
                    "Qualité d'organisation",
                    "L'appréciation de l'administrateur ne couvre aucune typologie de jeu proposée par le stand."),
            new ConstraintDefinition(
                    "souhaitsIncompatibles",
                    Niveau.MEDIUM,
                    "Qualité d'organisation",
                    "Aucune des typologies de jeu proposées par le stand ne figure dans les souhaits déclarés de "
                            + "l'animateur."),
            new ConstraintDefinition(
                    "limiterTypologiesDistinctesParAnimateur",
                    Niveau.MEDIUM,
                    "Qualité d'organisation",
                    "Un animateur devrait intervenir sur un petit nombre de typologies de jeu (plafond réglable, "
                            + "2 par défaut) sur l'ensemble de l'édition, et pas seulement sur une journée : deux "
                            + "typologies le même après-midi et deux à une semaine d'écart comptent pareil."),
            new ConstraintDefinition(
                    "maxJoursConsecutifsTravailles",
                    Niveau.MEDIUM,
                    "Qualité d'organisation",
                    "Un animateur ne devrait pas travailler plus de jours consécutifs que le plafond réglé "
                            + "sur la page Paramètres (huit par défaut) sans au moins un jour de repos : moins "
                            + "est possible, plus ne devrait pas l'être. Règle d'organisation, dosable : aucun "
                            + "article du Code du travail n'impose un décompte glissant (L3132-1 se lit sur la "
                            + "semaine civile, Cass. soc. 13 nov. 2025, n° 24-10.733)."),
            new ConstraintDefinition(
                    "maxJoursConsecutifsTravaillesDur",
                    Niveau.HARD,
                    "Qualité d'organisation",
                    "Éteinte par défaut. Le même plafond de jours consécutifs, tenu en dur : au-delà, le "
                            + "plan est refusé au lieu d'être pénalisé. Le seuil est celui de l'édition, "
                            + "réglable sur la page Paramètres : les deux formes le lisent au même endroit. "
                            + "Un poids ne change jamais le niveau "
                            + "d'une règle, d'où une contrainte séparée, qu'une édition allume depuis l'écran "
                            + "Contraintes, par activer_contrainte ou par contraintes.activees d'un scénario. "
                            + "Reste rangée en « Qualité d'organisation » et non en « Légal » : c'est une "
                            + "politique de l'organisateur, pas une obligation du Code du travail."),
            new ConstraintDefinition(
                    "favoriserMixiteDesNiveaux",
                    Niveau.SOFT,
                    "Préférences",
                    "Quand un référent est présent sur un créneau, y associer un débutant pour favoriser la montée en compétence."),
            new ConstraintDefinition(
                    "equilibrerCreneauxPenibles",
                    Niveau.SOFT,
                    "Préférences",
                    "Répartir équitablement entre animateurs les créneaux pénibles (stands épuisants ou premium)."),
            new ConstraintDefinition(
                    "preserverBufferPolyvalents",
                    Niveau.SOFT,
                    "Préférences",
                    "Garder au moins un animateur polyvalent (typologie ninja) libre sur chaque créneau, pour pouvoir "
                            + "réparer le planning en cas d'absence de dernière minute."));

    private ConstraintCatalog() {}
    /**
     * Every definition indexed by name, so a caller can attach the
     * business-facing niveau/catégorie/description to a raw constraint analysis
     * without a linear scan.
     *
     * <p>Lived in {@code PlanningService} for a while, which made three classes
     * depend on the service for what is a pure index of the list below.</p>
     */
    public static final Map<String, ConstraintDefinition> PAR_NOM = definitions().stream()
            .collect(Collectors.toUnmodifiableMap(ConstraintDefinition::name, Function.identity()));

    /**
     * Names of every constraint enforced at {@link Niveau#HARD}.
     *
     * <p>A diagnostic only builds per-match violations for these: a soft or
     * medium constraint can have thousands of matches, which would bloat the
     * payload for a detail nobody blocking on a failed solve needs to see.</p>
     */
    public static final Set<String> NOMS_DURS = definitions().stream()
            .filter(definition -> definition.niveau() == Niveau.HARD)
            .map(ConstraintDefinition::name)
            .collect(Collectors.toUnmodifiableSet());

    /**
     * Whether {@code nom} is enforced when nothing says otherwise. Read by
     * {@code ConstraintToggleSupport}, so a harness that hands the solver no
     * toggle at all still gets the catalogue's answer rather than « active,
     * always » — the hole a default-off rule would otherwise leave open in
     * every plain-Java test and every caller that builds its own problem.
     */
    public static boolean activeByDefault(String nom) {
        return !DESACTIVEES_PAR_DEFAUT.contains(nom);
    }

    public static List<ConstraintDefinition> definitions() {
        return DEFINITIONS;
    }
}
