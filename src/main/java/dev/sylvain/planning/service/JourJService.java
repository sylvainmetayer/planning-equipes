package dev.sylvain.planning.service;

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

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.PlanningService.SuggestionsReparation;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

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
     * Prefix of the exceptions this screen writes. The id is derived from
     * (animateur, timeslot) rather than random, which makes marking the same
     * person absent twice an overwrite instead of a pile of duplicates.
     */
    private static final String PREFIXE_ABSENCE = "absence-jour-j";

    /** Recorded against the exception when no admin session names the author. */
    private static final String AUTEUR_INCONNU = "jour-j";

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    PlanningPersistenceService persistenceService;

    @Inject
    PlanningService planningService;

    @Inject
    SecurityIdentity identity;

    /* -------------------------------- Reads -------------------------------- */

    /**
     * What the screen shows: the timeslots of {@code date} still ahead of
     * {@code heure}, who is on duty over them, which seats are unstaffed, and
     * the absences already recorded for that day.
     *
     * @param date  {@code null} means today
     * @param heure {@code null} means now when {@code date} is today, and the
     *              start of the day otherwise — reading another day is reading
     *              all of it
     */
    public EtatJourJ etat(LocalDate date, LocalTime heure) {
        LocalDate jour = date != null ? date : LocalDate.now();
        LocalTime reference = referenceTime(jour, heure);
        PlanningEvenement plan = persistenceService.loadPersistedPlanning();
        List<Creneau> creneauxDuJour = creneauxOf(referenceDataService.listCreneaux(), jour);
        List<Creneau> restants = creneauxDuJour.stream()
                .filter(creneau -> isStillAhead(creneau, reference))
                .toList();
        Set<Long> idsRestants = restants.stream().map(Creneau::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        List<PosteAffectation> postesRestants = plan.getPostes().stream()
                .filter(poste -> poste.getCreneau() != null && idsRestants.contains(poste.getCreneau().getId()))
                .toList();

        Map<String, Identite> identites = identites();
        List<AbsenceJourJ> absences = absencesOf(creneauxDuJour, identites);
        Set<String> absentIds = absences.stream().map(AbsenceJourJ::animateurId).collect(Collectors.toSet());

        return new EtatJourJ(jour, reference, creneauxDuJour.size(),
                restants.stream().map(creneau -> creneauJourJ(creneau, reference)).toList(),
                animateursAffectes(postesRestants, identites, absentIds),
                postesAPourvoir(postesRestants),
                absences);
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
     */
    public SuggestionsReparation suggestions(String posteId, Integer plafond) {
        return planningService.suggererReparations(persistenceService.loadPersistedPlanning(), posteId, plafond);
    }

    /* -------------------------------- Writes ------------------------------- */

    /**
     * Records that {@code animateurId} is not there for the rest of
     * {@code date}: one {@code INDISPONIBILITE_FORCEE} per timeslot still
     * ahead, and every seat they were holding over those timeslots emptied.
     *
     * <p>Refused as a whole — before anything is written — when one of those
     * exceptions would contradict an ad hoc exception already recorded (a
     * forced assignment on the very same timeslot, typically), or when one of
     * the seats to free is covered by a lock. Both refusals name what they
     * clash with: discovering it as a negative hard score three minutes into
     * the next solve is exactly the failure mode
     * {@link ContrainteAdHocContradictions} exists to replace.</p>
     */
    public AbsenceMarquee recordAbsence(String animateurId, String raison, LocalDate date, LocalTime heure) {
        LocalDate jour = date != null ? date : LocalDate.now();
        LocalTime reference = referenceTime(jour, heure);
        Animateur animateur = findAnimateur(animateurId, true);

        List<Creneau> restants = creneauxOf(referenceDataService.listCreneaux(), jour).stream()
                .filter(creneau -> isStillAhead(creneau, reference))
                .toList();
        if (restants.isEmpty()) {
            throw new BusinessError.Invalid("Aucun créneau ne reste à couvrir le " + jour + " après "
                    + reference + " : il n'y a rien à libérer.");
        }

        PlanningEvenement plan = persistenceService.loadPersistedPlanning();
        Set<Long> idsRestants = restants.stream().map(Creneau::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<PosteAffectation> aLiberer = plan.getPostes().stream()
                .filter(poste -> poste.getAnimateur() != null && animateurId.equals(poste.getAnimateur().getId()))
                .filter(poste -> poste.getCreneau() != null && idsRestants.contains(poste.getCreneau().getId()))
                .sorted(Comparator.comparing(PosteAffectation::getId))
                .toList();
        refuseLockedSeats(aLiberer);

        String motif = motif(raison, jour, reference);
        Instant maintenant = Instant.now();
        String recordedBy = author();
        List<ContrainteAdHoc> exceptions = restants.stream()
                .map(creneau -> exception(animateur, creneau, motif, recordedBy, maintenant))
                .toList();
        // Refuses the whole set rather than the first offender, and writes
        // nothing until every one of them is accepted.
        referenceDataService.createContraintesAdHoc(exceptions);

        for (PosteAffectation poste : aLiberer) {
            planningService.applyReparation(poste.getId(), null);
        }

        Map<Long, Creneau> byId = creneauxById(restants);
        return new AbsenceMarquee(animateurId, nomAffiche(identites().get(animateurId), animateurId),
                exceptions.stream().map(exception -> entree(exception, byId, true)).toList(),
                aLiberer.stream().map(JourJService::vacantSeat).toList());
    }

    /**
     * Undoes a marked absence: one timeslot when {@code creneauId} names one,
     * the whole day otherwise.
     *
     * <p>Deliberately keyed on the <b>day</b> and not on what is still ahead: an
     * absence typed by mistake is undone a minute later, by which time the
     * timeslot it covers may already have started. Only exceptions targeting
     * this animateur <em>alone</em> are removed — one naming several people
     * belongs to whoever wrote it, and the Ajustements manuels screen owns
     * it.</p>
     *
     * <p>The seats are not handed back. Who holds a seat is a decision, and
     * assuming the previous occupant should get it back would silently undo
     * whatever replacement was applied in between.</p>
     *
     * @return how many exceptions were removed
     */
    public int cancelAbsence(String animateurId, LocalDate date, Long creneauId) {
        LocalDate jour = date != null ? date : LocalDate.now();
        findAnimateur(animateurId, false);
        Map<Long, Creneau> creneaux = creneauxById(creneauxOf(referenceDataService.listCreneaux(), jour));
        List<ContrainteAdHoc> aSupprimer = referenceDataService.listContraintesAdHoc().stream()
                .filter(contrainte -> contrainte.getType() == TypeContrainteAdHoc.INDISPONIBILITE_FORCEE)
                .filter(contrainte -> targetsOnly(contrainte, animateurId))
                .filter(contrainte -> contrainte.getCreneau() != null
                        && creneaux.containsKey(contrainte.getCreneau().getId()))
                .filter(contrainte -> creneauId == null || creneauId.equals(contrainte.getCreneau().getId()))
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
     * Reading a day other than today reads all of it: "now" only means
     * something on the day it belongs to.
     */
    private static LocalTime referenceTime(LocalDate jour, LocalTime heure) {
        if (heure != null) {
            return heure;
        }
        return jour.equals(LocalDate.now()) ? LocalTime.now().withNano(0) : LocalTime.MIN;
    }

    /**
     * Whether the timeslot is still ahead of {@code reference}. A slot whose end
     * hour is not after its start hour crosses midnight and ends the next day —
     * the same normalisation {@code Creneau.chevaucheNuit} applies.
     */
    private static boolean isStillAhead(Creneau creneau, LocalTime reference) {
        LocalDate jour = creneau.getDate();
        LocalDateTime debut = jour.atTime(creneau.getHeureDebut());
        LocalDateTime fin = jour.atTime(creneau.getHeureFin());
        if (!fin.isAfter(debut)) {
            fin = fin.plusDays(1);
        }
        return fin.isAfter(jour.atTime(reference));
    }

    /** Started but not over: the one nobody is standing at right now. */
    private static boolean isUnderWay(Creneau creneau, LocalTime reference) {
        return !creneau.getHeureDebut().isAfter(reference) && isStillAhead(creneau, reference);
    }

    private static List<Creneau> creneauxOf(List<Creneau> creneaux, LocalDate jour) {
        return creneaux.stream()
                .filter(creneau -> creneau.getId() != null && jour.equals(creneau.getDate())
                        && creneau.getHeureDebut() != null && creneau.getHeureFin() != null)
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
                .filter(animateur -> animateur.getId() != null && animateur.getId().equals(animateurId))
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
                .map(poste -> poste.getStand().getId() + " (" + poste.getCreneau().getHeureDebut() + ")")
                .toList();
        if (!bloques.isEmpty()) {
            throw new BusinessError.Invalid("Ces postes sont verrouillés et ne peuvent pas être libérés : "
                    + String.join(", ", bloques) + ". Déverrouillez-les avant de marquer l'absence.");
        }
    }

    private static String motif(String raison, LocalDate jour, LocalTime reference) {
        String base = "Absent le " + jour + " à partir de " + reference + " (mode jour J)";
        return raison == null || raison.isBlank() ? base : base + " — " + raison.trim();
    }

    private static ContrainteAdHoc exception(Animateur animateur, Creneau creneau, String motif,
            String author, Instant maintenant) {
        ContrainteAdHoc contrainte = new ContrainteAdHoc(
                PREFIXE_ABSENCE + "-" + animateur.getId() + "-" + creneau.getId(),
                TypeContrainteAdHoc.INDISPONIBILITE_FORCEE);
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

    private static boolean targetsOnly(ContrainteAdHoc contrainte, String animateurId) {
        List<String> ids = animateurIds(contrainte);
        return ids.size() == 1 && ids.getFirst().equals(animateurId);
    }

    private static List<String> animateurIds(ContrainteAdHoc contrainte) {
        List<Animateur> cibles = contrainte.getAnimateursConcernes();
        if (cibles == null) {
            return List.of();
        }
        return cibles.stream().filter(Objects::nonNull).map(Animateur::getId)
                .filter(Objects::nonNull).distinct().toList();
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
                parAnimateur.computeIfAbsent(cible, id -> new ArrayList<>())
                        .add(entree(contrainte, byId, cibles.size() == 1));
            }
        }
        return parAnimateur.entrySet().stream()
                .map(absence -> new AbsenceJourJ(absence.getKey(),
                        nomAffiche(identites.get(absence.getKey()), absence.getKey()),
                        absence.getValue().stream()
                                .sorted(Comparator.comparing(EntreeAbsence::heureDebut,
                                        Comparator.nullsLast(Comparator.naturalOrder())))
                                .toList()))
                .sorted(Comparator.comparing(AbsenceJourJ::nomAffiche))
                .toList();
    }

    private static EntreeAbsence entree(ContrainteAdHoc contrainte, Map<Long, Creneau> creneaux,
            boolean annulable) {
        Creneau creneau = creneaux.get(contrainte.getCreneau().getId());
        return new EntreeAbsence(contrainte.getId(), contrainte.getCreneau().getId(),
                creneau == null ? null : creneau.getHeureDebut(),
                creneau == null ? null : creneau.getHeureFin(),
                contrainte.getRaison(), contrainte.getCreeParUtilisateurId(), contrainte.getCreeLe(),
                annulable);
    }

    private static CreneauJourJ creneauJourJ(Creneau creneau, LocalTime reference) {
        return new CreneauJourJ(creneau.getId(), creneau.getDate(), creneau.getHeureDebut(),
                creneau.getHeureFin(), isUnderWay(creneau, reference));
    }

    private static List<AnimateurAffecte> animateursAffectes(List<PosteAffectation> postesRestants,
            Map<String, Identite> identites, Set<String> absents) {
        Map<String, Integer> comptes = new LinkedHashMap<>();
        for (PosteAffectation poste : postesRestants) {
            if (poste.getAnimateur() != null) {
                comptes.merge(poste.getAnimateur().getId(), 1, Integer::sum);
            }
        }
        Set<String> vus = new HashSet<>(comptes.keySet());
        return vus.stream()
                .map(id -> new AnimateurAffecte(id, nomAffiche(identites.get(id), id),
                        comptes.getOrDefault(id, 0), absents.contains(id)))
                .sorted(Comparator.comparing(AnimateurAffecte::nomAffiche))
                .toList();
    }

    private List<PosteAPourvoir> postesAPourvoir(List<PosteAffectation> postesRestants) {
        List<VerrouillagePlanning> verrouillages = referenceDataService.listVerrouillages();
        return postesRestants.stream()
                .filter(poste -> poste.getAnimateur() == null)
                .sorted(Comparator.comparing((PosteAffectation poste) -> poste.getCreneau().getHeureDebut())
                        .thenComparing(PosteAffectation::getId))
                .map(poste -> new PosteAPourvoir(poste.getId(), poste.getStand().getId(),
                        poste.getStand().getNom(), poste.getCreneau().getId(),
                        poste.heureDebutEffectif(), poste.heureFinEffectif(),
                        verrouillages.stream().anyMatch(verrouillage -> verrouillage.couvre(poste))))
                .toList();
    }

    /** A seat this very call has just emptied: it was reachable, so it is not locked. */
    private static PosteAPourvoir vacantSeat(PosteAffectation poste) {
        return new PosteAPourvoir(poste.getId(), poste.getStand().getId(), poste.getStand().getNom(),
                poste.getCreneau().getId(), poste.heureDebutEffectif(), poste.heureFinEffectif(), false);
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
                + (identite.nom() == null ? "" : identite.nom())).trim();
        return complet.isBlank() ? fallback : complet;
    }

    private record Identite(String prenom, String nom) {
    }

    /* -------------------------------- Payloads ----------------------------- */

    /**
     * The whole screen in one answer.
     *
     * @param heureReference  the moment "remaining" is counted from, echoed back
     *                        so the screen states it rather than assuming the
     *                        phone's clock matches the server's
     * @param creneauxDuJour  how many timeslots the day holds in all — the
     *                        denominator that says how much of it is already
     *                        behind
     */
    public record EtatJourJ(LocalDate date, LocalTime heureReference, int creneauxDuJour,
            List<CreneauJourJ> creneauxRestants, List<AnimateurAffecte> animateursDeService,
            List<PosteAPourvoir> postesAPourvoir, List<AbsenceJourJ> absences) {
    }

    /** One timeslot still ahead. */
    public record CreneauJourJ(long id, LocalDate date, LocalTime heureDebut, LocalTime heureFin,
            boolean enCours) {
    }

    /**
     * Somebody holding at least one seat over the remaining timeslots — the
     * list the operator picks the missing person from.
     */
    public record AnimateurAffecte(String animateurId, String nomAffiche, int postesRestants,
            boolean absent) {
    }

    /**
     * An unstaffed seat on a remaining timeslot.
     *
     * @param verrouille a lock covers it: the repair assistant will refuse to
     *                   write here until it is lifted, and the screen has to say
     *                   so instead of offering a button that cannot work
     */
    public record PosteAPourvoir(String posteId, String standId, String standNom, long creneauId,
            LocalTime heureDebut, LocalTime heureFin, boolean verrouille) {
    }

    /** Somebody missing today, and over which timeslots. */
    public record AbsenceJourJ(String animateurId, String nomAffiche, List<EntreeAbsence> entrees) {
    }

    /**
     * One timeslot of an absence, with the trace the exception carries.
     *
     * @param annulable false when the exception names several animateurs: it was
     *                  not written by this screen and undoing it here would free
     *                  people nobody asked about
     */
    public record EntreeAbsence(String contrainteId, long creneauId, LocalTime heureDebut,
            LocalTime heureFin, String raison, String creeParUtilisateurId, Instant creeLe,
            boolean annulable) {
    }

    /** What one "marquer absent" wrote, so the screen can go straight to the holes it opened. */
    public record AbsenceMarquee(String animateurId, String nomAffiche, List<EntreeAbsence> entrees,
            List<PosteAPourvoir> postesLiberes) {
    }
}
