package dev.sylvain.planning.api;

import java.util.List;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.ModeGrilleCreneaux;
import dev.sylvain.planning.domain.TypeJoursHoraire;
import dev.sylvain.planning.service.CreneauGridService;
import dev.sylvain.planning.service.CreneauGridService.DiagnosticGrille;
import dev.sylvain.planning.service.CreneauGridService.RapportGrille;
import dev.sylvain.planning.service.GrilleDepuisFenetres;
import dev.sylvain.planning.service.ReferenceDataService;
import dev.sylvain.planning.service.ReferenceUsage;
import dev.sylvain.planning.service.WrittenCreneau;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * CRUD of the timeslot grid, plus the grid read as a whole: a recurrence
 * rule that adds a series at once, the verdict on what is there, and the
 * diagnostic of what it looks like — the same operations the MCP tools
 * offer an assistant, reachable from the Créneaux screen.
 */
@Path("/creneaux")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CreneauResource {

    @Inject
    ReferenceDataService referenceDataService;

    @GET
    public List<Creneau> listCreneaux() {
        return referenceDataService.listCreneaux();
    }

    /**
     * A recurrence rule as the screen sends it: the day selector of the stand
     * horaires, and the windows already structured (the client parses the
     * compact line). A créneau is the day's amplitude itself, so every window
     * needs its end.
     */
    public record RecurrenceRequest(TypeJoursHoraire jours, LocalDate dateDebut, LocalDate dateFin,
            Set<DayOfWeek> joursSemaine, Set<LocalDate> dates, Set<LocalDate> exclusions,
            List<FenetreHoraire> fenetres) {

        CreneauGridService.RegleRecurrence regle() {
            return new CreneauGridService.RegleRecurrence(jours, dateDebut, dateFin, joursSemaine, dates,
                    exclusions, fenetres);
        }
    }

    /** What a rule produced (or would produce), and the verdict on the resulting grid. */
    public record RapportRecurrence(int nombreGeneres, List<Creneau> creneaux, RapportGrille controle) {

        static RapportRecurrence of(ReferenceDataService.RecurrenceGrille resultat) {
            return new RapportRecurrence(resultat.creneaux().size(), resultat.creneaux(), resultat.controle());
        }
    }

    /**
     * The créneaux a rule would add, and the verdict on the grid that would
     * result — nothing written. {@code mode} defaults to the edition's
     * declared one ({@code parametresDecoupage.modeGrille}).
     */
    @POST
    @Path("/recurrence/apercu")
    public RapportRecurrence previewRecurrence(RecurrenceRequest requete, @QueryParam("mode") ModeGrilleCreneaux mode) {
        return RapportRecurrence.of(referenceDataService.previewRecurrence(requete.regle(), mode));
    }

    /**
     * Adds the rule's créneaux to the grid: existing ones are left untouched,
     * a rule that repeats one shows up as {@code DOUBLON} in the verdict.
     * {@code 400} on a malformed rule.
     */
    @POST
    @Path("/recurrence")
    public RapportRecurrence createRecurrence(RecurrenceRequest requete, @QueryParam("mode") ModeGrilleCreneaux mode) {
        return RapportRecurrence.of(referenceDataService.createRecurrence(requete.regle(), mode));
    }

    /**
     * What the stands' own hours imply as a grid. {@code heureFermeture} ends
     * the windows left open-ended ({@code 00:00} for midnight);
     * {@code dureeMinimaleMinutes} merges the stretches too short to be a
     * slot (default 15); {@code remplacer} judges — and, on the write,
     * replaces — the whole grid rather than adding to it.
     */
    public record DerivationRequest(LocalDate dateDebut, LocalDate dateFin, LocalTime heureFermeture,
            Integer dureeMinimaleMinutes, boolean remplacer) {

        GrilleDepuisFenetres.Parametres parametres() {
            return new GrilleDepuisFenetres.Parametres(dateDebut, dateFin, heureFermeture,
                    dureeMinimaleMinutes == null ? GrilleDepuisFenetres.DUREE_MINIMALE_PAR_DEFAUT
                            : dureeMinimaleMinutes);
        }
    }

    /** The derived créneaux, the cuts that produced them, the days nothing said anything about, and the verdict. */
    public record RapportDerivation(int nombreGeneres, List<Creneau> creneaux,
            List<GrilleDepuisFenetres.Coupure> coupures, List<LocalDate> joursSansFenetre, RapportGrille controle) {

        static RapportDerivation of(ReferenceDataService.DerivationGrille resultat) {
            GrilleDepuisFenetres.Derivation derivation = resultat.derivation();
            return new RapportDerivation(derivation.creneaux().size(), derivation.creneaux(), derivation.coupures(),
                    derivation.joursSansFenetre(), resultat.controle());
        }
    }

    /** The derivation, judged — nothing written. */
    @POST
    @Path("/derivation/apercu")
    public RapportDerivation previewDerivation(DerivationRequest requete, @QueryParam("mode") ModeGrilleCreneaux mode) {
        return RapportDerivation.of(referenceDataService.previewDerivation(requete.parametres(), requete.remplacer(), mode));
    }

    /**
     * Writes the derived grid: added to the current one, or — {@code remplacer}
     * — in its place, the persisted plan going with it as for the découpage.
     * {@code 400} when no stand has a window on the dates, {@code 409} while a
     * solve runs.
     */
    @POST
    @Path("/derivation")
    public RapportDerivation applyDerivation(DerivationRequest requete, @QueryParam("mode") ModeGrilleCreneaux mode) {
        return RapportDerivation.of(referenceDataService.applyDerivation(requete.parametres(), requete.remplacer(), mode));
    }

    /** The grid's verdict — its own anomalies, the stand openings, the staffing — read in {@code mode}. */
    @GET
    @Path("/controle")
    public RapportGrille validateGrid(@QueryParam("mode") ModeGrilleCreneaux mode) {
        return referenceDataService.controlerGrille(mode);
    }

    /** What the grid looks like, and which mode the data suggests — a suggestion, never a decision. */
    @GET
    @Path("/diagnostic")
    public DiagnosticGrille diagnoseGrid() {
        return referenceDataService.diagnoseGrille();
    }

    /**
     * Creates a timeslot. The body carries the timeslot — the generated id
     * included — <b>and</b> the non-blocking warnings the write raised
     * ({@link WrittenCreneau}).
     */
    @POST
    public WrittenCreneau createCreneau(Creneau creneau) {
        return referenceDataService.writeCreneau(creneau);
    }

    /** Same body, same warnings, for an edit — see {@link #createCreneau}. */
    @PUT
    @Path("/{id}")
    public WrittenCreneau updateCreneau(@PathParam("id") Long id, Creneau creneau) {
        return referenceDataService.writeCreneau(id, creneau);
    }

    @DELETE
    @Path("/{id}")
    public Response deleteCreneau(@PathParam("id") Long id) {
        referenceDataService.deleteCreneau(id);
        return Response.noContent().build();
    }

    /**
     * What deleting these timeslots would take with it — one aggregated total
     * for the whole selection, which is what the confirmation dialog shows.
     * Repeat {@code id} to count several at once; a bulk delete asks once,
     * never once per row.
     *
     * <p>The ids are taken as text although a timeslot id is a number: bound
     * as a {@code List<Long>} they would be converted by the container, whose
     * failure is a {@code 404} raised before this method runs. A mistyped
     * query field is a {@code 400}, and the conversion therefore belongs to
     * {@code ReferenceUsageService}.</p>
     */
    @GET
    @Path("/usages")
    public ReferenceUsage countCreneauUsages(@QueryParam("id") List<String> ids) {
        return referenceDataService.countCreneauUsages(ids);
    }

}
