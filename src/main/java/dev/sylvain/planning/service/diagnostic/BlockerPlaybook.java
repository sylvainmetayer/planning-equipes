package dev.sylvain.planning.service.diagnostic;

import dev.sylvain.planning.solver.ConstraintCatalog;
import dev.sylvain.planning.solver.ConstraintCatalog.ConstraintDefinition;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * What to do about a blocking problem — the playbook between « this timeslot
 * is short » and « the screen where everything is fixed ».
 *
 * <p>Each kind of blocking cause found before a solve, and each rule of the
 * catalogue found in default after one, is tied to one to four <b>action
 * types</b>, ordered from the most likely gesture to the least. An action is a
 * <b>navigation, never a write</b>: its button opens the screen that makes the
 * gesture, already positioned on the object in question through the query
 * parameters that screen reads (ADR 0012), and that screen keeps its own
 * previews and guards. The playbook advises; the organiser decides.</p>
 *
 * <p>The order is fixed and reads in this file — no relevance score, no
 * heuristic. The explanation of a rule's first action is its
 * {@link ConstraintDefinition#remediation()} word for word, so the same rule
 * never gets two different pieces of advice on two screens.</p>
 *
 * <p>The tables are complete by test ({@code BlockerPlaybookTest}): a cause
 * type or a rule without an action fails the build, the mechanism
 * {@code ConstraintFloorRulesTest} holds on the floors. There is no silent
 * fallback for that reason — a rule of a category nobody wrote a line for
 * gets nothing, and the test says so.</p>
 *
 * <p>Cause types are named by their enum name rather than by
 * {@code FeasibilityAnalyzer.TypeCauseInfaisabilite}: that analyser lives in
 * {@code service.analyse}, which already depends on this package, and naming
 * its enum here would close a package cycle.</p>
 */
public final class BlockerPlaybook {

    /** Query parameter naming the rule an action opens its screen on. */
    private static final String PARAM_REGLE = "regle";

    /**
     * One action type, instantiated on a problem.
     *
     * @param code        stable identifier, the one an MCP client reads
     * @param libelle     the words of the button
     * @param explication one sentence saying why this gesture, in the
     *                    organiser's terms
     * @param route       Angular route of the screen that makes the gesture
     * @param parametres  the query parameters positioning that screen on the
     *                    problem's object; empty when the problem names none
     */
    public record ActionType(
            String code, String libelle, String explication, String route, Map<String, String> parametres) {}

    /**
     * What a problem names, for the actions to be positioned on. Every field
     * may be empty: an aggregate rule names nothing, and its actions then open
     * their screen bare.
     *
     * @param frozenPast the problem sits on seats already started — the
     *                   freeze of ADR 0044, read on each seat's effective
     *                   start: nothing a solve or a hand does changes them
     *                   any more, and no gesture that would is offered
     */
    public record Context(
            Long creneauId,
            LocalDate date,
            List<String> standIds,
            List<String> typologieIds,
            List<String> contrainteIds,
            List<String> animateurIds,
            boolean frozenPast) {

        public static final Context NONE = new Context(null, null, List.of(), List.of(), List.of(), List.of(), false);

        public Context {
            standIds = standIds == null ? List.of() : List.copyOf(standIds);
            typologieIds = typologieIds == null ? List.of() : List.copyOf(typologieIds);
            contrainteIds = contrainteIds == null ? List.of() : List.copyOf(contrainteIds);
            animateurIds = animateurIds == null ? List.of() : List.copyOf(animateurIds);
        }
    }

    // ---- routes -----------------------------------------------------------

    static final String ROUTE_DIAGNOSTIC = "/diagnostic";
    static final String ROUTE_SKILLS = "/competences";
    static final String ROUTE_OPENINGS = "/ouvertures";
    static final String ROUTE_REST_DAYS = "/repos";
    static final String ROUTE_ADJUSTMENTS = "/ad-hoc-constraints";
    static final String ROUTE_ANIMATEURS = "/animateurs";
    static final String ROUTE_LOCKS = "/verrouillages";
    static final String ROUTE_CONSTRAINTS = "/constraints";
    static final String ROUTE_DAY = "/journee";
    static final String ROUTE_TIMESLOTS = "/creneaux";
    static final String ROUTE_TYPOLOGIES = "/typologies";
    static final String ROUTE_SETTINGS = "/parametres";

    // ---- action codes -----------------------------------------------------

    public static final String CODE_BENCH = "VOIR_BANC";
    public static final String CODE_ADD_SKILL = "AJOUTER_COMPETENCE";
    public static final String CODE_LOWER_STAFFING = "BAISSER_EFFECTIF";
    public static final String CODE_REVIEW_DAYS_OFF = "REVOIR_INDISPONIBILITES";
    public static final String CODE_COMPARE_ADJUSTMENTS = "COMPARER_AJUSTEMENTS";
    public static final String CODE_EDIT_ADJUSTMENT = "MODIFIER_AJUSTEMENT";
    public static final String CODE_REMOVE_DAY_OFF = "RETIRER_INDISPONIBILITE";
    public static final String CODE_LIFT_LOCK = "LEVER_VERROU";
    public static final String CODE_REPAIR = "PROPOSER_REPARATION";
    public static final String CODE_LOWER_WEIGHT = "BAISSER_POIDS";
    public static final String CODE_ADJUST_WEIGHT = "AJUSTER_POIDS";
    public static final String CODE_ENTER_MISSING_DATA = "SAISIR_DONNEE_MANQUANTE";
    public static final String CODE_REVIEW_ADJUSTMENTS = "REVOIR_AJUSTEMENTS";
    public static final String CODE_REVIEW_PROFILES = "REVOIR_FICHES";
    public static final String CODE_TYPOLOGIE_CAP = "REGLER_TYPOLOGIE";
    public static final String CODE_MEAL_WINDOW = "REGLER_REPAS";
    public static final String CODE_SHORTEN_VACATION = "RACCOURCIR_VACATION";
    public static final String CODE_SEE_RULE = "VOIR_REGLE";
    public static final String CODE_FROZEN_DAY = "JOURNEE_FIGEE";

    private BlockerPlaybook() {}

    // ---- causes found before a solve --------------------------------------

    private static final Map<String, List<Function<Context, ActionType>>> BY_CAUSE = Map.of(
            "CRENEAU_SOUS_EFFECTIF",
            List.of(
                    BlockerPlaybook::benchAction,
                    BlockerPlaybook::addSkillAction,
                    BlockerPlaybook::lowerStaffingAction,
                    BlockerPlaybook::reviewDaysOffAction),
            "CONTRAINTES_AD_HOC_CONTRADICTOIRES",
            List.of(context -> new ActionType(
                    CODE_COMPARE_ADJUSTMENTS,
                    "Comparer les ajustements en cause",
                    "Ces ajustements ne peuvent pas tenir ensemble : ouvrez-les côte à côte et gardez celui qui"
                            + " compte.",
                    ROUTE_ADJUSTMENTS,
                    idsParam(context.contrainteIds()))),
            "AFFECTATION_FORCEE_JOUR_INDISPONIBLE",
            List.of(
                    context -> editAdjustmentAction(
                            context,
                            "L'affectation forcée tombe sur un jour d'indisponibilité : déplacez-la vers un jour où"
                                    + " la personne est là."),
                    BlockerPlaybook::removeDayOffAction),
            "AFFECTATION_FORCEE_MOTIF_LEGAL",
            List.of(context -> editAdjustmentAction(
                    context,
                    "Une règle légale ne se règle pas : modifiez l'ajustement pour une place que la personne peut"
                            + " tenir, ou supprimez-le.")),
            "AFFECTATION_FORCEE_SIEGE_VERROUILLE",
            List.of(
                    BlockerPlaybook::liftLockAction,
                    context -> editAdjustmentAction(
                            context,
                            "Si le verrou doit rester, c'est l'affectation forcée qui cède : modifiez-la ou"
                                    + " supprimez-la.")));

    /** The cause types the playbook knows, for the exhaustiveness test. */
    public static Set<String> causeTypes() {
        return BY_CAUSE.keySet();
    }

    /**
     * The actions of one cause found before a solve, positioned on what it
     * names. A cause on a day already started gets the one action that says
     * so, rather than gestures that would rewrite a worked day.
     *
     * @param type the {@code TypeCauseInfaisabilite} name
     * @return empty for a type the playbook does not know
     */
    public static List<ActionType> forCause(String type, Context context) {
        List<Function<Context, ActionType>> templates = BY_CAUSE.get(type);
        if (templates == null) {
            return List.of();
        }
        Context target = context == null ? Context.NONE : context;
        if (target.frozenPast()) {
            return List.of(frozenDayAction(target));
        }
        return templates.stream().map(template -> template.apply(target)).toList();
    }

    // ---- rules found in default after a solve -----------------------------

    /** The rules whose lever is specific, first gesture first. */
    private static final Map<String, List<BiFunction<ConstraintDefinition, Context, ActionType>>> BY_RULE = Map.of(
            "posteDoitEtrePourvu",
            List.of(
                    (definition, context) -> benchAction(context),
                    (definition, context) -> addSkillAction(context),
                    (definition, context) -> lowerStaffingAction(context)),
            "animateurDisponible",
            List.of((definition, context) -> reviewProfilesAction(), (definition, context) -> repairAction(context)),
            "pasDeChevauchementHoraire",
            List.of((definition, context) -> repairAction(context)),
            "plafondCreneauxParTypologie",
            List.of((definition, context) -> typologieCapAction(), (definition, context) -> addSkillAction(context)),
            "coupureRepasObligatoire",
            List.of((definition, context) -> mealWindowAction(), (definition, context) -> shortenVacationAction()),
            "maxJoursConsecutifsTravaillesDur",
            List.of((definition, context) -> benchAction(context), (definition, context) -> ruleAction(definition)));

    /**
     * The rules without a lever of their own, by category. Every category of
     * the catalogue has a line; none proposes switching a protected rule off.
     */
    private static final Map<String, List<BiFunction<ConstraintDefinition, Context, ActionType>>> BY_CATEGORY = Map.of(
            "Affectation",
            List.of((definition, context) -> benchAction(context), (definition, context) -> repairAction(context)),
            "Légal (mineurs)",
            List.of((definition, context) -> repairAction(context), (definition, context) -> benchAction(context)),
            "Légal (temps de travail)",
            List.of((definition, context) -> repairAction(context), (definition, context) -> benchAction(context)),
            "Sécurité (mineurs)",
            List.of((definition, context) -> benchAction(context), (definition, context) -> repairAction(context)),
            ConstraintCatalog.CATEGORIE_ORGANISATION_REPAS,
            List.of((definition, context) -> mealWindowAction(), (definition, context) -> shortenVacationAction()),
            "Contraintes ad hoc",
            List.of((definition, context) -> reviewAdjustmentsAction(List.of())),
            "Verrouillage du planning",
            List.of((definition, context) -> liftLockAction(context)),
            ConstraintCatalog.CATEGORIE_QUALITE,
            List.of((definition, context) -> lowerWeightAction(definition)),
            "Préférences",
            List.of((definition, context) -> adjustWeightAction(definition)));

    /** The actions of one rule of the catalogue in default, on breaches still to come, positioned on nothing. */
    public static List<ActionType> forRule(ConstraintDefinition definition, String floorLink) {
        return forRule(definition, floorLink, Context.NONE);
    }

    /**
     * The actions of one rule of the catalogue in default.
     *
     * @param floorLink the route of the screen entering the data whose
     *                  absence makes this rule a floor, {@code null} when it
     *                  is none — the floor then comes first: no solve will
     *                  move those points, entering the data will
     * @param position  what the rule's first breach names — its timeslot,
     *                  day, stand and holder — for the actions to open their
     *                  screen on; {@link Context#NONE} for an aggregate. Its
     *                  {@code frozenPast} says every breach sits on seats
     *                  already started (ADR 0044), and the one action offered
     *                  is then the one that says so
     * @return empty for a rule of a category the playbook has no line for
     */
    public static List<ActionType> forRule(ConstraintDefinition definition, String floorLink, Context position) {
        // Map.of refuses a null key even on get(): a definition missing its
        // name or category finds no line rather than throwing.
        List<BiFunction<ConstraintDefinition, Context, ActionType>> templates =
                definition.name() == null ? null : BY_RULE.get(definition.name());
        if (templates == null && definition.categorie() != null) {
            templates = BY_CATEGORY.get(definition.categorie());
        }
        if (templates == null) {
            return List.of();
        }
        Context target = position == null ? Context.NONE : position;
        if (target.frozenPast()) {
            return List.of(frozenDayAction(target));
        }
        List<ActionType> actions = new ArrayList<>();
        if (floorLink != null && !floorLink.isBlank()) {
            actions.add(new ActionType(
                    CODE_ENTER_MISSING_DATA,
                    "Saisir la donnée manquante",
                    "Cette règle pénalise presque tout faute de donnée : aucune résolution ne fera bouger ces"
                            + " points, la saisie de la donnée le fera.",
                    floorLink,
                    Map.of()));
        }
        for (BiFunction<ConstraintDefinition, Context, ActionType> template : templates) {
            ActionType action = template.apply(definition, target);
            if (actions.stream().noneMatch(existing -> existing.code().equals(action.code()))) {
                actions.add(action);
            }
        }
        // The rule's own advice, word for word, on its first gesture: never two
        // different pieces of advice for the same rule on two screens.
        ActionType first = actions.getFirst();
        actions.set(
                0,
                new ActionType(
                        first.code(), first.libelle(), definition.remediation(), first.route(), first.parametres()));
        return List.copyOf(actions);
    }

    /** Every action the playbook can produce, bare — what the route test reads. */
    public static List<ActionType> everyAction() {
        Context full = new Context(
                1L, LocalDate.of(2026, 7, 1), List.of("S1"), List.of("T1"), List.of("C1"), List.of("A1"), false);
        List<ActionType> actions = new ArrayList<>();
        for (String type : BY_CAUSE.keySet()) {
            actions.addAll(forCause(type, full));
            actions.addAll(forCause(type, Context.NONE));
        }
        actions.add(frozenDayAction(full));
        for (ConstraintDefinition definition : ConstraintCatalog.definitions()) {
            actions.addAll(forRule(definition, null));
            actions.addAll(forRule(definition, null, full));
        }
        return actions;
    }

    // ---- the action types themselves ----------------------------------------

    private static ActionType benchAction(Context context) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("onglet", "banc");
        if (context.creneauId() != null) {
            params.put("creneau", String.valueOf(context.creneauId()));
        }
        return new ActionType(
                CODE_BENCH,
                "Voir qui pourrait venir",
                "Le banc de touche liste qui n'est de service nulle part sur ce créneau, et ce qui l'empêche de"
                        + " tenir la place.",
                ROUTE_DIAGNOSTIC,
                params);
    }

    private static ActionType addSkillAction(Context context) {
        return new ActionType(
                CODE_ADD_SKILL,
                "Ajouter une compétence",
                "Faire apprécier la typologie du stand par d'autres animateurs élargit le vivier de qui peut le"
                        + " tenir.",
                ROUTE_SKILLS,
                context.typologieIds().isEmpty()
                        ? Map.of()
                        : Map.of("typologies", String.join(",", context.typologieIds())));
    }

    private static ActionType lowerStaffingAction(Context context) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("vue", "saisie");
        putDate(params, context);
        putStand(params, context);
        return new ActionType(
                CODE_LOWER_STAFFING,
                "Baisser l'effectif demandé",
                "Moins de places ouvertes ce jour-là : l'écran Ouvertures montre ce que le planning retiendra avant"
                        + " d'enregistrer.",
                ROUTE_OPENINGS,
                params);
    }

    private static ActionType reviewDaysOffAction(Context context) {
        return new ActionType(
                CODE_REVIEW_DAYS_OFF,
                "Revoir les indisponibilités du jour",
                "Qui est indisponible ce jour-là, et qui se repose : une déclaration périmée suffit parfois à"
                        + " vider un créneau.",
                ROUTE_REST_DAYS,
                context.date() == null
                        ? Map.of()
                        : Map.of("date", context.date().toString()));
    }

    private static ActionType editAdjustmentAction(Context context, String explanation) {
        return new ActionType(
                CODE_EDIT_ADJUSTMENT,
                "Modifier l'ajustement",
                explanation,
                ROUTE_ADJUSTMENTS,
                context.contrainteIds().size() == 1
                        ? Map.of("edit", context.contrainteIds().getFirst())
                        : idsParam(context.contrainteIds()));
    }

    private static ActionType removeDayOffAction(Context context) {
        return new ActionType(
                CODE_REMOVE_DAY_OFF,
                "Retirer l'indisponibilité",
                "Si la personne est finalement là ce jour-là, corrigez sa fiche : l'affectation forcée redevient"
                        + " tenable.",
                ROUTE_ANIMATEURS,
                context.animateurIds().size() == 1
                        ? Map.of("edit", context.animateurIds().getFirst())
                        : Map.of());
    }

    private static ActionType liftLockAction(Context context) {
        return new ActionType(
                CODE_LIFT_LOCK,
                "Lever le verrou",
                "C'est un verrou posé à la main : levez-le depuis l'écran Verrouillages s'il n'a plus lieu d'être.",
                ROUTE_LOCKS,
                context.animateurIds().isEmpty()
                        ? Map.of()
                        : Map.of("animateur", String.join(",", context.animateurIds())));
    }

    private static ActionType repairAction(Context context) {
        Map<String, String> params = new LinkedHashMap<>();
        putDate(params, context);
        putStand(params, context);
        return new ActionType(
                CODE_REPAIR,
                "Proposer une réparation",
                "Sur la Journée, un clic sur la place en cause ouvre « Pourquoi lui ? », qui propose qui peut la"
                        + " reprendre sans écart dur.",
                ROUTE_DAY,
                params);
    }

    private static ActionType lowerWeightAction(ConstraintDefinition definition) {
        return new ActionType(
                CODE_LOWER_WEIGHT,
                "Baisser son poids",
                "Une règle de qualité se dose : à 1, elle cède devant les autres.",
                ROUTE_CONSTRAINTS,
                Map.of(PARAM_REGLE, definition.name()));
    }

    private static ActionType adjustWeightAction(ConstraintDefinition definition) {
        return new ActionType(
                CODE_ADJUST_WEIGHT,
                "Ajuster son poids",
                "Une préférence départage deux plannings valides : baissez son poids si l'écart vous convient.",
                ROUTE_CONSTRAINTS,
                Map.of(PARAM_REGLE, definition.name()));
    }

    private static ActionType ruleAction(ConstraintDefinition definition) {
        return new ActionType(
                CODE_SEE_RULE,
                "Voir la règle",
                "Sa ligne sur l'écran Contraintes dit ce qu'elle mesure et ce qui la règle.",
                ROUTE_CONSTRAINTS,
                Map.of(PARAM_REGLE, definition.name()));
    }

    private static ActionType reviewAdjustmentsAction(List<String> contrainteIds) {
        return new ActionType(
                CODE_REVIEW_ADJUSTMENTS,
                "Revoir l'ajustement",
                "C'est un ajustement écrit à la main : revoyez-le ou supprimez-le depuis l'écran Ajustements"
                        + " manuels.",
                ROUTE_ADJUSTMENTS,
                idsParam(contrainteIds));
    }

    private static ActionType reviewProfilesAction() {
        return new ActionType(
                CODE_REVIEW_PROFILES,
                "Revoir les fiches",
                "Une déclaration d'indisponibilité a peut-être changé : la fiche de la personne fait foi.",
                ROUTE_ANIMATEURS,
                Map.of());
    }

    private static ActionType typologieCapAction() {
        return new ActionType(
                CODE_TYPOLOGIE_CAP,
                "Régler le plafond de la typologie",
                "Le plafond de créneaux se règle typologie par typologie.",
                ROUTE_TYPOLOGIES,
                Map.of());
    }

    private static ActionType mealWindowAction() {
        return new ActionType(
                CODE_MEAL_WINDOW,
                "Régler la fenêtre repas",
                "La fenêtre repas et sa durée se règlent dans les paramètres légaux de l'édition.",
                ROUTE_SETTINGS,
                Map.of("onglet", "legaux"));
    }

    private static ActionType shortenVacationAction() {
        return new ActionType(
                CODE_SHORTEN_VACATION,
                "Raccourcir la vacation",
                "Une vacation plus courte laisse la place à la pause ou au repas.",
                ROUTE_TIMESLOTS,
                Map.of());
    }

    private static ActionType frozenDayAction(Context context) {
        return new ActionType(
                CODE_FROZEN_DAY,
                "Voir la journée",
                "Cette journée a commencé : son plan est figé tel que travaillé, rien ne s'y corrige plus."
                        + " Les jours à venir, eux, se règlent comme d'habitude.",
                ROUTE_DAY,
                context.date() == null
                        ? Map.of()
                        : Map.of("date", context.date().toString()));
    }

    /** {@code date=}, the day the Journée, Ouvertures and Repos screens open on. */
    private static void putDate(Map<String, String> params, Context context) {
        if (context.date() != null) {
            params.put("date", context.date().toString());
        }
    }

    /** {@code stand=}, by its exact id — only when the problem names one stand, never a guess among several. */
    private static void putStand(Map<String, String> params, Context context) {
        if (context.standIds().size() == 1) {
            params.put("stand", context.standIds().getFirst());
        }
    }

    /** {@code ids=a,b}, the list the Ajustements manuels screen narrows itself to. */
    private static Map<String, String> idsParam(List<String> contrainteIds) {
        return contrainteIds.isEmpty() ? Map.of() : Map.of("ids", String.join(",", contrainteIds));
    }
}
