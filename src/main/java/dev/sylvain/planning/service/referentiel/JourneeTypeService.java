package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.JourneeType;
import dev.sylvain.planning.domain.VacationType;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.ReferenceDataChangeTracker;
import dev.sylvain.planning.service.consigne.ConsigneRepository;
import dev.sylvain.planning.service.referentiel.CreneauGridService.RapportGrille;
import dev.sylvain.planning.service.referentiel.JourneesTypesMaterialisation.Affectation;
import dev.sylvain.planning.service.referentiel.JourneesTypesMaterialisation.Plan;
import dev.sylvain.planning.service.referentiel.JourneesTypesMaterialisation.Reconnaissance;
import dev.sylvain.planning.service.solve.SolverJobService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Day templates and their calendar: the way an edition that types its
 * vacations by hand describes them once per kind of day (ADR 0032).
 *
 * <p><b>One write touches the grid</b>, and it is the one that refuses while a
 * solve runs, like every rewrite of the grid: applying the calendar.
 * Everything else here writes only templates and their calendar — the
 * recognition included — which no solve reads: they change nothing until
 * applied, which is the whole point of previewing.</p>
 */
@ApplicationScoped
public class JourneeTypeService {

    static final int NOM_LONGUEUR_MAX = 80;

    @Inject
    JourneeTypeRepository repository;

    @Inject
    CreneauRepository creneaux;

    @Inject
    ReferenceDataChangeTracker changeTracker;

    @Inject
    ConsigneRepository consignes;

    @Inject
    SolverJobService solverJobs;

    /* ------------------------------ Templates ------------------------------ */

    public List<JourneeType> list() {
        return repository.list();
    }

    public JourneeType create(JourneeType journeeType) {
        journeeType.setId(null);
        validate(journeeType, repository.list());
        return repository.insert(journeeType);
    }

    public JourneeType update(long id, JourneeType journeeType) {
        journeeType.setId(id);
        List<JourneeType> existants = repository.list();
        if (existants.stream().noneMatch(autre -> autre.getId() == id)) {
            throw new BusinessError.NotFound("Journée type introuvable : " + id);
        }
        validate(journeeType, existants);
        repository.update(journeeType);
        return journeeType;
    }

    /** The template goes, its dates are no longer governed; the créneaux it produced stay. */
    public void delete(long id) {
        if (!repository.exists(id)) {
            throw new BusinessError.NotFound("Journée type introuvable : " + id);
        }
        repository.delete(id);
    }

    static void validate(JourneeType journeeType, List<JourneeType> existants) {
        if (journeeType.getNom() == null || journeeType.getNom().isBlank()) {
            throw new BusinessError.Invalid("Une journée type porte un nom, ex. « Jour normal »");
        }
        if (journeeType.getNom().strip().length() > NOM_LONGUEUR_MAX) {
            throw new BusinessError.Invalid("Le nom d'une journée type tient en " + NOM_LONGUEUR_MAX + " caractères");
        }
        journeeType.setNom(journeeType.getNom().strip());
        for (JourneeType autre : existants) {
            boolean memeLigne =
                    journeeType.getId() != null && journeeType.getId().equals(autre.getId());
            if (!memeLigne && autre.getNom().equalsIgnoreCase(journeeType.getNom())) {
                throw new BusinessError.Invalid("Une journée type s'appelle déjà « " + autre.getNom() + " »");
            }
        }
        if (journeeType.getVacations().isEmpty()) {
            throw new BusinessError.Invalid(
                    "Une journée type contient au moins une vacation, ex. « 09:00-12:00, 14:00-18:00 »");
        }
        Set<String> cles = new HashSet<>();
        for (VacationType vacation : journeeType.getVacations()) {
            if (vacation.heureDebut() == null || vacation.heureFin() == null) {
                throw new BusinessError.Invalid("Chaque vacation porte une heure de début et une heure de fin");
            }
            if (vacation.heureDebut().equals(vacation.heureFin())) {
                throw new BusinessError.Invalid("Vacation de durée nulle : " + vacation.heureDebut());
            }
            if (!cles.add(vacation.key())) {
                throw new BusinessError.Invalid("Vacation en double dans la journée type : " + vacation.heureDebut()
                        + "-" + vacation.heureFin());
            }
        }
    }

    /* ------------------------------ Calendar ------------------------------ */

    public List<Affectation> calendrier() {
        return repository.calendrier();
    }

