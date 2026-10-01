package dev.sylvain.planning.mcp;

import dev.sylvain.planning.domain.DemandeEchange;
import dev.sylvain.planning.domain.StatutDemandeEchange;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.espace.DemandeEchangeService;
import dev.sylvain.planning.service.espace.DemandeEchangeService.FenetreFoire;
import dev.sylvain.planning.service.espace.EchangeStatistics;
import dev.sylvain.planning.service.espace.EchangeStatistics.EchangeConstraintCount;
import dev.sylvain.planning.service.espace.EchangeStatisticsService;
import dev.sylvain.planning.service.espace.EspaceAnimateurService;
import dev.sylvain.planning.service.espace.EspaceAnimateurService.DemandeEchangeView;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import dev.sylvain.planning.service.solve.PlanningWhatIf.EchangeSimulation;
import dev.sylvain.planning.service.solve.PlanningWhatIf.HardViolation;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

/**
 * MCP tools over the swap requests animateurs send from their espace
 * ({@code DemandeEchangeResource}): the « foire aux échanges », its window,
 * and the admin decisions that apply a swap or leave the planning alone.
 *
 * <p>Accepting one rewrites the persisted plan and pins the result — the same
 * kind of write as {@code affecter_poste}, reached from the other end. An
 * assistant that could solve and repair but not decide a demande would leave
 * the operator to do by hand exactly the arbitration it is best placed to
 * document.</p>
 *
 * <p>A demande names two people and its prevalidation quotes constraint
 * violations worded for the web UI: ids only here, and the violation lines go
 * through {@link AnonymisationViolations} like everywhere else.</p>
 */
@EditionCiblee
@RefusMetier
@Journalise
@ApplicationScoped
public class EchangeMcpTools {

    private final DemandeEchangeService demandeEchangeService;

    private final EspaceAnimateurService espaceAnimateurService;

    private final ReferenceDataService referenceDataService;

    private final EchangeStatisticsService statisticsService;

    @Inject
    EchangeMcpTools(
            DemandeEchangeService demandeEchangeService,
            EspaceAnimateurService espaceAnimateurService,
            ReferenceDataService referenceDataService,
            EchangeStatisticsService statisticsService) {
        this.demandeEchangeService = demandeEchangeService;
        this.espaceAnimateurService = espaceAnimateurService;
        this.referenceDataService = referenceDataService;
        this.statisticsService = statisticsService;
    }

    @Tool(
            name = "lister_demandes_echange",
            description = "Liste les demandes d'échange de l'édition, de la plus récente à la plus ancienne. "
                    + "Filtrable par statut : EN_ATTENTE_CIBLE (le collègue visé n'a pas encore répondu), PROPOSEE "
                    + "(en attente de décision de l'organisation), ACCEPTEE, REFUSEE, ANNULEE. Les animateurs y sont "
                    + "désignés par id seul.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    List<DemandeView> listDemandesEchange(
            @ToolArg(description = "Statut pour filtrer, par exemple PROPOSEE", required = false) String statut,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        StatutDemandeEchange filtre =
                statut == null ? null : McpArgs.enumeration(StatutDemandeEchange.class, statut, "statut");
        AnonymisationViolations anonymisation = AnonymisationViolations.of(referenceDataService);
        return espaceAnimateurService.toViews(demandeEchangeService.list()).stream()
                .filter(demande -> filtre == null || filtre.name().equals(demande.statut()))
                .map(demande -> toView(demande, anonymisation))
                .toList();
    }

    @Tool(
            name = "consulter_foire_echanges",
            description = "Consulte la fenêtre de la foire aux échanges : l'interrupteur, ses dates éventuelles, "
                    + "et si elle accepte quelque chose aujourd'hui. Une foire fermée rend les espaces animateurs "
                    + "consultables mais non modifiables. Rend aussi ses statistiques sur une période de création "
                    + "des demandes (par défaut la fenêtre de la foire quand elle est datée, sinon toute l'édition ; "
                    + "touteEdition=true demande toute l'édition même quand la fenêtre est datée) : "
                    + "volumes, taux d'accord des collègues, d'acceptation par l'organisation et d'aboutissement, "
                    + "part des demandes prévalidées, délais en secondes (médiane, 90e centile, moyenne, demandes "
                    + "sans horodatage), répartitions par jour et par stand, contraintes dures les plus cassées. "
                    + "Chaque taux est donné en numérateur et dénominateur. Agrégats seuls, aucun animateur.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    FoireConsultationView getFoireEchanges(
            @ToolArg(description = "Premier jour de création compté (AAAA-MM-JJ)", required = false) String du,
            @ToolArg(description = "Dernier jour de création compté (AAAA-MM-JJ)", required = false) String au,
            @ToolArg(
                            description = "true : toute l'édition, même quand la fenêtre de la foire est datée "
                                    + "(incompatible avec du et au)",
                            required = false)
                    Boolean touteEdition,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        StatisticsPeriod period = statisticsPeriod(du, au, touteEdition);
        FoireView foire = foireView();
        return new FoireConsultationView(
                foire.ouverte(),
                foire.debut(),
                foire.fin(),
                foire.ouverteAujourdhui(),
                anonymised(
                        statisticsService.statistics(period.from(), period.to(), period.wholeEdition()),
                        AnonymisationViolations.of(referenceDataService)));
    }

