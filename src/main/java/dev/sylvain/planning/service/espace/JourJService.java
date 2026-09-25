package dev.sylvain.planning.service.espace;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.FrozenPast;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.solve.PlanningService;
import dev.sylvain.planning.service.solve.PlanningWhatIf.SuggestionsReparation;
import dev.sylvain.planning.service.solve.SolverJobService;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The event-day screen ("mode jour J"): somebody did not show up, and the seats
 * they were holding have to change hands <b>now</b>.
 *
 * <p>Nothing here is new machinery. Marking somebody absent writes the same
 * {@code INDISPONIBILITE_FORCEE} the Ajustements manuels screen writes, freeing
 * their seats is {@link PlanningService#applyReparation} with no replacement,
 * and looking for one is {@link PlanningService#suggererReparations}. What this
 * service adds is the <em>scope</em> the other screens have no reason to know
 * about: the timeslots of one day that are still ahead.</p>
 *
 * <h2>Two rules the whole screen hangs on</h2>
 *
 * <p><b>A past timeslot is never touched.</b> The person really did hold it;
 * turning it into an unavailability would make the plan lie about what
 * happened. The cut is the timeslot's <em>end</em>, so the one running right
 * now counts as ahead — that is precisely the one nobody is standing at.</p>
 *
 * <p><b>Nothing is applied by halves.</b> An absence spans several timeslots and
 * therefore several exceptions and several seats; a refusal on the third one
 * must not leave the first two written. Contradictions and locks are therefore
 * checked over the whole scope before a single row is written.</p>
 *
 * <p>No solve is ever started, here or downstream: every write is the surgical
 * {@code UPDATE} of the repair assistant.</p>
 */
@ApplicationScoped
public class JourJService {

    /**
     * The mark every reason this screen writes carries — what recognises its
     * exceptions, since their ids are drawn by the application like any other
     * (ADR 0050). Marking the same person absent twice on a timeslot
     * overwrites the exception already there instead of piling up duplicates.
     */
    private static final String MARQUE_ABSENCE = "(mode jour J)";

    /** Recorded against the exception when no admin session names the author. */
    private static final String AUTEUR_INCONNU = "jour-j";

    private final ReferenceDataService referenceDataService;

    private final dev.sylvain.planning.service.consigne.ConsigneService consigneService;

    private final PlanningPersistenceService persistenceService;

    private final PlanningService planningService;

    private final SolverJobService solverJobs;

    private final SecurityIdentity identity;

    private final JourJClock clock;

    @Inject
    public JourJService(
            ReferenceDataService referenceDataService,
            dev.sylvain.planning.service.consigne.ConsigneService consigneService,
            PlanningPersistenceService persistenceService,
            PlanningService planningService,
            SolverJobService solverJobs,
            SecurityIdentity identity,
            JourJClock clock) {
        this.referenceDataService = referenceDataService;
        this.consigneService = consigneService;
        this.persistenceService = persistenceService;
        this.planningService = planningService;
        this.solverJobs = solverJobs;
        this.identity = identity;
        this.clock = clock;
    }

    /* -------------------------------- Reads -------------------------------- */

    /**
     * What the screen shows: the timeslots of the journée still ahead of the
     * reference moment, who is on duty over them, which seats are unstaffed, and
     * the absences already recorded for that journée.
     *
     * @param date       {@code null} resolves the journée under way — see
     *                   {@link #journee}
     * @param maintenant {@code null} means the server's own clock
     */
    public EtatJourJ etat(LocalDate date, LocalDateTime maintenantDemande) {
        List<Creneau> tousLesCreneaux = referenceDataService.listCreneaux();
        Journee journee = journee(tousLesCreneaux, date, maintenantDemande);
        LocalDate jour = journee.jour();
        LocalDateTime maintenant = journee.maintenant();
        PlanningEvenement plan = persistenceService.loadPersistedPlanning();
        List<Creneau> creneauxDuJour = creneauxOf(tousLesCreneaux, jour);
        List<Creneau> restants = creneauxDuJour.stream()
                .filter(creneau -> isStillAhead(creneau, maintenant))
                .toList();
        Set<Long> idsRestants =
                restants.stream().map(Creneau::getId).collect(Collectors.toCollection(LinkedHashSet::new));

        List<PosteAffectation> postesRestants = plan.getPostes().stream()
                .filter(poste -> poste.getCreneau() != null
                        && idsRestants.contains(poste.getCreneau().getId()))
                .toList();

        Map<String, Identite> identites = identites();
        List<AbsenceJourJ> absences = absencesOf(creneauxDuJour, identites);
        // Only an absence covering a timeslot that is still ahead counts as
        // "already marked". Somebody declared unavailable this morning and
        // nowhere else is on duty this afternoon like anyone else, and the
        // screen has to let them be marked absent for the rest of the day —
        // which is the one gesture it exists for.
        Set<String> absentIds = absences.stream()
                .filter(absence ->
                        absence.entrees().stream().anyMatch(entree -> idsRestants.contains(entree.creneauId())))
                .map(AbsenceJourJ::animateurId)
                .collect(Collectors.toSet());

        return new EtatJourJ(
                jour,
                maintenant,
                creneauxDuJour.size(),
                restants.stream()
                        .map(creneau -> creneauJourJ(creneau, maintenant))
                        .toList(),
                animateursAffectes(postesRestants, identites, absentIds),
                postesAPourvoir(postesRestants),
                absences,
                nommes(identites),
                consigneService
                        .find(jour)
                        .map(consigne ->
                                new ConsigneJourJ(consigne.fermetureDebut(), consigne.fermetureFin(), consigne.motif()))
                        .orElse(null));
    }

    /**
     * Repair suggestions for one seat, read from the <b>persisted</b> plan
     * rather than from a plan the caller uploads.
     *
     * <p>The assistant of issue #71 is reached over
     * {@code POST /api/postes/{id}/suggestions-reparation}, which takes the
     * whole planning as its request body — reasonable for a desktop screen that
     * already holds it, a megabyte per seat on the phone this one runs on. Same
     * service call, same bounded cost, one identifier on the wire.</p>
     *
     * <p>Prepared like the write that follows it: a plan read back from the
     * database carries seats and no rules, and a suggestion scored without them
     * is one {@code applyReparation} can refuse a click later.</p>
     */
    public SuggestionsReparation suggestions(String posteId, Integer plafond) {
        return planningService.persistedSuggererReparations(posteId, plafond);
    }

    /* -------------------------------- Writes ------------------------------- */

    /**
     * Records that {@code animateurId} is not there for the rest of
     * {@code date}: one {@code INDISPONIBILITE_FORCEE} per timeslot still
     * ahead, and every seat they were holding over those timeslots emptied —
     * except the seat of a timeslot that has already started, which the
     * freeze keeps as it is (ADR 0044): the exception is recorded on it, the
     * seat is not rewritten.
     *
     * <p>Refused as a whole — before anything is written — when one of those
     * exceptions would contradict an ad hoc exception already recorded (a
     * forced assignment on the very same timeslot, typically), or when one of
     * the seats to free is covered by a lock. Both refusals name what they
     * clash with: discovering it as a negative hard score three minutes into
     * the next solve is exactly the failure mode
     * {@link ContrainteAdHocContradictions} exists to replace.</p>
     */
    public AbsenceMarquee recordAbsence(
            String animateurId, String raison, LocalDate date, LocalDateTime maintenantDemande) {
        List<Creneau> tousLesCreneaux = referenceDataService.listCreneaux();
        Journee journee = journee(tousLesCreneaux, date, maintenantDemande);
        LocalDate jour = journee.jour();
        LocalDateTime maintenant = journee.maintenant();
        Animateur animateur = findAnimateur(animateurId, true);

        List<Creneau> duJour = creneauxOf(tousLesCreneaux, jour);
        if (duJour.isEmpty()) {
            throw new BusinessError.Invalid(
                    "Aucun créneau n'est programmé le " + jour + " : il n'y a pas de journée à couvrir.");
        }
        List<Creneau> restants = duJour.stream()
                .filter(creneau -> isStillAhead(creneau, maintenant))
                .toList();
        if (restants.isEmpty()) {
            throw new BusinessError.Invalid("Aucun créneau ne reste à couvrir le " + jour + " après "
                    + maintenant.toLocalTime() + " : il n'y a rien à libérer.");
        }

        PlanningEvenement plan = persistenceService.loadPersistedPlanning();
        Set<Long> idsRestants =
                restants.stream().map(Creneau::getId).collect(Collectors.toCollection(LinkedHashSet::new));
        // The seat of a timeslot already started stays as it is (ADR 0044):
        // the absence is recorded on it all the same, but « le passé ne se
        // modifie plus » — the freeze pins it, and the repair would be refused.
        PastHorizon horizon = planningService.pastHorizon();
        List<PosteAffectation> aLiberer = plan.getPostes().stream()
                .filter(poste -> poste.getAnimateur() != null
                        && animateurId.equals(poste.getAnimateur().getId()))
                .filter(poste -> poste.getCreneau() != null
                        && idsRestants.contains(poste.getCreneau().getId()))
                .filter(poste -> !FrozenPast.isPast(poste, horizon))
                .sorted(Comparator.comparing(PosteAffectation::getId))
                .toList();
        refuseLockedSeats(aLiberer);
        // Refused while a solve holds the edition, seats to free or not: the
        // solve read neither these exceptions nor the freed seats, and its
        // landing could seat the absent person on the day again. Asked here,
        // before the exceptions are written, so a refusal leaves nothing half
        // done.
        solverJobs.refuseIfSolving();

        String motif = motif(raison, jour, maintenant);
        Instant ecritLe = Instant.now();
        String recordedBy = author();
        Map<Long, String> dejaEcrites = new java.util.HashMap<>();
        referenceDataService.listContraintesAdHoc().stream()
                .filter(contrainte -> isWrittenHere(contrainte, animateur.getId()))
                .forEach(contrainte -> dejaEcrites.put(contrainte.getCreneau().getId(), contrainte.getId()));
        List<ContrainteAdHoc> exceptions = restants.stream()
                .map(creneau ->
                        exception(dejaEcrites.get(creneau.getId()), animateur, creneau, motif, recordedBy, ecritLe))
                .toList();
        // Refuses the whole set rather than the first offender, and writes
        // nothing until every one of them is accepted.
        referenceDataService.createContraintesAdHoc(exceptions);

        // One write per seat, but a single read of the plan: applyReparation
        // reloads all of it on every call, and somebody holding five remaining
        // timeslots would pay five full loads of an 1 800-seat plan — on the one
        // screen whose reason to exist is answering fast on a phone.
        planningService.applyReparations(
                plan, aLiberer.stream().map(PosteAffectation::getId).toList(), null);

        Map<Long, Creneau> byId = creneauxById(restants);
        return new AbsenceMarquee(
                animateurId,
                nomAffiche(identites().get(animateurId), animateurId),
                exceptions.stream()
                        .map(exception -> entree(exception, byId, true))
                        .toList(),
                aLiberer.stream().map(JourJService::vacantSeat).toList());
    }

    /**
     * Undoes a marked absence: one timeslot when {@code creneauId} names one,
     * the whole day otherwise.
     *
     * <p>Deliberately keyed on the <b>day</b> and not on what is still ahead: an
     * absence typed by mistake is undone a minute later, by which time the
     * timeslot it covers may already have started.</p>
     *
     * <p>Only what <b>this screen wrote</b> is removed, recognised by the id it
     * derives from (animateur, timeslot). Deleting every single-target
     * unavailability landing on that day would have taken, along with the
     * absence typed by mistake this morning, a long-standing one recorded weeks
     * earlier from the Ajustements manuels screen — silently widening what the
     * next solve is allowed to do. Same reason exceptions naming several people
     * are left alone: they belong to whoever wrote them.</p>
     *
     * <p>The seats are not handed back. Who holds a seat is a decision, and
     * assuming the previous occupant should get it back would silently undo
     * whatever replacement was applied in between.</p>
     *
     * @return how many exceptions were removed
     */
    public int cancelAbsence(String animateurId, LocalDate date, Long creneauId) {
        LocalDate jour = date != null
                ? date
                : journee(referenceDataService.listCreneaux(), null, null).jour();
        findAnimateur(animateurId, false);
        Map<Long, Creneau> creneaux = creneauxById(creneauxOf(referenceDataService.listCreneaux(), jour));
        List<ContrainteAdHoc> aSupprimer = referenceDataService.listContraintesAdHoc().stream()
                .filter(contrainte -> contrainte.getType() == TypeContrainteAdHoc.INDISPONIBILITE_FORCEE)
                .filter(contrainte -> isWrittenHere(contrainte, animateurId))
                .filter(contrainte -> targetsOnly(contrainte, animateurId))
                .filter(contrainte -> contrainte.getCreneau() != null
                        && creneaux.containsKey(contrainte.getCreneau().getId()))
                .filter(contrainte -> creneauId == null
                        || creneauId.equals(contrainte.getCreneau().getId()))
                .toList();
        if (aSupprimer.isEmpty()) {
            throw new BusinessError.NotFound("Aucune absence enregistrée pour " + animateurId + " le " + jour
                    + (creneauId == null ? "" : " sur le créneau " + creneauId) + ".");
        }
        aSupprimer.forEach(contrainte -> referenceDataService.deleteContrainteAdHoc(contrainte.getId()));
        return aSupprimer.size();
    }

    /* ------------------------------- Internals ----------------------------- */

    /**
     * Which journée is being looked at, and from which moment "remaining" is
     * counted. The two are kept apart on purpose: at one in the morning they sit
     * on different calendar dates.
     *
     * <p><b>A journée starts at its first timeslot.</b> Not at midnight, and not
     * at some configured hour: a festival day begins when the first stand opens.
     * So the journée under way at moment {@code T} is the latest one whose first
     * timeslot has already started and whose last one has not ended — which is
     * how, at one in the morning, the answer is still yesterday's date while its
     * 22:00-02:00 shift runs.</p>
     *
     * <p>When nothing is running — between two days, or on a date carrying no
     * timeslot at all — the answer falls back to the calendar date. There is no
     * journée to name, and inventing one would be worse than saying "nothing is
     * scheduled".</p>
     *
     * @param date       names the journée outright, for a rehearsal or a test
     * @param maintenant names the moment outright, same reason; it also names the
     *                   journée when {@code date} does not
     */
    private Journee journee(List<Creneau> creneaux, LocalDate date, LocalDateTime maintenant) {
        LocalDateTime horloge = maintenant != null ? maintenant : clock.today().atTime(clock.now());
        LocalDate enCours = currentDay(creneaux, horloge);
        LocalDate jour = date != null ? date : enCours;
        if (maintenant != null || jour.equals(enCours)) {
            return new Journee(jour, horloge);
        }
        // A journée nobody is standing in is read whole: an instant before its
        // first timeslot leaves all of them ahead.
        return new Journee(jour, jour.atStartOfDay());
    }

    /** The journée being looked at, and the moment it is read from. */
    private record Journee(LocalDate jour, LocalDateTime maintenant) {}

    /**
     * The journée under way at {@code maintenant}, or the calendar date when
     * none is. See {@link #journee} for why the boundary is the first timeslot.
     */
    private static LocalDate currentDay(List<Creneau> creneaux, LocalDateTime maintenant) {
        return TimeslotWindows.currentDay(
                creneaux.stream().filter(JourJService::horaireConnu).toList(), maintenant);
    }

    private static boolean horaireConnu(Creneau creneau) {
        return creneau.getId() != null
                && creneau.getDate() != null
                && creneau.getHeureDebut() != null
                && creneau.getHeureFin() != null;
    }

    /** The wall-clock window a timeslot really covers — see {@link TimeslotWindows#window(Creneau)}. */
    private static LocalDateTime[] window(Creneau creneau) {
        return TimeslotWindows.window(creneau);
    }

    /** Whether the timeslot has not ended yet at {@code reference}. */
    private static boolean isStillAhead(Creneau creneau, LocalDateTime reference) {
        return window(creneau)[1].isAfter(reference);
    }

    /** Started but not over: the one nobody is standing at right now. */
    private static boolean isUnderWay(Creneau creneau, LocalDateTime reference) {
        return TimeslotWindows.isUnderWay(creneau, reference);
    }

    /**
     * The timeslots of one journée: those the journée <b>opens</b>, which is to
     * say those whose start falls on that date.
     *
     * <p>A 22:00-02:00 shift therefore belongs to the evening that opens it and
     * to that evening alone — it does not split itself over two journées. What
     * carries it past midnight is not membership but
     * {@link #isStillAhead(Creneau, LocalDateTime)}, which compares the end of
     * its window: at one in the morning it is the timeslot of yesterday's
     * journée that is still running, and {@link #currentDay} is what says the
     * journée being looked at is still yesterday's.</p>
     */
    private static List<Creneau> creneauxOf(List<Creneau> creneaux, LocalDate jour) {
        return creneaux.stream()
                .filter(JourJService::horaireConnu)
                .filter(creneau -> jour.equals(creneau.getDate()))
                .sorted(Comparator.comparing(Creneau::getHeureDebut).thenComparing(Creneau::getId))
                .toList();
    }

    private static Map<Long, Creneau> creneauxById(List<Creneau> creneaux) {
        Map<Long, Creneau> byId = new HashMap<>();
        creneaux.forEach(creneau -> byId.putIfAbsent(creneau.getId(), creneau));
        return byId;
    }

    /**
     * The named animateur, or a refusal whose status matches where the name
     * came from: a body field that names nobody is a bad request, a path
     * segment that names nobody is a 404.
     */
    private Animateur findAnimateur(String animateurId, boolean fromBody) {
        return referenceDataService.listAnimateurs().stream()
                .filter(animateur ->
                        animateur.getId() != null && animateur.getId().equals(animateurId))
                .findFirst()
                .orElseThrow(() -> fromBody
                        ? new BusinessError.Invalid("Animateur inconnu : " + animateurId)
                        : new BusinessError.NotFound("Animateur inconnu : " + animateurId));
    }

    /**
     * Refuses the whole absence when one of the seats to free is locked, rather
     * than letting {@link PlanningService#applyReparation} refuse it halfway
     * through the loop. Same rule, same reason — a lock is the operator saying
     * "this one does not move" — but stated before anything is written.
     */
    private void refuseLockedSeats(List<PosteAffectation> postes) {
        List<VerrouillagePlanning> verrouillages = referenceDataService.listVerrouillages();
        List<String> bloques = postes.stream()
                .filter(poste -> verrouillages.stream().anyMatch(verrouillage -> verrouillage.couvre(poste)))
                .map(poste ->
                        poste.getStand().getId() + " (" + poste.getCreneau().getHeureDebut() + ")")
                .toList();
        if (!bloques.isEmpty()) {
            throw new BusinessError.Invalid("Ces postes sont verrouillés et ne peuvent pas être libérés : "
                    + String.join(", ", bloques) + ". Déverrouillez-les avant de marquer l'absence.");
        }
    }

    private static String motif(String raison, LocalDate jour, LocalDateTime maintenant) {
        String base = "Absent le " + jour + " à partir de " + maintenant.toLocalTime() + " " + MARQUE_ABSENCE;
        return raison == null || raison.isBlank() ? base : base + " — " + raison.trim();
    }

    /** @param id the exception this screen already wrote there, {@code null} for a new one */
    private static ContrainteAdHoc exception(
            String id, Animateur animateur, Creneau creneau, String motif, String author, Instant maintenant) {
        ContrainteAdHoc contrainte = new ContrainteAdHoc(id, TypeContrainteAdHoc.INDISPONIBILITE_FORCEE);
        Animateur cible = new Animateur();
        cible.setId(animateur.getId());
        contrainte.setAnimateursConcernes(new ArrayList<>(List.of(cible)));
        Creneau portee = new Creneau();
        portee.setId(creneau.getId());
        contrainte.setCreneau(portee);
        contrainte.setRaison(motif);
        contrainte.setCreeParUtilisateurId(author);
        contrainte.setCreeLe(maintenant);
        return contrainte;
    }

    /**
     * Who is recorded as the author of the exception. The admin session names
     * one; an anonymous call (the test profile opens the API) falls back to a
     * constant rather than to null, so the trace never reads as "nobody".
     */
    private String author() {
        return identity == null || identity.isAnonymous() || identity.getPrincipal() == null
                ? AUTEUR_INCONNU
                : identity.getPrincipal().getName();
    }

    /**
     * Whether this exception is one this screen wrote for that animateur: an
     * unavailability on one timeslot, naming that person alone, whose reason
     * carries {@link #MARQUE_ABSENCE} — read back without a column of its own.
     */
    private static boolean isWrittenHere(ContrainteAdHoc contrainte, String animateurId) {
        return contrainte.getType() == TypeContrainteAdHoc.INDISPONIBILITE_FORCEE
                && contrainte.getCreneau() != null
                && contrainte.getCreneau().getId() != null
                && contrainte.getRaison() != null
                && contrainte.getRaison().contains(MARQUE_ABSENCE)
                && targetsOnly(contrainte, animateurId);
    }

    private static boolean targetsOnly(ContrainteAdHoc contrainte, String animateurId) {
        List<String> ids = animateurIds(contrainte);
        return ids.size() == 1 && ids.getFirst().equals(animateurId);
    }

    private static List<String> animateurIds(ContrainteAdHoc contrainte) {
        List<Animateur> cibles = contrainte.getAnimateursConcernes();
        if (cibles == null) {
            return List.of();
        }
        return cibles.stream()
                .filter(Objects::nonNull)
                .map(Animateur::getId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
    }

    /**
     * Every forced unavailability in force on one of the day's timeslots,
     * grouped by animateur — whoever wrote it. An absence declared weeks ahead
     * from the Ajustements manuels screen shows here exactly like one marked
     * this morning: what the screen answers is "who is missing today", not
     * "what did this screen write".
     */
    private List<AbsenceJourJ> absencesOf(List<Creneau> creneauxDuJour, Map<String, Identite> identites) {
        Map<Long, Creneau> byId = creneauxById(creneauxDuJour);
        Map<String, List<EntreeAbsence>> parAnimateur = new LinkedHashMap<>();
        for (ContrainteAdHoc contrainte : referenceDataService.listContraintesAdHoc()) {
            if (contrainte.getType() != TypeContrainteAdHoc.INDISPONIBILITE_FORCEE
                    || contrainte.getCreneau() == null
                    || !byId.containsKey(contrainte.getCreneau().getId())) {
                continue;
            }
            List<String> cibles = animateurIds(contrainte);
            for (String cible : cibles) {
                parAnimateur
                        .computeIfAbsent(cible, id -> new ArrayList<>())
                        .add(entree(contrainte, byId, cibles.size() == 1));
            }
        }
        return parAnimateur.entrySet().stream()
                .map(absence -> new AbsenceJourJ(
                        absence.getKey(),
                        nomAffiche(identites.get(absence.getKey()), absence.getKey()),
                        absence.getValue().stream()
                                .sorted(Comparator.comparing(
                                        EntreeAbsence::heureDebut, Comparator.nullsLast(Comparator.naturalOrder())))
                                .toList()))
                .sorted(Comparator.comparing(AbsenceJourJ::nomAffiche))
                .toList();
    }

    private static EntreeAbsence entree(ContrainteAdHoc contrainte, Map<Long, Creneau> creneaux, boolean annulable) {
        Creneau creneau = creneaux.get(contrainte.getCreneau().getId());
        return new EntreeAbsence(
                contrainte.getId(),
                contrainte.getCreneau().getId(),
                creneau == null ? null : creneau.getHeureDebut(),
                creneau == null ? null : creneau.getHeureFin(),
                contrainte.getRaison(),
                contrainte.getCreeParUtilisateurId(),
                contrainte.getCreeLe(),
                annulable);
    }

    private static CreneauJourJ creneauJourJ(Creneau creneau, LocalDateTime maintenant) {
        return new CreneauJourJ(
                creneau.getId(),
                creneau.getDate(),
                creneau.getHeureDebut(),
                creneau.getHeureFin(),
                isUnderWay(creneau, maintenant));
    }

    private static List<AnimateurAffecte> animateursAffectes(
            List<PosteAffectation> postesRestants, Map<String, Identite> identites, Set<String> absents) {
        Map<String, Integer> comptes = new LinkedHashMap<>();
        for (PosteAffectation poste : postesRestants) {
            if (poste.getAnimateur() != null) {
                comptes.merge(poste.getAnimateur().getId(), 1, Integer::sum);
            }
        }
        Set<String> vus = new HashSet<>(comptes.keySet());
        return vus.stream()
                .map(id -> new AnimateurAffecte(
                        id, nomAffiche(identites.get(id), id), comptes.getOrDefault(id, 0), absents.contains(id)))
                .sorted(Comparator.comparing(AnimateurAffecte::nomAffiche))
                .toList();
    }

    private List<PosteAPourvoir> postesAPourvoir(List<PosteAffectation> postesRestants) {
        List<VerrouillagePlanning> verrouillages = referenceDataService.listVerrouillages();
        return postesRestants.stream()
                .filter(poste -> poste.getAnimateur() == null)
                .sorted(Comparator.comparing(
                                (PosteAffectation poste) -> poste.getCreneau().getHeureDebut())
                        .thenComparing(PosteAffectation::getId))
                .map(poste -> new PosteAPourvoir(
                        poste.getId(),
                        poste.getStand().getId(),
                        poste.getStand().getNom(),
                        poste.getCreneau().getId(),
                        poste.heureDebutEffectif(),
                        poste.heureFinEffectif(),
                        verrouillages.stream().anyMatch(verrouillage -> verrouillage.couvre(poste))))
                .toList();
    }

    /** A seat this very call has just emptied: it was reachable, so it is not locked. */
    private static PosteAPourvoir vacantSeat(PosteAffectation poste) {
        return new PosteAPourvoir(
                poste.getId(),
                poste.getStand().getId(),
                poste.getStand().getNom(),
                poste.getCreneau().getId(),
                poste.heureDebutEffectif(),
                poste.heureFinEffectif(),
                false);
    }

    /**
     * Every animateur of the edition, id and display name only.
     *
     * <p>The screen has to name the people the repair assistant proposes, and
     * those are precisely the ones <em>not</em> working the remaining timeslots
     * — the best replacement is somebody free. Named from the on-duty list
     * alone, the main action button read « anim-73 ». Two short fields per
     * animateur, on a screen that already lists names.</p>
     */
    private static List<AnimateurNomme> nommes(Map<String, Identite> identites) {
        return identites.entrySet().stream()
                .map(entree -> new AnimateurNomme(entree.getKey(), nomAffiche(entree.getValue(), entree.getKey())))
                .sorted(Comparator.comparing(AnimateurNomme::nomAffiche))
                .toList();
    }

    private Map<String, Identite> identites() {
        Map<String, Identite> identites = new HashMap<>();
        for (Animateur animateur : referenceDataService.listAnimateurs()) {
            identites.put(animateur.getId(), new Identite(animateur.getPrenom(), animateur.getNom()));
        }
        return identites;
    }

    private static String nomAffiche(Identite identite, String fallback) {
        if (identite == null) {
            return fallback;
        }
        String complet = ((identite.prenom() == null ? "" : identite.prenom() + " ")
                        + (identite.nom() == null ? "" : identite.nom()))
                .trim();
        return complet.isBlank() ? fallback : complet;
    }

    private record Identite(String prenom, String nom) {}

    /* -------------------------------- Payloads ----------------------------- */

    /**
     * The whole screen in one answer.
     *
     * @param maintenant      the moment "remaining" is counted from, echoed back
     *                        so the screen states it rather than assuming the
     *                        phone's clock matches the server's. A full instant,
     *                        not an hour: at one in the morning it sits on the
     *                        calendar date <em>after</em> {@code date}, since a
     *                        journée running past midnight is still that
     *                        journée
     * @param creneauxDuJour  how many timeslots the day holds in all — the
     *                        denominator that says how much of it is already
     *                        behind
     * @param animateurs      the whole roster, id and name: the replacements the
     *                        assistant proposes are by definition not in
     *                        {@code animateursDeService}, and they still have to
     *                        be named on the button that hands them a seat
     */
    @Schema(requiredProperties = {"creneauxDuJour"})
    public record EtatJourJ(
            LocalDate date,
            LocalDateTime maintenant,
            int creneauxDuJour,
            List<CreneauJourJ> creneauxRestants,
            List<AnimateurAffecte> animateursDeService,
            List<PosteAPourvoir> postesAPourvoir,
            List<AbsenceJourJ> absences,
            List<AnimateurNomme> animateurs,
            ConsigneJourJ consigne) {}

    /** The consigne governing the day (issue #4), {@code null} on an ordinary day. */
    @Schema(requiredProperties = {"fermetureDebut", "motif"})
    public record ConsigneJourJ(java.time.LocalTime fermetureDebut, java.time.LocalTime fermetureFin, String motif) {}

    /** An animateur of the edition, named. */
    public record AnimateurNomme(String animateurId, String nomAffiche) {}

    /** One timeslot still ahead. */
    @Schema(requiredProperties = {"enCours", "id"})
    public record CreneauJourJ(long id, LocalDate date, LocalTime heureDebut, LocalTime heureFin, boolean enCours) {}

    /**
     * Somebody holding at least one seat over the remaining timeslots — the
     * list the operator picks the missing person from.
     */
    @Schema(requiredProperties = {"absent", "postesRestants"})
    public record AnimateurAffecte(String animateurId, String nomAffiche, int postesRestants, boolean absent) {}

    /**
     * An unstaffed seat on a remaining timeslot.
     *
     * @param verrouille a lock covers it: the repair assistant will refuse to
     *                   write here until it is lifted, and the screen has to say
     *                   so instead of offering a button that cannot work
     */
    @Schema(requiredProperties = {"creneauId", "verrouille"})
    public record PosteAPourvoir(
            String posteId,
            String standId,
            String standNom,
            long creneauId,
            LocalTime heureDebut,
            LocalTime heureFin,
            boolean verrouille) {}

    /** Somebody missing today, and over which timeslots. */
    public record AbsenceJourJ(String animateurId, String nomAffiche, List<EntreeAbsence> entrees) {}

    /**
     * One timeslot of an absence, with the trace the exception carries.
     *
     * @param annulable false when the exception names several animateurs: it was
     *                  not written by this screen and undoing it here would free
     *                  people nobody asked about
     */
    @Schema(requiredProperties = {"annulable", "creneauId"})
    public record EntreeAbsence(
            String contrainteId,
            long creneauId,
            LocalTime heureDebut,
            LocalTime heureFin,
            String raison,
            String creeParUtilisateurId,
            Instant creeLe,
            boolean annulable) {}

    /** What one "marquer absent" wrote, so the screen can go straight to the holes it opened. */
    public record AbsenceMarquee(
            String animateurId, String nomAffiche, List<EntreeAbsence> entrees, List<PosteAPourvoir> postesLiberes) {}
}
