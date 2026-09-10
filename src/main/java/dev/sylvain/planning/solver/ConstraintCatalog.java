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

    /**
     * Categories whose rules found the plan in law (or in the organiser's own
     * safety policy for young workers). Switching one off lets the solver
     * return a plan scoring zero hard that nonetheless breaks the Code du
     * travail, so the UI asks for a confirmation naming the rule and the
     * article behind it — and the Contraintes screen keeps showing what is
     * off. See {@code docs/contraintes.md}.
     */
    public static final Set<String> CATEGORIES_PROTEGEES =
            Set.of("Légal (mineurs)", "Légal (temps de travail)", "Sécurité (mineurs)");

    /** The one category whose rules are dosed rather than switched off — see {@link ConstraintDefinition#dosable()}. */
    public static final String CATEGORIE_QUALITE = "Qualité d'organisation";

    public record ConstraintDefinition(String name, Niveau niveau, String categorie, String description) {

        /** True when disabling this rule needs the confirmation described on {@link #CATEGORIES_PROTEGEES}. */
        public boolean protegee() {
            return CATEGORIES_PROTEGEES.contains(categorie);
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
                    "Un mineur doit toujours être accompagné d'au moins un majeur sur le même stand et le même "
                            + "créneau. Règle de sécurité posée par l'organisateur, pas une obligation du Code du "
                            + "travail — maintenue en contrainte dure par choix."),
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
                            + "(art. D4153-3). Les pauses prises sur le poste, si l'organisateur les déclare, "
                            + "sont déduites."),
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
                    "Aucune période de travail ininterrompue de plus de 4 h 30 pour un mineur : au-delà, une pause "
                            + "d'au moins 30 minutes consécutives est obligatoire "
                            + "(Code du travail art. L3162-3). Inerte quand l'organisateur déclare la pause "
                            + "prise sur le poste, par relais."),
            new ConstraintDefinition(
                    "dureeHebdomadaireMax",
                    Niveau.HARD,
                    "Légal (temps de travail)",
                    "Aucun animateur majeur (tous payés, manager ou non) ne peut dépasser la durée hebdomadaire de "
                            + "travail effectif maximale paramétrée (48 h par défaut, Code du travail art. L3121-20, "
                            + "d'ordre public / Convention collective de l'Animation art. 5.2)."),
            new ConstraintDefinition(
                    "dureeHebdomadaireMaxMineur",
                    Niveau.HARD,
                    "Légal (mineurs)",
                    "Un mineur ne peut pas dépasser 35 heures de travail effectif par semaine "
                            + "(Code du travail art. L3162-1 ; art. D4153-3 pour les 14 à moins de 16 ans employés "
                            + "pendant les vacances scolaires)."),
            new ConstraintDefinition(
                    "dureeQuotidienneMaxMajeur",
                    Niveau.HARD,
                    "Légal (temps de travail)",
                    "Un animateur majeur ne peut pas dépasser 10 heures de travail effectif sur une même journée "
                            + "(Code du travail art. L3121-18). Les pauses prises sur le poste, si l'organisateur "
                            + "les déclare, sont déduites."),
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
                    "Aucune période de travail ininterrompue de plus de 6 heures pour un majeur : au-delà, une pause "
                            + "d'au moins 20 minutes consécutives est obligatoire "
                            + "(Code du travail art. L3121-16). Inerte quand l'organisateur déclare la pause "
                            + "prise sur le poste, par relais."),
            new ConstraintDefinition(
                    "pauseMinimaleEntreVacations",
                    Niveau.HARD,
                    "Légal (temps de travail)",
                    "Entre deux vacations d'un même animateur le même jour, l'écart doit être d'au moins la pause "
                            + "minimale paramétrée (30 min par défaut)."),
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
                            + "le dérangement. Muette tant que rien n'a été publié ; un stand ou un créneau créé "
                            + "depuis reste libre."),
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
                    "Un animateur devrait idéalement intervenir sur une ou deux typologies de jeu sur l'ensemble "
                            + "du planning."),
            new ConstraintDefinition(
                    "maxJoursConsecutifsTravailles",
                    Niveau.MEDIUM,
                    "Qualité d'organisation",
                    "Un animateur ne devrait pas travailler plus de six jours consécutifs sans au moins un jour "
                            + "de repos : moins est possible, plus ne devrait pas l'être."),
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

    public static List<ConstraintDefinition> definitions() {
        return DEFINITIONS;
    }
}
