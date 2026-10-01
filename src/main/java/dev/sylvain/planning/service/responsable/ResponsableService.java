package dev.sylvain.planning.service.responsable;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.compte.Compte;
import dev.sylvain.planning.service.compte.Habilitation;
import dev.sylvain.planning.service.compte.RoleHabilitation;
import dev.sylvain.planning.service.edition.EditionRepository;
import dev.sylvain.planning.service.publication.PlanPublieService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.responsable.ResponsableView.MembreEquipe;
import dev.sylvain.planning.service.responsable.ResponsableView.PersonneVacation;
import dev.sylvain.planning.service.responsable.ResponsableView.PlageMembre;
import dev.sylvain.planning.service.responsable.ResponsableView.StandResponsable;
import dev.sylvain.planning.service.responsable.ResponsableView.VacationResponsable;
import dev.sylvain.planning.service.solve.PlanSnapshotService;
import dev.sylvain.planning.service.solve.SeatSplit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * The read-only views of a responsable de stand (issue #295, ADR 0071).
 *
 * <p><b>Who</b> is the account the request authenticated as; <b>where</b> is
 * the rights of that account in force on the edition asked for, never the
 * request's say-so. A request for an edition, or a stand, outside those rights
 * gets the very same 404 as one that names nothing at all: the answer must
 * not tell « pas à vous » from « n'existe pas ».</p>
 *
 * <p><b>What</b> is the published plan — what the team was sent —, filtered by
 * {@link StandScope} and projected into {@link ResponsableView}. Names or
 * head counts per stand, as the edition's setting and the rights' overrides
 * say. Nothing here writes.</p>
 */
@ApplicationScoped
public class ResponsableService {

    /** One message for an edition or a stand out of reach and for one that does not exist. */
    static final String HORS_PERIMETRE = "Aucun périmètre de responsable à cette adresse.";

    private final EditionRepository editions;

    private final EditionContext editionContext;

    private final PlanPublieService planPublieService;

    private final ReferenceDataService referenceDataService;

    @Inject
    public ResponsableService(
            EditionRepository editions,
            EditionContext editionContext,
            PlanPublieService planPublieService,
            ReferenceDataService referenceDataService) {
        this.editions = editions;
        this.editionContext = editionContext;
        this.planPublieService = planPublieService;
        this.referenceDataService = referenceDataService;
    }

    /** The editions where {@code compte} is responsable de stand today, default one first. */
    public List<EditionResponsable> editions(Compte compte) {
        Instant maintenant = Instant.now();
        Map<String, Instant> expirations = new LinkedHashMap<>();
        for (Habilitation droit : rights(compte, null, maintenant)) {
            // Not Map.merge: it refuses a null value, and a right without an
            // expiry (granted before it was mandatory, or written by hand) is
            // one that never ends — null, the latest of all.
            String edition = droit.editionId();
            expirations.put(
                    edition,
                    expirations.containsKey(edition)
                            ? later(expirations.get(edition), droit.expireLe())
                            : droit.expireLe());
        }
        String defaut = editions.defaultEditionId();
        return editions.listEditions().stream()
                .filter(edition -> expirations.containsKey(edition.getId()))
                .map(edition -> new EditionResponsable(
                        edition.getId(),
                        edition.getNom(),
                        edition.getId().equals(defaut),
                        expirations.get(edition.getId())))
                .sorted(Comparator.comparing((EditionResponsable e) -> !e.defaut())
                        .thenComparing(EditionResponsable::editionNom, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /**
     * The scope of {@code compte} on {@code editionId}, or the one stand
     * {@code standId} of it.
     *
     * @throws BusinessError.NotFound when the account holds no right in force
     *                                there, or none covering {@code standId}
     */
    public ResponsableView view(Compte compte, String editionId, String standId) {
        List<Habilitation> droits = rights(compte, editionId, Instant.now());
        Edition edition = droits.isEmpty()
                ? null
                : editions.listEditions().stream()
                        .filter(e -> e.getId().equals(editionId))
                        .findFirst()
                        .orElse(null);
        Set<String> standIds = droits.stream()
                .flatMap(droit -> droit.standIds().stream())
                .filter(id -> standId == null || id.equals(standId))
                .collect(Collectors.toUnmodifiableSet());
        if (edition == null || standIds.isEmpty()) {
            throw new BusinessError.NotFound(HORS_PERIMETRE);
        }
        return editionContext.executeIn(editionId, () -> build(edition, droits, new StandScope(standIds)));
    }

    private ResponsableView build(Edition edition, List<Habilitation> droits, StandScope scope) {
        boolean parDefaut = referenceDataService.getParametresResponsables().nominatif();
        List<Stand> stands = scope.stands(referenceDataService.listStands());
        Set<String> nommes = stands.stream()
                .map(Stand::getId)
                .filter(id -> nominatif(droits, id, parDefaut))
                .collect(Collectors.toUnmodifiableSet());
        PlanSnapshotService.SnapshotMeta publication = planPublieService.lastPublication();
        PlanningEvenement plan = publication == null ? null : planPublieService.planPublie();
        List<PosteAffectation> sieges = plan == null ? List.of() : scope.seats(plan);

        List<StandResponsable> vues = stands.stream()
                .map(stand -> stand(stand, sieges, nommes.contains(stand.getId())))
                .sorted(Comparator.comparing(
                                (StandResponsable s) -> s.emplacementNom() == null ? "" : s.emplacementNom(),
                                String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(StandResponsable::standNom, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(StandResponsable::standId))
                .toList();
        return new ResponsableView(
                edition.getId(),
                edition.getNom(),
                publication == null ? null : publication.creeLe(),
                vues,
                plan == null ? List.of() : equipe(plan, scope, sieges, nommes));
    }

    private static StandResponsable stand(Stand stand, List<PosteAffectation> sieges, boolean nominatif) {
        Map<List<LocalDateTime>, List<PosteAffectation>> parFenetre = new LinkedHashMap<>();
        for (PosteAffectation siege : sieges) {
            LocalDateTime[] fenetre = SeatSplit.window(siege);
            if (fenetre != null && stand.getId().equals(siege.getStand().getId())) {
                parFenetre
                        .computeIfAbsent(List.of(fenetre[0], fenetre[1]), ignore -> new ArrayList<>())
                        .add(siege);
            }
        }
        List<VacationResponsable> vacations = parFenetre.entrySet().stream()
                .map(entree -> vacation(entree.getKey(), entree.getValue(), nominatif))
                .sorted(Comparator.comparing(VacationResponsable::debut).thenComparing(VacationResponsable::fin))
                .toList();
        return new StandResponsable(
                stand.getId(),
                stand.getNom() == null ? stand.getId() : stand.getNom(),
                stand.getEmplacement() == null ? null : stand.getEmplacement().getNom(),
                nominatif,
                vacations);
    }

    private static VacationResponsable vacation(
            List<LocalDateTime> fenetre, List<PosteAffectation> sieges, boolean nominatif) {
        List<Animateur> titulaires = sieges.stream()
                .map(PosteAffectation::getAnimateur)
                .filter(Objects::nonNull)
                .toList();
        List<PersonneVacation> personnes = nominatif
                ? titulaires.stream()
                        .map(a -> new PersonneVacation(a.getPrenom(), a.getNom()))
                        .sorted(PAR_NOM)
                        .toList()
                : List.of();
        return new VacationResponsable(
                fenetre.get(0), fenetre.get(1), titulaires.size(), sieges.size() - titulaires.size(), personnes);
    }

    /**
     * The people holding a seat on a stand shown by name: their seats there,
     * and — on those days only — the windows they are taken elsewhere, as
     * « occupé ». A day they spend entirely elsewhere is not the
     * responsable's business, and neither is the total.
     */
    private static List<MembreEquipe> equipe(
            PlanningEvenement plan, StandScope scope, List<PosteAffectation> sieges, Set<String> nommes) {
        Map<String, Animateur> personnes = new LinkedHashMap<>();
        Map<String, List<PlageMembre>> plages = new LinkedHashMap<>();
        for (PosteAffectation siege : sieges) {
            Animateur animateur = siege.getAnimateur();
            LocalDateTime[] fenetre = SeatSplit.window(siege);
            if (animateur == null
                    || fenetre == null
                    || !nommes.contains(siege.getStand().getId())) {
                continue;
            }
            personnes.putIfAbsent(animateur.getId(), animateur);
            plages.computeIfAbsent(animateur.getId(), ignore -> new ArrayList<>())
                    .add(new PlageMembre(
                            fenetre[0],
                            fenetre[1],
                            siege.getStand().getNom() == null
                                    ? siege.getStand().getId()
                                    : siege.getStand().getNom()));
        }
        Map<String, List<LocalDateTime[]>> ailleurs = scope.occupations(plan, personnes.keySet(), nommes);
        List<Animateur> tries = personnes.values().stream()
                .sorted(Comparator.comparing((Animateur a) -> a.getNom() == null ? "" : a.getNom(), FR)
                        .thenComparing(a -> a.getPrenom() == null ? "" : a.getPrenom(), FR))
                .toList();
        List<MembreEquipe> equipe = new ArrayList<>();
        for (Animateur animateur : tries) {
            List<PlageMembre> siennes = plages.get(animateur.getId());
            Set<LocalDate> jours =
                    siennes.stream().map(p -> p.debut().toLocalDate()).collect(Collectors.toSet());
            List<PlageMembre> toutes = new ArrayList<>(siennes);
            merged(ailleurs.getOrDefault(animateur.getId(), List.of())).stream()
                    .filter(f -> jours.contains(f[0].toLocalDate()))
                    .map(f -> new PlageMembre(f[0], f[1], null))
                    .forEach(toutes::add);
            toutes.sort(Comparator.comparing(PlageMembre::debut).thenComparing(PlageMembre::fin));
            equipe.add(new MembreEquipe(
                    String.valueOf(equipe.size() + 1), animateur.getPrenom(), animateur.getNom(), List.copyOf(toutes)));
        }
        return List.copyOf(equipe);
    }

    /**
     * Overlapping or touching windows fused: two seats back to back elsewhere
     * are one « occupé », not a count of how many stands the person went
     * through.
     */
    static List<LocalDateTime[]> merged(List<LocalDateTime[]> fenetres) {
        TreeMap<LocalDateTime, LocalDateTime> tries = new TreeMap<>();
        for (LocalDateTime[] f : fenetres) {
            tries.merge(f[0], f[1], (a, b) -> a.isAfter(b) ? a : b);
        }
        List<LocalDateTime[]> fusion = new ArrayList<>();
        for (Map.Entry<LocalDateTime, LocalDateTime> f : tries.entrySet()) {
            LocalDateTime[] dernier = fusion.isEmpty() ? null : fusion.getLast();
            if (dernier != null && !f.getKey().isAfter(dernier[1])) {
                if (f.getValue().isAfter(dernier[1])) {
                    dernier[1] = f.getValue();
                }
            } else {
                fusion.add(new LocalDateTime[] {f.getKey(), f.getValue()});
            }
        }
        return fusion;
    }

    /** A stand is shown by name when any right covering it says so — its override, else the edition's. */
    private static boolean nominatif(List<Habilitation> droits, String standId, boolean parDefaut) {
        return droits.stream()
                .filter(droit -> droit.standIds().contains(standId))
                .anyMatch(droit -> droit.nominatif() == null ? parDefaut : droit.nominatif());
    }

    /** The rights of responsable de stand in force, on {@code editionId} or on any edition ({@code null}). */
    private static List<Habilitation> rights(Compte compte, String editionId, Instant maintenant) {
        if (compte == null || !compte.actif()) {
            return List.of();
        }
        return compte.habilitations().stream()
                .filter(droit -> droit.role() == RoleHabilitation.RESPONSABLE_STAND && droit.inForce(maintenant))
                .filter(droit -> droit.editionId() != null
                        && (editionId == null || droit.editionId().equals(editionId)))
                .toList();
    }

    private static Instant later(Instant a, Instant b) {
        if (a == null || b == null) {
            return null;
        }
        return a.isAfter(b) ? a : b;
    }

    private static final Comparator<String> FR = String.CASE_INSENSITIVE_ORDER;

    private static final Comparator<PersonneVacation> PAR_NOM = Comparator.comparing(
                    (PersonneVacation p) -> p.nom() == null ? "" : p.nom(), FR)
            .thenComparing(p -> p.prenom() == null ? "" : p.prenom(), FR);
}