    @Tool(
            name = "configurer_foire_echanges",
            description = "Ouvre ou ferme la foire aux échanges, et la borne éventuellement par des dates. "
                    + "L'interrupteur est le maître : une fenêtre datée dont l'interrupteur est éteint n'accepte rien. "
                    + "N'envoie aucun courriel.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    FoireView configureFoireEchanges(
            @ToolArg(description = "Foire ouverte ou fermée") boolean ouverte,
            @ToolArg(description = "Début de la foire (AAAA-MM-JJ)", required = false) String debut,
            @ToolArg(description = "Fin de la foire (AAAA-MM-JJ)", required = false) String fin,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        demandeEchangeService.openFoire(
                new FenetreFoire(ouverte, McpArgs.date(debut, "debut"), McpArgs.date(fin, "fin")));
        return foireView();
    }

    @Tool(
            name = "analyser_impact_echange",
            description = "Chiffre l'impact d'une demande d'échange sur le planning persisté d'aujourd'hui : "
                    + "score avant et après, delta, et contraintes dures qu'elle casserait. Recalculé à la demande — la "
                    + "prévalidation stockée ne décrit que le planning du moment où la demande a été envoyée. Ne "
                    + "persiste rien.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    ImpactEchangeView analyzeEchangeImpact(
            @ToolArg(description = "Id de la demande") String id,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        EchangeSimulation simulation = demandeEchangeService.impact(id);
        AnonymisationViolations anonymisation = AnonymisationViolations.of(referenceDataService);
        return new ImpactEchangeView(
                simulation.posteDemandeurId(),
                simulation.posteCibleId(),
                simulation.echangeCroise(),
                simulation.standCibleId(),
                String.valueOf(simulation.scoreAvant()),
                String.valueOf(simulation.scoreApres()),
                String.valueOf(simulation.delta()),
                simulation.casseContrainteDure(),
                simulation.nouvellesViolationsDures().stream()
                        .map(violation -> toView(violation, anonymisation))
                        .toList());
    }

