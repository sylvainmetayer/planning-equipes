package dev.sylvain.planning.service.profile;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.StatutDeclaration;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.analyse.EquiteService;
import dev.sylvain.planning.service.analyse.EquiteService.RapportEquite;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.AnimateurFragilite;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.CompetenceRare;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.RapportFragilite;
import dev.sylvain.planning.service.espace.DeclarationDisponibiliteService;
import dev.sylvain.planning.service.espace.DemandeEchangeService;
import dev.sylvain.planning.service.espace.EspaceAnimateurService;
import dev.sylvain.planning.service.espace.EspaceAnimateurService.DeclarationAdminView;
import dev.sylvain.planning.service.espace.JourJClock;
import dev.sylvain.planning.service.publication.ConfirmationPlanningService;
import dev.sylvain.planning.service.publication.ConfirmationPlanningService.ConfirmationView;
import dev.sylvain.planning.service.publication.PlanPublieService;
import dev.sylvain.planning.service.referentiel.JoursEvenement;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanSnapshotService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.Period;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The « fiche 360° » of one animateur: what six screens say about them, read
 * once and narrowed to this person.
 *
 * <p>An assembler, deliberately. The equity line is the Équité report's, the
 * fragile seats are the Fragilité report's, the acknowledgement is the
 * Animateurs page's — each computed by the service that owns the rule, over
 * the whole persisted plan, and only then filtered. Recomputing any of them
 * for one person would be the way the fiche and the specialised screen start
 * telling two stories about the same animateur.</p>
 *
 * <p>Reads the <b>persisted</b> plan, never the one a running solve is
 * working on — the same plan every analysis screen reads.</p>
 */
@ApplicationScoped
public class AnimateurProfileService {

    private final ReferenceDataService referenceDataService;

    private final PlanningPersistenceService persistenceService;

    private final EquiteService equiteService;

    private final FragiliteAnalyzer fragiliteAnalyzer;

    private final ConfirmationPlanningService confirmationService;

    private final PlanPublieService planPublieService;

    private final DemandeEchangeService demandeEchangeService;

    private final DeclarationDisponibiliteService declarationService;

    private final EspaceAnimateurService espaceService;

    private final JourJClock clock;

    @Inject
    public AnimateurProfileService(
            ReferenceDataService referenceDataService,
            PlanningPersistenceService persistenceService,
            EquiteService equiteService,
            FragiliteAnalyzer fragiliteAnalyzer,
            ConfirmationPlanningService confirmationService,
            PlanPublieService planPublieService,
            DemandeEchangeService demandeEchangeService,
            DeclarationDisponibiliteService declarationService,
            EspaceAnimateurService espaceService,
            JourJClock clock) {
        this.referenceDataService = referenceDataService;
        this.persistenceService = persistenceService;
        this.equiteService = equiteService;
        this.fragiliteAnalyzer = fragiliteAnalyzer;
        this.confirmationService = confirmationService;
        this.planPublieService = planPublieService;
        this.demandeEchangeService = demandeEchangeService;
        this.declarationService = declarationService;
        this.espaceService = espaceService;
        this.clock = clock;
    }

