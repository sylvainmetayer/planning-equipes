package dev.sylvain.planning.service.weather;

import dev.sylvain.planning.domain.ParametresMeteo;
import dev.sylvain.planning.service.JdbcEditionScope;
import dev.sylvain.planning.service.WriteStamp;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;

/**
 * The two weather tables of an edition: what the organiser set
 * ({@code parametres_meteo}, dumped and copied by a duplication, switched off)
 * and what the machine last saw ({@code meteo_etat}, neither).
 */
@ApplicationScoped
public class WeatherRepository {

    /** The settings, under the write precondition of every referential row. */
    private static final String SAVE_SETTINGS = """
            INSERT INTO parametres_meteo (edition_id, actif, horizon_jours, seuil_temperature,
            seuil_rafales, orage, prereglage_chaleur, prereglage_vent, prereglage_orage)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (edition_id)
            DO UPDATE SET actif = EXCLUDED.actif, horizon_jours = EXCLUDED.horizon_jours,
            seuil_temperature = EXCLUDED.seuil_temperature, seuil_rafales = EXCLUDED.seuil_rafales,
            orage = EXCLUDED.orage, prereglage_chaleur = EXCLUDED.prereglage_chaleur,
            prereglage_vent = EXCLUDED.prereglage_vent, prereglage_orage = EXCLUDED.prereglage_orage,
            modifie_le = now()
            WHERE CAST(? AS boolean)
            AND (CAST(? AS timestamptz) IS NULL
                 OR date_trunc('milliseconds', parametres_meteo.modifie_le)
                    = date_trunc('milliseconds', CAST(? AS timestamptz)))
            RETURNING modifie_le""";

    /** What the last query saw, replacing what the one before saw. */
    private static final String SAVE_STATE = """
            INSERT INTO meteo_etat (edition_id, issue, derniere_tentative, derniere_lecture,
            injoignable_depuis, erreur)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (edition_id)
            DO UPDATE SET issue = EXCLUDED.issue, derniere_tentative = EXCLUDED.derniere_tentative,
            derniere_lecture = EXCLUDED.derniere_lecture, injoignable_depuis = EXCLUDED.injoignable_depuis,
            erreur = EXCLUDED.erreur""";

    private final JdbcEditionScope scope;

    @Inject
    public WeatherRepository(JdbcEditionScope scope) {
        this.scope = scope;
    }

    public ParametresMeteo settings() {
        return scope.read("Failed to load the weather settings", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    SELECT actif, horizon_jours, seuil_temperature, seuil_rafales, orage,
                    prereglage_chaleur, prereglage_vent, prereglage_orage, modifie_le
                    FROM parametres_meteo WHERE edition_id = ?""");
                    ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new ParametresMeteo();
                }
                OffsetDateTime modifieLe = rs.getObject("modifie_le", OffsetDateTime.class);
                return new ParametresMeteo(
                        rs.getBoolean("actif"),
                        rs.getInt("horizon_jours"),
                        rs.getInt("seuil_temperature"),
                        rs.getInt("seuil_rafales"),
                        rs.getBoolean("orage"),
                        rs.getString("prereglage_chaleur"),
                        rs.getString("prereglage_vent"),
                        rs.getString("prereglage_orage"),
                        modifieLe == null ? null : modifieLe.toInstant());
            }
        });
    }

    /**
     * Writes the settings, unless they were written since {@code
     * parametres.modifieLe()} — the precondition is part of the write, as on
     * every referential row (issue #362).
     *
     * @return the stamp written, {@code null} when the precondition refused it
     */
    public Instant save(ParametresMeteo parametres) {
        return scope.writeAndReturn("Failed to save the weather settings", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, SAVE_SETTINGS)) {
                ps.setBoolean(2, parametres.actif());
                ps.setInt(3, parametres.horizonJours());
                ps.setInt(4, parametres.seuilTemperature());
                ps.setInt(5, parametres.seuilRafales());
                ps.setBoolean(6, parametres.orage());
                ps.setString(7, parametres.prereglageChaleur());
                ps.setString(8, parametres.prereglageVent());
                ps.setString(9, parametres.prereglageOrage());
                WriteStamp.bindPrecondition(ps, 10, true, parametres.modifieLe());
                return WriteStamp.writtenOrRefused(ps);
            }
        });
    }

    /** What the last query of this edition saw, {@link WeatherState#never()} before the first. */
    public WeatherState state() {
        return scope.read("Failed to load the weather state", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, """
                    SELECT issue, derniere_tentative, derniere_lecture, injoignable_depuis, erreur
                    FROM meteo_etat WHERE edition_id = ?""");
                    ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return WeatherState.never();
                }
                String issue = rs.getString("issue");
                return new WeatherState(
                        issue == null ? null : WeatherState.Outcome.valueOf(issue),
                        instant(rs.getTimestamp("derniere_tentative")),
                        instant(rs.getTimestamp("derniere_lecture")),
                        instant(rs.getTimestamp("injoignable_depuis")),
                        rs.getString("erreur"));
            }
        });
    }

    public void saveState(WeatherState etat) {
        scope.write("Failed to save the weather state", connection -> {
            try (PreparedStatement ps = scope.prepareScoped(connection, SAVE_STATE)) {
                ps.setString(2, etat.outcome() == null ? null : etat.outcome().name());
                ps.setTimestamp(3, timestamp(etat.lastAttemptAt()));
                ps.setTimestamp(4, timestamp(etat.lastReadAt()));
                ps.setTimestamp(5, timestamp(etat.unreachableSince()));
                ps.setString(6, etat.error());
                ps.executeUpdate();
            }
        });
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
