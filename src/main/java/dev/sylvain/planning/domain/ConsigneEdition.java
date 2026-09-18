package dev.sylvain.planning.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What the organiser imposes on the whole event for one date (issue #4): a
 * band every stand is shut on — an arrêté préfectoral, typically — and the
 * compensation chosen for it, stand by stand.
 *
 * <p>The band closes <b>every</b> stand, above any rule or dated exception of
 * its own, and it is all it closes: a stand keeps its own hours outside it,
 * whether the consigne opens it or not. An opening is an <em>extension</em>,
 * chosen for each stand, never implied — and a stand that has none stays shut
 * on the créneaux the consigne added to the grid, which belong to nobody's
 * usual hours (see {@code ConsigneResolver}).</p>
 *
 * <p>One value per date, so extending an alert adds dates, lifting it removes
 * the dates still to come, and a date already worked keeps for good the
 * consigne that actually governed it.</p>
 *
 * @param fermetureDebut   start of the band
 * @param fermetureFin     end of the band, {@code null} meaning « jusqu'à minuit »
 *                         — the convention of every dated window here
 * @param motif            why, always given: it is printed wherever the day is
 *                         shown as modified
 * @param prereglage       name of the preset the consigne was made from, or
 *                         {@code null} when typed freely
 * @param fenetres         the day's default compensation windows, the ones a
 *                         stand ticked in the screen receives before any
 *                         individual adjustment
 * @param ouvertures       the stands opened, one entry per window
 * @param creneauxAjoutes  ids of the créneaux the consigne added to the grid
 *                         because none covered an opening
 * @param repas            the meal windows this date runs under instead of the
 *                         edition's, with the reason; {@code null} when the
 *                         edition's own apply. Dated by construction: they
 *                         govern this date only and leave with the consigne,
 *                         so nobody has to remember to put a parameter back
 */
@Schema(requiredProperties = {"date", "fermetureDebut", "motif", "fenetres", "ouvertures", "creneauxAjoutes"})
public record ConsigneEdition(
        LocalDate date,
        LocalTime fermetureDebut,
        LocalTime fermetureFin,
        String motif,
        String prereglage,
        List<Fenetre> fenetres,
        List<Ouverture> ouvertures,
        List<Long> creneauxAjoutes,
        Instant creeLe,
        Instant modifieLe,
        RepasConsigne repas) {

    /**
     * The meal windows a consigne states for its date, each field
     * {@code null} to keep the edition's value. The meal break is the
     * organiser's own rule, not the Code du travail, which is why a consigne
     * may restate it: people ate during the closed band, so an evening
     * compensation 18h-22h owes no break — provided the evening window says
     * so on that date. The legal caps (daily rest, daily and weekly hours,
     * days per week) are the law and stay out of a consigne's reach.
     *
     * @param justification why, in the organiser's words — required as soon
     *                      as one field is set, and printed beside the day
     */
    @Schema(requiredProperties = {"justification"})
    public record RepasConsigne(
            LocalTime midiDebut,
            LocalTime midiFin,
            LocalTime soirDebut,
            LocalTime soirFin,
            Integer coupureMinutes,
            String justification) {

        /** True when at least one field departs from the edition's parameters. */
        public boolean surcharge() {
            return midiDebut != null
                    || midiFin != null
                    || soirDebut != null
                    || soirFin != null
                    || coupureMinutes != null;
        }
    }

    /**
     * One stretch of a day, {@code [debut, fin)}; {@code fin} {@code null}
     * reads « jusqu'à minuit ». A caller typing {@code 00:00} as the end means
     * the same thing, and every write path stores it as {@code null} through
     * {@link #normalised()} — the database only knows the {@code null} form.
     */
    @Schema(requiredProperties = {"debut"})
    public record Fenetre(LocalTime debut, LocalTime fin) {

        /** The same window with a midnight end written as an open end. */
        public Fenetre normalised() {
            return new Fenetre(debut, openEnd(fin));
        }
    }

    /**
     * One stand opened on one window.
     *
     * @param effectif seats on the window; {@code null} inherits the highest
     *                 headcount of the stand's windows lost to the band, or its
     *                 minimum when nothing was lost
     */
    @Schema(requiredProperties = {"standId", "debut"})
    public record Ouverture(String standId, LocalTime debut, LocalTime fin, Integer effectif) {

        public Fenetre fenetre() {
            return new Fenetre(debut, fin);
        }

        /** The same opening with a midnight end written as an open end. */
        public Ouverture normalised() {
            return new Ouverture(standId, debut, openEnd(fin), effectif);
        }
    }

    public ConsigneEdition {
        fenetres = fenetres == null ? List.of() : List.copyOf(fenetres);
        ouvertures = ouvertures == null ? List.of() : List.copyOf(ouvertures);
        creneauxAjoutes = creneauxAjoutes == null ? List.of() : List.copyOf(creneauxAjoutes);
    }

    /**
     * An end of {@code 00:00} is midnight, the end of the day — the same thing
     * as no end at all. Stored as {@code null} everywhere: the tables' checks
     * only know that form, and a reader has one case to handle instead of two.
     */
    public static LocalTime openEnd(LocalTime fin) {
        return fin == null || fin.equals(LocalTime.MIDNIGHT) ? null : fin;
    }

    /** The band as a window. */
    public Fenetre bande() {
        return new Fenetre(fermetureDebut, fermetureFin);
    }

    /** The openings chosen for {@code standId}, each with its own headcount; empty when the consigne does not open it. */
    public List<Ouverture> openingsOf(String standId) {
        return ouvertures.stream()
                .filter(ouverture -> ouverture.standId().equals(standId))
                .toList();
    }

    /**
     * The same consigne with every midnight end — the band's, the default
     * windows', the openings' — written as an open end.
     */
    public ConsigneEdition normalised() {
        return new ConsigneEdition(
                date,
                fermetureDebut,
                openEnd(fermetureFin),
                motif,
                prereglage,
                fenetres.stream().map(Fenetre::normalised).toList(),
                ouvertures.stream().map(Ouverture::normalised).toList(),
                creneauxAjoutes,
                creeLe,
                modifieLe,
                repas);
    }

    /** True when the consigne opens {@code standId} on at least one window. */
    public boolean opens(String standId) {
        return ouvertures.stream().anyMatch(ouverture -> ouverture.standId().equals(standId));
    }

    /** The same consigne with the créneaux it added recorded. */
    public ConsigneEdition withCreneauxAjoutes(List<Long> ids) {
        return new ConsigneEdition(
                date,
                fermetureDebut,
                fermetureFin,
                motif,
                prereglage,
                fenetres,
                ouvertures,
                ids,
                creeLe,
                modifieLe,
                repas);
    }
}