    /** The fiche of {@code animateurId}; {@code 404} when the edition holds nobody by that id. */
    public AnimateurProfile profile(String animateurId) {
        Animateur animateur = referenceDataService.listAnimateurs().stream()
                .filter(candidat -> candidat.getId().equals(animateurId))
                .findFirst()
                .orElseThrow(() -> new BusinessError.NotFound("Animateur inconnu : " + animateurId));

        JoursEvenement jours = JoursEvenement.of(referenceDataService.listCreneaux());
        PlanningEvenement planning = persistenceService.loadPersistedPlanning();
        List<PosteAffectation> postes = planning.getPostes() == null ? List.of() : planning.getPostes();
        boolean planCalcule = postes.stream().anyMatch(poste -> poste.getAnimateur() != null);

        RapportEquite rapport = equiteService.rapport();
        RapportEquite equite = new RapportEquite(
                rapport.heureDebutSoiree(),
                rapport.semaines(),
                rapport.lignes().stream()
                        .filter(ligne -> animateurId.equals(ligne.animateurId()))
                        .toList(),
                rapport.syntheses(),
                rapport.colonnesSolveur());

        RapportFragilite fragilite = fragiliteAnalyzer.analyze(planning);
        AnimateurFragilite ligneFragilite = fragilite.animateurs().stream()
                .filter(ligne -> animateurId.equals(ligne.animateurId()))
                .findFirst()
                .orElse(null);
        List<CompetenceRare> competencesRares = fragilite.competencesRares().stream()
                .filter(ligne -> animateurId.equals(ligne.animateurId()))
                .toList();

        List<VerrouillagePlanning> tousVerrous = referenceDataService.listVerrouillages();
        PastHorizon horizon = PastHorizon.of(clock.dateTime());
        List<AnimateurProfile.ProfileSeat> affectations = postes.stream()
                .filter(poste -> poste.getAnimateur() != null
                        && animateurId.equals(poste.getAnimateur().getId()))
                .map(poste -> seat(poste, horizon, tousVerrous))
                .sorted(Comparator.comparing(
                                AnimateurProfile.ProfileSeat::date, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(
                                AnimateurProfile.ProfileSeat::heureDebut,
                                Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(AnimateurProfile.ProfileSeat::standId))
                .toList();

        ConfirmationView confirmation = confirmationService.byAnimateur().stream()
                .filter(vue -> animateurId.equals(vue.animateurId()))
                .findFirst()
                .orElse(null);
        PlanSnapshotService.SnapshotMeta publication = planPublieService.lastPublication();

        List<EspaceAnimateurService.DemandeEchangeView> echanges =
                espaceService.toViews(demandeEchangeService.pendingDemandes().stream()
                        .filter(demande -> animateurId.equals(demande.getDemandeurId())
                                || animateurId.equals(demande.getCibleId()))
                        .toList());

        Map<String, Animateur> animateurs = referenceDataService.listAnimateurs().stream()
                .collect(Collectors.toMap(Animateur::getId, Function.identity(), (left, right) -> left));
        Map<String, Stand> stands = referenceDataService.listStands().stream()
                .collect(Collectors.toMap(Stand::getId, Function.identity(), (left, right) -> left));
        List<AnimateurProfile.ProfileAdjustment> ajustements = referenceDataService.listContraintesAdHoc().stream()
                .filter(contrainte -> names(contrainte, animateurId))
                .map(contrainte -> adjustment(contrainte, animateurId, animateurs, stands))
                .toList();

        List<VerrouillagePlanning> verrous = tousVerrous.stream()
                .filter(verrou -> animateurId.equals(verrou.getAnimateurId()))
                .toList();

        DeclarationAdminView declaration = espaceService
                .toDeclarationViews(declarationService.listForAnimateur(animateurId).stream()
                        .filter(candidate -> candidate.getStatut() == StatutDeclaration.EN_ATTENTE)
                        .limit(1)
                        .toList())
                .stream()
                .findFirst()
                .orElse(null);

        return new AnimateurProfile(
                animateur,
                jours.jours(),
                jours.isEmpty() ? null : regime(animateur, jours.first()),
                jours.isEmpty() ? null : regime(animateur, jours.last()),
                planCalcule,
                equite,
                ligneFragilite,
                competencesRares,
                affectations,
                confirmation,
                publication == null ? null : publication.publieLe(),
                echanges,
                ajustements,
                verrous,
                declaration);
    }

    /** Under 16, 16 to 18, or adult on {@code date} — the three regimes of the Code du travail. */
    static AnimateurProfile.LegalRegime regime(Animateur animateur, LocalDate date) {
        if (animateur.getDateNaissance() == null || date == null) {
            return null;
        }
        String regime;
        if (animateur.isUnder16On(date)) {
            regime = "MOINS_DE_16";
        } else if (animateur.isMineurOn(date)) {
            regime = "MINEUR";
        } else {
            regime = "MAJEUR";
        }
        return new AnimateurProfile.LegalRegime(
                date, Period.between(animateur.getDateNaissance(), date).getYears(), regime);
    }

    private static AnimateurProfile.ProfileSeat seat(
            PosteAffectation poste, PastHorizon horizon, List<VerrouillagePlanning> verrous) {
        Stand stand = poste.getStand();
        Creneau creneau = poste.getCreneau();
        LocalDate date = creneau == null ? null : creneau.getDate();
        LocalTime debut = poste.heureDebutEffectif();
        return new AnimateurProfile.ProfileSeat(
                poste.getId(),
                stand == null ? null : stand.getId(),
                stand == null ? null : stand.getNom(),
                creneau == null || creneau.getId() == null ? 0L : creneau.getId(),
                date,
                debut,
                poste.heureFinEffectif(),
                stand == null || stand.getEmplacement() == null
                        ? null
                        : stand.getEmplacement().getId(),
                stand == null || stand.getEmplacement() == null
                        ? null
                        : stand.getEmplacement().getNom(),
                horizon.hasStarted(date, debut),
                verrous.stream().anyMatch(verrou -> verrou.couvre(poste)));
    }

    private static boolean names(ContrainteAdHoc contrainte, String animateurId) {
        return contrainte.getAnimateursConcernes() != null
                && contrainte.getAnimateursConcernes().stream()
                        .filter(Objects::nonNull)
                        .anyMatch(animateur -> animateurId.equals(animateur.getId()));
    }

    private static AnimateurProfile.ProfileAdjustment adjustment(
            ContrainteAdHoc contrainte,
            String animateurId,
            Map<String, Animateur> animateurs,
            Map<String, Stand> stands) {
        Creneau creneau = contrainte.getCreneau();
        Stand stand = contrainte.getStand() == null
                ? null
                : stands.getOrDefault(contrainte.getStand().getId(), contrainte.getStand());
        return new AnimateurProfile.ProfileAdjustment(
                contrainte.getId(),
                contrainte.getType() == null ? null : contrainte.getType().name(),
                creneau == null ? null : creneau.getId(),
                creneau == null ? null : creneau.getDate(),
                creneau == null ? null : creneau.getHeureDebut(),
                creneau == null ? null : creneau.getHeureFin(),
                stand == null ? null : stand.getId(),
                stand == null ? null : stand.getNom(),
                contrainte.getAnimateursConcernes().stream()
                        .filter(Objects::nonNull)
                        .filter(animateur -> !animateurId.equals(animateur.getId()))
                        .map(animateur -> new AnimateurProfile.ProfileColleague(
                                animateur.getId(),
                                animateurs
                                        .getOrDefault(animateur.getId(), animateur)
                                        .nomAffiche()))
                        .toList(),
                contrainte.getRaison());
    }
}
