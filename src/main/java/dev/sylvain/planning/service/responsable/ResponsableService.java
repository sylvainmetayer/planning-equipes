package dev.sylvain.planning.service.responsable;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.compte.Compte;
import dev.sylvain.planning.service.compte.CompteService;
import dev.sylvain.planning.service.compte.Habilitation;
import dev.sylvain.planning.service.compte.RoleHabilitation;
import dev.sylvain.planning.service.edition.EditionRepository;
import dev.sylvain.planning.service.export.ExportProvenance;
import dev.sylvain.planning.service.export.StandAffichettePdf;
import dev.sylvain.planning.service.publication.PlanPublieService;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.responsable.ResponsableView.PersonneVacation;
import dev.sylvain.planning.service.responsable.ResponsableView.StandResponsable;
import dev.sylvain.planning.service.responsable.ResponsableView.VacationResponsable;
import dev.sylvain.planning.service.solve.SeatSplit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * What a responsable de stand reads, and the sheet they print.
 *
 * <p><b>Who</b> is the account the request authenticated as; <b>where</b> is
 * the rights of that account in force on the <b>active</b> edition (ADR 0072),
 * never anything the request says — there is no edition, no stand to name but
 * their own. On an edition that is not active, or with no right in force, the
 * answer is the same as for a person with nothing to read: no stand.</p>
 *
 * <p><b>What</b> is the published plan — what the team was sent — narrowed to
 * the stands of the scope by {@link #seatsOf}, the one door this class reads
 * seats through, and projected into {@link ResponsableView}. Names appear only
 * where {@link ReglagesResponsables} says so at this instant. The sheet is
 * built from the very same {@link StandResponsable}, so the two cannot differ.
 * Nothing here writes.</p>
 */
@ApplicationScoped
public class ResponsableService {

    private final EditionRepository editions;
    private final EditionContext editionContext;
    private final PlanPublieService planPublieService;
    private final ReferenceDataService referenceDataService;
    private final ReglageResponsableRepository reglages;
    private final ExportProvenance provenance;
    private final StandAffichettePdf affichette;

    @Inject
    public ResponsableService(
            EditionRepository editions,
            EditionContext editionContext,
            PlanPublieService planPublieService,
            ReferenceDataService referenceDataService,
            ReglageResponsableRepository reglages,
            ExportProvenance provenance,
            StandAffichettePdf affichette) {
        this.editions = editions;
        this.editionContext = editionContext;
        this.planPublieService = planPublieService;
        this.referenceDataService = referenceDataService;
        this.reglages = reglages;
        this.provenance = provenance;
        this.affichette = affichette;
    }

    /** The stands of the caller's rights on the active edition, with their published shifts. */
    public ResponsableView view(Compte compte) {
        return read(compte, null).map(Lecture::vue).orElseGet(() -> new ResponsableView(null, null, List.of()));
    }

    /** The sheet of one stand of the caller's scope: 404 when it is not theirs, whatever the reason. */
    public byte[] affichette(Compte compte, String standId) {
        Lecture lecture = read(compte, standId).orElseThrow(ResponsableService::outOfScope);
        if (lecture.vue().stands().isEmpty()) {
            throw outOfScope();
        }
        StandResponsable stand = lecture.vue().stands().getFirst();
        StandAffichettePdf.Contenu contenu = new StandAffichettePdf.Contenu(
                stand.standNom(),
                stand.emplacementNom(),
                stand.nominatif(),
                stand.vacations().stream()
                        .map(v -> new StandAffichettePdf.Vacation(
                                v.debut(),
                                v.fin(),
                                v.pourvus(),
                                v.vides(),
                                v.personnes().stream()
                                        .map(p -> (nonNull(p.prenom()) + " " + nonNull(p.nom())).trim())
                                        .toList()))
                        .toList());
        return editionContext.executeIn(lecture.editionId(), () -> affichette.render(contenu, provenance.publiee()));
    }

    private static BusinessError.NotFound outOfScope() {
        // One message for a stand out of reach and for one that does not exist.
        return new BusinessError.NotFound("Aucun stand à cette adresse.");
    }

    private record Lecture(String editionId, ResponsableView vue) {}

    /** The view of the caller's stands (or of {@code seulement}), or empty when nothing is open to them now. */
    private Optional<Lecture> read(Compte compte, String seulement) {
        Optional<String> active = editionContext.activeEditionId();
        if (compte == null || active.isEmpty() || !CompteService.isActive(compte)) {
            return Optional.empty();
        }
        String editionId = active.get();
        Instant maintenant = Instant.now();
        Set<String> standIds = compte.habilitations().stream()
                .filter(h -> h.role() == RoleHabilitation.RESPONSABLE_STAND
                        && editionId.equals(h.editionId())
                        && inForce(h, maintenant))
                .flatMap(h -> h.standIds().stream())
                .filter(id -> seulement == null || id.equals(seulement))
                .collect(Collectors.toUnmodifiableSet());
        if (standIds.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(editionContext.executeIn(
                editionId, () -> new Lecture(editionId, build(editionId, standIds, maintenant))));
    }

    private static boolean inForce(Habilitation h, Instant maintenant) {
        return h.retireeLe() == null && (h.expireLe() == null || h.expireLe().isAfter(maintenant));
    }

    private ResponsableView build(String editionId, Set<String> standIds, Instant maintenant) {
        List<Stand> stands = referenceDataService.listStands().stream()
                .filter(stand -> standIds.contains(stand.getId()))
                .toList();
        var publiee = planPublieService.publicationInForce();
        List<PosteAffectation> sieges =
                publiee.map(p -> seatsOf(p.plan(), standIds)).orElse(List.of());
        ReglagesResponsables reglage = reglages.get();
        List<StandResponsable> vues = stands.stream()
                .map(stand -> stand(stand, sieges, reglage.forStand(stand.getId()), maintenant))
                .sorted(Comparator.comparing(
                                (StandResponsable s) -> s.emplacementNom() == null ? "" : s.emplacementNom(),
                                String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(StandResponsable::standNom, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(StandResponsable::standId))
                .toList();
        String nom = editions.listEditions().stream()
                .filter(e -> e.getId().equals(editionId))
                .map(Edition::getNom)
                .findFirst()
                .orElse(null);
        return new ResponsableView(
                nom, publiee.map(p -> p.publication().publieLe()).orElse(null), vues);
    }

    /** The one door to the seats of the plan: those standing on a stand of the scope, held or not. */
    private static List<PosteAffectation> seatsOf(PlanningEvenement plan, Set<String> standIds) {
        List<PosteAffectation> postes = plan.getPostes();
        if (postes == null) {
            return List.of();
        }
        return postes.stream()
                .filter(poste -> poste.getStand() != null
                        && standIds.contains(poste.getStand().getId()))
                .toList();
    }

    private static StandResponsable stand(
            Stand stand, List<PosteAffectation> sieges, ReglageNoms reglage, Instant maintenant) {
        boolean nominatif = reglage.visibleA(maintenant);
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
                .map(e -> vacation(e.getKey(), e.getValue(), nominatif))
                .sorted(Comparator.comparing(VacationResponsable::debut).thenComparing(VacationResponsable::fin))
                .toList();
        return new StandResponsable(
                stand.getId(),
                stand.getNom() == null ? stand.getId() : stand.getNom(),
                stand.getEmplacement() == null ? null : stand.getEmplacement().getNom(),
                nominatif,
                reglage.attenduA(maintenant),
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
                        .map(a -> new PersonneVacation(nonNull(a.getPrenom()), nonNull(a.getNom())))
                        .sorted(Comparator.comparing(PersonneVacation::nom, String.CASE_INSENSITIVE_ORDER)
                                .thenComparing(PersonneVacation::prenom, String.CASE_INSENSITIVE_ORDER))
                        .toList()
                : List.of();
        return new ResponsableView.VacationResponsable(
                fenetre.get(0), fenetre.get(1), titulaires.size(), sieges.size() - titulaires.size(), personnes);
    }

    private static String nonNull(String valeur) {
        return valeur == null ? "" : valeur;
    }
}
