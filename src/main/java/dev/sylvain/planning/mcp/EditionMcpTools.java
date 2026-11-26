package dev.sylvain.planning.mcp;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Edition;
import dev.sylvain.planning.domain.EditionPhase;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.edition.EditionActivationService;
import dev.sylvain.planning.service.edition.EditionDelta;
import dev.sylvain.planning.service.edition.EditionDeltaService;
import dev.sylvain.planning.service.edition.EditionPhaseService;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.edition.EtatEditionService;
import dev.sylvain.planning.service.edition.EtatEditionView;
import dev.sylvain.planning.service.journal.CurrentAction;
import dev.sylvain.planning.service.referentiel.ReferenceDataService;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * MCP tools for the editions themselves — the partition every other tool of
 * this package works inside (issue #181).
 *
 * <p>Before these existed, an assistant could not answer "how many editions do
 * I have, and how many créneaux in each?": every tool worked in the default
 * edition without naming it, so a reading looked like the whole truth and a
 * write could land in the wrong edition unnoticed. {@code lister_editions}
 * answers the question and says which edition is <b>active</b> — the only one
 * that publishes, mails and opens the espace (ADR 0072) — and the
 * {@code edition} argument every other tool carries designates the edition a
 * single call works in. That argument is required: a call without it is
 * refused rather than sent to a default edition.</p>
 *
 * <p>The volumetry {@code lister_editions} returns costs one query per edition
 * per counted référentiel. That is deliberate: an edition list without it
 * ("2025", "2026", "2026 bis") gives an assistant nothing to recognise
 * the right one by, and editions are counted in units, not in thousands.</p>
 *
 * <p>Two tools here work <em>inside</em> an edition rather than on the
 * list — {@code etat_edition}, which reads it, and {@code modifier_phase_edition},
 * which moves its phase (ADR 0115, in place of the 3.x {@code figer_referentiel}
 * and {@code lever_gel}) —
 * hence the {@code @EditionCiblee} on the class: their {@code edition}
 * argument designates the edition to work in, the way every other tool's
 * does. Dropping the annotation would let both run without an edition.
 * The other tools name their edition as a plain argument,
 * resolved by hand — the interceptor only reads {@code @EditionArg}, so it
 * leaves them alone.</p>
 */
@RefusMetier
@Journalise
@EditionCiblee
@ApplicationScoped
public class EditionMcpTools {

    /** Argument names, as a refusal quotes them back to the caller. */
    private static final String ARG_EDITION = "edition";

    private static final String ARG_PHASE = "phase";

    private static final String ARG_STRUCTURE = "structure";

    /** What {@code modifier_phase_edition} does to the structure of a published edition. */
    private enum StructureAction {
        DEBLOQUER,
        REFIGER
    }

    private final EditionService editionService;

    private final EditionContext editionContext;

    private final ReferenceDataService referenceDataService;

    private final McpEditions editions;

    private final EtatEditionService etatEditionService;

    private final EditionPhaseService phaseService;

    private final CurrentAction currentAction;

    private final EditionActivationService activationService;

    private final EditionDeltaService deltaService;

    @Inject
    EditionMcpTools(
            EditionActivationService activationService,
            EditionService editionService,
            EditionContext editionContext,
            ReferenceDataService referenceDataService,
            McpEditions editions,
            EtatEditionService etatEditionService,
            EditionPhaseService phaseService,
            CurrentAction currentAction,
            EditionDeltaService deltaService) {
        this.activationService = activationService;
        this.editionService = editionService;
        this.editionContext = editionContext;
        this.referenceDataService = referenceDataService;
        this.editions = editions;
        this.etatEditionService = etatEditionService;
        this.phaseService = phaseService;
        this.currentAction = currentAction;
        this.deltaService = deltaService;
    }

    @Tool(
            name = "lister_editions",
            description = "Liste les éditions (« Année 2025 », « Année 2026 »…) : l'édition est la partition "
                    + "dans laquelle vivent stands, animateurs, créneaux et paramètres, et deux éditions ne voient "
                    + "jamais les données l'une de l'autre. Chaque ligne indique si elle est l'édition ACTIVE — la "
                    + "seule qui publie, envoie des courriels et ouvre l'espace animateur (au plus une, parfois "
                    + "aucune) — et de quoi la reconnaître : nombre de créneaux, période couverte, nombre de stands "
                    + "et d'animateurs. Les autres outils exigent l'argument « edition » : passer l'id lu ici.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    List<EditionView> listEditions() {
        return editionService.listEditions().stream()
                .filter(edition -> editionContext.visibleToCaller(edition.getId()))
                .map(this::view)
                .toList();
    }

    @Tool(
            name = "edition_courante",
            description = "Nomme l'édition active : la seule qui publie, envoie des courriels et ouvre l'espace "
                    + "animateur, le flux ICS et l'affichage mural. Répond une erreur quand aucune édition n'est "
                    + "active (entre deux événements). Les autres outils exigent leur argument « edition » : celui-ci "
                    + "ne le remplace pas.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    EditionView currentEdition() {
        String active = editionContext
                .activeEditionId()
                .orElseThrow(() -> new BusinessError.Conflict(
                        "Aucune édition n'est active : activer_edition en désigne une (voir lister_editions)."));
        if (!editionContext.visibleToCaller(active)) {
            throw new BusinessError.Conflict("Aucune édition active n'est visible avec vos droits.");
        }
        return view(find(active));
    }

    @Tool(
            name = "etat_edition",
            description = "État de l'édition : la checklist du cycle, calculée — référentiels saisis, cohérence "
                    + "du référentiel (anomalies bloquantes, à vérifier, pour information ; le détail par "
                    + "lister_anomalies_referentiel), collecte "
                    + "des disponibilités, ouvertures des stands, besoin en animateurs, dernière résolution (et si "
                    + "les données ont bougé depuis, ou si une résolution est en cours), problèmes bloquants et "
                    + "avertissements, publication, accusés de réception, foire au planning ; et le bloc aTraiter "
                    + "(« à traiter aujourd'hui », jugé sur la date du jour du serveur) : déclarations de "
                    + "disponibilité en attente, demandes d'échange à arbitrer et celles en alerte, journées non "
                    + "relues des sept jours à venir (aujourd'hui compris), silencieux à relancer, données modifiées depuis la "
                    + "résolution, personnes à prévenir — des comptes et des dates, jamais un nom ; et le bloc gel : les "
                    + "familles du référentiel que la phase de l'édition fige (stands, créneaux, typologies et "
                    + "emplacements, compétences) et depuis quand. Chaque ligne porte un "
                    + "statut A_FAIRE, ATTENTION, INFO (des chiffres à lire, rien qui bloque) ou FAIT, et les "
                    + "chiffres qui le décident ; aucune donnée nominative. "
                    + "À appeler en premier pour savoir où en est l'organisateur, avant de choisir un outil plus fin.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    EtatEditionView editionState(@ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        return etatEditionService.etat();
    }

    @Tool(
            name = "modifier_phase_edition",
            description = "Change la phase de l'édition (ADR 0115), qui décide ce que le référentiel laisse "
                    + "modifier. PREPARATION : tout est modifiable. CALCUL : stands, créneaux, typologies, "
                    + "emplacements et compétences des animateurs sont figés pendant que le planning se calcule et "
                    + "se corrige. PUBLIEE : la structure (stands, créneaux, typologies, emplacements) reste figée, "
                    + "les compétences se modifient ; la première publication y fait passer l'édition. TERMINEE : "
                    + "tout est figé, comme en CALCUL. Toute écriture figée est refusée (REFERENTIEL_FIGE), par tous "
                    + "les chemins ; restent libres : disponibilités, souhaits, déclarations, ajustements, verrous "
                    + "et consignes. Tout passage est permis, retour compris : repasser en PREPARATION rouvre le "
                    + "référentiel, ce qui, après une publication, fera bouger des plannings déjà envoyés — à ne "
                    + "faire que sur demande explicite. Pour un changement de dernière minute dans une édition "
                    + "PUBLIEE sans quitter la phase, passer « structure » à DEBLOQUER (la publication suivante, ou "
                    + "REFIGER, refige la structure ; refusé hors de PUBLIEE). Donner « phase » ou « structure », "
                    + "jamais les deux. Distinct de verrouiller, qui fige "
                    + "des sièges du planning.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    PhaseView changeEditionPhase(
            @ToolArg(
                            description = "Phase à déclarer : PREPARATION, CALCUL, PUBLIEE ou TERMINEE. "
                                    + "Absent quand « structure » est donné.",
                            required = false)
                    String phase,
            @ToolArg(
                            description = "Seulement en PUBLIEE : DEBLOQUER rouvre la structure, REFIGER la ferme. "
                                    + "Absent quand « phase » est donné.",
                            required = false)
                    String structure,
            @ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        EditionPhase cible = McpArgs.enumeration(EditionPhase.class, phase, ARG_PHASE);
        StructureAction action = McpArgs.enumeration(StructureAction.class, structure, ARG_STRUCTURE);
        if ((cible == null) == (action == null)) {
            throw new BusinessError.Invalid(
                    "Donner « phase » (PREPARATION, CALCUL, PUBLIEE ou TERMINEE) ou « structure » (DEBLOQUER ou"
                            + " REFIGER), l'un des deux et pas les deux.");
        }
        if (cible != null) {
            return phaseView(phaseService.setPhase(cible));
        }
        // The line of the history says what was done, not only that a phase moved.
        currentAction.action(action == StructureAction.DEBLOQUER ? "STRUCTURE_DEBLOQUEE" : "STRUCTURE_REFIGEE");
        return phaseView(
                action == StructureAction.DEBLOQUER
                        ? phaseService.unblockStructure()
                        : phaseService.refreezeStructure());
    }

    @InstanceOnlyTool
    @Tool(
            name = "creer_edition",
            description = "Crée une édition vide ; son id (E suivi d'un nombre) est attribué par l'application "
                    + "et figure dans la réponse. Pour repartir d'une édition existante (stands, animateurs, "
                    + "paramètres), utiliser dupliquer_edition à la place.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = false))
    EditionView createEdition(
            @ToolArg(
                            description = "Nom affiché (ex. « Année 2027 ») ; un nom de la forme E12 est refusé, c'est "
                                    + "celle des identifiants")
                    String nom) {
        return view(editionService.create(new Edition(null, nom, false, null)));
    }

    @InstanceOnlyTool
    @Tool(
            name = "dupliquer_edition",
            description = "Duplique une édition dans une nouvelle : stands, typologies, emplacements, créneaux, "
                    + "horaires et paramètres sont recopiés, jamais le planning résolu. La copie naît INACTIVE : "
                    + "elle ne publie rien et n'écrit à personne tant qu'activer_edition ne l'a pas désignée. Un "
                    + "plan de repli (canicule, orage) ne se fait pas par duplication mais par une consigne datée "
                    + "dans l'édition vivante (simuler_consigne, appliquer_consigne). avec_animateurs=false laisse "
                    + "les personnes derrière — "
                    + "c'est le modèle d'année, à utiliser pour préparer l'édition suivante sans recopier un "
                    + "fichier de personnes qui ne se sont pas réinscrites. L'id de la nouvelle édition est attribué "
                    + "par l'application ; les stands, animateurs, typologies et emplacements gardent les leurs.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = false,
                            openWorldHint = false))
    EditionView duplicateEdition(
            @ToolArg(description = "Édition à copier : son id ou son nom (voir lister_editions)") String source,
            @ToolArg(description = "Nom affiché de l'édition à créer") String nom,
            @ToolArg(
                            description = "Reprendre les animateurs et ce qui les concerne (compétences, "
                                    + "indisponibilités, souhaits, ajustements manuels). Par défaut true.",
                            required = false,
                            name = "avec_animateurs")
                    Boolean avecAnimateurs) {
        String sourceId = requireEdition(source, "source");
        return view(editionService.duplicate(
                sourceId, new Edition(null, nom, false, null), avecAnimateurs == null || avecAnimateurs));
    }

    /**
     * Two editions named as plain arguments, like {@code dupliquer_edition}'s
     * source: the comparison spans both, so neither is « the edition the call
     * works in ». Animateurs leave by id only: {@link EditionDeltaService#compare}
     * is the anonymous delta, the labels the screen shows are another method.
     */
    @InstanceOnlyTool
    @Tool(
            name = "comparer_editions",
            description = "Compare le référentiel de deux éditions (l'an dernier et cette année, ou une édition et "
                    + "sa variante) : typologies, emplacements, stands, animateurs, journées types, créneaux, "
                    + "paramètres légaux, poids et activation des contraintes, ajustements comptés par type, et la "
                    + "volumétrie de chacune côte à côte (sièges, heures à pourvoir, heures disponibles, taux de "
                    + "remplissage). Ne lit aucun planning. Seules les différences sont listées : une édition "
                    + "comparée à elle-même rend des listes vides. Les lignes ne sont jamais rapprochées par id — "
                    + "deux éditions numérotent leurs fiches du même compteur, A151 peut désigner deux personnes — "
                    + "mais par code puis par nom (stands, emplacements, typologies ; rapprochement NOM signalé), "
                    + "par e-mail puis par identité (animateurs : EMAIL ou IDENTITE), par rang du jour d'ouverture "
                    + "puis heure de début (créneaux : POSITION). Chaque ligne porte les ids des deux côtés et les "
                    + "noms des champs qui diffèrent, jamais leurs valeurs ; aucun nom ni date de naissance "
                    + "d'animateur. noAnimateurMatched vrai : la référence a des animateurs et la cible aucun, ce "
                    + "que laisse une duplication sans les personnes.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = true,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    EditionDelta compareEditions(
            @ToolArg(description = "Édition de référence (A) : son id ou son nom (voir lister_editions)")
                    String reference,
            @ToolArg(description = "Édition comparée (B) : son id ou son nom") String cible) {
        // compare, never compareNamingAnimateurs: the anonymous delta is the
        // default, and EditionMcpToolsTest reads the answer for the names.
        return deltaService.compare(requireEdition(reference, "reference"), requireEdition(cible, "cible"));
    }

    @InstanceOnlyTool
    @Tool(
            name = "renommer_edition",
            description = "Renomme une édition. Seul le nom affiché change : l'id, lui, est repris dans les URLs "
                    + "et les configurations, il n'est pas modifiable.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    EditionView renameEdition(
            @ToolArg(description = "Édition à renommer : son id ou son nom") String edition,
            @ToolArg(description = "Nouveau nom affiché") String nom) {
        String id = requireEdition(edition, ARG_EDITION);
        return view(editionService.renommer(id, new Edition(id, nom, false, null)));
    }

    @InstanceOnlyTool
    @Tool(
            name = "activer_edition",
            description = "Fait de cette édition l'édition ACTIVE, et désactive l'ancienne dans le même geste. "
                    + "Seule l'édition active publie, envoie des courriels (rappels, relances, invitations) et ouvre "
                    + "l'espace animateur, le flux ICS et l'affichage mural : les liens de l'ancienne cessent de "
                    + "fonctionner. N'envoie rien lui-même. Refusé pendant une résolution sur l'une des deux "
                    + "éditions. À ne lancer qu'avec l'accord explicite de l'utilisateur.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    EditionView activateEdition(@ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        String id = requireEdition(edition, ARG_EDITION);
        return view(activationService.activate(id));
    }

    @InstanceOnlyTool
    @Tool(
            name = "desactiver_edition",
            description = "Désactive l'édition active, sans en activer d'autre : plus rien ne part vers "
                    + "l'extérieur (ni publication, ni courriel, ni espace animateur) — l'état normal entre deux "
                    + "événements. Sans effet sur une édition déjà inactive. Refusé pendant une résolution sur "
                    + "cette édition. À ne lancer qu'avec l'accord explicite de l'utilisateur.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = false,
                            idempotentHint = true,
                            openWorldHint = false))
    EditionView deactivateEdition(@ToolArg(description = EditionArg.DESCRIPTION) @EditionArg String edition) {
        String id = requireEdition(edition, ARG_EDITION);
        activationService.deactivate(id);
        return view(find(id));
    }

    @InstanceOnlyTool
    @Tool(
            name = "supprimer_edition",
            description = "Supprime une édition ET tout ce qu'elle contient : stands, animateurs, créneaux, "
                    + "contraintes, planning résolu. Destructif et irréversible, à ne lancer que sur demande explicite. "
                    + "L'édition active, la dernière édition restante et une édition sur laquelle une résolution "
                    + "tourne ou attend dans la file, ou une vérification du besoin est en cours, sont refusées.",
            annotations =
                    @Tool.Annotations(
                            readOnlyHint = false,
                            destructiveHint = true,
                            idempotentHint = false,
                            openWorldHint = false))
    SuppressionResult deleteEdition(@ToolArg(description = "Édition à supprimer : son id ou son nom") String edition) {
        String id = requireEdition(edition, ARG_EDITION);
        editionService.delete(id);
        return new SuppressionResult(id, true);
    }

    /**
     * Resolves a designation the caller had to provide. Unlike the optional
     * {@code edition} argument of the other tools, a blank value here is a
     * missing argument, not "the current edition".
     */
    private String requireEdition(String edition, String champ) {
        if (edition == null || edition.isBlank()) {
            throw new BusinessError.Invalid(champ + " est requis : id ou nom de l'édition (voir lister_editions)");
        }
        return editions.solve(edition);
    }

    private static PhaseView phaseView(Edition edition) {
        return new PhaseView(
                edition.getId(), edition.getPhase().name(), edition.getPhaseDepuis(), edition.isStructureDebloquee());
    }

    private Edition find(String id) {
        return editionService
                .find(id)
                .orElseThrow(() -> new IllegalStateException("Édition introuvable après écriture : " + id));
    }

    /** Counted inside the edition, hence the {@code executeIn}: the tool itself runs in another one. */
    private EditionView view(Edition edition) {
        return editionContext.executeIn(edition.getId(), () -> {
            List<Creneau> creneaux = referenceDataService.listCreneaux();
            return new EditionView(
                    edition.getId(),
                    edition.getNom(),
                    edition.isActive(),
                    creneaux.size(),
                    firstDate(creneaux),
                    lastDate(creneaux),
                    referenceDataService.listStands().size(),
                    referenceDataService.listAnimateurs().size());
        });
    }

    private static LocalDate firstDate(List<Creneau> creneaux) {
        return dates(creneaux).min(Comparator.naturalOrder()).orElse(null);
    }

    private static LocalDate lastDate(List<Creneau> creneaux) {
        return dates(creneaux).max(Comparator.naturalOrder()).orElse(null);
    }

    private static Stream<LocalDate> dates(List<Creneau> creneaux) {
        return creneaux.stream().map(Creneau::getDate).filter(Objects::nonNull);
    }

    /**
     * The phase an edition stands in (ADR 0115) and whether its structure is unblocked —
     * what {@code modifier_phase_edition} answers.
     */
    public record PhaseView(String edition, String phase, Instant phaseDepuis, boolean structureDebloquee) {}

    /**
     * @param active the edition allowed to reach outside (ADR 0072) — at most one
     */
    public record EditionView(
            String id,
            String nom,
            boolean active,
            int nombreCreneaux,
            LocalDate premiereDate,
            LocalDate derniereDate,
            int nombreStands,
            int nombreAnimateurs) {}
}
