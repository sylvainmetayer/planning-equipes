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
            new ConstraintDefinition("competenceCompatible", Niveau.HARD, "Affectation",
                    "L'animateur doit maîtriser au moins une typologie de jeu proposée par le stand."),
            new ConstraintDefinition("pasDeDoubleAffectationSurMemeCreneau", Niveau.HARD, "Affectation",
                    "Un animateur ne peut tenir qu'un seul poste sur un créneau donné."),

            new ConstraintDefinition("standReserveAuxMajeurs", Niveau.HARD, "Légal (mineurs)",
                    "Les stands réservés aux majeurs ne peuvent accueillir aucun mineur."),
            new ConstraintDefinition("mineurNecessiteEncadrementMajeur", Niveau.HARD, "Légal (mineurs)",
                    "Un mineur doit toujours être accompagné d'au moins un majeur sur le même stand et le même créneau."),
            new ConstraintDefinition("travailDeNuitInterditPourMineur", Niveau.HARD, "Légal (mineurs)",
                    "Un mineur ne peut pas être affecté sur un créneau qui empiète sur la nuit."),
            new ConstraintDefinition("dureeQuotidienneMaxMineur", Niveau.HARD, "Légal (mineurs)",
                    "Un mineur ne peut pas dépasser 8 heures de présence sur une même journée."),
            new ConstraintDefinition("reposQuotidienMineur", Niveau.HARD, "Légal (mineurs)",
                    "Après un créneau de nuit, un mineur ne peut pas reprendre avant midi le lendemain (repos d'environ 12 h)."),

            new ConstraintDefinition("dureeHebdomadaireMax", Niveau.HARD, "Légal (temps de travail)",
                    "Aucun animateur (tous payés, manager ou non) ne peut dépasser la durée hebdomadaire de travail "
                            + "maximale paramétrée (48 h par défaut, Code du travail art. L3121-20 / Convention "
                            + "collective de l'Animation)."),

            new ConstraintDefinition("indisponibiliteForcee", Niveau.HARD, "Contraintes ad hoc",
                    "Indisponibilité posée manuellement par l'administrateur : l'animateur ne doit jamais être affecté sur le périmètre visé."),
            new ConstraintDefinition("incompatibiliteAdHoc", Niveau.HARD, "Contraintes ad hoc",
                    "Deux animateurs déclarés incompatibles ne doivent jamais travailler sur le même créneau."),
            new ConstraintDefinition("affectationForcee", Niveau.HARD, "Contraintes ad hoc",
                    "Affectation imposée par l'administrateur : l'animateur doit être présent sur le créneau ou le stand visé."),

            new ConstraintDefinition("standComplexeAvecReferent", Niveau.MEDIUM, "Qualité d'organisation",
                    "Chaque stand devrait compter au moins un référent sur chaque créneau."),
            new ConstraintDefinition("equilibrerCharge", Niveau.MEDIUM, "Qualité d'organisation",
                    "La charge de travail doit être répartie équitablement entre les animateurs."),
            new ConstraintDefinition("repartitionMineursParCreneau", Niveau.MEDIUM, "Qualité d'organisation",
                    "Sur un créneau, un stand ne devrait pas compter plus de mineurs que de majeurs."),
            new ConstraintDefinition("experienceRequisePourStandsPremium", Niveau.MEDIUM, "Qualité d'organisation",
                    "Un stand premium ne devrait pas être tenu par un animateur débutant sur sa typologie."),
            new ConstraintDefinition("eviterRoulementStandsPremium", Niveau.MEDIUM, "Qualité d'organisation",
                    "Sur un stand premium, éviter de faire tourner plusieurs animateurs différents : on privilégie la continuité."),
            new ConstraintDefinition("eviterChangementEmplacementEloigne", Niveau.MEDIUM, "Qualité d'organisation",
                    "Entre deux créneaux consécutifs, éviter de faire basculer un animateur vers un stand dont "
                            + "l'emplacement est éloigné (> 300 m à vol d'oiseau) de celui du créneau précédent."),

            new ConstraintDefinition("favoriserRotationDesStands", Niveau.SOFT, "Préférences",
                    "Éviter d'affecter plusieurs fois le même animateur sur le même stand : on privilégie la rotation."),
            new ConstraintDefinition("favoriserMixiteDesNiveaux", Niveau.SOFT, "Préférences",
                    "Quand un référent est présent sur un créneau, y associer un débutant pour favoriser la montée en compétence."));

    private ConstraintCatalog() {
    }

    public static List<ConstraintDefinition> definitions() {
        return DEFINITIONS;
    }
}
