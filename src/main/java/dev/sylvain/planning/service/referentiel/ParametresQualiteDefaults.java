package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.ParametresQualite;
import java.time.LocalTime;
import org.eclipse.microprofile.config.Config;

/**
 * The {@code planning.contraintes.*} block of the deployment, read as a
 * {@link ParametresQualite}.
 *
 * <p>Since issue #591 those thresholds are stored per edition, so this is no
 * longer <i>the</i> value a solve uses: it is what an edition that never opened
 * the Paramètres screen falls back to. Two callers need it — the repository's
 * fallback and the solver's own configuration — which is why it lives on its
 * own rather than inside either.</p>
 */
public final class ParametresQualiteDefaults {

    private ParametresQualiteDefaults() {}

    public static ParametresQualite of(Config config) {
        return new ParametresQualite(
                config.getOptionalValue("planning.contraintes.max-emplacements-par-jour", Integer.class)
                        .orElse(ParametresQualite.EMPLACEMENTS_DISTINCTS_PAR_JOUR_MAX_PAR_DEFAUT),
                heure(config, "planning.contraintes.heure-service-tardif"),
                heure(config, "planning.contraintes.heure-service-matinal"),
                config.getOptionalValue(
                                "planning.contraintes.repos-souhaite-apres-service-tardif-minutes", Integer.class)
                        .orElse(ParametresQualite.REPOS_SOUHAITE_APRES_SERVICE_TARDIF_MINUTES_PAR_DEFAUT),
                config.getOptionalValue("planning.contraintes.typologies-distinctes-max", Integer.class)
                        .orElse(ParametresQualite.TYPOLOGIES_DISTINCTES_MAX_PAR_DEFAUT),
                config.getOptionalValue("planning.contraintes.jours-consecutifs-max", Integer.class)
                        .orElse(ParametresQualite.JOURS_CONSECUTIFS_MAX_PAR_DEFAUT));
    }

    /**
     * A {@code HH:mm} property, {@code null} when unset or left blank. An hour
     * left blank reads as absent, not as midnight: that is how a deployment
     * neutralises {@code eviterFermeturePuisOuverture} without editing the
     * catalogue, and {@code LocalTime.parse("")} would otherwise fail the boot.
     */
    private static LocalTime heure(Config config, String propriete) {
        return config.getOptionalValue(propriete, String.class)
                .map(String::strip)
                .filter(valeur -> !valeur.isEmpty())
                .map(LocalTime::parse)
                .orElse(null);
    }
}
