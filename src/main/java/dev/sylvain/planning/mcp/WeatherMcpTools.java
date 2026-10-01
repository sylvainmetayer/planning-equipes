package dev.sylvain.planning.mcp;

import dev.sylvain.planning.service.weather.WeatherAlertService;
import dev.sylvain.planning.service.weather.WeatherAlertService.WeatherAlertsView;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * MCP tool mirroring the weather alerts of the home screen: read only. The
 * alert suggests a consigne preset, and applying it stays an explicit
 * {@code appliquer_consigne} — never something the weather job, or this tool,
 * does.
 */
@EditionCiblee
@RefusMetier
@Journalise
@ApplicationScoped
public class WeatherMcpTools {

    private final WeatherAlertService weatherService;

    @Inject
    WeatherMcpTools(WeatherAlertService weatherService) {
        this.weatherService = weatherService;
    }

    @Tool(
            name = "consulter_alertes_meteo",
            description = "Alertes météo de l'édition (Open-Meteo, une interrogation par matin) : pour chaque "
                    + "alerte levée, la date, le phénomène (chaleur, rafales, orage), son palier, la phrase affichée "
                    + "sur l'accueil et l'identifiant du préréglage de consigne suggéré aujourd'hui pour ce "
                    + "phénomène ; plus l'état de la dernière interrogation (lue, hors prévision, aucun lieu, "
                    + "injoignable) et si l'alerte est active. Lecture seule : aucune consigne n'est posée — "
                    + "appliquer un préréglage reste un appel explicite à appliquer_consigne. Aucun nom de personne.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    WeatherAlertsView weatherAlerts(@ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return weatherService.alerts();
    }
}
