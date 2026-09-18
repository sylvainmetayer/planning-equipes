package dev.sylvain.planning.domain;

import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A named preset for a {@link ConsigneEdition} — « Plan canicule : 12h-18h
 * fermé, soir 18h-22h » — kept on the edition so that each wave of an alert
 * is the same gesture: pick the preset, tick the dates, review the stands.
 *
 * <p>Validated cold, before any arrêté, and copied when the edition is
 * duplicated: it describes the event's shape. The dated consignes made from
 * it are not copied — they belong to the days of one edition.</p>
 *
 * @param id           minted by the server when a create request carries
 *                     none — always set on what is read back
 * @param fermetureFin {@code null} reads « jusqu'à minuit », like a consigne's;
 *                     {@code 00:00} is stored as {@code null}
 * @param fenetres     the default compensation windows a consigne made from
 *                     this preset proposes
 * @param repas        the meal windows a consigne made from this preset runs
 *                     under, with the reason; {@code null} for the edition's
 * @param creeLe       when the preset was first written; {@code null} on a
 *                     request
 */
@Schema(requiredProperties = {"nom", "fermetureDebut", "motif", "fenetres"})
public record PrereglageConsigne(
        String id,
        String nom,
        LocalTime fermetureDebut,
        LocalTime fermetureFin,
        String motif,
        List<ConsigneEdition.Fenetre> fenetres,
        Instant creeLe,
        Instant modifieLe,
        ConsigneEdition.RepasConsigne repas) {

    public PrereglageConsigne {
        fenetres = fenetres == null ? List.of() : List.copyOf(fenetres);
    }

    /** The same preset with every midnight end written as an open end, like a consigne's. */
    public PrereglageConsigne normalised() {
        return new PrereglageConsigne(
                id,
                nom,
                fermetureDebut,
                ConsigneEdition.openEnd(fermetureFin),
                motif,
                fenetres.stream().map(ConsigneEdition.Fenetre::normalised).toList(),
                creeLe,
                modifieLe,
                repas);
    }
}
