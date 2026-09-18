package dev.sylvain.planning.service.journee;

import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.publication.PublicationDiffService.TypeChangement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What moved on one day of the plan since a reference — the Changements
 * rendering of the Journée page.
 *
 * <p>Read twice over the same facts, because two people ask two questions of
 * it: {@code parVacation} is the stand's view (this seat, these hours, who
 * held it and who holds it now), {@code parAnimateur} is the person's view
 * (what this animateur gained, lost or had moved that day), worded exactly as
 * the publication mail words it.</p>
 *
 * @param reference           which plan the persisted one was compared to
 * @param referenceDisponible false when that plan does not exist — nothing was
 *                            ever published, or no solve ever replaced a plan.
 *                            The counts are then all zero and mean nothing;
 *                            the screen says the reference is missing rather
 *                            than announcing a day without change
 * @param referenceLe         when the reference was taken: the publication's
 *                            instant, or the automatique snapshot's
 * @param nouveaux            seats of the day held now and empty (or absent) in
 *                            the reference
 * @param retires             seats held in the reference and empty (or gone)
 *                            now
 * @param remplaces           seats held by somebody else now
 * @param horairesModifies    seats kept by the same person on other hours —
 *                            a créneau a consigne trimmed, typically
 * @param animateursConcernes how many people {@code parAnimateur} lists
 */
@Schema(
        requiredProperties = {
            "animateursConcernes",
            "horairesModifies",
            "jour",
            "nouveaux",
            "parAnimateur",
            "parVacation",
            "reference",
            "referenceDisponible",
            "remplaces",
            "retires"
        })
public record ChangementsJournee(
        LocalDate jour,
        ReferenceChangements reference,
        boolean referenceDisponible,
        Instant referenceLe,
        int nouveaux,
        int retires,
        int remplaces,
        int horairesModifies,
        int animateursConcernes,
        List<SeatLine> parVacation,
        List<AnimateurLine> parAnimateur) {

    /** The plan a day is compared to. */
    public enum ReferenceChangements {

        /** The last published plan — what the animateurs were sent. */
        PUBLICATION,

        /** The plan as it stood right before the last solve. */
        RESOLUTION;

        /**
         * The query-string form, {@code publication} or {@code resolution};
         * {@code null} when absent, which lets the caller pick a default.
         *
         * @throws BusinessError.Invalid on any other value
         */
        public static ReferenceChangements fromParam(String value) {
            if (value == null || value.isBlank()) {
                return null;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "publication" -> PUBLICATION;
                case "resolution" -> RESOLUTION;
                default ->
                    throw new BusinessError.Invalid(
                            "Référence inconnue : " + value + " (attendu publication ou resolution)");
            };
        }
    }

    /** What happened to one seat of the day. */
    public enum SeatChangeType {

        /** Held now, empty or absent in the reference. */
        NOUVEAU,

        /** Held in the reference, empty or gone now. */
        RETIRE,

        /** Held by somebody else now. */
        REMPLACE,

        /**
         * Held by the same person, on other hours: the seat the reference
         * held and the one the plan holds are the same stand and day at
         * different hours — a créneau trimmed or stretched by a consigne. One
         * line rather than a retrait and a nouveau, as the person's own
         * reading words it as one déplacement.
         */
        HORAIRES
    }

    /** Somebody holding a seat — the id the screen links, the name it prints. */
    @Schema(requiredProperties = {"animateurId", "nomAffiche"})
    public record Holder(String animateurId, String nomAffiche) {}

    /**
     * One seat of the day whose holder changed, named by its natural key —
     * stand, day, hours — never by a poste id, which a solve renumbers without
     * moving anybody.
     *
     * @param heureDebut      the hours of the seat — for {@link
     *                        SeatChangeType#HORAIRES}, the hours it has now
     * @param heureDebutAvant the hours the reference held the seat on, only
     *                        for {@link SeatChangeType#HORAIRES}; {@code null}
     *                        otherwise, the hours being then the same on both
     *                        sides
     * @param avant           who held the seat in the reference, {@code null}
     *                        for an empty seat or one the reference did not
     *                        hold
     * @param apres           who holds it now, {@code null} for an empty seat
     *                        or one the plan no longer holds
     */
    @Schema(requiredProperties = {"date", "heureDebut", "heureFin", "standId", "standNom", "type"})
    public record SeatLine(
            String standId,
            String standNom,
            LocalDate date,
            LocalTime heureDebut,
            LocalTime heureFin,
            LocalTime heureDebutAvant,
            LocalTime heureFinAvant,
            Holder avant,
            Holder apres,
            SeatChangeType type) {}

    /** One change of one person's day, worded as their mail would word it. */
    @Schema(requiredProperties = {"libelle", "type"})
    public record AnimateurChange(TypeChangement type, String libelle) {}

    /** One person whose day differs from the reference, and every line they would read. */
    @Schema(requiredProperties = {"animateurId", "changements", "nomAffiche"})
    public record AnimateurLine(String animateurId, String nomAffiche, List<AnimateurChange> changements) {}

    /** The answer when the reference does not exist: nothing to compare, and said so. */
    public static ChangementsJournee withoutReference(LocalDate jour, ReferenceChangements reference) {
        return new ChangementsJournee(jour, reference, false, null, 0, 0, 0, 0, 0, List.of(), List.of());
    }
}
