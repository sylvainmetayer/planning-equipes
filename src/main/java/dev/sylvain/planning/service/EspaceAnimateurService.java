package dev.sylvain.planning.service;

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
import dev.sylvain.planning.domain.DemandeEchange;
import dev.sylvain.planning.domain.GroupeCreneau;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
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
 */
@ApplicationScoped
public class EspaceAnimateurService {

    @Inject
    ReferenceDataService referenceDataService;

    @Inject
    PlanningPersistenceService persistenceService;

    @Inject
    PlanningExportService exportService;

    @Inject
    DemandeEchangeService demandeEchangeService;

    /** One of the animateur's seats in the persisted planning. */
    public record PosteAnimateurView(Long creneauId, LocalDate date, LocalTime heureDebut, LocalTime heureFin,
            String standId, String standNom, List<String> coequipiers) {
    }

    /** A colleague an échange can target. First name + name: what a PDF already prints. */
    public record CollegueView(String id, String nomComplet) {
    }

    /**
     * The espace's home payload: who I am, my planning, who I can swap with —
     * and whether the foire is open ({@code foireOuverte} false turns the
     * espace read-only: the closure itself is enforced server-side, this flag
     * only lets the interface say so instead of failing on submit).
     */
    /**
     * @param joursRepos festival days the animateur holds no seat on — shown
     *                    as explicit « Repos » days rather than silently
     *                    missing cards; empty when they hold no seat at all
     */
    public record EspaceAnimateurView(String animateurId, String prenom, String nom, Instant planningResoluLe,
            String groupeCreneauNom, boolean foireOuverte, List<PosteAnimateurView> postes,
            List<LocalDate> joursRepos, List<CollegueView> collegues) {
    }

    /**
     * One demande with every label resolved, shared by the espace and the
     * admin screen. {@code horsGroupe} marks a demande submitted on another
     * groupe de créneaux than the one the persisted planning belongs to (an
     * edition can hold several groups, but the link — the animateur's token —
     * is unique): impossible to measure or accept against the current
     * planning, {@code groupeCreneauNom} names where it comes from.
     */
    public record DemandeEchangeView(String id, Long creneauId, LocalDate date, LocalTime heureDebut,
            LocalTime heureFin, String standId, String standNom, String demandeurId, String demandeurNom,
            String cibleId, String cibleNom, String motif, String statut, Boolean prevalidationOk,
            List<String> contraintesViolees, String commentaireAdmin, boolean horsGroupe,
            String groupeCreneauNom, Instant creeLe, Instant decideLe) {
    }

    public EspaceAnimateurView construireVue(String animateurId) {
        List<Animateur> animateurs = referenceDataService.listAnimateurs();
        Animateur animateur = animateurs.stream()
                .filter(candidat -> candidat.getId().equals(animateurId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Animateur inconnu : " + animateurId));

        PlanningFestival planning = persistenceService.loadPersistedPlanning();
        Map<String, List<String>> coequipiers = exportService.coequipiersParPoste(planning, animateurId);
        List<PosteAnimateurView> postes = planning.getPostes().stream()
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

        List<CollegueView> collegues = animateurs.stream()
                .filter(candidat -> !candidat.getId().equals(animateurId))
                .map(candidat -> new CollegueView(candidat.getId(), nomComplet(candidat)))
                .sorted(Comparator.comparing(CollegueView::nomComplet, String.CASE_INSENSITIVE_ORDER))
                .toList();

        List<LocalDate> joursRepos = exportService.joursDeRepos(planning, animateurId).stream()
                .map(PlanningExportService.JourRepos::date)
                .toList();

        PlanningPersistenceService.PlanningResolution resolution = persistenceService.loadResolution();
        return new EspaceAnimateurView(animateur.getId(), animateur.getPrenom(), animateur.getNom(),
                resolution == null ? null : resolution.resoluLe(),
                resolution == null ? null : resolution.groupeCreneauNom(),
                demandeEchangeService.estFoireOuverte(), postes, joursRepos, collegues);
    }

    /** Resolves labels for a batch of demandes, in their given order. */
    public List<DemandeEchangeView> versVues(List<DemandeEchange> demandes) {
        Map<String, Animateur> animateurs = referenceDataService.listAnimateurs().stream()
                .collect(Collectors.toMap(Animateur::getId, Function.identity()));
        Map<Long, Creneau> creneaux = referenceDataService.listCreneaux().stream()
                .filter(creneau -> creneau.getId() != null)
                .collect(Collectors.toMap(Creneau::getId, Function.identity()));
        Map<String, Stand> stands = referenceDataService.listStands().stream()
                .collect(Collectors.toMap(Stand::getId, Function.identity()));
        Map<String, String> nomsGroupes = referenceDataService.listGroupesCreneaux().stream()
                .collect(Collectors.toMap(GroupeCreneau::getId, groupe ->
                        groupe.getNom() == null ? groupe.getId() : groupe.getNom()));
        PlanningPersistenceService.PlanningResolution resolution = persistenceService.loadResolution();
        String groupeCourant = resolution == null ? null : resolution.groupeCreneauId();
        return demandes.stream()
                .map(demande -> versVue(demande, animateurs, creneaux, stands, nomsGroupes, groupeCourant))
                .toList();
    }

    private static DemandeEchangeView versVue(DemandeEchange demande, Map<String, Animateur> animateurs,
            Map<Long, Creneau> creneaux, Map<String, Stand> stands, Map<String, String> nomsGroupes,
            String groupeCourant) {
        // A demande without a recorded groupe (or before any resolution) is
        // never flagged: there is nothing to contradict.
        boolean horsGroupe = demande.getGroupeCreneauId() != null && groupeCourant != null
                && !demande.getGroupeCreneauId().equals(groupeCourant);
        Creneau creneau = creneaux.get(demande.getCreneauId());
        Stand stand = stands.get(demande.getStandId());
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
                demande.getMotif(),
                demande.getStatut().name(),
                demande.getPrevalidationOk(),
                demande.getContraintesViolees(),
                demande.getCommentaireAdmin(),
                horsGroupe,
                horsGroupe
                        ? nomsGroupes.getOrDefault(demande.getGroupeCreneauId(), demande.getGroupeCreneauId())
                        : null,
                demande.getCreeLe(),
                demande.getDecideLe());
    }

    private static String nomComplet(Animateur animateur) {
        return animateur.getPrenom() + " " + animateur.getNom();
    }

    private static String nomComplet(Animateur animateur, String fallbackId) {
        return animateur == null ? fallbackId : nomComplet(animateur);
    }
}