    /** The whole calendar, rewritten: a date at most once, every template named must exist. */
    public List<Affectation> setCalendrier(List<Affectation> calendrier) {
        Set<Long> ids = new HashSet<>();
        for (JourneeType journeeType : repository.list()) {
            ids.add(journeeType.getId());
        }
        checkCalendrier(calendrier, ids);
        repository.replaceCalendrier(calendrier);
        return repository.calendrier();
    }

    /**
     * A date is assigned at most once, and only to a template that exists.
     * Shared with the scenario section: a file listing the same date under two
     * templates would otherwise reach the primary key of
     * {@code journee_type_date} and come back as a 500 rather than as the data
     * error it is.
     */
    static void checkCalendrier(List<Affectation> calendrier, Set<Long> idsConnus) {
        Set<LocalDate> dates = new HashSet<>();
        for (Affectation affectation : calendrier) {
            if (affectation.date() == null || affectation.journeeTypeId() == null) {
                throw new BusinessError.Invalid("Chaque ligne du calendrier porte une date et une journée type");
            }
            if (!idsConnus.contains(affectation.journeeTypeId())) {
                throw new BusinessError.Invalid("Journée type introuvable : " + affectation.journeeTypeId());
            }
            if (!dates.add(affectation.date())) {
                throw new BusinessError.Invalid("Le " + affectation.date() + " est affecté deux fois");
            }
        }
    }

    /* ------------------------------ Whole state ------------------------------ */

    /** Everything the card reads in one call, the drift between calendar and grid included. */
    /**
     * @param datesSousConsigne the dates a consigne governs (issue #4): the
     *                          card shows them « sous consigne » rather than
     *                          « en écart », since the créneaux the consigne
     *                          added are not a drift from the template
     */
    @Schema(requiredProperties = {"journeesTypes", "calendrier", "datesEnEcart", "datesSousConsigne"})
    public record EtatJourneesTypes(
            List<JourneeType> journeesTypes,
            List<Affectation> calendrier,
            List<LocalDate> datesEnEcart,
            List<LocalDate> datesSousConsigne) {}

    public EtatJourneesTypes etat() {
        List<JourneeType> journeesTypes = repository.list();
        List<Affectation> calendrier = repository.calendrier();
        Plan plan = JourneesTypesMaterialisation.planifier(journeesTypes, calendrier, grilleNominale());
        return new EtatJourneesTypes(journeesTypes, calendrier, plan.datesEnEcart(), datesSousConsigne());
    }

    /**
     * The grid without the créneaux a consigne added (issue #4). A template
     * never names an evening an arrêté made necessary, and applying it must
     * neither count that evening as a drift nor delete it with its seats —
     * that would destroy the compensation in the middle of an alert. Those
     * créneaux belong to the consigne, which removes them when it is lifted.
     */
    private List<Creneau> grilleNominale() {
        Set<Long> ajoutes = consignes.creneauxAjoutes();
        if (ajoutes.isEmpty()) {
            return creneaux.listCreneaux();
        }
        return creneaux.listCreneaux().stream()
                .filter(creneau -> !ajoutes.contains(creneau.getId()))
                .toList();
    }

    private List<LocalDate> datesSousConsigne() {
        return consignes.list().stream()
                .map(dev.sylvain.planning.domain.ConsigneEdition::date)
                .sorted()
                .toList();
    }

    /* ------------------------------ Application ------------------------------ */

    /**
     * What applying the calendar does or would do, and the verdict on the
     * resulting grid.
     *
     * @param supprimesAvecPostes among {@code aSupprimer}, those carrying seats of the persisted plan
     * @param postesSupprimes     how many seats those deletions take away
     */
    @Schema(requiredProperties = {"conserves", "misAJour", "crees", "supprimes", "postesSupprimes", "aucunChangement"})
    public record RapportApplication(
            int conserves,
            int misAJour,
            int crees,
            int supprimes,
            List<Creneau> creneauxSupprimes,
            List<Creneau> supprimesAvecPostes,
            int postesSupprimes,
            List<LocalDate> datesEnEcart,
            boolean aucunChangement,
            RapportGrille controle) {

        public RapportApplication withVerdict(RapportGrille verdict) {
            return new RapportApplication(
                    conserves,
                    misAJour,
                    crees,
                    supprimes,
                    creneauxSupprimes,
                    supprimesAvecPostes,
                    postesSupprimes,
                    datesEnEcart,
                    aucunChangement,
                    verdict);
        }
    }

    /** A report and the grid it describes — the facade reads the verdict on that grid. */
    public record Application(RapportApplication rapport, List<Creneau> grilleResultante) {}

