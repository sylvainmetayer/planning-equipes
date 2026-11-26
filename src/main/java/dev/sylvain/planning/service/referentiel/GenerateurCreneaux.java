package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.Gabarit;
import dev.sylvain.planning.domain.TypeJoursHoraire;
import dev.sylvain.planning.domain.VacationType;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.ReferenceDataChangeTracker;
import dev.sylvain.planning.service.consigne.ConsigneRepository;
import dev.sylvain.planning.service.referentiel.CreneauGridService.RapportGrille;
import dev.sylvain.planning.service.referentiel.JourneesTypesMaterialisation.Affectation;
import dev.sylvain.planning.service.referentiel.JourneesTypesMaterialisation.Plan;
import dev.sylvain.planning.service.referentiel.JourneesTypesMaterialisation.Reconnaissance;
import dev.sylvain.planning.service.solve.RefusedWhileSolving;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The edition's <b>one timeslot generator</b> (ADR 0126): every way the grid is
 * produced in bulk goes through here — the stands' opening hours (their model
 * and dated plages, ADR 0104), the templates (« gabarits », the day templates
 * of 3.x) applied to their dates, and a recurrence typed once — each one
 * previewed, applied and, for the templates, withdrawn from dates. A timeslot
 * typed by hand stays {@link CreneauService}'s.
 *
 * <p>Every timeslot it creates goes through {@link CreneauRepository}, the one
 * door that unfolds a stand's model on a day the edition did not count yet.
 * Previews write nothing: they return the timeslots and the verdict on the
 * grid that would result, read by {@link ReferenceDataService#controlerGrille}.</p>
 *
 * <p><b>The writes that reach the grid</b> refuse while a solve runs and while
 * the timeslots are frozen: {@link #apply(Demande)} and the 3.x entry points
 * that delegate to it. Everything else writes only templates and their
 * calendar, which no solve reads until applied.</p>
 */
@ApplicationScoped
public class GenerateurCreneaux {

    static final int NOM_LONGUEUR_MAX = 80;

    private final GabaritRepository repository;

    private final CreneauRepository creneaux;

    private final ReferenceDataChangeTracker changeTracker;

    private final ConsigneRepository consignes;

    /**
     * The verdict on a grid and the stands it is judged against. It reaches this class back through the CSV
     * import and export services it owns, a cycle between normal-scoped beans that the client proxies absorb:
     * nothing here may read it from a constructor.
     */
    private final ReferenceDataService referentiel;

    private final CreneauService creneauService;

    @Inject
    public GenerateurCreneaux(
            GabaritRepository repository,
            CreneauRepository creneaux,
            ReferenceDataChangeTracker changeTracker,
            ConsigneRepository consignes,
            ReferenceDataService referentiel,
            CreneauService creneauService) {
        this.repository = repository;
        this.creneaux = creneaux;
        this.changeTracker = changeTracker;
        this.consignes = consignes;
        this.referentiel = referentiel;
        this.creneauService = creneauService;
    }

    /* ------------------------------ Templates ------------------------------ */

    public List<Gabarit> list() {
        return repository.list();
    }

    public Gabarit create(Gabarit journeeType) {
        journeeType.setId(null);
        validate(journeeType, repository.list());
        return repository.insert(journeeType);
    }

    public Gabarit update(long id, Gabarit journeeType) {
        journeeType.setId(id);
        List<Gabarit> existants = repository.list();
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

    static void validate(Gabarit journeeType, List<Gabarit> existants) {
        if (journeeType.getNom() == null || journeeType.getNom().isBlank()) {
            throw new BusinessError.Invalid("Une journée type porte un nom, ex. « Jour normal »");
        }
        if (journeeType.getNom().strip().length() > NOM_LONGUEUR_MAX) {
            throw new BusinessError.Invalid("Le nom d'une journée type tient en " + NOM_LONGUEUR_MAX + " caractères");
        }
        journeeType.setNom(journeeType.getNom().strip());
        for (Gabarit autre : existants) {
            boolean memeLigne =
                    journeeType.getId() != null && journeeType.getId().equals(autre.getId());
            if (!memeLigne && autre.getNom().equalsIgnoreCase(journeeType.getNom())) {
                throw new BusinessError.Invalid("Une journée type s'appelle déjà « " + autre.getNom() + " »");
            }
        }
        validateVacations(journeeType);
    }

    private static void validateVacations(Gabarit journeeType) {
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
        Set<Long> ids = repository.list().stream().map(Gabarit::getId).collect(Collectors.toSet());
        checkCalendrier(calendrier, ids);
        repository.replaceCalendrier(calendrier);
        return repository.calendrier();
    }

    /**
     * Assigns {@code dates} to one template; the other dates of the calendar
     * keep theirs, and a date already assigned changes template.
     */
    public List<Affectation> assign(long gabaritId, Set<LocalDate> dates) {
        if (dates.isEmpty()) {
            throw new BusinessError.Invalid("Aucune date : donnez une plage (dateDebut, dateFin) ou des dates");
        }
        if (dates.stream().anyMatch(java.util.Objects::isNull)) {
            throw new BusinessError.Invalid("Une date est vide : format attendu AAAA-MM-JJ");
        }
        Map<LocalDate, Long> parDate = new TreeMap<>();
        repository.calendrier().forEach(affectation -> parDate.put(affectation.date(), affectation.gabaritId()));
        dates.forEach(date -> parDate.put(date, gabaritId));
        List<Affectation> reecrit = new ArrayList<>();
        parDate.forEach((date, id) -> reecrit.add(new Affectation(date, id)));
        return setCalendrier(reecrit);
    }

    /**
     * The generator's withdrawal: {@code dates} leave the calendar, so no
     * template governs them any more. Their timeslots stay as they are — a
     * date nothing governs is never touched (ADR 0032); deleting timeslots is
     * {@link CreneauService}'s.
     */
    public EtatGenerateur withdraw(Set<LocalDate> dates) {
        if (dates == null || dates.isEmpty()) {
            throw new BusinessError.Invalid("Aucune date à retirer");
        }
        setCalendrier(repository.calendrier().stream()
                .filter(affectation -> !dates.contains(affectation.date()))
                .toList());
        return etat();
    }

    /**
     * A date is assigned at most once, and only to a template that exists.
     * Shared with the scenario section: a file listing the same date under two
     * templates would otherwise reach the primary key of
     * {@code gabarit_date} and come back as a 500 rather than as the data
     * error it is.
     */
    static void checkCalendrier(List<Affectation> calendrier, Set<Long> idsConnus) {
        Set<LocalDate> dates = new HashSet<>();
        for (Affectation affectation : calendrier) {
            if (affectation.date() == null || affectation.gabaritId() == null) {
                throw new BusinessError.Invalid("Chaque ligne du calendrier porte une date et une journée type");
            }
            if (!idsConnus.contains(affectation.gabaritId())) {
                throw new BusinessError.Invalid("Journée type introuvable : " + affectation.gabaritId());
            }
            if (!dates.add(affectation.date())) {
                throw new BusinessError.Invalid("Le " + affectation.date() + " est affecté deux fois");
            }
        }
    }

    /* ------------------------------ Whole state ------------------------------ */

    /**
     * Everything the card reads in one call, the drift between calendar and grid included.
     *
     * @param datesSousConsigne the dates a consigne governs (issue #4): the
     *                          card shows them « sous consigne » rather than
     *                          « en écart », since the créneaux the consigne
     *                          added are not a drift from the template
     */
    @Schema(requiredProperties = {"gabarits", "calendrier", "datesEnEcart", "datesSousConsigne"})
    public record EtatGenerateur(
            List<Gabarit> gabarits,
            List<Affectation> calendrier,
            List<LocalDate> datesEnEcart,
            List<LocalDate> datesSousConsigne) {}

    public EtatGenerateur etat() {
        List<Gabarit> gabarits = repository.list();
        List<Affectation> calendrier = repository.calendrier();
        Plan plan = JourneesTypesMaterialisation.planifier(gabarits, calendrier, grilleNominale());
        return new EtatGenerateur(gabarits, calendrier, plan.datesEnEcart(), datesSousConsigne());
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
    @Schema(requiredProperties = {"conserves", "crees", "supprimes", "postesSupprimes", "aucunChangement"})
    public record RapportApplication(
            int conserves,
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

    /* ------------------------------ Generation ------------------------------ */

    /** Where a generation reads its timeslots from. */
    public enum Source {
        /** The templates applied to their dates, by difference on (date, start, end). */
        GABARITS,
        /** The stands' opening hours: every hour a stand opens or closes is a cut. */
        STANDS,
        /** Windows repeated over a date selector, added to the grid. */
        RECURRENCE
    }

    /**
     * One generation to preview or apply. Only the part of the source is read:
     * {@code recurrence} for {@link Source#RECURRENCE}, {@code derivation} and
     * {@code remplacer} (the derived grid replaces the whole grid, the
     * persisted plan going with it) for {@link Source#STANDS}; the templates
     * need nothing, their calendar says it all.
     */
    public record Demande(
            Source source, RegleRecurrence recurrence, GrilleDepuisFenetres.Parametres derivation, boolean remplacer) {

        public Demande {
            if (source == null) {
                throw new BusinessError.Invalid("source est requise : GABARITS, STANDS ou RECURRENCE");
            }
            if (source == Source.RECURRENCE && recurrence == null) {
                throw new BusinessError.Invalid("La source RECURRENCE demande sa règle (recurrence)");
            }
            if (source == Source.STANDS && derivation == null) {
                throw new BusinessError.Invalid("La source STANDS demande ses paramètres (derivation)");
            }
        }

        public static Demande gabarits() {
            return new Demande(Source.GABARITS, null, null, false);
        }

        public static Demande recurrence(RegleRecurrence regle) {
            return new Demande(Source.RECURRENCE, regle, null, false);
        }

        public static Demande stands(GrilleDepuisFenetres.Parametres parametres, boolean remplacer) {
            return new Demande(Source.STANDS, null, parametres, remplacer);
        }
    }

    /**
     * What a generation produced, or would.
     *
     * @param creneaux         the timeslots it adds (created, on a write — with their ids)
     * @param application      for the templates: kept, updated, created and removed timeslots
     * @param coupures         for the stands: why each cut is there
     * @param joursSansFenetre for the stands: the days no stand said anything about
     * @param controle         the verdict on the grid that results
     */
    @Schema(requiredProperties = {"source", "nombreGeneres"})
    public record Generation(
            Source source,
            int nombreGeneres,
            List<Creneau> creneaux,
            RapportApplication application,
            List<GrilleDepuisFenetres.Coupure> coupures,
            List<LocalDate> joursSansFenetre,
            RapportGrille controle) {

        static Generation of(Source source, List<Creneau> creneaux, RapportGrille controle) {
            return new Generation(source, creneaux.size(), creneaux, null, List.of(), List.of(), controle);
        }
    }

    /** What {@code demande} would do, and the verdict on the grid that would result — nothing written. */
    public Generation preview(Demande demande) {
        return switch (demande.source()) {
            case GABARITS -> {
                Plan plan = planCourant();
                List<Creneau> resultante = new ArrayList<>(plan.conserves());
                resultante.addAll(plan.aCreer());
                resultante.addAll(creneauxNonGouvernes(plan));
                RapportGrille controle = referentiel.controlerGrille(resultante);
                yield fromTemplates(plan.aCreer(), rapport(plan).withVerdict(controle));
            }
            case STANDS -> {
                GrilleDepuisFenetres.Derivation derivation = derive(demande);
                List<Creneau> resultante =
                        demande.remplacer() ? new ArrayList<>() : new ArrayList<>(referentiel.listCreneaux());
                resultante.addAll(derivation.creneaux());
                yield fromStands(derivation, referentiel.controlerGrille(resultante));
            }
            case RECURRENCE -> {
                List<Creneau> generes = generateRecurrence(demande.recurrence());
                List<Creneau> resultante = new ArrayList<>(referentiel.listCreneaux());
                resultante.addAll(generes);
                yield Generation.of(Source.RECURRENCE, generes, referentiel.controlerGrille(resultante));
            }
        };
    }

    /**
     * Writes {@code demande}, then reads the verdict on the grid as it now
     * stands. The templates materialise their calendar (a timeslot unnamed on
     * a governed date goes with its seats); the stands' grid is added, or
     * replaces the whole grid; a recurrence only adds.
     */
    @RefusedWhileFrozen(ReferentialFamily.CRENEAUX)
    @RefusedWhileSolving
    public Generation apply(Demande demande) {
        return switch (demande.source()) {
            case GABARITS -> {
                Plan plan = planCourant();
                RapportApplication rapport = rapport(plan);
                if (!plan.isEmpty()) {
                    repository.apply(plan);
                    changeTracker.markModified();
                }
                yield fromTemplates(plan.aCreer(), rapport.withVerdict(referentiel.controlerGrille()));
            }
            case STANDS -> {
                GrilleDepuisFenetres.Derivation derivation = derive(demande);
                if (derivation.creneaux().isEmpty()) {
                    throw new BusinessError.Invalid(
                            "Aucun stand n'a de fenêtre d'ouverture sur ces dates : rien à dériver");
                }
                List<Creneau> ecrits = demande.remplacer()
                        ? creneauService.replace(derivation.creneaux())
                        : creneauService.createInBulk(derivation.creneaux());
                yield fromStands(
                        new GrilleDepuisFenetres.Derivation(
                                ecrits, derivation.coupures(), derivation.joursSansFenetre()),
                        referentiel.controlerGrille());
            }
            case RECURRENCE -> {
                List<Creneau> crees = creneauService.createInBulk(generateRecurrence(demande.recurrence()));
                yield Generation.of(Source.RECURRENCE, crees, referentiel.controlerGrille());
            }
        };
    }

    private GrilleDepuisFenetres.Derivation derive(Demande demande) {
        return GrilleDepuisFenetres.deriver(referentiel.listStands(), demande.derivation());
    }

    private static Generation fromTemplates(List<Creneau> crees, RapportApplication rapport) {
        return new Generation(Source.GABARITS, crees.size(), crees, rapport, List.of(), List.of(), rapport.controle());
    }

    private static Generation fromStands(GrilleDepuisFenetres.Derivation derivation, RapportGrille controle) {
        return new Generation(
                Source.STANDS,
                derivation.creneaux().size(),
                derivation.creneaux(),
                null,
                derivation.coupures(),
                derivation.joursSansFenetre(),
                controle);
    }

    private Plan planCourant() {
        List<Gabarit> gabarits = repository.list();
        List<Affectation> calendrier = repository.calendrier();
        if (calendrier.isEmpty()) {
            throw new BusinessError.Invalid(
                    "Aucune date n'est affectée à une journée type : rien à appliquer. Ajoutez des dates d'abord.");
        }
        return JourneesTypesMaterialisation.planifier(gabarits, calendrier, grilleNominale());
    }

    /** The créneaux on dates the calendar leaves alone — part of the grid the verdict judges. */
    private List<Creneau> creneauxNonGouvernes(Plan plan) {
        Set<Long> touches = Stream.of(plan.conserves(), plan.aSupprimer())
                .flatMap(List::stream)
                .map(Creneau::getId)
                .collect(Collectors.toSet());
        return creneaux.listCreneaux().stream()
                .filter(creneau -> !touches.contains(creneau.getId()))
                .toList();
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
        repository.replaceAll(reconnaissance.gabarits(), reconnaissance.calendrier());
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
    public void importer(List<Gabarit> gabarits, List<Affectation> calendrier) {
        List<Gabarit> valides = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        for (Gabarit journeeType : gabarits) {
            validate(journeeType, valides);
            valides.add(journeeType);
            ids.add(journeeType.getId());
        }
        // The file's ids are the provisional ones the mapper drew, so the
        // calendar is checked against those rather than against the database.
        checkCalendrier(calendrier, ids);
        repository.replaceAll(valides, calendrier);
    }

    /* ------------------------------ Recurrence ------------------------------ */

    /**
     * Expands {@code regle} into unsaved créneaux, one per (matching date ×
     * window), ordered by date then start time.
     *
     * <p>{@link Creneau#getJour()} is left at its default: the day number is
     * never stored, {@code CreneauRepository} recomputes it with
     * {@link Creneau#assignerJours} on every read so that consecutive calendar
     * days always differ by one. Setting it here would be write-only noise —
     * and would be wrong the moment these créneaux join an edition whose
     * earliest date is older.</p>
     */
    public static List<Creneau> generateRecurrence(RegleRecurrence regle) {
        List<FenetreHoraire> fenetres = regle.fenetresValidees();
        List<Creneau> creneaux = new ArrayList<>();
        for (LocalDate date : regle.datesCouvertes()) {
            for (FenetreHoraire fenetre : fenetres) {
                creneaux.add(new Creneau(null, 0, date, fenetre.getHeureDebut(), fenetre.getHeureFin()));
            }
        }
        creneaux.sort(Comparator.comparing(Creneau::getDate).thenComparing(Creneau::getHeureDebut));
        return creneaux;
    }

    /**
     * A recurrence to expand. Reuses {@link TypeJoursHoraire} and
     * {@link FenetreHoraire} rather than inventing a parallel vocabulary: an
     * operator who has learned "TOUS / JOURS_SEMAINE / PLAGE / DATES" on stand
     * horaires should not have to learn a second dialect for créneaux.
     *
     * @param exclusions dates to skip whatever the selector says — the
     *                   "every day except the 14th" case, which would
     *                   otherwise force listing every date explicitly
     */
    public record RegleRecurrence(
            TypeJoursHoraire jours,
            LocalDate dateDebut,
            LocalDate dateFin,
            Set<DayOfWeek> joursSemaine,
            Set<LocalDate> dates,
            Set<LocalDate> exclusions,
            List<FenetreHoraire> fenetres) {

        public RegleRecurrence {
            jours = jours != null ? jours : TypeJoursHoraire.TOUS;
            joursSemaine = joursSemaine != null ? Set.copyOf(joursSemaine) : Set.of();
            dates = dates != null ? Set.copyOf(dates) : Set.of();
            exclusions = exclusions != null ? Set.copyOf(exclusions) : Set.of();
            fenetres = fenetres != null ? List.copyOf(fenetres) : List.of();
        }

        /** The windows, rejecting the malformed ones with a message an assistant can act on. */
        List<FenetreHoraire> fenetresValidees() {
            if (fenetres.isEmpty()) {
                throw new BusinessError.Invalid("Aucune fenêtre horaire fournie, ex. « 09:00-12:00,14:00-18:00 »");
            }
            for (FenetreHoraire fenetre : fenetres) {
                if (fenetre.getHeureDebut() == null || fenetre.getHeureFin() == null) {
                    throw new BusinessError.Invalid(
                            "Chaque fenêtre doit porter une heure de début ET une heure de fin : un créneau est "
                                    + "l'amplitude elle-même, il n'a pas de fermeture extérieure dont hériter.");
                }
                if (fenetre.getHeureDebut().equals(fenetre.getHeureFin())) {
                    throw new BusinessError.Invalid(
                            "Fenêtre de durée nulle : " + fenetre.getHeureDebut() + " → " + fenetre.getHeureFin());
                }
            }
            return fenetres;
        }

        /**
         * The dates the rule actually covers, exclusions removed. A selector
         * needing bounds says so explicitly rather than silently generating
         * nothing, which is the failure mode that wastes an assistant's turn.
         */
        Set<LocalDate> datesCouvertes() {
            Set<LocalDate> retenues = new TreeSet<>();
            if (jours == TypeJoursHoraire.DATES) {
                if (dates.isEmpty()) {
                    throw new BusinessError.Invalid("La portée DATES exige la liste des dates.");
                }
                retenues.addAll(dates);
            } else {
                retenues.addAll(datesInRange());
            }
            retenues.removeAll(exclusions);
            if (retenues.isEmpty()) {
                throw new BusinessError.Invalid("La règle ne couvre aucune date : sélecteur et exclusions s'annulent.");
            }
            return retenues;
        }

        /** The dates of a bounded selector, before exclusions. */
        private List<LocalDate> datesInRange() {
            if (dateDebut == null || dateFin == null) {
                throw new BusinessError.Invalid(
                        "La portée " + jours + " exige dateDebut et dateFin (bornes incluses).");
            }
            if (dateFin.isBefore(dateDebut)) {
                throw new BusinessError.Invalid(
                        "dateFin (" + dateFin + ") est antérieure à dateDebut (" + dateDebut + ").");
            }
            if (jours == TypeJoursHoraire.JOURS_SEMAINE && joursSemaine.isEmpty()) {
                throw new BusinessError.Invalid("La portée JOURS_SEMAINE exige joursSemaine, ex. MONDAY,TUESDAY.");
            }
            List<LocalDate> retenues = new ArrayList<>();
            for (LocalDate date = dateDebut; !date.isAfter(dateFin); date = date.plusDays(1)) {
                if (jours != TypeJoursHoraire.JOURS_SEMAINE || joursSemaine.contains(date.getDayOfWeek())) {
                    retenues.add(date);
                }
            }
            return retenues;
        }
    }
}
