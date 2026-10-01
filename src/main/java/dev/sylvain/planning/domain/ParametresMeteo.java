package dev.sylvain.planning.domain;

import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What the weather alert watches on one edition, and which consigne preset it
 * suggests for each phenomenon (ADR 0074).
 *
 * <p>It <b>suggests</b>, never applies: a consigne closes bands, frees seats
 * and changes real people's plannings on the strength of a forecast, which
 * stays a probability — and the decision is often a prefect's order, not the
 * organiser's. Off by default: an edition nobody armed queries nobody.</p>
 *
 * @param actif             the alert runs for this edition — and only if the
 *                          edition may emit outward at all
 * @param horizonJours      how many days ahead are watched, 1 to 14
 * @param seuilTemperature  daily maximum temperature, °C, from which heat is alerted
 * @param seuilRafales      daily maximum gust, km/h, from which wind is alerted
 * @param orage             thunderstorms (WMO codes 95, 96, 99) are alerted
 * @param prereglageChaleur the preset suggested for heat, {@code null} for none
 * @param prereglageVent    the preset suggested for wind
 * @param prereglageOrage   the preset suggested for a storm
 * @param modifieLe         the stamp a save is checked against, {@code null}
 *                          to overwrite without comparing
 */
@Schema(requiredProperties = {"actif", "horizonJours", "seuilTemperature", "seuilRafales", "orage"})
public record ParametresMeteo(
        boolean actif,
        int horizonJours,
        int seuilTemperature,
        int seuilRafales,
        boolean orage,
        String prereglageChaleur,
        String prereglageVent,
        String prereglageOrage,
        Instant modifieLe) {

    public static final int HORIZON_PAR_DEFAUT = 5;

    /** Open-Meteo forecasts sixteen days; fourteen leaves the last ones, the least reliable, out. */
    public static final int HORIZON_MAX = 14;

    public static final int SEUIL_TEMPERATURE_PAR_DEFAUT = 33;

    public static final int SEUIL_RAFALES_PAR_DEFAUT = 60;

    /** What an edition that has never been configured answers. */
    public ParametresMeteo() {
        this(
                false,
                HORIZON_PAR_DEFAUT,
                SEUIL_TEMPERATURE_PAR_DEFAUT,
                SEUIL_RAFALES_PAR_DEFAUT,
                true,
                null,
                null,
                null,
                null);
    }
}
