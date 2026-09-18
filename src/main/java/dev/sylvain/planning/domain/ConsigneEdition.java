package dev.sylvain.planning.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
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
        Instant modifieLe) {

    /** One stretch of a day, {@code [debut, fin)}; {@code fin} {@code null} reads « jusqu'à minuit ». */
    @Schema(requiredProperties = {"debut"})
    public record Fenetre(LocalTime debut, LocalTime fin) {}

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
    }

    public ConsigneEdition {
        fenetres = fenetres == null ? List.of() : List.copyOf(fenetres);
        ouvertures = ouvertures == null ? List.of() : List.copyOf(ouvertures);
        creneauxAjoutes = creneauxAjoutes == null ? List.of() : List.copyOf(creneauxAjoutes);
    }

    /** The band as a window. */
    public Fenetre bande() {
        return new Fenetre(fermetureDebut, fermetureFin);
    }

    /** The windows chosen for {@code standId}, empty when the consigne does not open it. */
    public List<Fenetre> openingsOf(String standId) {
        return ouvertures.stream()
                .filter(ouverture -> ouverture.standId().equals(standId))
                .map(Ouverture::fenetre)
                .toList();
    }

    /** The headcount typed for {@code standId}, when one of its windows carries one. */
    public Optional<Integer> effectifOf(String standId) {
        return ouvertures.stream()
                .filter(ouverture -> ouverture.standId().equals(standId))
                .map(Ouverture::effectif)
                .filter(effectif -> effectif != null)
                .findFirst();
    }

    /** True when the consigne opens {@code standId} on at least one window. */
    public boolean opens(String standId) {
        return ouvertures.stream().anyMatch(ouverture -> ouverture.standId().equals(standId));
    }

    /** The same consigne with the créneaux it added recorded. */
    public ConsigneEdition withCreneauxAjoutes(List<Long> ids) {
        return new ConsigneEdition(
                date, fermetureDebut, fermetureFin, motif, prereglage, fenetres, ouvertures, ids, creeLe, modifieLe);
    }
}
