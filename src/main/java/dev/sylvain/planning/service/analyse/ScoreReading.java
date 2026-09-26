package dev.sylvain.planning.service.analyse;

import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.ConstraintDiagnostic;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.ContributionAdHoc;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService.PlanningDiagnostic;
import dev.sylvain.planning.solver.ConstraintCatalog;
import dev.sylvain.planning.solver.ConstraintCatalog.ConstraintDefinition;
import dev.sylvain.planning.solver.ConstraintCatalog.Niveau;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The score read out in a few French sentences — what a « 0hard/-1234medium »
 * means to an organiser who never heard of a medium level.
 *
 * <p>Written from templates, never by a language model: the same diagnostic
 * always gives the same text, nothing leaves the server to be written, and no
 * sentence can name a cause the diagnostic does not hold. That is the doctrine
 * of the feasibility causes and of {@link ViolationFormatter}, which already
 * speak French server-side, so the Solveur page, the Diagnostic, the
 * Comparateur and the MCP tool all say exactly the same thing.</p>
 *
 * <p>The structure is fixed, and a sentence appears only when it has something
 * to say: the legal verdict, the coverage, the organisation (medium), the
 * floors, the comfort (soft), the hand-entered exceptions — six at most, and a
 * comparison with the plan a solve replaced when that plan is known. A rule is
 * named by its {@link ConstraintDefinition#libelleCourt() short label}, never by
 * its technical name; a day by its date. No animateur is ever named, so the
 * text travels over MCP without filtering.</p>
 *
 * <p>Pure and static: no CDI, no clock, no database — the tests build the
 * diagnostics by hand. The JSON keys of the two records ({@code sujet},
 * {@code niveau}, {@code texte}, {@code liens}…) are the contract the screens
 * read, hence their French names.</p>
 */
public final class ScoreReading {

    /** What a sentence talks about, in the fixed reading order. The values are on the wire. */
    public enum ReadingSubject {
        VERDICT,
        COUVERTURE,
        ORGANISATION,
        PLANCHER,
        CONFORT,
        AJUSTEMENTS,
        COMPARAISON
    }

    /** How the screen colours a sentence. */
    public enum ReadingTone {
        OK,
        INFO,
        ATTENTION,
        BLOQUANT
    }

    /**
     * One link inside a sentence: {@code texte} appears verbatim in the
     * sentence, and the screen turns that occurrence into a link.
     *
     * @param texte      the words of the sentence the link sits on
     * @param route      Angular route of the screen it opens
     * @param parametres query parameters positioning that screen
     * @param fragment   anchor on that screen, {@code null} when none
     */
    public record ReadingLink(String texte, String route, Map<String, String> parametres, String fragment) {}

    /**
     * One sentence of the reading.
     *
     * @param liens the links it carries, in the order their words appear in
     *              {@code texte}
     */
    public record ScoreSentence(ReadingSubject sujet, ReadingTone niveau, String texte, List<ReadingLink> liens) {}

    /** More sentences than this and the reading stops being the way in to the tables. */
    public static final int MAX_SENTENCES = 6;

    /** Rules named by the organisation sentence, heaviest first. */
    static final int MAX_MEDIUM_RULES = 3;

    /** Share of the comfort points the first soft rule must carry for its sentence to be worth reading. */
    static final double COMFORT_THRESHOLD = 0.5;

    private static final String ROUTE_RULES = "/regles";
    private static final String ROUTE_DAY = "/journee";
    private static final String ROUTE_ADJUSTMENTS = "/ad-hoc-constraints";
    private static final String SEATS_RULE = "posteDoitEtrePourvu";
    private static final String ECART = "écart";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE dd/MM", Locale.FRENCH);

    private ScoreReading() {}

    /** The reading of one diagnostic, with no comparison and no rule known to be off. */
    public static List<ScoreSentence> read(PlanningDiagnostic diagnostic) {
        return read(diagnostic, Optional.empty(), Set.of());
    }

    /**
     * The reading of one diagnostic.
     *
     * @param previous      the diagnostic of the plan this one replaced, when
     *                      known: adds the comparison sentence
     * @param disabledRules names of the rules switched off on the plan
     *                      diagnosed — they score nothing, so they never
     *                      appear, but a hard one switched off is recalled by
     *                      the verdict, which cannot vouch for it
     * @return an empty list when there is no diagnostic to read
     */
    public static List<ScoreSentence> read(
            PlanningDiagnostic diagnostic, Optional<PlanningDiagnostic> previous, Collection<String> disabledRules) {
        if (diagnostic == null) {
            return List.of();
        }
        List<ScoreSentence> sentences = new ArrayList<>();
        sentences.add(verdict(diagnostic, disabledRules == null ? Set.of() : disabledRules));
        coverage(diagnostic).ifPresent(sentences::add);
        organisation(diagnostic).ifPresent(sentences::add);
        floors(diagnostic).ifPresent(sentences::add);
        comfort(diagnostic).ifPresent(sentences::add);
        adjustments(diagnostic).ifPresent(sentences::add);
        previous.flatMap(before -> compare(diagnostic, before)).ifPresent(sentences::add);
        return capped(sentences);
    }

    /**
     * The same reading with the comparison sentence appended — the form the
     * Solveur page gets right after a solve, when the plan it replaced is
     * known. The verdict and the rest are taken from {@code diagnostic} as
     * already read.
     */
    public static List<ScoreSentence> withComparison(PlanningDiagnostic diagnostic, PlanningDiagnostic previous) {
        List<ScoreSentence> sentences =
                new ArrayList<>(diagnostic.lecture() == null ? List.of() : diagnostic.lecture());
        sentences.removeIf(sentence -> sentence.sujet() == ReadingSubject.COMPARAISON);
        if (previous != null) {
            compare(diagnostic, previous).ifPresent(sentences::add);
        }
        return capped(sentences);
    }

    /**
     * At most {@link #MAX_SENTENCES}: the comparison is the one sentence that
     * can push the count past the cap, and comfort is the one the reader loses
     * least by not reading.
     */
    private static List<ScoreSentence> capped(List<ScoreSentence> sentences) {
        if (sentences.size() > MAX_SENTENCES) {
            sentences.removeIf(sentence -> sentence.sujet() == ReadingSubject.CONFORT);
        }
        return List.copyOf(sentences.subList(0, Math.min(MAX_SENTENCES, sentences.size())));
    }

    // ---- 1. legal verdict ---------------------------------------------------

    private static ScoreSentence verdict(PlanningDiagnostic diagnostic, Collection<String> disabledRules) {
        String reminder = disabledReminder(disabledRules);
        List<Rule> failing = rules(diagnostic, Niveau.HARD).stream()
                .filter(rule -> rule.score().hardScore() < 0)
                .sorted(Comparator.comparingLong((Rule rule) -> rule.score().hardScore())
                        .thenComparing(Comparator.comparingInt(Rule::matchCount).reversed())
                        .thenComparing(Rule::name))
                .toList();
        if (failing.isEmpty() && diagnostic.hardScore() >= 0) {
            // With a hard rule switched off, « toutes » would vouch for a rule
            // nothing checked: said, and never in the colour of good news.
            String text = reminder.isEmpty()
                    ? "Le planning respecte toutes les règles impératives."
                    : "Le planning respecte les règles impératives vérifiées." + reminder;
            return new ScoreSentence(
                    ReadingSubject.VERDICT,
                    reminder.isEmpty() ? ReadingTone.OK : ReadingTone.ATTENTION,
                    text,
                    List.of());
        }
        if (failing.isEmpty()) {
            // A negative hard score no rule accounts for: said, without inventing a culprit.
            return new ScoreSentence(
                    ReadingSubject.VERDICT,
                    ReadingTone.BLOQUANT,
                    "Des règles impératives ne sont pas respectées : le planning ne devrait pas être publié en l'état."
                            + reminder,
                    List.of());
        }
        Rule heaviest = failing.getFirst();
        int n = failing.size();
        String start =
                n == 1 ? "1 règle impérative n'est pas respectée" : n + " règles impératives ne sont pas respectées";
        String name = quote(heaviest.definition());
        String ecarts = count(heaviest.matchCount(), ECART);
        String detail = n == 1 ? " (" + name + ", " + ecarts + ")" : " (surtout " + name + ", " + ecarts + ")";
        String text = start + " : le planning ne devrait pas être publié en l'état" + detail + "." + reminder;
        return new ScoreSentence(
                ReadingSubject.VERDICT, ReadingTone.BLOQUANT, text, List.of(ruleLink(heaviest.definition())));
    }

    /**
     * « 1 règle légale est désactivée pour cette édition » — the guard of the
     * Contraintes screen, said where the verdict is read, and extended to
     * every hard rule: a seat nobody has to fill, or an exception nobody has
     * to honour, is just as absent from a zero hard score. A rule the
     * catalogue ships off is not somebody's decision and is not recalled.
     */
    static String disabledReminder(Collection<String> disabledRules) {
        int legal = 0;
        int otherHard = 0;
        for (String ruleName : new LinkedHashSet<>(disabledRules)) {
            ConstraintDefinition definition = ConstraintCatalog.PAR_NOM.get(ruleName);
            if (isRecalledWhenDisabled(definition)) {
                if (definition.legale()) {
                    legal++;
                } else {
                    otherHard++;
                }
            }
        }
        StringBuilder reminder = new StringBuilder();
        if (legal > 0) {
            reminder.append(legalReminder(legal));
        }
        if (otherHard > 0) {
            reminder.append(otherHardReminder(otherHard, legal > 0));
        }
        return reminder.toString();
    }

    /** A hard rule the catalogue ships on: switching it off was somebody's decision. */
    private static boolean isRecalledWhenDisabled(ConstraintDefinition definition) {
        return definition != null && definition.activeByDefault() && definition.niveau() == Niveau.HARD;
    }

    private static String legalReminder(int legal) {
        if (legal == 1) {
            return " 1 règle légale est désactivée pour cette édition : le verdict ne la vérifie pas.";
        }
        return " " + legal + " règles légales sont désactivées pour cette édition : le verdict ne les vérifie pas.";
    }

    private static String otherHardReminder(int otherHard, boolean afterLegal) {
        if (otherHard == 1) {
            return " 1 " + (afterLegal ? "autre " : "") + "règle impérative est désactivée pour cette"
                    + " édition : le verdict ne la vérifie pas.";
        }
        return " " + otherHard + (afterLegal ? " autres" : "") + " règles impératives sont désactivées"
                + " pour cette édition : le verdict ne les vérifie pas.";
    }

    // ---- 2. coverage --------------------------------------------------------

    private static Optional<ScoreSentence> coverage(PlanningDiagnostic diagnostic) {
        int empty = diagnostic.postesNonPourvus();
        if (empty <= 0) {
            return Optional.empty();
        }
        Optional<Map.Entry<String, Integer>> worst = (diagnostic.pivotEcarts() == null
                        ? List.<PivotEcarts.Cellule>of()
                        : diagnostic.pivotEcarts())
                .stream()
                        .filter(cell -> SEATS_RULE.equals(cell.contrainte()) && cell.axe() == PivotEcarts.Axe.JOUR)
                        .filter(cell -> dayLabel(cell.cle()) != null)
                        .sorted(Comparator.comparingInt(PivotEcarts.Cellule::ecarts)
                                .reversed()
                                .thenComparing(PivotEcarts.Cellule::cle))
                        .map(cell -> Map.entry(cell.cle(), Math.min(cell.ecarts(), empty)))
                        .findFirst();
        String start = empty == 1 ? "1 place reste vide" : number(empty) + " places restent vides";
        ReadingTone tone = diagnostic.hardScore() < 0 ? ReadingTone.BLOQUANT : ReadingTone.ATTENTION;
        if (worst.isEmpty()) {
            return Optional.of(new ScoreSentence(ReadingSubject.COUVERTURE, tone, start + ".", List.of()));
        }
        String date = worst.get().getKey();
        String day = dayLabel(date);
        int onThatDay = worst.get().getValue();
        String rest;
        if (empty == 1) {
            rest = ", le " + day;
        } else if (onThatDay >= empty) {
            rest = ", toutes le " + day;
        } else {
            rest = ", dont " + number(onThatDay) + " le " + day;
        }
        ReadingLink link = new ReadingLink(day, ROUTE_DAY, Map.of("date", date), null);
        return Optional.of(new ScoreSentence(ReadingSubject.COUVERTURE, tone, start + rest + ".", List.of(link)));
    }

    // ---- 3. organisation (medium) -------------------------------------------

    private static Optional<ScoreSentence> organisation(PlanningDiagnostic diagnostic) {
        List<Rule> penalising = rules(diagnostic, Niveau.MEDIUM).stream()
                .filter(rule -> !rule.floor() && rule.score().mediumScore() < 0)
                .sorted(Comparator.comparingLong((Rule rule) -> rule.score().mediumScore())
                        .thenComparing(Rule::name))
                .toList();
        if (penalising.isEmpty()) {
            return Optional.empty();
        }
        // The shares are taken on the rules outside the floors, penalties only:
        // a floor would crush every other share, and a reward would push the
        // total past a hundred.
        long total = penalising.stream()
                .mapToLong(rule -> -rule.score().mediumScore())
                .sum();
        List<Rule> named = penalising.subList(0, Math.min(MAX_MEDIUM_RULES, penalising.size()));
        List<String> pieces = new ArrayList<>();
        List<ReadingLink> links = new ArrayList<>();
        for (int i = 0; i < named.size(); i++) {
            Rule rule = named.get(i);
            String share = share(-rule.score().mediumScore(), total);
            String detail = i == 0
                    ? " (" + share + " des points, " + count(rule.matchCount(), "cas") + ")"
                    : " (" + share + ", " + count(rule.matchCount(), "cas") + ")";
            pieces.add(quote(rule.definition()) + detail);
            links.add(ruleLink(rule.definition()));
        }
        String text = penalising.size() == 1
                ? "L'organisation n'est pénalisée que par " + pieces.getFirst() + "."
                : "L'organisation est pénalisée surtout par " + enumerate(pieces) + ".";
        return Optional.of(new ScoreSentence(ReadingSubject.ORGANISATION, ReadingTone.INFO, text, links));
    }

    /**
     * Rounded down, so the shares named never add up past a hundred; « moins
     * de 1 % » rather than a zero that reads as « nothing ».
     */
    static String share(long points, long total) {
        if (total <= 0) {
            return "0 %";
        }
        long percent = points * 100 / total;
        return percent == 0 ? "moins de 1 %" : percent + " %";
    }

    // ---- 4. floors ----------------------------------------------------------

    private static Optional<ScoreSentence> floors(PlanningDiagnostic diagnostic) {
        long medium = -Math.min(0L, diagnostic.plancherMedium());
        long soft = -Math.min(0L, diagnostic.plancherSoft());
        if (medium == 0 && soft == 0) {
            return Optional.empty();
        }
        List<Rule> floorRules = penalisingFloorRules(diagnostic);
        List<String> points = new ArrayList<>();
        if (medium > 0) {
            points.add(number(medium) + (medium == 1 ? " point d'organisation" : " points d'organisation"));
        }
        if (soft > 0) {
            points.add(number(soft) + (soft == 1 ? " point de confort" : " points de confort"));
        }
        StringBuilder text = new StringBuilder(enumerate(points))
                .append(medium + soft == 1 ? " vient" : " viennent")
                .append(" de règles qu'aucune résolution ne fera bouger, faute de données");
        List<ReadingLink> links = new ArrayList<>();
        if (!floorRules.isEmpty()) {
            List<String> pieces = new ArrayList<>();
            for (Rule rule : floorRules) {
                pieces.add(floorPiece(rule));
                links.add(ruleLink(rule.definition()));
            }
            text.append(" : ").append(enumerate(pieces));
        }
        text.append('.');
        return Optional.of(new ScoreSentence(ReadingSubject.PLANCHER, ReadingTone.INFO, text.toString(), links));
    }

    /** The medium and soft rules stuck at a floor that costs points, heaviest first. */
    private static List<Rule> penalisingFloorRules(PlanningDiagnostic diagnostic) {
        List<Rule> floorRules = new ArrayList<>();
        floorRules.addAll(rules(diagnostic, Niveau.MEDIUM));
        floorRules.addAll(rules(diagnostic, Niveau.SOFT));
        return floorRules.stream()
                .filter(Rule::floor)
                .filter(rule -> rule.score().mediumScore() < 0 || rule.score().softScore() < 0)
                .sorted(Comparator.comparingLong((Rule rule) -> rule.score().mediumScore())
                        .thenComparingLong(rule -> rule.score().softScore())
                        .thenComparing(Rule::name))
                .toList();
    }

    private static String floorPiece(Rule rule) {
        String reason = reason(rule.diagnostic().plancher().libelle());
        return quote(rule.definition()) + (reason.isEmpty() ? "" : " (" + reason + ")");
    }

    /**
     * « Aucun souhait déclaré sur les fiches animateur : la règle… » → « aucun
     * souhait déclaré sur les fiches animateur ». Empty when nothing is left
     * once the sentence is cut — a label reduced to « . » says nothing.
     */
    static String reason(String label) {
        if (label == null || label.isBlank()) {
            return "";
        }
        String shortened = label.strip();
        int colon = shortened.indexOf(" :");
        if (colon > 0) {
            shortened = shortened.substring(0, colon);
        }
        while (shortened.endsWith(".")) {
            shortened = shortened.substring(0, shortened.length() - 1).strip();
        }
        if (shortened.isEmpty()) {
            return "";
        }
        return Character.toLowerCase(shortened.charAt(0)) + shortened.substring(1);
    }

    // ---- 5. comfort (soft) --------------------------------------------------

    private static Optional<ScoreSentence> comfort(PlanningDiagnostic diagnostic) {
        List<Rule> penalising = rules(diagnostic, Niveau.SOFT).stream()
                .filter(rule -> !rule.floor() && rule.score().softScore() < 0)
                .sorted(Comparator.comparingLong((Rule rule) -> rule.score().softScore())
                        .thenComparing(Rule::name))
                .toList();
        if (penalising.isEmpty()) {
            return Optional.empty();
        }
        long total =
                penalising.stream().mapToLong(rule -> -rule.score().softScore()).sum();
        Rule first = penalising.getFirst();
        long points = -first.score().softScore();
        if (points < total * COMFORT_THRESHOLD) {
            return Optional.empty();
        }
        String text = "Côté confort, " + quote(first.definition()) + " pèse le plus (" + share(points, total)
                + " des points de confort, " + count(first.matchCount(), "cas") + ").";
        return Optional.of(new ScoreSentence(
                ReadingSubject.CONFORT, ReadingTone.INFO, text, List.of(ruleLink(first.definition()))));
    }

    // ---- 6. hand-entered exceptions -----------------------------------------

    /**
     * Counted and attributed to the rules they broke — never with their free
     * text, which the author may have written with somebody's name in it. The
     * rules are listed by label and the exceptions by id, so the sentence does
     * not depend on the order the analysis met them in.
     */
    private static Optional<ScoreSentence> adjustments(PlanningDiagnostic diagnostic) {
        List<ContributionAdHoc> involved =
                diagnostic.contraintesAdHocEnCause() == null ? List.of() : diagnostic.contraintesAdHocEnCause();
        if (involved.isEmpty()) {
            return Optional.empty();
        }
        int breaches = involved.stream().mapToInt(ContributionAdHoc::violations).sum();
        Map<String, ConstraintDefinition> brokenRules = new TreeMap<>();
        for (ContributionAdHoc contribution : involved) {
            for (String ruleName : contribution.contraintes()) {
                ConstraintDefinition definition = ConstraintCatalog.PAR_NOM.get(ruleName);
                if (definition != null) {
                    brokenRules.putIfAbsent(definition.libelleCourt(), definition);
                }
            }
        }
        String subject = involved.size() == 1
                ? "1 ajustement manuel est en cause"
                : involved.size() + " ajustements manuels sont en cause";
        String ids = String.join(
                ",",
                involved.stream()
                        .map(ContributionAdHoc::contrainteId)
                        .filter(id -> id != null && !id.isBlank())
                        .sorted()
                        .toList());
        String linkWords = involved.size() == 1 ? "ajustement manuel" : "ajustements manuels";
        List<ReadingLink> links = new ArrayList<>();
        links.add(new ReadingLink(linkWords, ROUTE_ADJUSTMENTS, ids.isEmpty() ? Map.of() : Map.of("ids", ids), null));
        StringBuilder text = new StringBuilder(subject).append(" : ").append(count(breaches, ECART));
        if (!brokenRules.isEmpty()) {
            text.append(" sur ")
                    .append(enumerate(brokenRules.values().stream()
                            .map(ScoreReading::quote)
                            .toList()));
            brokenRules.values().forEach(definition -> links.add(ruleLink(definition)));
        }
        text.append('.');
        return Optional.of(new ScoreSentence(ReadingSubject.AJUSTEMENTS, ReadingTone.BLOQUANT, text.toString(), links));
    }

    // ---- comparison -----------------------------------------------------------

    /**
     * « Par rapport au plan précédent : 4 places vides en moins, … » — what a
     * solve changed, in the terms of the reading rather than as two scores.
     * Counted in matches, not in points: the weights may have moved between
     * the two runs, and « 12 cas en moins » stays true whatever they were.
     */
    static Optional<ScoreSentence> compare(PlanningDiagnostic after, PlanningDiagnostic before) {
        if (after == null || before == null) {
            return Optional.empty();
        }
        List<String> pieces = new ArrayList<>();
        List<ReadingLink> links = new ArrayList<>();
        int emptyDelta = after.postesNonPourvus() - before.postesNonPourvus();
        if (emptyDelta != 0) {
            pieces.add(number(Math.abs(emptyDelta)) + delta(emptyDelta, " place vide", " places vides"));
        }
        int hardDelta = failingHardRules(after) - failingHardRules(before);
        if (hardDelta != 0) {
            pieces.add(Math.abs(hardDelta)
                    + delta(hardDelta, " règle impérative en défaut", " règles impératives en défaut"));
        }
        RuleMoves moves = RuleMoves.between(matchesByRule(before, Niveau.MEDIUM), matchesByRule(after, Niveau.MEDIUM));
        if (moves.improved != null) {
            ConstraintDefinition definition = ConstraintCatalog.PAR_NOM.get(moves.improved);
            pieces.add(quote(definition) + " en progrès (" + count(-moves.bestGain, "cas") + " en moins)");
            links.add(ruleLink(definition));
        }
        if (moves.worsened != null) {
            ConstraintDefinition definition = ConstraintCatalog.PAR_NOM.get(moves.worsened);
            pieces.add(quote(definition) + " en recul (" + count(moves.worstLoss, "cas") + " en plus)");
            links.add(ruleLink(definition));
        }
        ReadingTone tone = tone(after.score(), before.score());
        if (pieces.isEmpty()) {
            return Optional.of(new ScoreSentence(
                    ReadingSubject.COMPARAISON,
                    tone,
                    "Par rapport au plan précédent : ni les places vides, ni les règles impératives, ni les règles "
                            + "d'organisation ne changent.",
                    List.of()));
        }
        return Optional.of(new ScoreSentence(
                ReadingSubject.COMPARAISON, tone, "Par rapport au plan précédent : " + enumerate(pieces) + ".", links));
    }

    /** « place vide en moins », « places vides en plus »: the noun agreed with the size of a non-zero change. */
    private static String delta(int change, String singular, String plural) {
        return (Math.abs(change) == 1 ? singular : plural) + (change < 0 ? " en moins" : " en plus");
    }

    /**
     * The medium rule that gained the most matches back and the one that lost
     * the most, over the rules both analyses measured: a rule missing on one
     * side is unknown there, not at zero. Ties go to the first name.
     */
    private static final class RuleMoves {
        private String improved;
        private int bestGain;
        private String worsened;
        private int worstLoss;

        static RuleMoves between(Map<String, Integer> beforeByRule, Map<String, Integer> afterByRule) {
            Set<String> names = new LinkedHashSet<>(beforeByRule.keySet());
            names.retainAll(afterByRule.keySet());
            RuleMoves moves = new RuleMoves();
            for (String ruleName : names.stream().sorted().toList()) {
                moves.consider(
                        ruleName, afterByRule.getOrDefault(ruleName, 0) - beforeByRule.getOrDefault(ruleName, 0));
            }
            return moves;
        }

        private void consider(String ruleName, int delta) {
            if (delta < bestGain) {
                bestGain = delta;
                improved = ruleName;
            }
            if (delta > worstLoss) {
                worstLoss = delta;
                worsened = ruleName;
            }
        }
    }

    private static ReadingTone tone(String after, String before) {
        HardMediumSoftScore scoreAfter = parse(after);
        HardMediumSoftScore scoreBefore = parse(before);
        if (scoreAfter == null || scoreBefore == null) {
            return ReadingTone.INFO;
        }
        int comparison = scoreAfter.compareTo(scoreBefore);
        if (comparison > 0) {
            return ReadingTone.OK;
        }
        return comparison < 0 ? ReadingTone.ATTENTION : ReadingTone.INFO;
    }

    private static int failingHardRules(PlanningDiagnostic diagnostic) {
        return (int) rules(diagnostic, Niveau.HARD).stream()
                .filter(rule -> rule.score().hardScore() < 0)
                .count();
    }

    private static Map<String, Integer> matchesByRule(PlanningDiagnostic diagnostic, Niveau niveau) {
        Map<String, Integer> matches = new LinkedHashMap<>();
        for (Rule rule : rules(diagnostic, niveau)) {
            // A floor moves with the referential, not with the solve.
            if (!rule.floor()) {
                matches.put(rule.name(), rule.matchCount());
            }
        }
        return matches;
    }

    // ---- shared -------------------------------------------------------------

    /** One rule of the diagnostic joined with its definition, its score parsed once. */
    private record Rule(ConstraintDefinition definition, ConstraintDiagnostic diagnostic, HardMediumSoftScore score) {

        String name() {
            return definition.name();
        }

        int matchCount() {
            return diagnostic.matchCount();
        }

        boolean floor() {
            return diagnostic.plancher() != null;
        }
    }

    /**
     * The rules of one level the catalogue knows and whose score reads: an
     * unknown name — a rule since removed — or an unreadable score is skipped
     * rather than named by its identifier.
     */
    private static List<Rule> rules(PlanningDiagnostic diagnostic, Niveau niveau) {
        List<Rule> rules = new ArrayList<>();
        for (ConstraintDiagnostic constraint :
                diagnostic.contraintes() == null ? List.<ConstraintDiagnostic>of() : diagnostic.contraintes()) {
            ConstraintDefinition definition = ConstraintCatalog.PAR_NOM.get(constraint.name());
            HardMediumSoftScore score = parse(constraint.score());
            if (definition != null && definition.niveau() == niveau && score != null) {
                rules.add(new Rule(definition, constraint, score));
            }
        }
        return rules;
    }

    private static HardMediumSoftScore parse(String score) {
        if (score == null || score.isBlank()) {
            return null;
        }
        try {
            return HardMediumSoftScore.parseScore(score);
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    private static String quote(ConstraintDefinition definition) {
        return "«\u00A0" + definition.libelleCourt() + "\u00A0»";
    }

    private static ReadingLink ruleLink(ConstraintDefinition definition) {
        // The rule's own panel on « Règles du planning », which picks the tab from the rule.
        return new ReadingLink(definition.libelleCourt(), ROUTE_RULES, Map.of("regle", definition.name()), null);
    }

    /** « samedi 12/07 », or {@code null} when the key is not an ISO date. */
    private static String dayLabel(String iso) {
        try {
            return LocalDate.parse(iso).format(DAY);
        } catch (DateTimeParseException | NullPointerException _) {
            return null;
        }
    }

    /** « 3 écarts », « 1 écart », « 2 cas ». */
    private static String count(long n, String word) {
        if (n == 1 || word.endsWith("s")) {
            return number(n) + " " + word;
        }
        return number(n) + " " + word + "s";
    }

    /** « 5 000 » with a non-breaking space, the same on every JDK and every locale data version. */
    static String number(long n) {
        String digits = Long.toString(Math.abs(n));
        StringBuilder grouped = new StringBuilder();
        for (int i = 0; i < digits.length(); i++) {
            if (i > 0 && (digits.length() - i) % 3 == 0) {
                grouped.append('\u00A0');
            }
            grouped.append(digits.charAt(i));
        }
        return (n < 0 ? "-" : "") + grouped;
    }

    /** « a », « a et b », « a, b et c ». */
    private static String enumerate(List<String> pieces) {
        if (pieces.size() <= 1) {
            return pieces.isEmpty() ? "" : pieces.getFirst();
        }
        return String.join(", ", pieces.subList(0, pieces.size() - 1)) + " et " + pieces.getLast();
    }
}
