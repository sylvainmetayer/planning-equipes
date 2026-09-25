package dev.sylvain.planning.service.profile;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.analyse.EquiteService.RapportEquite;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.AnimateurFragilite;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.CompetenceRare;
import dev.sylvain.planning.service.espace.EspaceAnimateurService.DeclarationAdminView;
import dev.sylvain.planning.service.espace.EspaceAnimateurService.DemandeEchangeView;
import dev.sylvain.planning.service.publication.ConfirmationPlanningService.ConfirmationView;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Everything the application knows about one animateur, on one page — the
 * « fiche 360° ». Each part is the answer of the service that owns it,
 * narrowed to this person; nothing here is computed a second time.
 *
 * @param animateur             the fiche as the Animateurs page edits it
 * @param joursEvenement        the dates carrying a timeslot, for the
 *                              availability strip
 * @param regimeDebut           the legal regime on the first event day,
 *                              {@code null} without a birth date or a timeslot
 * @param regimeFin             the same on the last event day — different
 *                              when a birthday falls during the event
 * @param planCalcule           the persisted plan holds at least one seat:
 *                              without it, the equity, fragility and seat
 *                              sections have nothing to say and say so
 * @param equite                the Équité report with this person's line only
 *                              (none when they hold no seat) — the syntheses
 *                              stay those of the whole plan, which is what the
 *                              distance to the median is measured against
 * @param fragilite             this person's line of the Fragilité report,
 *                              {@code null} when they hold no seat
 * @param competencesRares      the scarcity rows naming this person as the
 *                              single specialist
 * @param affectations          the seats of the persisted plan, in time order
 * @param confirmation          their answer to the last publication
 * @param dernierePublicationLe when the last publication left, {@code null}
 *                              while nothing was ever published
 * @param echangesEnCours       swap requests still waiting, asked by or of
 *                              this person
 * @param ajustements           the ad hoc constraints naming this person
 * @param verrous               the locks naming this person
 * @param declarationEnAttente  their pending availability declaration, if any
 */
@Schema(
        requiredProperties = {
            "animateur",
            "joursEvenement",
            "planCalcule",
            "equite",
            "competencesRares",
            "affectations",
            "echangesEnCours",
            "ajustements",
            "verrous"
        })
public record AnimateurProfile(
        Animateur animateur,
        List<LocalDate> joursEvenement,
        LegalRegime regimeDebut,
        LegalRegime regimeFin,
        boolean planCalcule,
        RapportEquite equite,
        AnimateurFragilite fragilite,
        List<CompetenceRare> competencesRares,
        List<ProfileSeat> affectations,
        ConfirmationView confirmation,
        Instant dernierePublicationLe,
        List<DemandeEchangeView> echangesEnCours,
        List<ProfileAdjustment> ajustements,
        List<VerrouillagePlanning> verrous,
        DeclarationAdminView declarationEnAttente) {

    /**
     * The legal regime on one date, derived from the birth date — never stored.
     *
     * @param regime {@code MOINS_DE_16}, {@code MINEUR} (16 to 18) or {@code MAJEUR}
     */
    @Schema(requiredProperties = {"date", "age", "regime"})
    public record LegalRegime(LocalDate date, int age, String regime) {}

    /**
     * One seat of the persisted plan.
     *
     * @param passe       already started at the current horizon — the past a
     *                    solve no longer moves
     * @param verrouille  a lock of the edition covers it
     */
    @Schema(requiredProperties = {"posteId", "standId", "creneauId", "passe", "verrouille"})
    public record ProfileSeat(
            String posteId,
            String standId,
            String standNom,
            long creneauId,
            LocalDate date,
            LocalTime heureDebut,
            LocalTime heureFin,
            String emplacementId,
            String emplacementNom,
            boolean passe,
            boolean verrouille) {}

    /**
     * One ad hoc constraint naming this person, its references spelled out.
     *
     * @param autres the other animateurs it names — the other half of an
     *               incompatibility or an affinity
     */
    @Schema(requiredProperties = {"id", "type", "autres"})
    public record ProfileAdjustment(
            String id,
            String type,
            Long creneauId,
            LocalDate date,
            LocalTime heureDebut,
            LocalTime heureFin,
            String standId,
            String standNom,
            List<ProfileColleague> autres,
            String raison) {}

    /** Another animateur named by an ad hoc constraint: the id to link to, the name to read. */
    @Schema(requiredProperties = {"id", "nom"})
    public record ProfileColleague(String id, String nom) {}
}
