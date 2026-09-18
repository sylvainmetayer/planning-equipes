package dev.sylvain.planning.service.consigne;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ConsigneEdition;
import dev.sylvain.planning.domain.ConsigneEdition.Fenetre;
import dev.sylvain.planning.domain.ConsigneEdition.Ouverture;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.PrereglageConsigne;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.ValidationJournee;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.Ids;
import dev.sylvain.planning.service.ReferenceDataChangeTracker;
import dev.sylvain.planning.service.espace.JourJClock;
import dev.sylvain.planning.service.referentiel.HoraireStandResolver;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.solve.ProblemBuilder;
import dev.sylvain.planning.service.solve.SolverJobService;
import dev.sylvain.planning.service.validation.ValidationJourneeService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Proposing, previewing, laying down, changing and lifting an edition's
 * consignes (issue #4), and the presets they are made from.
 *
 * <p>Laying a consigne down writes one row per date and, when the grid does
 * not reach one of its openings, <b>adds</b> the missing créneaux — the only
 * write this feature makes on the grid, and it is additive. Changing a date
 * replaces its row in place, keeping the added créneaux its windows still need
 * and removing the others. Lifting removes the rows of the dates still to come
 * and deletes the créneaux they had added, with their seats: the next
 * publication announces the retrait from the published snapshot, which names
 * those vacations by day and hours.</p>
 *
 * <p>Nothing else is ever deleted. The nominal créneaux keep their ids, their
 * seats and their locks throughout, and a date already begun keeps for good
 * the consigne that governed it — the statistics of a worked day are the
 * statistics of what was actually worked.</p>
 */
@ApplicationScoped
public class ConsigneService {

    static final String MOTIF_EXCEPTION_DATEE = "Horaires posés à la main ce jour-là";

    private static final int MINUTES_PAR_JOUR = 24 * 60;

    /** Same shape as the publication's own lines: « mercredi 08/07 ». */
    private static final java.time.format.DateTimeFormatter JOUR =
            java.time.format.DateTimeFormatter.ofPattern("EEEE dd/MM", java.util.Locale.FRENCH);

    @Inject
    ConsigneRepository repository;

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    PlanningPersistenceService persistence;

    @Inject
    ValidationJourneeService validations;

    @Inject
    ReferenceDataChangeTracker changeTracker;

    @Inject
    SolverJobService solverJobs;

    @Inject
    JourJClock clock;

    /* --------------------------------- reads --------------------------------- */

    public List<ConsigneEdition> list() {
        return repository.list();
    }

    public Optional<ConsigneEdition> find(LocalDate date) {
        return repository.find(date);
    }

    /** The dates under consigne, for the screens that mark them. */
    public Set<LocalDate> datesSousConsigne() {
        Set<LocalDate> dates = new LinkedHashSet<>();
        repository.list().forEach(consigne -> dates.add(consigne.date()));
        return dates;
    }

    /** The consignes by date. */
    public Map<LocalDate, ConsigneEdition> parDate() {
        Map<LocalDate, ConsigneEdition> parDate = new LinkedHashMap<>();
        repository.list().forEach(consigne -> parDate.put(consigne.date(), consigne));
        return parDate;
    }

    /** Every créneau a consigne of the edition added to the grid. */
    public Set<Long> creneauxAjoutes() {
        return repository.creneauxAjoutes();
    }

    /** Everything the screen reads in one call. */
    @Schema(requiredProperties = {"consignes", "prereglages", "aujourdhui", "indicateurs"})
    public record EtatConsignes(
            List<ConsigneEdition> consignes,
            List<PrereglageConsigne> prereglages,
            LocalDate aujourdhui,
            List<Indicateur> indicateurs) {}

    public EtatConsignes etat() {
        return new EtatConsignes(repository.list(), repository.listPrereglages(), clock.today(), indicateurs());
    }

    /* ------------------------------- indicateurs ------------------------------- */

    /**
     * What one modified day cost, read off the seats: kept on the page, in the
     * KPI history and in the snapshots — a day declared modified stays so in
     * the statistics.
     *
     * @param siegesNominaux       seats the day generates without the consigne
     * @param siegesSousConsigne   seats it generates with it
     * @param minutesFermees       seat-minutes the band takes away
     * @param minutesRouvertes     seat-minutes the openings give back
     * @param animateursConcernes  people of the persisted plan holding a seat that day
     */
    @Schema(
            requiredProperties = {
                "date",
                "motif",
                "siegesNominaux",
                "siegesSousConsigne",
                "minutesFermees",
                "minutesRouvertes",
                "animateursConcernes"
            })
    public record Indicateur(
            LocalDate date,
            String motif,
            int siegesNominaux,
            int siegesSousConsigne,
            int minutesFermees,
            int minutesRouvertes,
            int animateursConcernes) {}

    /** One indicator per date under consigne, by date. */
    public List<Indicateur> indicateurs() {
        List<ConsigneEdition> consignes = repository.list();
        if (consignes.isEmpty()) {
            return List.of();
        }
        List<Creneau> creneaux = referenceDataService.listCreneaux();
        PlanningEvenement persiste = persistence.loadPersistedPlanning();
        List<Indicateur> indicateurs = new ArrayList<>();
        for (ConsigneEdition consigne : consignes) {
            LocalDate date = consigne.date();
            Set<Long> ajoutes = new HashSet<>(consigne.creneauxAjoutes());
            List<Creneau> duJour = creneauxDuJour(creneaux, date);
            List<Creneau> nominale =
                    duJour.stream().filter(c -> !ajoutes.contains(c.getId())).toList();
            List<PosteAffectation> avant =
                    ProblemBuilder.buildPostes(nominal(referenceDataService.listStands(), nominale), nominale);
            List<Stand> apres = nominal(referenceDataService.listStands(), duJour);
            ConsigneResolver.apply(apres, List.of(consigne), duJour);
            List<PosteAffectation> postesApres = ProblemBuilder.buildPostes(apres, duJour);
            int[] bande = ConsigneResolver.minutes(consigne.fermetureDebut(), consigne.fermetureFin());
            int fermees = 0;
            for (PosteAffectation poste : avant) {
                int[] fenetre = ConsigneResolver.minutes(poste.heureDebutEffectif(), poste.heureFinEffectif());
                fermees += Math.max(0, Math.min(fenetre[1], bande[1]) - Math.max(fenetre[0], bande[0]));
            }
            int rouvertes = Math.max(0, minutes(postesApres) - (minutes(avant) - fermees));
            Set<String> concernes = new HashSet<>();
            for (PosteAffectation poste : persiste.getPostes()) {
                if (poste.getAnimateur() != null
                        && poste.getCreneau() != null
                        && date.equals(poste.getCreneau().getDate())) {
                    concernes.add(poste.getAnimateur().getId());
                }
            }
            indicateurs.add(new Indicateur(
                    date, consigne.motif(), avant.size(), postesApres.size(), fermees, rouvertes, concernes.size()));
        }
        return indicateurs;
    }

    /**
     * The sentence a mail, a PDF or the espace prints for each of {@code dates}
     * under consigne, in date order: « mercredi 08/07 : horaires modifiés —
     * Arrêté préfectoral canicule (fermé de 12h à 16h) ». Dates without a
     * consigne are skipped.
     */
    public List<String> lignesJourneesModifiees(Collection<LocalDate> dates) {
        if (dates == null || dates.isEmpty()) {
            return List.of();
        }
        Map<LocalDate, ConsigneEdition> parDate = parDate();
        return dates.stream()
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .map(parDate::get)
                .filter(Objects::nonNull)
                .map(ConsigneService::ligne)
                .toList();
    }

    /** The sentence for one consigne, without its date. */
    public static String libelle(ConsigneEdition consigne) {
        return "horaires modifiés — " + consigne.motif() + " (fermé de " + heureCourte(consigne.fermetureDebut())
                + " à " + (consigne.fermetureFin() == null ? "minuit" : heureCourte(consigne.fermetureFin())) + ")";
    }

    private static String ligne(ConsigneEdition consigne) {
        return JOUR.format(consigne.date()) + " : " + libelle(consigne);
    }

    private static String heureCourte(LocalTime heure) {
        return heure.getMinute() == 0
                ? heure.getHour() + "h"
                : heure.getHour() + "h" + String.format("%02d", heure.getMinute());
    }

    /* ------------------------------ préselection ------------------------------ */

    /**
     * One stand as the screen lists it for a date and a band: what the band
     * takes from it, what it inherits, and whether it is proposed ticked.
     *
     * @param minutesPerdues   opening minutes the band closes on that stand,
     *                         within the day's créneaux — zero for a stand the
     *                         band does not touch
     * @param effectifHerite   the headcount an opening without one would get
     * @param exceptionDatee   the stand stated that day by hand (a dated
     *                         window): it stays out of the pre-selection with
     *                         {@link #motif}, but may still be ticked
     * @param preCoche         proposed ticked: it loses minutes and stated
     *                         nothing by hand
     * @param ouvertures       what the date's existing consigne already opens
     *                         on it, empty when there is none
     */
    @Schema(
            requiredProperties = {
                "standId",
                "standNom",
                "minutesPerdues",
                "effectifHerite",
                "exceptionDatee",
                "preCoche",
                "ouvertures"
            })
    public record LigneStandConsigne(
            String standId,
            String standNom,
            int minutesPerdues,
            int effectifHerite,
            boolean exceptionDatee,
            String motif,
            boolean preCoche,
            List<Ouverture> ouvertures) {}

    /** The stands of the edition read against one date and one band. */
    @Schema(requiredProperties = {"date", "creneauxDuJour", "stands"})
    public record Preselection(LocalDate date, int creneauxDuJour, List<LigneStandConsigne> stands) {}

    /**
     * What the band takes from each stand on {@code date}, before anything is
     * chosen: the list the screen starts from, recomputed on every opening of
     * the form — a stand closed since for a breakdown is not kept ticked by
     * inertia (decision 13 of the issue).
     */
    public Preselection preselection(LocalDate date, LocalTime fermetureDebut, LocalTime fermetureFin) {
        if (date == null) {
            throw new BusinessError.Invalid("Date manquante");
        }
        checkBande(fermetureDebut, fermetureFin);
        int[] bande = ConsigneResolver.minutes(fermetureDebut, fermetureFin);
        List<Creneau> duJour = creneauxDuJour(referenceDataService.listCreneaux(), date);
        List<Stand> stands = nominal(referenceDataService.listStands(), duJour);
        Map<String, List<Ouverture>> actuelles = new HashMap<>();
        repository.find(date).ifPresent(consigne -> {
            for (Ouverture ouverture : consigne.ouvertures()) {
                actuelles
                        .computeIfAbsent(ouverture.standId(), id -> new ArrayList<>())
                        .add(ouverture);
            }
        });
        List<LigneStandConsigne> lignes = new ArrayList<>();
        for (Stand stand : stands) {
            int perdues = 0;
            Integer herite = null;
            for (Creneau creneau : duJour) {
                for (Creneau.SegmentOuvert segment : creneau.segmentsOuverts(stand)) {
                    int[] absolu = absolu(creneau, segment);
                    int recouvrement = Math.min(absolu[1], bande[1]) - Math.max(absolu[0], bande[0]);
                    if (recouvrement > 0) {
                        perdues += recouvrement;
                        herite = herite == null ? segment.effectif() : Math.max(herite, segment.effectif());
                    }
                }
            }
            boolean exception =
                    HoraireStandResolver.sourceOfDay(stand, date) == HoraireStandResolver.SourceHoraire.EXCEPTION;
            lignes.add(new LigneStandConsigne(
                    stand.getId(),
                    stand.getNom(),
                    perdues,
                    herite != null ? herite : stand.getEffectifMin(),
                    exception,
                    exception ? MOTIF_EXCEPTION_DATEE : null,
                    perdues > 0 && !exception,
                    actuelles.getOrDefault(stand.getId(), List.of())));
        }
        lignes.sort(Comparator.comparing(LigneStandConsigne::standNom, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(LigneStandConsigne::standId));
        return new Preselection(date, duJour.size(), lignes);
    }

    /* --------------------------------- aperçu --------------------------------- */

    /**
     * One request covering several dates: the shape an arrêté takes — the same
     * band on two to five days — so extending is the same call with more
     * dates, and changing a date is the same call on that date.
     */
    @Schema(requiredProperties = {"dates", "fermetureDebut", "motif"})
    public record Demande(
            List<LocalDate> dates,
            LocalTime fermetureDebut,
            LocalTime fermetureFin,
            String motif,
            String prereglage,
            List<Fenetre> fenetres,
            List<Ouverture> ouvertures) {

        List<ConsigneEdition> versConsignes() {
            if (dates == null || dates.isEmpty()) {
                throw new BusinessError.Invalid("Aucune date : une consigne s'applique à au moins un jour");
            }
            List<ConsigneEdition> consignes = new ArrayList<>();
            for (LocalDate date : new LinkedHashSet<>(dates)) {
                consignes.add(new ConsigneEdition(
                        date,
                        fermetureDebut,
                        fermetureFin,
                        motif == null ? null : motif.strip(),
                        prereglage == null || prereglage.isBlank() ? null : prereglage.strip(),
                        fenetres,
                        ouvertures,
                        List.of(),
                        null,
                        null));
            }
            return consignes;
        }
    }

    /** A vacation of the grid named by its day and hours, the way the publication names one. */
    @Schema(requiredProperties = {"date", "heureDebut"})
    public record VacationRef(LocalDate date, LocalTime heureDebut, LocalTime heureFin) {}

    /**
     * What one day of consigne costs, before writing it.
     *
     * @param dejaSousConsigne       the date already carries a consigne: this is a change, not a first laying down
     * @param creneauxDuJour         créneaux the edition holds on that date — without it, a date the
     *                               edition has no grid on reads like a date the consigne emptied
     * @param siegesAvant            seats the day generates today, and would generate after
     * @param minutesAvant           seat-minutes today, and after
     * @param creneauxAAjouter       stretches no créneau covers, that applying adds to the grid
     * @param creneauxARetirer       créneaux a previous consigne of the date added and no window needs any more
     * @param vacationsSansSiege     créneaux that carry seats today and none after
     * @param standsOuverts          stands the consigne opens
     * @param standsEntrants         stands opened now and not by the date's existing consigne
     * @param standsSortants         stands the existing consigne opened and this one no longer does
     * @param standsExceptionCoches  stands opened although they stated the day by hand
     * @param mineursConcernes       minors available that day — the late windows are closed to them by the
     *                               night and rest rules, so the solver staffs them with adults
     * @param majeursDisponibles     adults available that day
     * @param validationRetiree      the day is « relu et accepté » and laying the consigne withdraws it
     * @param verrousTouches         locks naming a vacation of the band, or a person on one
     * @param contraintesTouchees    ad hoc rules naming a vacation of the band
     * @param animateursDansLaBande  people of the persisted plan seated in the band: an order of magnitude
     *                               of what the publication will announce
     */
    @Schema(
            requiredProperties = {
                "date",
                "dejaSousConsigne",
                "creneauxDuJour",
                "siegesAvant",
                "siegesApres",
                "minutesAvant",
                "minutesApres",
                "creneauxAAjouter",
                "creneauxARetirer",
                "vacationsSansSiege",
                "standsOuverts",
                "standsEntrants",
                "standsSortants",
                "standsExceptionCoches",
                "mineursConcernes",
                "majeursDisponibles",
                "validationRetiree",
                "verrousTouches",
                "contraintesTouchees",
                "animateursDansLaBande"
            })
    public record ApercuJour(
            LocalDate date,
            boolean dejaSousConsigne,
            int creneauxDuJour,
            int siegesAvant,
            int siegesApres,
            int minutesAvant,
            int minutesApres,
            List<Fenetre> creneauxAAjouter,
            List<VacationRef> creneauxARetirer,
            List<VacationRef> vacationsSansSiege,
            int standsOuverts,
            List<String> standsEntrants,
            List<String> standsSortants,
            List<String> standsExceptionCoches,
            int mineursConcernes,
            int majeursDisponibles,
            boolean validationRetiree,
            int verrousTouches,
            int contraintesTouchees,
            int animateursDansLaBande) {}

    /**
     * What applying {@code demande} would do, without writing anything: one
     * line per date. Also validates the whole request before any write.
     */
    public List<ApercuJour> apercu(Demande demande) {
        List<ConsigneEdition> consignes = demande.versConsignes();
        validate(consignes);
        Contexte contexte = contexte();
        List<ApercuJour> apercu = new ArrayList<>();
        for (ConsigneEdition demandee : consignes) {
            apercu.add(apercuJour(demandee, contexte));
        }
        return apercu;
    }

    /** What the reads below share, read once per request. */
    private record Contexte(
            List<Creneau> creneaux,
            List<Animateur> animateurs,
            Map<LocalDate, ConsigneEdition> existantes,
            Set<LocalDate> joursValides,
            List<VerrouillagePlanning> verrouillages,
            List<ContrainteAdHoc> contraintes,
            PlanningEvenement persiste) {}

    private Contexte contexte() {
        Set<LocalDate> joursValides = new HashSet<>();
        for (ValidationJournee validation : validations.list()) {
            joursValides.add(validation.jour());
        }
        return new Contexte(
                referenceDataService.listCreneaux(),
                referenceDataService.listAnimateurs(),
                parDate(),
                joursValides,
                referenceDataService.listVerrouillages(),
                referenceDataService.listContraintesAdHoc(),
                persistence.loadPersistedPlanning());
    }

    private ApercuJour apercuJour(ConsigneEdition demandee, Contexte contexte) {
        LocalDate date = demandee.date();
        ConsigneEdition existante = contexte.existantes().get(date);
        Set<Long> dejaAjoutes = existante == null ? Set.of() : new HashSet<>(existante.creneauxAjoutes());
        List<Creneau> duJour = creneauxDuJour(contexte.creneaux(), date);
        // The nominal grid: the day's créneaux minus those a previous consigne added.
        List<Creneau> nominale =
                duJour.stream().filter(c -> !dejaAjoutes.contains(c.getId())).toList();
        Plan plan = planifier(demandee, duJour, nominale);

        List<Stand> stands = referenceDataService.listStands();
        List<Stand> avant = nominal(stands, nominale);
        List<PosteAffectation> postesAvant = ProblemBuilder.buildPostes(avant, nominale);

        List<Creneau> apresGrille = new ArrayList<>(plan.conserves());
        // The créneaux to create have no id yet; the preview lends them one
        // below zero so the resolver can tell them apart from the nominal
        // grid — a stand not opened stays shut on them, as it will once they
        // exist. Nothing here is written.
        long emprunt = -1;
        for (Creneau aCreer : plan.aCreer()) {
            aCreer.setId(emprunt--);
            apresGrille.add(aCreer);
        }
        ConsigneEdition appliquee = demandee.withCreneauxAjoutes(
                apresGrille.stream().map(Creneau::getId).toList());
        List<Creneau> grilleApres = new ArrayList<>(nominale);
        grilleApres.addAll(apresGrille);
        List<Stand> apres = nominal(referenceDataService.listStands(), grilleApres);
        ConsigneResolver.apply(apres, List.of(appliquee), grilleApres);
        List<PosteAffectation> postesApres = ProblemBuilder.buildPostes(apres, grilleApres);

        Set<Long> avecSiegeAvant = new HashSet<>();
        postesAvant.forEach(poste -> avecSiegeAvant.add(poste.getCreneau().getId()));
        Set<Long> avecSiegeApres = new HashSet<>();
        postesApres.forEach(poste -> avecSiegeApres.add(poste.getCreneau().getId()));
        List<VacationRef> sansSiege = nominale.stream()
                .filter(c -> avecSiegeAvant.contains(c.getId()) && !avecSiegeApres.contains(c.getId()))
                .sorted(Comparator.comparing(Creneau::getHeureDebut))
                .map(ConsigneService::ref)
                .toList();

        Set<String> ouverts = new LinkedHashSet<>();
        demandee.ouvertures().forEach(ouverture -> ouverts.add(ouverture.standId()));
        Set<String> ouvertsAvant = new LinkedHashSet<>();
        if (existante != null) {
            existante.ouvertures().forEach(ouverture -> ouvertsAvant.add(ouverture.standId()));
        }
        List<String> entrants =
                ouverts.stream().filter(id -> !ouvertsAvant.contains(id)).toList();
        List<String> sortants =
                ouvertsAvant.stream().filter(id -> !ouverts.contains(id)).toList();
        List<String> exceptionsCochees = stands.stream()
                .filter(stand -> ouverts.contains(stand.getId()))
                .filter(stand ->
                        HoraireStandResolver.sourceOfDay(stand, date) == HoraireStandResolver.SourceHoraire.EXCEPTION)
                .map(Stand::getId)
                .toList();

        int mineurs = 0;
        int majeurs = 0;
        for (Animateur animateur : contexte.animateurs()) {
            if (animateur.isIndisponibleOn(date)) {
                continue;
            }
            if (animateur.isMineurOn(date)) {
                mineurs++;
            } else {
                majeurs++;
            }
        }

        int[] bande = ConsigneResolver.minutes(demandee.fermetureDebut(), demandee.fermetureFin());
        int verrous = 0;
        for (VerrouillagePlanning verrouillage : contexte.verrouillages()) {
            if (verrouillage.vacation() != null
                    && date.equals(verrouillage.vacation().date())
                    && chevauche(
                            verrouillage.vacation().heureDebut(),
                            verrouillage.vacation().heureFin(),
                            bande)) {
                verrous++;
            }
        }
        int contraintes = 0;
        for (ContrainteAdHoc contrainte : contexte.contraintes()) {
            Creneau creneau = contrainte.getCreneau();
            if (creneau != null
                    && date.equals(creneau.getDate())
                    && chevauche(creneau.getHeureDebut(), creneau.getHeureFin(), bande)) {
                contraintes++;
            }
        }
        Set<String> dansLaBande = new HashSet<>();
        for (PosteAffectation poste : contexte.persiste().getPostes()) {
            if (poste.getAnimateur() != null
                    && poste.getCreneau() != null
                    && date.equals(poste.getCreneau().getDate())
                    && chevauche(poste.heureDebutEffectif(), poste.heureFinEffectif(), bande)) {
                dansLaBande.add(poste.getAnimateur().getId());
            }
        }

        return new ApercuJour(
                date,
                existante != null,
                nominale.size(),
                postesAvant.size(),
                postesApres.size(),
                minutes(postesAvant),
                minutes(postesApres),
                plan.aCreer().stream()
                        .map(c -> new Fenetre(c.getHeureDebut(), c.getHeureFin()))
                        .toList(),
                plan.aSupprimer().stream().map(ConsigneService::ref).toList(),
                sansSiege,
                ouverts.size(),
                entrants,
                sortants,
                exceptionsCochees,
                mineurs,
                majeurs,
                contexte.joursValides().contains(date),
                verrous,
                contraintes,
                dansLaBande.size());
    }

    /* ------------------------------ the grid plan ------------------------------ */

    /**
     * What the consigne does to the grid of its date: the added créneaux it
     * keeps, the ones it creates, the ones a previous consigne added and no
     * window needs any more.
     */
    record Plan(List<Creneau> conserves, List<Creneau> aCreer, List<Creneau> aSupprimer) {}

    /**
     * Plans the créneaux the openings need. The stretches of the chosen
     * windows outside the band that no créneau of the nominal grid covers are
     * the ones to add; an added créneau of a previous consigne on that date is
     * kept when it still matches one of them exactly, so a change that leaves
     * the windows alone leaves their créneaux — and their seats — alone too.
     */
    static Plan planifier(ConsigneEdition consigne, List<Creneau> duJour, List<Creneau> nominale) {
        int[] bande = ConsigneResolver.minutes(consigne.fermetureDebut(), consigne.fermetureFin());
        List<int[]> couverts = new ArrayList<>();
        for (Creneau creneau : nominale) {
            couverts.add(bornes(creneau));
        }
        List<int[]> manques = new ArrayList<>();
        List<Ouverture> tri = new ArrayList<>(consigne.ouvertures());
        tri.sort(Comparator.comparing(Ouverture::debut));
        for (Ouverture ouverture : tri) {
            for (int[] horsBande : ConsigneResolver.soustraire(
                    ConsigneResolver.minutes(ouverture.debut(), ouverture.fin()), List.of(bande))) {
                for (int[] manque : gaps(horsBande[0], horsBande[1], couverts)) {
                    manques.add(manque);
                    couverts.add(manque);
                }
            }
        }
        manques.sort(Comparator.comparingInt(m -> m[0]));
        List<Creneau> ajoutes = duJour.stream()
                .filter(c -> nominale.stream().noneMatch(n -> Objects.equals(n.getId(), c.getId())))
                .toList();
        List<Creneau> conserves = new ArrayList<>();
        List<Creneau> aCreer = new ArrayList<>();
        Set<Long> gardes = new HashSet<>();
        for (int[] manque : manques) {
            Creneau present = ajoutes.stream()
                    .filter(c -> !gardes.contains(c.getId()))
                    .filter(c -> bornes(c)[0] == manque[0] && bornes(c)[1] == manque[1])
                    .findFirst()
                    .orElse(null);
            if (present != null) {
                gardes.add(present.getId());
                conserves.add(present);
            } else {
                aCreer.add(new Creneau(null, 0, consigne.date(), heure(manque[0]), heure(manque[1])));
            }
        }
        List<Creneau> aSupprimer =
                ajoutes.stream().filter(c -> !gardes.contains(c.getId())).toList();
        return new Plan(conserves, aCreer, aSupprimer);
    }

    /** Stretches of {@code [debut, fin)} no interval of {@code couverts} covers. */
    static List<int[]> gaps(int debut, int fin, List<int[]> couverts) {
        List<int[]> tries =
                couverts.stream().sorted(Comparator.comparingInt(c -> c[0])).toList();
        List<int[]> manquants = new ArrayList<>();
        int curseur = debut;
        for (int[] couvert : tries) {
            if (couvert[1] <= curseur || couvert[0] >= fin) {
                continue;
            }
            if (couvert[0] > curseur) {
                manquants.add(new int[] {curseur, Math.min(couvert[0], fin)});
            }
            curseur = Math.max(curseur, couvert[1]);
            if (curseur >= fin) {
                break;
            }
        }
        if (curseur < fin) {
            manquants.add(new int[] {curseur, fin});
        }
        return manquants;
    }

    /* --------------------------------- writes --------------------------------- */

    /**
     * Lays the consignes down — or replaces the ones their dates already carry
     * — and adds whatever créneaux their openings need. Returns the preview's
     * figures, computed before the write: once the créneaux exist, a preview
     * can no longer tell them from the nominal grid.
     */
    public List<ApercuJour> poser(Demande demande) {
        solverJobs.refuseIfSolving();
        List<ConsigneEdition> consignes = demande.versConsignes();
        validate(consignes);
        LocalDate aujourdhui = clock.today();
        for (ConsigneEdition consigne : consignes) {
            checkAVenir(consigne.date(), aujourdhui);
        }
        List<ApercuJour> apercu = apercu(demande);
        Map<LocalDate, ConsigneEdition> existantes = parDate();
        for (ConsigneEdition consigne : consignes) {
            ConsigneEdition existante = existantes.get(consigne.date());
            Set<Long> dejaAjoutes = existante == null ? Set.of() : new HashSet<>(existante.creneauxAjoutes());
            List<Creneau> duJour = creneauxDuJour(referenceDataService.listCreneaux(), consigne.date());
            List<Creneau> nominale = duJour.stream()
                    .filter(c -> !dejaAjoutes.contains(c.getId()))
                    .toList();
            Plan plan = planifier(consigne, duJour, nominale);
            List<Long> ids = new ArrayList<>();
            plan.conserves().forEach(creneau -> ids.add(creneau.getId()));
            for (Creneau aCreer : plan.aCreer()) {
                // The journaled write, like every other door onto the referential:
                // the history records the créneau the consigne added.
                ids.add(referenceDataService.writeCreneau(aCreer).creneau().getId());
            }
            repository.save(consigne.withCreneauxAjoutes(ids));
            if (!plan.aSupprimer().isEmpty()) {
                referenceDataService.deleteCreneaux(
                        plan.aSupprimer().stream().map(Creneau::getId).toList());
            }
        }
        validations.withdrawDays(consignes.stream().map(ConsigneEdition::date).toList());
        changeTracker.markModified();
        return apercu;
    }

    /** What lifting {@code dates} would do. */
    @Schema(
            requiredProperties = {
                "date",
                "sousConsigne",
                "creneauxARetirer",
                "validationRetiree",
                "animateursSurLesCreneauxRetires"
            })
    public record ApercuLevee(
            LocalDate date,
            boolean sousConsigne,
            List<VacationRef> creneauxARetirer,
            boolean validationRetiree,
            int animateursSurLesCreneauxRetires) {}

    public List<ApercuLevee> apercuLevee(List<LocalDate> dates) {
        checkDates(dates);
        Contexte contexte = contexte();
        List<ApercuLevee> apercu = new ArrayList<>();
        for (LocalDate date : new LinkedHashSet<>(dates)) {
            ConsigneEdition consigne = contexte.existantes().get(date);
            if (consigne == null) {
                apercu.add(new ApercuLevee(date, false, List.of(), false, 0));
                continue;
            }
            Set<Long> ajoutes = new HashSet<>(consigne.creneauxAjoutes());
            List<VacationRef> retires = contexte.creneaux().stream()
                    .filter(c -> ajoutes.contains(c.getId()))
                    .sorted(Comparator.comparing(Creneau::getHeureDebut))
                    .map(ConsigneService::ref)
                    .toList();
            Set<String> assis = new HashSet<>();
            for (PosteAffectation poste : contexte.persiste().getPostes()) {
                if (poste.getAnimateur() != null
                        && poste.getCreneau() != null
                        && ajoutes.contains(poste.getCreneau().getId())) {
                    assis.add(poste.getAnimateur().getId());
                }
            }
            apercu.add(
                    new ApercuLevee(date, true, retires, contexte.joursValides().contains(date), assis.size()));
        }
        return apercu;
    }

    /**
     * Lifts the consignes of {@code dates}: the stands get their own hours
     * back, the créneaux the consignes added leave the grid with their seats.
     * Refuses a date already begun, and a date that carries no consigne.
     */
    public void lever(List<LocalDate> dates) {
        solverJobs.refuseIfSolving();
        checkDates(dates);
        LocalDate aujourdhui = clock.today();
        Map<LocalDate, ConsigneEdition> existantes = parDate();
        for (LocalDate date : dates) {
            checkAVenir(date, aujourdhui);
            if (!existantes.containsKey(date)) {
                throw new BusinessError.NotFound("Aucune consigne le " + date);
            }
        }
        for (LocalDate date : new LinkedHashSet<>(dates)) {
            ConsigneEdition consigne = existantes.get(date);
            repository.delete(date);
            if (!consigne.creneauxAjoutes().isEmpty()) {
                referenceDataService.deleteCreneaux(consigne.creneauxAjoutes());
            }
        }
        validations.withdrawDays(dates);
        changeTracker.markModified();
    }

    /* ------------------------------- préréglages ------------------------------- */

    public List<PrereglageConsigne> listPrereglages() {
        return repository.listPrereglages();
    }

    /** Creates or replaces a preset, validated cold: a wave of the alert must not be the first test of it. */
    public PrereglageConsigne savePrereglage(PrereglageConsigne prereglage) {
        if (prereglage == null) {
            throw new BusinessError.Invalid("Préréglage manquant");
        }
        String id = prereglage.id() == null || prereglage.id().isBlank()
                ? UUID.randomUUID().toString()
                : Ids.required(prereglage.id(), "id du préréglage");
        if (prereglage.nom() == null || prereglage.nom().isBlank()) {
            throw new BusinessError.Invalid("Un préréglage porte un nom");
        }
        String nom = prereglage.nom().strip();
        if (nom.length() > 120) {
            throw new BusinessError.Invalid("Nom de préréglage trop long (120 caractères au plus)");
        }
        for (PrereglageConsigne autre : repository.listPrereglages()) {
            if (!autre.id().equals(id) && autre.nom().equalsIgnoreCase(nom)) {
                throw new BusinessError.Invalid("Un préréglage nommé « " + nom + " » existe déjà");
            }
        }
        checkBande(prereglage.fermetureDebut(), prereglage.fermetureFin());
        String motif = checkedMotif(prereglage.motif());
        checkFenetres(prereglage.fenetres(), prereglage.fermetureDebut(), prereglage.fermetureFin(), "Le préréglage");
        PrereglageConsigne propre = new PrereglageConsigne(
                id, nom, prereglage.fermetureDebut(), prereglage.fermetureFin(), motif, prereglage.fenetres(), null);
        repository.savePrereglage(propre);
        return repository.listPrereglages().stream()
                .filter(p -> p.id().equals(id))
                .findFirst()
                .orElse(propre);
    }

    public void deletePrereglage(String id) {
        if (id == null || id.isBlank() || !repository.deletePrereglage(id)) {
            throw new BusinessError.NotFound("Préréglage introuvable : " + id);
        }
    }

    /* ------------------------------- validation ------------------------------- */

    /**
     * Refuses a band without a start or ending before it starts, a blank
     * motif, a window ending before it starts or lying entirely inside the
     * band, a stand opened twice on the same window, a headcount that is not
     * positive or above the stand's maximum, a stand the edition does not
     * have, and a date the edition has no créneau on — all before anything is
     * written.
     */
    private void validate(List<ConsigneEdition> consignes) {
        if (consignes == null || consignes.isEmpty()) {
            throw new BusinessError.Invalid("Aucune date : une consigne s'applique à au moins un jour");
        }
        Map<String, Stand> stands = new HashMap<>();
        referenceDataService.listStands().forEach(stand -> stands.put(stand.getId(), stand));
        Set<LocalDate> joursGrille = new HashSet<>();
        referenceDataService.listCreneaux().forEach(creneau -> joursGrille.add(creneau.getDate()));
        for (ConsigneEdition consigne : consignes) {
            if (consigne.date() == null) {
                throw new BusinessError.Invalid("Date manquante");
            }
            if (!joursGrille.contains(consigne.date())) {
                throw new BusinessError.Invalid("Aucun créneau le " + consigne.date() + " : rien à fermer ce jour-là");
            }
            checkBande(consigne.fermetureDebut(), consigne.fermetureFin());
            checkedMotif(consigne.motif());
            checkFenetres(consigne.fenetres(), consigne.fermetureDebut(), consigne.fermetureFin(), "La consigne");
            Set<String> vues = new HashSet<>();
            Set<String> inconnus = new LinkedHashSet<>();
            for (Ouverture ouverture : consigne.ouvertures()) {
                String id = ouverture.standId();
                if (id == null || id.isBlank()) {
                    throw new BusinessError.Invalid("Une ouverture désigne un stand");
                }
                Stand stand = stands.get(id);
                if (stand == null) {
                    inconnus.add(id);
                    continue;
                }
                checkFenetre(
                        ouverture.fenetre(),
                        consigne.fermetureDebut(),
                        consigne.fermetureFin(),
                        "L'ouverture de " + stand.getNom());
                if (!vues.add(id + "|" + ouverture.debut() + "|" + ouverture.fin())) {
                    throw new BusinessError.Invalid("Le stand " + stand.getNom()
                            + " est ouvert deux fois sur la même fenêtre le " + consigne.date());
                }
                if (ouverture.effectif() != null) {
                    if (ouverture.effectif() <= 0) {
                        throw new BusinessError.Invalid("L'effectif de " + stand.getNom() + " doit être positif");
                    }
                    if (ouverture.effectif() > stand.getEffectifMax()) {
                        throw new BusinessError.Invalid("L'effectif de " + stand.getNom() + " dépasse son maximum ("
                                + stand.getEffectifMax() + ")");
                    }
                }
            }
            if (!inconnus.isEmpty()) {
                throw new BusinessError.Invalid("Stand inconnu dans cette édition : " + String.join(", ", inconnus));
            }
        }
    }

    private static void checkBande(LocalTime debut, LocalTime fin) {
        if (debut == null) {
            throw new BusinessError.Invalid("La bande interdite requiert une heure de début");
        }
        if (fin != null && !fin.equals(LocalTime.MIDNIGHT) && !debut.isBefore(fin)) {
            throw new BusinessError.Invalid("La bande interdite doit finir après son début");
        }
    }

    private static String checkedMotif(String motif) {
        if (motif == null || motif.isBlank()) {
            throw new BusinessError.Invalid(
                    "Le motif est obligatoire : il est affiché partout où la journée est marquée modifiée");
        }
        if (motif.strip().length() > 500) {
            throw new BusinessError.Invalid("Motif trop long (500 caractères au plus)");
        }
        return motif.strip();
    }

    private static void checkFenetres(List<Fenetre> fenetres, LocalTime bandeDebut, LocalTime bandeFin, String sujet) {
        if (fenetres == null) {
            return;
        }
        for (Fenetre fenetre : fenetres) {
            checkFenetre(fenetre, bandeDebut, bandeFin, sujet);
        }
    }

    private static void checkFenetre(Fenetre fenetre, LocalTime bandeDebut, LocalTime bandeFin, String sujet) {
        if (fenetre == null || fenetre.debut() == null) {
            throw new BusinessError.Invalid(sujet + " requiert une heure de début pour chaque fenêtre");
        }
        if (fenetre.fin() != null
                && !fenetre.fin().equals(LocalTime.MIDNIGHT)
                && !fenetre.debut().isBefore(fenetre.fin())) {
            throw new BusinessError.Invalid(sujet + " : une fenêtre doit finir après son début, dans la même journée");
        }
        int[] bande = ConsigneResolver.minutes(bandeDebut, bandeFin);
        if (ConsigneResolver.soustraire(ConsigneResolver.minutes(fenetre.debut(), fenetre.fin()), List.of(bande))
                .isEmpty()) {
            throw new BusinessError.Invalid(
                    sujet + " : la fenêtre " + libelle(fenetre) + " est entièrement dans la bande interdite");
        }
    }

    private static void checkDates(List<LocalDate> dates) {
        if (dates == null || dates.isEmpty() || dates.stream().anyMatch(Objects::isNull)) {
            throw new BusinessError.Invalid("Aucune date");
        }
    }

    private static void checkAVenir(LocalDate date, LocalDate aujourdhui) {
        if (!date.isAfter(aujourdhui)) {
            throw new BusinessError.Invalid("Le " + date
                    + " est passé ou en cours : une consigne ne se pose et ne se lève que sur les jours à venir");
        }
    }

    /* -------------------------------- helpers -------------------------------- */

    /** The stands with their rules and dated exceptions expanded — the day a consigne departs from. */
    private static List<Stand> nominal(List<Stand> stands, List<Creneau> creneaux) {
        HoraireStandResolver.apply(stands, creneaux);
        return stands;
    }

    private static List<Creneau> creneauxDuJour(List<Creneau> creneaux, LocalDate date) {
        return creneaux.stream()
                .filter(c -> date.equals(c.getDate()))
                .sorted(Comparator.comparing(Creneau::getHeureDebut).thenComparing(Creneau::getId))
                .toList();
    }

    private static int minutes(List<PosteAffectation> postes) {
        return postes.stream()
                .mapToInt(PosteAffectation::getDureeEffectiveMinutes)
                .sum();
    }

    private static VacationRef ref(Creneau creneau) {
        return new VacationRef(creneau.getDate(), creneau.getHeureDebut(), creneau.getHeureFin());
    }

    /** {@code [debut, fin)} of a créneau in minutes of its date; crossing midnight runs to it. */
    private static int[] bornes(Creneau creneau) {
        int debut = creneau.getHeureDebut().toSecondOfDay() / 60;
        int fin = creneau.getHeureFin() == null
                ? MINUTES_PAR_JOUR
                : creneau.getHeureFin().toSecondOfDay() / 60;
        if (fin <= debut) {
            fin = MINUTES_PAR_JOUR;
        }
        return new int[] {debut, fin};
    }

    /** A segment of a créneau in minutes of the day. */
    private static int[] absolu(Creneau creneau, Creneau.SegmentOuvert segment) {
        int debut = creneau.getHeureDebut().toSecondOfDay() / 60;
        return new int[] {debut + segment.debutMinutes(), debut + segment.finMinutes()};
    }

    private static boolean chevauche(LocalTime debut, LocalTime fin, int[] bande) {
        if (debut == null) {
            return false;
        }
        return ConsigneResolver.chevauchent(ConsigneResolver.minutes(debut, fin), bande);
    }

    private static LocalTime heure(int minutes) {
        return LocalTime.ofSecondOfDay((minutes % MINUTES_PAR_JOUR) * 60L);
    }

    private static String libelle(Fenetre fenetre) {
        return fenetre.debut() + "-" + (fenetre.fin() == null ? "minuit" : fenetre.fin());
    }
}