    @Tool(
            name = "accepter_demande_echange",
            description = "Accepte une demande d'échange : l'échange est appliqué au planning persisté tel qu'il "
                    + "a été simulé, puis figé par des verrouillages ANIMATEUR_CRENEAU pour qu'une résolution ne le "
                    + "défasse pas. Vérifier analyser_impact_echange d'abord : une demande acceptable à l'envoi peut "
                    + "casser une contrainte dure sur le planning d'aujourd'hui.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = false))
    DemandeView acceptDemandeEchange(
            @ToolArg(description = "Id de la demande") String id,
            @ToolArg(description = "Commentaire pour le demandeur", required = false) String commentaire,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return view(demandeEchangeService.accept(id, commentaire));
    }

    @Tool(
            name = "refuser_demande_echange",
            description = "Refuse une demande d'échange : le planning n'est pas touché. Le commentaire est ce "
                    + "que le demandeur lira à la prochaine publication.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = false))
    DemandeView refuseDemandeEchange(
            @ToolArg(description = "Id de la demande") String id,
            @ToolArg(description = "Commentaire pour le demandeur", required = false) String commentaire,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return view(demandeEchangeService.refuse(id, commentaire));
    }

    /* -------------------------------- Views -------------------------------- */

    private FoireView foireView() {
        FenetreFoire fenetre = demandeEchangeService.fenetre();
        return new FoireView(fenetre.ouverte(), fenetre.debut(), fenetre.fin(), demandeEchangeService.isFoireOpen());
    }

    private DemandeView view(DemandeEchange demande) {
        return toView(
                espaceAnimateurService.toViews(List.of(demande)).get(0),
                AnonymisationViolations.of(referenceDataService));
    }

    /**
     * The period of {@code consulter_foire_echanges}, as REST reads
     * {@code du}, {@code au} and {@code periode=edition}: no bound is the foire
     * window when it is dated, {@code touteEdition} the whole edition whatever
     * the window. Asking for both the whole edition and a bound is a
     * contradiction, refused rather than settled silently.
     */
    static StatisticsPeriod statisticsPeriod(String du, String au, Boolean touteEdition) {
        LocalDate from = McpArgs.date(du, "du");
        LocalDate to = McpArgs.date(au, "au");
        boolean wholeEdition = Boolean.TRUE.equals(touteEdition);
        if (wholeEdition && (from != null || to != null)) {
            throw new BusinessError.Invalid("touteEdition=true ne se combine ni avec du ni avec au");
        }
        if (from != null && to != null && to.isBefore(from)) {
            throw new BusinessError.Invalid("La fin de la période précède son début");
        }
        return new StatisticsPeriod(from, to, wholeEdition);
    }

    /** The creation days the statistics are asked for; both {@code null} and not whole: the default. */
    record StatisticsPeriod(LocalDate from, LocalDate to, boolean wholeEdition) {}

    /**
     * The statistics as MCP sends them: aggregates already, and the constraint
     * lines run through the anonymisation like every other violation line —
     * they are catalogue descriptions, but nothing here relies on it.
     */
    static EchangeStatistics anonymised(EchangeStatistics statistiques, AnonymisationViolations anonymisation) {
        return statistiques.withContraintesViolees(statistiques.contraintesViolees().stream()
                .map(ligne -> new EchangeConstraintCount(anonymisation.anonymiser(ligne.contrainte()), ligne.nombre()))
                .toList());
    }

    static DemandeView toView(DemandeEchangeView demande, AnonymisationViolations anonymisation) {
        return new DemandeView(
                demande.id(),
                demande.statut(),
                demande.demandeurId(),
                demande.cibleId(),
                demande.creneauId(),
                demande.date(),
                demande.heureDebut(),
                demande.heureFin(),
                demande.standId(),
                demande.creneauCibleId(),
                demande.dateCible(),
                demande.heureDebutCible(),
                demande.heureFinCible(),
                demande.standCibleId(),
                TextesLibres.renseigne(demande.motif()),
                demande.prevalidationOk(),
                anonymisation.anonymiser(demande.contraintesViolees()),
                TextesLibres.renseigne(demande.commentaireAdmin()),
                demande.creeLe(),
                demande.cibleDecideLe(),
                demande.decideLe(),
                demande.communiqueeLe());
    }

    static ViolationHardView toView(HardViolation violation, AnonymisationViolations anonymisation) {
        return new ViolationHardView(
                violation.name(),
                anonymisation.anonymiser(violation.description()),
                violation.matchesSupplementaires());
    }

    /**
     * One swap request, by ids.
     *
     * @param cibleId          the colleague the demande is aimed at, when it is
     *                         a directed swap; {@code null} for a seat given up
     * @param creneauCibleId   the colleague's seat wanted in return — directed
     *                         swaps only, {@code null} otherwise
     * @param prevalidationOk  what the check said <b>at submission time</b>;
     *                         {@code analyser_impact_echange} re-answers it
     *                         against today's plan
     * @param motifRenseigne   whether the demandeur gave a reason, never the
     *                         reason itself — see {@link TextesLibres}
     */
    public record DemandeView(
            String id,
            String statut,
            String demandeurId,
            String cibleId,
            Long creneauId,
            LocalDate date,
            LocalTime heureDebut,
            LocalTime heureFin,
            String standId,
            Long creneauCibleId,
            LocalDate dateCible,
            LocalTime heureDebutCible,
            LocalTime heureFinCible,
            String standCibleId,
            boolean motifRenseigne,
            Boolean prevalidationOk,
            List<String> contraintesViolees,
            boolean commentaireAdminRenseigne,
            Instant creeLe,
            Instant cibleDecideLe,
            Instant decideLe,
            // Null on a decision the next publication still has to announce.
            Instant communiqueeLe) {}

    /**
     * @param ouverteAujourdhui the switch <b>and</b> today's date against the
     *                          bounds — a foire switched on for next week
     *                          accepts nothing today
     */
    public record FoireView(boolean ouverte, LocalDate debut, LocalDate fin, boolean ouverteAujourdhui) {}

    /**
     * The foire window and its statistics over the period asked.
     *
     * @param statistiques aggregates only, by stand id and name and by day —
     *                     never an animateur
     */
    public record FoireConsultationView(
            boolean ouverte,
            LocalDate debut,
            LocalDate fin,
            boolean ouverteAujourdhui,
            EchangeStatistics statistiques) {}

    /** Scores are rendered as text ({@code "0hard/-12medium/…"}), as everywhere else in this package. */
    public record ImpactEchangeView(
            String posteDemandeurId,
            String posteCibleId,
            boolean echangeCroise,
            String standCibleId,
            String scoreAvant,
            String scoreApres,
            String delta,
            boolean casseContrainteDure,
            List<ViolationHardView> nouvellesViolationsDures) {}

    public record ViolationHardView(String contrainte, String description, int matchesSupplementaires) {}
}
