package dev.sylvain.planning.solver;

import java.util.List;

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

    public record ConstraintDefinition(String name, Niveau niveau, String categorie, String description) {
    }

    private static final List<ConstraintDefinition> DEFINITIONS = List.of(
            new ConstraintDefinition("posteDoitEtrePourvu", Niveau.HARD, "Affectation",
                    "Chaque place ouverte sur un stand doit être pourvue par un animateur."),
            new ConstraintDefinition("animateurDisponible", Niveau.HARD, "Affectation",
                    "Un animateur ne peut pas être affecté un jour qu'il a déclaré indisponible."),
            new ConstraintDefinition("pasDeChevauchementHoraire", Niveau.HARD, "Affectation",
                    "Un animateur ne peut pas tenir deux postes dont les créneaux se chevauchent dans le temps "
                            + "(y compris deux créneaux distincts qui se recouvrent, et pas seulement deux postes "
                            + "sur le même créneau)."),

            new ConstraintDefinition("standReserveAuxMajeurs", Niveau.HARD, "Légal (mineurs)",
                    "Les stands réservés aux majeurs ne peuvent accueillir aucun mineur."),
            // Reclassée « Sécurité (mineurs) » : aucun article du Code du travail n'impose la présence
            // d'un majeur aux côtés d'un jeune travailleur sur son poste. C'est une politique de sécurité
            // de l'organisateur, maintenue en contrainte DURE par choix. Voir docs/contraintes.md.
            new ConstraintDefinition("mineurNecessiteEncadrementMajeur", Niveau.HARD, "Sécurité (mineurs)",
                    "Un mineur doit toujours être accompagné d'au moins un majeur sur le même stand et le même "
                            + "créneau. Règle de sécurité posée par l'organisateur, pas une obligation du Code du "
                            + "travail — maintenue en contrainte dure par choix."),
            new ConstraintDefinition("travailDeNuitInterditPourMineur", Niveau.HARD, "Légal (mineurs)",
                    "Un mineur ne peut pas être affecté sur un créneau qui empiète sur sa nuit légale : "
                            + "20 h-6 h avant 16 ans, 22 h-6 h de 16 à 18 ans "
                            + "(Code du travail art. L3163-1)."),
            new ConstraintDefinition("dureeQuotidienneMaxMineur", Niveau.HARD, "Légal (mineurs)",
                    "Un mineur ne peut pas dépasser 8 heures de travail effectif sur une même journée "
                            + "(Code du travail art. L3162-1), ramenées à 7 heures avant 16 ans "
                            + "(art. D4153-3)."),
            new ConstraintDefinition("travailInterditJourFerieMineur", Niveau.HARD, "Légal (mineurs)",
                    "Un mineur ne peut pas travailler un jour férié légal "
                            + "(Code du travail art. L3164-6, liste de l'art. L3133-1). Aucune dérogation "
                            + "sectorielle n'est appliquée : celle de l'art. R3164-2 reste à instruire."),
            new ConstraintDefinition("reposHebdomadaireMineur", Niveau.HARD, "Légal (mineurs)",
                    "Un mineur bénéficie de deux jours de repos consécutifs par semaine "
                            + "(Code du travail art. L3164-2). Les dérogations conventionnelles supposent un accord "
                            + "étendu ou une autorisation de l'inspection du travail : elles ne sont pas présumées."),
            new ConstraintDefinition("travailContinuMaxMineur", Niveau.HARD, "Légal (mineurs)",
                    "Aucune période de travail ininterrompue de plus de 4 h 30 pour un mineur : au-delà, une pause "
                            + "d'au moins 30 minutes consécutives est obligatoire "
                            + "(Code du travail art. L3162-3)."),

            new ConstraintDefinition("dureeHebdomadaireMax", Niveau.HARD, "Légal (temps de travail)",
                    "Aucun animateur majeur (tous payés, manager ou non) ne peut dépasser la durée hebdomadaire de "
                            + "travail effectif maximale paramétrée (48 h par défaut, Code du travail art. L3121-20, "
                            + "d'ordre public / Convention collective de l'Animation art. 5.2)."),
            new ConstraintDefinition("dureeHebdomadaireMaxMineur", Niveau.HARD, "Légal (mineurs)",
                    "Un mineur ne peut pas dépasser 35 heures de travail effectif par semaine "
                            + "(Code du travail art. L3162-1 ; art. D4153-3 pour les 14 à moins de 16 ans employés "
                            + "pendant les vacances scolaires)."),
            new ConstraintDefinition("dureeQuotidienneMaxMajeur", Niveau.HARD, "Légal (temps de travail)",
                    "Un animateur majeur ne peut pas dépasser 10 heures de travail effectif sur une même journée "
                            + "(Code du travail art. L3121-18)."),
            new ConstraintDefinition("reposQuotidienMinimal", Niveau.HARD, "Légal (temps de travail)",
                    "Entre deux journées travaillées, tout animateur bénéficie d'un repos quotidien minimal : "
                            + "11 h pour un majeur (art. L3131-1), 12 h pour un mineur et 14 h avant 16 ans "
                            + "(art. L3164-1)."),
            new ConstraintDefinition("maxJoursTravaillesParSemaine", Niveau.HARD, "Légal (temps de travail)",
                    "Aucun animateur ne peut travailler plus de six jours dans la même semaine "
                            + "(Code du travail art. L3132-1)."),
            new ConstraintDefinition("reposHebdomadaireMinimal", Niveau.HARD, "Légal (temps de travail)",
                    "Chaque animateur bénéficie, dans chaque semaine, d'un repos hebdomadaire de 35 heures "
                            + "consécutives : 24 heures (art. L3132-2) auxquelles s'ajoutent les 11 heures de repos "
                            + "quotidien (art. L3131-1)."),
            new ConstraintDefinition("travailContinuMaxMajeur", Niveau.HARD, "Légal (temps de travail)",
                    "Aucune période de travail ininterrompue de plus de 6 heures pour un majeur : au-delà, une pause "
                            + "d'au moins 20 minutes consécutives est obligatoire "
                            + "(Code du travail art. L3121-16)."),
            new ConstraintDefinition("pauseMinimaleEntreVacations", Niveau.HARD, "Légal (temps de travail)",
                    "Entre deux vacations d'un même animateur le même jour, l'écart doit être d'au moins la pause "
                            + "minimale paramétrée (30 min par défaut)."),

            new ConstraintDefinition("indisponibiliteForcee", Niveau.HARD, "Contraintes ad hoc",
                    "Indisponibilité posée manuellement par l'administrateur : l'animateur ne doit jamais être affecté sur le périmètre visé."),
            new ConstraintDefinition("incompatibiliteAdHoc", Niveau.HARD, "Contraintes ad hoc",
                    "Deux animateurs déclarés incompatibles ne doivent jamais travailler sur le même créneau."),
            new ConstraintDefinition("affectationForcee", Niveau.HARD, "Contraintes ad hoc",
                    "Affectation imposée par l'administrateur : l'animateur doit être présent sur le créneau ou le stand visé."),

            new ConstraintDefinition("animateurVerrouilleFige", Niveau.HARD, "Verrouillage du planning",
                    "Le planning d'un animateur verrouillé ne bouge plus : ses postes validés sont figés et "
                            + "le solveur ne peut plus lui en attribuer de nouveaux."),

            new ConstraintDefinition("standComplexeAvecReferent", Niveau.MEDIUM, "Qualité d'organisation",
                    "Chaque stand devrait compter au moins un référent sur chaque créneau."),
            new ConstraintDefinition("equilibrerCharge", Niveau.MEDIUM, "Qualité d'organisation",
                    "La charge de travail doit être répartie équitablement entre les animateurs."),
            new ConstraintDefinition("repartitionMineursParCreneau", Niveau.MEDIUM, "Qualité d'organisation",
                    "Sur un créneau, un stand ne devrait pas compter plus de mineurs que de majeurs."),
            new ConstraintDefinition("experienceRequisePourStandsPremium", Niveau.MEDIUM, "Qualité d'organisation",
                    "Un stand premium ne devrait pas être tenu par un animateur débutant sur sa typologie."),
            new ConstraintDefinition("eviterRoulementStandsPremium", Niveau.MEDIUM, "Qualité d'organisation",
                    "Sur un stand premium, limiter le nombre d'animateurs différents qui s'y relaient au-delà d'un "
                            + "équipage : on privilégie la continuité."),
            new ConstraintDefinition("eviterChangementEmplacementEloigne", Niveau.MEDIUM, "Qualité d'organisation",
                    "Entre deux créneaux consécutifs, éviter de faire basculer un animateur vers un stand dont "
                            + "l'emplacement est éloigné (> 300 m à vol d'oiseau) de celui du créneau précédent."),
            new ConstraintDefinition("limiterEmplacementsParJour", Niveau.MEDIUM, "Qualité d'organisation",
                    "Sur une même journée, limiter le nombre d'emplacements distincts visités par un animateur "
                            + "(plafond réglable, 3 par défaut) : au-delà, la journée est dispersée quelles que "
                            + "soient les distances."),
            new ConstraintDefinition("eviterEnchainementStandsEpuisants", Niveau.MEDIUM, "Qualité d'organisation",
                    "Entre deux créneaux consécutifs, éviter d'enchaîner un animateur sur deux stands physiquement "
                            + "épuisants sans repos ni stand plus facile entre les deux."),
            new ConstraintDefinition("appreciationIncompatible", Niveau.MEDIUM, "Qualité d'organisation",
                    "L'appréciation de l'administrateur ne couvre aucune typologie de jeu proposée par le stand."),
            new ConstraintDefinition("souhaitsIncompatibles", Niveau.MEDIUM, "Qualité d'organisation",
                    "Aucune des typologies de jeu proposées par le stand ne figure dans les souhaits déclarés de "
                            + "l'animateur."),
            new ConstraintDefinition("limiterTypologiesDistinctesParAnimateur", Niveau.MEDIUM, "Qualité d'organisation",
                    "Un animateur devrait idéalement intervenir sur une ou deux typologies de jeu sur l'ensemble "
                            + "du planning."),
            new ConstraintDefinition("maxJoursConsecutifsTravailles", Niveau.MEDIUM, "Qualité d'organisation",
                    "Un animateur ne devrait pas travailler plus de six jours consécutifs sans au moins un jour "
                            + "de repos : moins est possible, plus ne devrait pas l'être."),

            new ConstraintDefinition("favoriserMixiteDesNiveaux", Niveau.SOFT, "Préférences",
                    "Quand un référent est présent sur un créneau, y associer un débutant pour favoriser la montée en compétence."),
            new ConstraintDefinition("equilibrerCreneauxPenibles", Niveau.SOFT, "Préférences",
                    "Répartir équitablement entre animateurs les créneaux pénibles (stands épuisants ou premium)."),
            new ConstraintDefinition("preserverBufferPolyvalents", Niveau.SOFT, "Préférences",
                    "Garder au moins un animateur polyvalent (typologie ninja) libre sur chaque créneau, pour pouvoir "
                            + "réparer le planning en cas d'absence de dernière minute."));

    private ConstraintCatalog() {
    }

    public static List<ConstraintDefinition> definitions() {
        return DEFINITIONS;
    }
}
