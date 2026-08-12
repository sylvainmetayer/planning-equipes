package dev.sylvain.planning.domain;

/**
 * Optional top-level {@code decoupageAuto:} section of a scenario YAML file
 * (see {@code PlanningService}): when present, importing the scenario lands
 * its raw créneaux (amplitudes) into a source {@link GroupeCreneau} named
 * {@link #groupeSourceNom()}, then materializes the
 * {@code VacationGeneratorService} découpage into a target group named
 * {@link #groupeCibleNom()} (created if needed) and activates it — sparing
 * the operator the manual "Découpage" screen round-trip after every import of
 * that scenario. Generation-time only, same as {@link ParametresDecoupage}:
 * never a solver problem fact, never exposed on {@link PlanningFestival}.
 */
public record DecoupageAutoConfig(String groupeSourceNom, String groupeCibleNom) {

    public DecoupageAutoConfig {
        if (groupeSourceNom == null || groupeSourceNom.isBlank()) {
            throw new IllegalArgumentException("decoupageAuto.groupeSourceNom est requis");
        }
        if (groupeCibleNom == null || groupeCibleNom.isBlank()) {
            throw new IllegalArgumentException("decoupageAuto.groupeCibleNom est requis");
        }
        if (groupeSourceNom.equalsIgnoreCase(groupeCibleNom)) {
            throw new IllegalArgumentException(
                    "decoupageAuto.groupeSourceNom et decoupageAuto.groupeCibleNom doivent être différents");
        }
    }
}
