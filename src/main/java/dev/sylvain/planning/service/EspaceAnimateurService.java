package dev.sylvain.planning.service;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.DeclarationDisponibilite;
import dev.sylvain.planning.domain.DemandeEchange;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.StatutConfirmation;
import dev.sylvain.planning.domain.StatutDeclaration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Read views of the espace animateur (issue #165): the animateur's own slice
 * of the persisted planning, the colleagues they can propose an échange to,
 * and their demandes with every label resolved — the espace speaks business
 * words (dates, stand names, colleague names), never referential ids alone.
 *
 * <p>Everything here is scoped to the edition resolved from the access token
 * by the caller (see {@code EspaceAnimateurResource}), and read-only: the
 * espace can never trigger a solve or touch the planning.</p>
 *
 * <p>Since issue #245 it reads the <b>published</b> plan, not the working one:
 * what an animateur sees is what somebody sent them. A swap validated this
 * morning, a repair applied from « Pourquoi lui ? », a fresh solve — none of
 * them move anybody's espace before the admin publishes. Before the first
 * publication the plan is empty on purpose, and {@code publieLe} being
 * {@code null} is how the interface says « votre planning n'a pas encore été
 * communiqué » instead of showing a planning nobody promised.</p>
 */
@ApplicationScoped
public class EspaceAnimateurService {

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    PlanPublieService planPublieService;

    @Inject
    PlanningExportService exportService;

    @Inject
    DemandeEchangeService demandeEchangeService;

    @Inject
    PlanningService planningService;

    @Inject
    DeclarationDisponibiliteService declarationService;

    @Inject
    TypologieService typologieService;

    @Inject
    ConfirmationPlanningService confirmationService;

    @Inject
    PauseAnalyzer pauseAnalyzer;

    /** One of the animateur's seats in the persisted planning. */
    public record PosteAnimateurView(Long creneauId, LocalDate date, LocalTime heureDebut, LocalTime heureFin,
            String standId, String standNom, List<String> coequipiers) {
    }

    /** A colleague an échange can target. First name + name: what a PDF already prints. */
    public record ColleagueView(String id, String nomComplet) {
    }

    /**
     * The espace's home payload: who I am, my planning, who I can swap with —
     * and whether the foire is open ({@code foireOuverte} false turns the
     * espace read-only: the closure itself is enforced server-side, this flag
     * only lets the interface say so instead of failing on submit).
     *
     * <p>One block, not four: only the <b>last</b> javadoc comment before a
     * declaration is attached to it, so the successive blocks this grew into
     * were silently dropping every {@code @param} but the newest.</p>
     *
     * @param publieLe when the plan on display was communicated, {@code null}
     *                 while nothing has ever been published on this edition —
     *                 the postes are then empty, and the espace says so
     * @param joursRepos event days the animateur holds no seat on — shown as
     *                 explicit « Repos » days rather than silently missing
     *                 cards; empty when they hold no seat at all
     * @param statutConfirmation NON_VU / CONFIRME / RELANCE (issue #293) — the
     *                 espace only ever moves it to CONFIRME, and a
     *                 republication that changes this planning sends it back
     *                 to NON_VU
     * @param confirmeLe when « j'ai lu et je serai là » was clicked,
     *                 {@code null} while it has not been
     * @param foireOuvreLe the day the foire opens, when it is shut only
     *                 because it has not started yet — {@code null} when it is
     *                 open, or shut for good. {@code foireOuverte} alone is one
     *                 boolean for two situations that say the opposite to the
     *                 person reading, and « c'est terminé » two weeks before it
     *                 starts is the version nobody comes back from
     * @param foireFermeLe the last day demandes are accepted, {@code null} when
     *                 the window has no end
     * @param abonnementToken credential of the permanent calendar feed
     *                 ({@code /api/abonnements/&lt;token&gt;/planning.ics}),
     *                 sent so the espace can build the subscription URL. It is
     *                 <b>not</b> the espace token: it opens that one document
     *                 and nothing else, and the espace rotates it on its own
     * @param pauses   the legal breaks their days owe — « 20 min at the latest
     *                 at 19:00, on stand X » — read from the same published
     *                 plan as {@code postes}, so the note never contradicts
     *                 the shifts it sits under (see {@link PauseAnalyzer})
     */
    @Schema(requiredProperties = {"foireOuverte"})
    public record EspaceAnimateurView(String animateurId, String prenom, String nom, Instant publieLe,
            boolean foireOuverte, List<PosteAnimateurView> postes,
            List<LocalDate> joursRepos, List<ColleagueView> collegues,
            String statutConfirmation, Instant confirmeLe,
            LocalDate foireOuvreLe, LocalDate foireFermeLe,
            String abonnementToken, List<PauseAnalyzer.PauseAnimateurView> pauses) {
    }

    /**
     * One demande with every label resolved, shared by the espace and the
     * admin screen.
     */
    public record DemandeEchangeView(String id, Long creneauId, LocalDate date, LocalTime heureDebut,
            LocalTime heureFin, String standId, String standNom, String demandeurId, String demandeurNom,
            String cibleId, String cibleNom,
            // Directed exchange only — the colleague's seat wanted in return; null otherwise.
            Long creneauCibleId, LocalDate dateCible, LocalTime heureDebutCible, LocalTime heureFinCible,
            String standCibleId, String standCibleNom,
            String motif, String statut, Boolean prevalidationOk,
            List<String> contraintesViolees, String commentaireAdmin, Instant creeLe,
            Instant cibleDecideLe, Instant decideLe) {
    }

    public EspaceAnimateurView buildView(String animateurId) {
        List<Animateur> animateurs = referenceDataService.listAnimateurs();
        Animateur animateur = animateurs.stream()
                .filter(candidat -> candidat.getId().equals(animateurId))
                .findFirst()
                .orElseThrow(() -> new BusinessError.Invalid("Animateur inconnu : " + animateurId));

        PlanningEvenement planning = planPublieService.planPublie();
        Map<String, List<String>> coequipiers = exportService.teammatesByPoste(planning, animateurId);
        List<PosteAnimateurView> postes = postesOf(planning, animateurId, coequipiers);

        List<ColleagueView> collegues = animateurs.stream()
                .filter(candidat -> !candidat.getId().equals(animateurId))
                .map(candidat -> new ColleagueView(candidat.getId(), candidat.nomAffiche()))
                .sorted(Comparator.comparing(ColleagueView::nomComplet, String.CASE_INSENSITIVE_ORDER))
                .toList();

        List<LocalDate> joursRepos = exportService.daysOff(planning, animateurId).stream()
                .map(PlanningExportService.JourRepos::date)
                .toList();

        PlanSnapshotService.SnapshotMeta publication = planPublieService.lastPublication();
        ConfirmationPlanningRepository.Confirmation confirmation =
                confirmationService.stored(animateurId).orElse(null);
        DemandeEchangeService.FenetreFoire foire = demandeEchangeService.fenetre();
        return new EspaceAnimateurView(animateur.getId(), animateur.getPrenom(), animateur.getNom(),
                publication == null ? null : publication.publieLe(),
                foire.openOn(LocalDate.now()), postes, joursRepos, collegues,
                (confirmation == null ? StatutConfirmation.NON_VU : confirmation.statut()).name(),
                confirmation == null ? null : confirmation.confirmeLe(),
                foire.ouvertureAVenir(LocalDate.now()), foire.fin(),
                referenceDataService.abonnementToken(animateurId),
                pauseAnalyzer.pausesAnimateur(planning, animateurId));
    }

    private static List<PosteAnimateurView> postesOf(PlanningEvenement planning, String animateurId,
            Map<String, List<String>> coequipiers) {
        return planning.getPostes().stream()
                .filter(poste -> poste.getAnimateur() != null && animateurId.equals(poste.getAnimateur().getId())
                        && poste.getCreneau() != null && poste.getStand() != null)
                .sorted(Comparator
                        .comparing((PosteAffectation poste) -> poste.getCreneau().getDate(),
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(poste -> poste.heureDebutEffectif(),
                                Comparator.nullsLast(Comparator.naturalOrder())))
                .map(poste -> new PosteAnimateurView(
                        poste.getCreneau().getId(),
                        poste.getCreneau().getDate(),
                        poste.heureDebutEffectif(),
                        poste.heureFinEffectif(),
                        poste.getStand().getId(),
                        poste.getStand().getNom(),
                        coequipiers.getOrDefault(poste.getId(), List.of())))
                .toList();
    }

    /**
     * A colleague's seats, for the « créneau souhaité en échange » picker of a
     * directed exchange: nothing but slots and stands — the same information
     * the printed global planning already shows — without teammates.
     *
     * <p>Two guards narrow an access that is broad <b>by design</b>: the roster
     * is already handed out in full by {@link #buildView}, and a colleague's
     * slots and stands are what the printed global planning circulates anyway.
     * What they take away is the ability to harvest the lot — one session would
     * otherwise reconstruct the whole event's nominative planning, minors
     * included, in as many requests as there are animateurs.</p>
     *
     * <p>An id nobody bears answers 404 — like every other unknown entity of
     * this codebase, and like an unknown token. It used to answer {@code 200 []},
     * which let anyone probe which ids exist.</p>
     *
     * <p>The other half of the narrowing — the foire must be open — is declared
     * on the route itself, {@code @FoireOpenRequired}.</p>
     */
    public List<PosteAnimateurView> colleaguePostes(String collegueId) {
        boolean connu = referenceDataService.listAnimateurs().stream()
                .anyMatch(candidat -> candidat.getId().equals(collegueId));
        if (!connu) {
            throw new BusinessError.NotFound("Animateur not found: " + collegueId);
        }
        return postesOf(planPublieService.planPublie(), collegueId, Map.of());
    }

    /**
     * One viable way out of a créneau, in the espace's words: who takes it,
     * what it does for the demandeur, and — when a seat comes back — which one.
     *
     * <p>Carries <b>no score</b> on purpose, where the admin's repair
     * suggestions do: a {@code HardMediumSoftScore} means nothing to an
     * animateur, and publishing the plan's global health in the espace tells
     * every holder of a token how healthy (or not) the whole event's planning
     * is. What they need is the facts below.</p>
     *
     * @param nature LIBERE / CROISE / DIRIGE — see {@link PlanningWhatIf.NatureEchange}
     */
    public record SuggestionEchangeView(String animateurId, String nomComplet, String nature,
            Long creneauCibleId, LocalDate dateCible, LocalTime heureDebutCible, LocalTime heureFinCible,
            String standCibleId, String standCibleNom) {
    }

    /**
     * Answer of « qui peut me remplacer ? ».
     *
     * @param listeTronquee the search stopped at its plafond: the list is the
     *                      best of what was tried, and the interface must say so
     *                      rather than let « personne d'autre » be read into it
     */
    @Schema(requiredProperties = {"listeTronquee", "optionsEligibles", "optionsEvaluees"})
    public record SuggestionsEchangeView(Long creneauId, String standId, int optionsEligibles,
            int optionsEvaluees, boolean listeTronquee, List<SuggestionEchangeView> suggestions) {
    }

    /**
     * Every viable way this animateur could get rid of one of their créneaux:
     * the search behind the espace's « qui peut me remplacer ? » button, for
     * the animateur who does not want that créneau and has nobody in mind.
     *
     * <p>Three families, deliberately — being freed, trading on the same
     * créneau, or trading against a colleague's seat on another day (see
     * {@link PlanningWhatIf.NatureEchange}). An échange is not only « someone
     * takes my place », and an assistant that only proposed that would hide
     * half of what the foire allows.</p>
     *
     * <p>Only the demandeur's own seats are searchable — the créneau/stand pair
     * must be one of theirs, which {@link PlanningService#suggererEchanges}
     * enforces by looking the seat up under their id. Nothing is created here:
     * the animateur still picks one, submits a demande, and the colleague still
     * has to agree.</p>
     */
    public SuggestionsEchangeView suggestionsEchange(String animateurId, Long creneauId, String standId,
            Integer plafond) {
        if (creneauId == null || standId == null || standId.isBlank()) {
            throw new BusinessError.Invalid("Créneau ou stand manquant");
        }
        // On the published plan, like the rest of the espace: you only trade
        // what you were told about. Applying an accepted échange, on the other
        // hand, happens against the working plan — the admin arbitrates between
        // the two at validation time.
        PlanningWhatIf.SuggestionsEchange suggestions = planningService.suggererEchanges(
                planPublieService.planPublie(), animateurId, creneauId, standId, plafond);
        Map<String, Animateur> animateurs = referenceDataService.listAnimateurs().stream()
                .collect(Collectors.toMap(Animateur::getId, Function.identity()));
        Map<String, Stand> stands = referenceDataService.listStands().stream()
                .collect(Collectors.toMap(Stand::getId, Function.identity()));
        Map<Long, Creneau> creneaux = referenceDataService.listCreneaux().stream()
                .filter(creneau -> creneau.getId() != null)
                .collect(Collectors.toMap(Creneau::getId, Function.identity()));
        List<SuggestionEchangeView> vues = suggestions.suggestions().stream()
                .map(suggestion -> toView(suggestion, animateurs, stands, creneaux))
                .toList();
        return new SuggestionsEchangeView(creneauId, standId,
                suggestions.optionsEligibles(), suggestions.optionsEvaluees(),
                suggestions.optionsEvaluees() < suggestions.optionsEligibles(), vues);
    }

    private static SuggestionEchangeView toView(PlanningWhatIf.SuggestionEchange suggestion,
            Map<String, Animateur> animateurs, Map<String, Stand> stands, Map<Long, Creneau> creneaux) {
        Stand standCible = suggestion.standCibleId() == null ? null : stands.get(suggestion.standCibleId());
        Creneau creneauCible = suggestion.creneauCibleId() == null ? null
                : creneaux.get(suggestion.creneauCibleId());
        return new SuggestionEchangeView(
                suggestion.animateurId(),
                nomComplet(animateurs.get(suggestion.animateurId()), suggestion.animateurId()),
                suggestion.nature().name(),
                suggestion.creneauCibleId(),
                creneauCible == null ? null : creneauCible.getDate(),
                creneauCible == null ? null : creneauCible.getHeureDebut(),
                creneauCible == null ? null : creneauCible.getHeureFin(),
                suggestion.standCibleId(),
                suggestion.standCibleId() == null ? null : nomStand(standCible, suggestion.standCibleId()));
    }

    private static String nomStand(Stand stand, String fallbackId) {
        return stand == null ? fallbackId : stand.getNom();
    }

    /** Resolves labels for a batch of demandes, in their given order. */
    public List<DemandeEchangeView> toViews(List<DemandeEchange> demandes) {
        Map<String, Animateur> animateurs = referenceDataService.listAnimateurs().stream()
                .collect(Collectors.toMap(Animateur::getId, Function.identity()));
        Map<Long, Creneau> creneaux = referenceDataService.listCreneaux().stream()
                .filter(creneau -> creneau.getId() != null)
                .collect(Collectors.toMap(Creneau::getId, Function.identity()));
        Map<String, Stand> stands = referenceDataService.listStands().stream()
                .collect(Collectors.toMap(Stand::getId, Function.identity()));
        return demandes.stream()
                .map(demande -> toView(demande, animateurs, creneaux, stands))
                .toList();
    }

    private static DemandeEchangeView toView(DemandeEchange demande, Map<String, Animateur> animateurs,
            Map<Long, Creneau> creneaux, Map<String, Stand> stands) {
        Creneau creneau = creneaux.get(demande.getCreneauId());
        Stand stand = stands.get(demande.getStandId());
        Creneau creneauCible = demande.getCreneauCibleId() == null ? null
                : creneaux.get(demande.getCreneauCibleId());
        Stand standCible = demande.getStandCibleId() == null ? null : stands.get(demande.getStandCibleId());
        return new DemandeEchangeView(
                demande.getId(),
                demande.getCreneauId(),
                creneau == null ? null : creneau.getDate(),
                creneau == null ? null : creneau.getHeureDebut(),
                creneau == null ? null : creneau.getHeureFin(),
                demande.getStandId(),
                stand == null ? demande.getStandId() : stand.getNom(),
                demande.getDemandeurId(),
                nomComplet(animateurs.get(demande.getDemandeurId()), demande.getDemandeurId()),
                demande.getCibleId(),
                nomComplet(animateurs.get(demande.getCibleId()), demande.getCibleId()),
                demande.getCreneauCibleId(),
                creneauCible == null ? null : creneauCible.getDate(),
                creneauCible == null ? null : creneauCible.getHeureDebut(),
                creneauCible == null ? null : creneauCible.getHeureFin(),
                demande.getStandCibleId(),
                standCible == null ? demande.getStandCibleId() : standCible.getNom(),
                demande.getMotif(),
                demande.getStatut().name(),
                demande.getPrevalidationOk(),
                demande.getContraintesViolees(),
                demande.getCommentaireAdmin(),
                demande.getCreeLe(),
                demande.getCibleDecideLe(),
                demande.getDecideLe());
    }

    /** {@code null} happens: an échange can name an animateur the referential no longer holds. */
    private static String nomComplet(Animateur animateur, String fallbackId) {
        return animateur == null ? fallbackId : animateur.nomAffiche();
    }

    /* ---------------- Declaration of availability (issue #291) ---------------- */

    /** One game category, as the espace offers it: an id and the word for it. */
    public record TypologieChoixView(String id, String label) {
    }

    /** One of my declarations, with the game categories named rather than referenced. */
    public record DeclarationView(String id, String statut, List<LocalDate> joursIndisponibles,
            List<String> souhaits, List<String> souhaitsLabels, String commentaire,
            String commentaireAdmin, Instant creeLe, Instant decideLe) {
    }

    /**
     * Everything the declaration tab needs in one read: whether the window is
     * open, which days the event covers, what game categories exist, what the
     * organisation currently holds for me, and where my own declarations stand.
     *
     * @param collecteOuverte the closure is enforced server-side on submit;
     *                        this flag only lets the interface say so instead
     *                        of failing on the button
     * @param joursActuels    what the referential says today — the form opens
     *                        on it, so a declaration corrects rather than
     *                        starts from a blank page
     * @param enAttente       my single pending proposal, {@code null} when I
     *                        have none: submitting again replaces it
     */
    @Schema(requiredProperties = {"collecteOuverte"})
    public record DeclarationEspaceView(boolean collecteOuverte, LocalDate collecteDebut, LocalDate collecteFin,
            List<LocalDate> joursEvenement, List<TypologieChoixView> typologies,
            List<LocalDate> joursActuels, List<String> souhaitsActuels,
            DeclarationView enAttente, List<DeclarationView> historique) {
    }

    public DeclarationEspaceView buildDeclarationView(String animateurId) {
        Animateur animateur = referenceDataService.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(animateurId))
                .findFirst()
                .orElseThrow(() -> new BusinessError.Invalid("Animateur inconnu : " + animateurId));
        Map<String, String> labels = typologieService.labelsById();
        List<TypologieChoixView> typologies = labels.entrySet().stream()
                .map(entree -> new TypologieChoixView(entree.getKey(), entree.getValue()))
                .sorted(Comparator.comparing(TypologieChoixView::label, String.CASE_INSENSITIVE_ORDER))
                .toList();
        List<DeclarationView> mesDeclarations = declarationService.listForAnimateur(animateurId).stream()
                .map(declaration -> toView(declaration, labels))
                .toList();
        DeclarationDisponibiliteRepository.FenetreCollecte fenetre = declarationService.fenetre();
        return new DeclarationEspaceView(
                declarationService.isCollecteOuverte(), fenetre.debut(), fenetre.fin(),
                declarationService.joursEvenement(),
                typologies,
                animateur.getJoursIndisponibles().stream().sorted().toList(),
                animateur.getSouhaits().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList(),
                mesDeclarations.stream()
                        .filter(vue -> StatutDeclaration.EN_ATTENTE.name().equals(vue.statut()))
                        .findFirst()
                        .orElse(null),
                mesDeclarations.stream()
                        .filter(vue -> !StatutDeclaration.EN_ATTENTE.name().equals(vue.statut()))
                        .toList());
    }

    /**
     * One declaration for the admin screen: the animateur named, and the game
     * categories spelled out — the admin decides on words, not on ids.
     *
     * @param joursActuels what the fiche says today, so the screen can show
     *                     what applying would change rather than only what was
     *                     asked for
     */
    public record DeclarationAdminView(String id, String animateurId, String animateurNom, String statut,
            List<LocalDate> joursIndisponibles, List<String> souhaits, List<String> souhaitsLabels,
            String commentaire, String commentaireAdmin, Instant creeLe, Instant decideLe,
            List<LocalDate> joursActuels, List<String> souhaitsActuelsLabels) {
    }

    /** Resolves labels for a batch of declarations, in their given order. */
    public List<DeclarationAdminView> toDeclarationViews(List<DeclarationDisponibilite> declarations) {
        Map<String, String> labels = typologieService.labelsById();
        Map<String, Animateur> animateurs = referenceDataService.listAnimateurs().stream()
                .collect(Collectors.toMap(Animateur::getId, Function.identity()));
        return declarations.stream()
                .map(declaration -> {
                    Animateur animateur = animateurs.get(declaration.getAnimateurId());
                    return new DeclarationAdminView(
                            declaration.getId(),
                            declaration.getAnimateurId(),
                            nomComplet(animateur, declaration.getAnimateurId()),
                            declaration.getStatut().name(),
                            declaration.getJoursIndisponibles(),
                            declaration.getSouhaits(),
                            labelsOf(declaration.getSouhaits(), labels),
                            declaration.getCommentaire(),
                            declaration.getCommentaireAdmin(),
                            declaration.getCreeLe(),
                            declaration.getDecideLe(),
                            animateur == null ? List.of()
                                    : animateur.getJoursIndisponibles().stream().sorted().toList(),
                            animateur == null ? List.of()
                                    : labelsOf(animateur.getSouhaits().stream().sorted().toList(), labels));
                })
                .toList();
    }

    private static DeclarationView toView(DeclarationDisponibilite declaration, Map<String, String> labels) {
        return new DeclarationView(
                declaration.getId(),
                declaration.getStatut().name(),
                declaration.getJoursIndisponibles(),
                declaration.getSouhaits(),
                labelsOf(declaration.getSouhaits(), labels),
                declaration.getCommentaire(),
                declaration.getCommentaireAdmin(),
                declaration.getCreeLe(),
                declaration.getDecideLe());
    }

    /** A category dropped since the declaration was written falls back to its id. */
    private static List<String> labelsOf(List<String> ids, Map<String, String> labels) {
        return ids.stream().map(id -> labels.getOrDefault(id, id)).toList();
    }
}