    public Application previewApplication() {
        Plan plan = planCourant();
        List<Creneau> resultante = new ArrayList<>(plan.conserves());
        resultante.addAll(plan.misAJour());
        resultante.addAll(plan.aCreer());
        resultante.addAll(creneauxNonGouvernes(plan));
        return new Application(rapport(plan), resultante);
    }

    /** Writes the plan: the calendar's vacations become the edition's grid. */
    public Application apply() {
        solverJobs.refuseIfSolving();
        Plan plan = planCourant();
        RapportApplication rapport = rapport(plan);
        if (!plan.isEmpty()) {
            repository.apply(plan);
            changeTracker.markModified();
        }
        return new Application(rapport, creneaux.listCreneaux());
    }

    private Plan planCourant() {
        List<JourneeType> journeesTypes = repository.list();
        List<Affectation> calendrier = repository.calendrier();
        if (calendrier.isEmpty()) {
            throw new BusinessError.Invalid(
                    "Aucune date n'est affectée à une journée type : rien à appliquer. Ajoutez des dates d'abord.");
        }
        return JourneesTypesMaterialisation.planifier(journeesTypes, calendrier, grilleNominale());
    }

    /** The créneaux on dates the calendar leaves alone — part of the grid the verdict judges. */
    private List<Creneau> creneauxNonGouvernes(Plan plan) {
        Set<Long> touches = new HashSet<>();
        for (Creneau creneau : plan.conserves()) {
            touches.add(creneau.getId());
        }
        for (Creneau creneau : plan.misAJour()) {
            touches.add(creneau.getId());
        }
        for (Creneau creneau : plan.aSupprimer()) {
            touches.add(creneau.getId());
        }
        List<Creneau> restants = new ArrayList<>();
        for (Creneau creneau : creneaux.listCreneaux()) {
            if (!touches.contains(creneau.getId())) {
                restants.add(creneau);
            }
        }
        return restants;
    }

    private RapportApplication rapport(Plan plan) {
        Map<Long, Integer> postes = plan.aSupprimer().isEmpty() ? Map.of() : repository.seatsByCreneau();
        List<Creneau> avecPostes = new ArrayList<>();
        int postesSupprimes = 0;
        for (Creneau creneau : plan.aSupprimer()) {
            int n = postes.getOrDefault(creneau.getId(), 0);
            if (n > 0) {
                avecPostes.add(creneau);
                postesSupprimes += n;
            }
        }
        return new RapportApplication(
                plan.conserves().size(),
                plan.misAJour().size(),
                plan.aCreer().size(),
                plan.aSupprimer().size(),
                plan.aSupprimer(),
                avecPostes,
                postesSupprimes,
                plan.datesEnEcart(),
                plan.isEmpty(),
                null);
    }

    /* ------------------------------ Recognition ------------------------------ */

    /** The templates the current grid implies, nothing written. */
    public Reconnaissance previewReconnaissance() {
        return reconnaissanceCourante();
    }

    /**
     * Replaces every template and the whole calendar by what the grid implies.
     * The créneaux are untouched by construction — the recognition reads them
     * — so applying right after changes nothing.
     */
    public Reconnaissance reconnaitre() {
        Reconnaissance reconnaissance = reconnaissanceCourante();
        repository.replaceAll(reconnaissance.journeesTypes(), reconnaissance.calendrier());
        return new Reconnaissance(repository.list(), repository.calendrier());
    }

    private Reconnaissance reconnaissanceCourante() {
        List<Creneau> grille = creneaux.listCreneaux();
        if (grille.isEmpty()) {
            throw new BusinessError.Invalid("Aucun créneau dans l'édition : rien à reconnaître.");
        }
        return JourneesTypesMaterialisation.reconnaitre(grille);
    }

    /**
     * A scenario's own section: the file's templates and their dates replace
     * whatever the edition held. Called after the file's créneaux landed, so
     * that an application right after finds nothing to change when the file
     * is consistent with itself.
     */
    public void importer(List<JourneeType> journeesTypes, List<Affectation> calendrier) {
        List<JourneeType> valides = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        for (JourneeType journeeType : journeesTypes) {
            validate(journeeType, valides);
            valides.add(journeeType);
            ids.add(journeeType.getId());
        }
        // The file's ids are the provisional ones the mapper drew, so the
        // calendar is checked against those rather than against the database.
        checkCalendrier(calendrier, ids);
        repository.replaceAll(valides, calendrier);
    }
}
