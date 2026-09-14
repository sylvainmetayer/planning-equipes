package dev.sylvain.planning.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Everything is partitioned by edition (see {@code docs/decisions/0001-cloisonnement-par-edition.md}), and a
 * statement that forgets its {@code edition_id} predicate does not show: it
 * throws nothing, it simply reads or writes at the neighbours'.
 *
 * <p>It happened. Demoting the ninja typologie was written
 * {@code UPDATE typologie SET ninja = FALSE WHERE ninja AND id <> ?}, with no
 * edition, while the unique index it protects has been scoped per edition since
 * {@code V33}: marking a ninja on one edition erased the ninja of every other
 * one, and since that flag feeds the ordering of the construction heuristic,
 * the next solve of the neighbouring edition started somewhere else without
 * anything reporting it.</p>
 *
 * <p>This test reads the SQL of the whole backend and refuses a statement
 * aiming at a business table without an edition predicate. The list of
 * exceptions is short and each one is justified here: it is the only place
 * where "this statement is deliberately cross-edition" is a verified claim
 * rather than a comment.</p>
 *
 * <p><b>The scan follows indirection.</b> It used to read only the literals
 * written between the parentheses of the call, so a statement handed over by a
 * variable was invisible — 21 % of them, concentrated in the two services that
 * write the persisted plan and its snapshots. It now resolves the argument
 * expression itself: literals and text blocks, the {@code static final String}
 * constants of the same file, the local variables of the enclosing scope, the
 * concatenation of any of those, and the SQL parameter of a private helper
 * (resolved at that helper's own call sites, which a private method has all of
 * in its own file). What it still cannot follow — a statement assembled by a
 * {@code StringBuilder}, by {@code String.join}, in a loop, handed over
 * through a public API, built from a literal followed by a method call
 * ({@code "…".concat(TABLE)}), or held by a variable reassigned after its
 * declaration — is <b>reported</b> rather than skipped: it fails this test
 * unless it is named in {@link #INDIRECTIONS_ASSUMEES} with a reason.</p>
 *
 * <p>Every one of those refusals has a fixture below, and so does every case
 * the scan is supposed to <em>accept</em>: a scan that shouted at everything
 * would look exactly as green as one that read everything, and the difference
 * between the two is the whole point.</p>
 */
class IsolationEditionStructurelleTest {

    private static final Path SOURCES = Path.of("src/main/java/dev/sylvain/planning");

    /**
     * Every table carrying an {@code edition_id} column (migrations V33/V36 and
     * every table created since). The names are the ones the migrations use:
     * {@code chaqueTableTouchéeEstClassée} below refuses any other name the
     * backend queries, so a table renamed or added without passing here is a
     * failure rather than a silent hole.
     */
    private static final List<String> TABLES_METIER = List.of(
            "stand",
            "animateur",
            "creneau",
            "emplacement",
            "typologie",
            "contrainte_ad_hoc",
            "contrainte_animateur",
            "constraint_toggle",
            "ponderation_contrainte",
            "verrouillage_planning",
            "parametres_legaux",
            "parametres_solveur",
            "poste_affectation",
            "planning_resolution",
            "stand_typologie",
            "animateur_competence",
            "animateur_souhait",
            "animateur_jour_indispo",
            "stand_indisponibilite",
            "stand_ouverture",
            "stand_horaire",
            "stand_horaire_fenetre",
            "journee_type",
            "journee_type_vacation",
            "journee_type_date",
            "creneau_stand_ouvert",
            "demande_echange",
            "parametres_echange",
            "espace_session",
            "espace_acces",
            "plan_snapshot",
            "publication_destinataire",
            "declaration_disponibilite",
            "parametres_collecte",
            "confirmation_planning",
            "parametres_notifications",
            "notification_planifiee",
            "journal_action");

    /**
     * The tables the backend queries <b>outside</b> any edition, and why. An
     * entry here means "no edition predicate will ever be asked of a statement
     * naming this table", so it must stay a short and argued list.
     *
     * <ul>
     *   <li>{@code edition} — the partition itself. It carries no
     *       {@code edition_id}: it <em>is</em> the edition.</li>
     *   <li>{@code backup_settings} — one row for the whole instance: the
     *       nightly dump saves the database, not an edition.</li>
     *   <li>{@code horloge_jour_j} — the simulated clock of the instance. A
     *       demo that moves the date moves it for everybody, on purpose.</li>
     *   <li>{@code kpi_historique} — deliberately cross-edition, reads
     *       <b>and</b> deletion. It is the history one reads to confront two
     *       editions, and it stores the edition name alongside the id so a row
     *       survives the deletion of its edition. The deletion follows: the
     *       screen that shows the rows shows every edition's, edition column
     *       included, so the row the operator deletes is the row they picked;
     *       and a row orphaned by the deletion of its edition could not be
     *       cleaned up at all by an edition-scoped {@code DELETE}. The id is a
     *       server-wide {@code BIGSERIAL}, so it names one row on its own.</li>
     *   <li>{@code solver_job} — the solve queue is global by design (one
     *       solve at a time for the whole JVM, see the ADR §5); the job stores
     *       the edition it targets, it is not partitioned by it.</li>
     *   <li>{@code creneau_remap} — the temporary mapping table of
     *       {@code EditionRepository.copyCreneaux}, created {@code ON COMMIT
     *       DROP} by that one transaction and carrying two columns, neither of
     *       them {@code edition_id}. It cannot hold the predicate, so asking
     *       one of it would only push the statement into the exception
     *       list.</li>
     * </ul>
     */
    private static final List<String> TABLES_HORS_EDITION =
            List.of("edition", "backup_settings", "horloge_jour_j", "kpi_historique", "solver_job", "creneau_remap");

    /**
     * The five deliberately cross-edition statements, and why.
     *
     * <ul>
     *   <li>The espace animateur token arrives on a public URL, with no
     *       {@code X-Edition-Id} to believe: it is globally unique precisely so
     *       it can name the edition on its own, the caller carrying on inside
     *       {@code EditionContext.executeIn}.</li>
     *   <li>The ICS subscription token, for the very same reason: it reaches
     *       the server on a calendar client's bare {@code GET}, which carries
     *       no header anybody may trust.</li>
     *   <li>The e-mail address collision is checked when the "trusted header"
     *       mode boots, which has no edition to consider and wants to know
     *       whether the collision exists anywhere at all.</li>
     *   <li>{@code PlanSnapshotService.listAllEditions} lists every edition's
     *       snapshots for the A/B comparator: since a variant <b>is</b> another
     *       edition, scoping this listing would hide exactly the pair the user
     *       asked to compare.</li>
     *   <li>{@code PlanSnapshotService.loadAllEditions} reads one of them by
     *       id for the same comparator — snapshot ids are a single server-wide
     *       {@code BIGSERIAL}, so an id names one snapshot on its own. Both are
     *       reads: restoring a snapshot stays edition-scoped
     *       ({@code restaurer} goes through {@code load}).</li>
     *   <li>{@code JournalActionRepository.purgeAvant} drops the history lines
     *       that have aged out, across every edition at once. It is run by the
     *       nightly job, which has no edition of its own, and scoping it would
     *       leave the editions nobody visits growing forever — the retention
     *       is a property of the table, not of an edition. Writing every other
     *       statement of that repository through {@code prepareScoped} is what
     *       keeps the exception to this one line.</li>
     * </ul>
     */
    private static final List<String> EXCEPTIONS_ASSUMEES = List.of(
            "SELECT edition_id, id, email FROM animateur WHERE access_token = ?",
            "SELECT edition_id, id FROM animateur WHERE abonnement_token = ?",
            "SELECT 1 FROM animateur WHERE lower(email) = lower(?) LIMIT 1",
            "SELECT s.id, s.libelle, s.automatique, s.score, s.nombre_affectations, s.cree_le, s.edition_id,"
                    + " s.publie_le, e.nom AS edition_nom, e.reference_modifie_le , s.kpi FROM plan_snapshot s"
                    + " LEFT JOIN edition e ON e.id = s.edition_id ORDER BY s.cree_le DESC, s.id DESC",
            "SELECT s.id, s.libelle, s.automatique, s.score, s.nombre_affectations, s.cree_le, s.edition_id,"
                    + " s.publie_le, e.nom AS edition_nom, e.reference_modifie_le , s.contenu, s.kpi"
                    + " FROM plan_snapshot s LEFT JOIN edition e ON e.id = s.edition_id WHERE s.id = ?",
            "DELETE FROM journal_action WHERE survenu_le < ?");

    /**
     * The call sites whose SQL the scan cannot resolve, keyed by
     * {@code File.java#method} <b>and the very fragment</b> it could not read,
     * with the reason each is acceptable and how many calls it covers.
     *
     * <p>Both halves of the key matter. Keying on the method alone would bless
     * every future unreadable statement of that method — a second one, on
     * another fragment, would go through in silence, which is the blind spot
     * this scan exists to close. The count closes the last of it: a third
     * {@code delete} handed an unreadable {@code sql} makes
     * {@link #chaqueIndirectionAssumeeCorrespondAUnAppelReel} red rather than
     * riding on the two that are argued below.</p>
     *
     * <p>All three are {@link JdbcEditionScope} itself, and for the same
     * reason: it is <b>the</b> generic helper, so its {@code sql} is a
     * parameter of a public method and its callers live in every repository of
     * the package. They are not lost for it — {@code prepareScoped(…)} and
     * {@code scope.delete(…)} are both anchors of this scan, so each caller's
     * statement is read at the caller's own call site, where it is written.</p>
     *
     * <p>Anything else landing here is a hole: either the statement is written
     * in a form the scan should learn to follow, or it must be rewritten into
     * one it already follows. Parking a repository here would give back
     * exactly the blind spot this scan was extended to close.</p>
     */
    private record Indirection(int appels, String motif) {}

    private static final Map<String, Indirection> INDIRECTIONS_ASSUMEES = Map.of(
            "JdbcEditionScope.java#prepareScoped [sql]",
            new Indirection(1, "the generic helper: every caller's SQL is read at its own prepareScoped(…) call site"),
            "JdbcEditionScope.java#delete [sql]",
            new Indirection(2, "the generic helper: every caller's SQL is read at its own scope.delete(…) call site"));

    /**
     * Where a statement can be read from, and which argument carries the SQL.
     *
     * <p>{@code scope.delete(…)} is an anchor of its own because
     * {@link JdbcEditionScope#delete(String, String)} takes the statement from
     * its caller: without it, those four DELETEs would be read nowhere.</p>
     */
    private record Anchor(Pattern pattern, int argIndex) {}

    private static final List<Anchor> ANCHORS = List.of(
            new Anchor(Pattern.compile("prepareScoped\\("), 1),
            new Anchor(Pattern.compile("prepareStatement\\("), 0),
            new Anchor(Pattern.compile("scope\\.delete\\("), 0));

    private static final Pattern COLONNES_INSERT = Pattern.compile("insert\\s+into\\s+\\w+\\s*\\(([^)]*)\\)");
    private static final Pattern DECLARATION = Pattern.compile("\\bString\\s+([A-Za-z_$][\\w$]*)\\s*=");
    private static final Pattern METHODE =
            Pattern.compile("([a-zA-Z_$][\\w$]*)\\s*\\(([^()]*)\\)\\s*(?:throws[^{;]*)?\\{");

    /**
     * What {@link #METHODE} matches that is not a method: {@code catch
     * (SQLException e) {} reads exactly like a one-parameter declaration, and
     * {@code if (o instanceof Stand s) {} like another. Left in, they become
     * the innermost {@code MethodSpan} around a statement written inside them:
     * {@code site()} then reads {@code MonDepot.java#catch} — the key of
     * {@link #INDIRECTIONS_ASSUMEES} and of the failure message — and
     * {@link #atCallSites} gives up on a genuinely private helper because the
     * {@code catch} line carries no {@code private}.
     */
    private static final Set<String> MOTS_CLES_BLOC = Set.of(
            "if",
            "for",
            "while",
            "switch",
            "catch",
            "synchronized",
            "try",
            "do",
            "else",
            "return",
            "new",
            "record",
            "yield",
            "assert");

    /** Keywords that introduce a <em>type</em> whose name would otherwise pass for a method. */
    private static final Set<String> MOTS_CLES_TYPE = Set.of("record", "new", "enum", "class", "interface");

    private static final Pattern PARAMETRE =
            Pattern.compile("(?:final\\s+)?[A-Za-z_$][\\w$<>\\[\\].,\\s]*\\s+[A-Za-z_$][\\w$]*");
    private static final Pattern IDENTIFIANT = Pattern.compile("[A-Za-z_$][\\w$]*");
    private static final Pattern TABLE =
            Pattern.compile("(?:from|join|into|update)\\s+([a-z_][a-z_0-9]*)", Pattern.CASE_INSENSITIVE);

    /** Words {@link #TABLE} picks up behind {@code DO UPDATE SET}, not table names. */
    private static final Set<String> MOTS_CLES_SQL = Set.of("set", "select");

    /**
     * One statement the backend prepares: what could be resolved of its SQL,
     * and the expression fragments that could not.
     */
    private record Enonce(String file, int line, String method, String sql, List<String> irresoluble) {

        String site() {
            return file + "#" + method;
        }

        /** The key of {@link #INDIRECTIONS_ASSUMEES}: the site <b>and</b> what could not be read there. */
        String indirection() {
            return site() + " " + irresoluble();
        }
    }

    /* ---------------------------- The scan itself --------------------------- */

    /**
     * The source with every comment, string literal and text block replaced by
     * spaces of the same length. Braces, parentheses and commas can then be
     * counted without a {@code ", ("} inside a statement being mistaken for
     * code, while every offset still points at the same character of the
     * original.
     */
    private static String skeleton(String source) {
        char[] code = source.toCharArray();
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            int end;
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                end = source.indexOf('\n', i);
                end = end < 0 ? n : end;
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                end = source.indexOf("*/", i + 2);
                end = end < 0 ? n : end + 2;
            } else if (c == '"' && i + 2 < n && source.charAt(i + 1) == '"' && source.charAt(i + 2) == '"') {
                end = source.indexOf("\"\"\"", i + 3);
                end = end < 0 ? n : end + 3;
            } else if (c == '"' || c == '\'') {
                int j = i + 1;
                while (j < n && source.charAt(j) != c) {
                    j += source.charAt(j) == '\\' ? 2 : 1;
                }
                end = Math.min(j + 1, n);
            } else {
                i++;
                continue;
            }
            for (int k = i; k < end; k++) {
                if (source.charAt(k) != '\n') {
                    code[k] = ' ';
                }
            }
            i = end;
        }
        return new String(code);
    }

    /**
     * A {@code String x = …;} declaration, be it a class constant or a local.
     * {@code finale} decides two things: a {@code final} declaration can be
     * read from above it when it sits at class level (legal Java), and it can
     * never be reassigned between its declaration and its use.
     */
    private record Declaration(String name, int at, int from, int to, int depth, boolean finale) {}

    /** A method declaration, its parameters and the span of its body. */
    private record MethodSpan(
            String name, int at, int paramsFrom, int paramsTo, int bodyFrom, int bodyTo, boolean prive) {}

    /** One backend source file, pre-chewed: skeleton, brace depths, declarations. */
    private static final class Source {

        private final String name;
        private final String text;
        private final String code;
        private final int[] depth;
        private final List<Declaration> declarations = new ArrayList<>();
        private final List<MethodSpan> methods = new ArrayList<>();

        private Source(String name, String text) {
            this.name = name;
            this.text = text;
            this.code = skeleton(text);
            this.depth = new int[text.length() + 1];
            int niveau = 0;
            for (int i = 0; i < text.length(); i++) {
                depth[i] = niveau;
                niveau += code.charAt(i) == '{' ? 1 : code.charAt(i) == '}' ? -1 : 0;
            }
            depth[text.length()] = niveau;
            Matcher declaration = DECLARATION.matcher(code);
            while (declaration.find()) {
                int fin = code.indexOf(';', declaration.end());
                if (fin > 0) {
                    declarations.add(new Declaration(
                            declaration.group(1),
                            declaration.start(),
                            declaration.end(),
                            fin,
                            depth[declaration.start()],
                            prefixe(declaration.start()).contains("final")));
                }
            }
            Matcher method = METHODE.matcher(code);
            while (method.find()) {
                if (method.group(2).isBlank() || !isParameterList(this, method.start(2), method.end(2))) {
                    continue;
                }
                String prefixe = prefixe(method.start());
                if (MOTS_CLES_BLOC.contains(method.group(1)) || MOTS_CLES_TYPE.contains(dernierMot(prefixe))) {
                    continue;
                }
                int corps = code.indexOf('{', method.end(2));
                methods.add(new MethodSpan(
                        method.group(1),
                        method.start(),
                        method.start(2),
                        method.end(2),
                        corps,
                        closing(corps),
                        prefixe.contains("private")));
            }
        }

        /** What is written on the same line before {@code position} — the modifiers, in practice. */
        private String prefixe(int position) {
            return text.substring(text.lastIndexOf('\n', position) + 1, position);
        }

        private static String dernierMot(String prefixe) {
            String[] mots = prefixe.trim().split("\\s+");
            return mots[mots.length - 1];
        }

        /** Index of the {@code )} closing the argument list opened before {@code from}. */
        private int closingParenthesis(int from) {
            int niveau = 1;
            int i = from;
            while (niveau > 0 && i < text.length()) {
                char c = code.charAt(i++);
                niveau += c == '(' ? 1 : c == ')' ? -1 : 0;
            }
            return i - 1;
        }

        /** Index just after the {@code }} closing the block opened at {@code open}. */
        private int closing(int open) {
            int niveau = 1;
            int i = open + 1;
            while (niveau > 0 && i < text.length()) {
                char c = code.charAt(i++);
                niveau += c == '{' ? 1 : c == '}' ? -1 : 0;
            }
            return i;
        }

        /**
         * The declaration of {@code name} visible at {@code position}: the
         * nearest one before it whose enclosing block has not closed in
         * between. That last condition is what keeps a {@code String sql} of a
         * previous method from being read as this method's — resolving to a
         * neighbour's statement would make the scan <em>more</em> permissive,
         * which is the one failure mode it must not have.
         *
         * <p>Failing that, a {@code final} field declared <b>after</b> the
         * method that uses it, which Java allows: refusing it would fail the
         * build on a perfectly scoped repository whose constants sit at the
         * bottom of the file, and the only way out would be to park it in
         * {@link #INDIRECTIONS_ASSUMEES} — that is, to switch the guard off.
         * The forward search is restricted to class-level {@code final}: a
         * local declared later is not visible, and a mutable field could have
         * been assigned anything in between.</p>
         */
        private Declaration visible(String name, int position) {
            Declaration trouvee = null;
            for (Declaration declaration : declarations) {
                if (declaration.at() >= position || !declaration.name().equals(name)) {
                    continue;
                }
                boolean encoreOuverte = true;
                for (int i = declaration.at(); i < position && encoreOuverte; i++) {
                    encoreOuverte = depth[i] >= declaration.depth();
                }
                if (encoreOuverte) {
                    trouvee = declaration;
                }
            }
            if (trouvee != null) {
                return trouvee;
            }
            for (Declaration declaration : declarations) {
                if (declaration.at() > position
                        && declaration.name().equals(name)
                        && declaration.depth() <= 1
                        && declaration.finale()) {
                    return declaration;
                }
            }
            return null;
        }

        /**
         * Is {@code name} assigned again between its declaration and
         * {@code position}? {@link #DECLARATION} only sees {@code String x =
         * …}; a later {@code x = …} carries no type and would otherwise be
         * invisible, leaving the scan to answer confidently with the first
         * value while the branch actually taken hands over another statement.
         * The scan must say what it cannot read rather than trust the first
         * assignment.
         */
        private boolean reassigned(String name, Declaration declaration, int position) {
            if (declaration.finale()) {
                return false;
            }
            int from = Math.min(declaration.to(), position);
            int to = Math.max(declaration.to(), position);
            Matcher affectation = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*\\+?=[^=]")
                    .matcher(code);
            return affectation.find(from) && affectation.start() < to;
        }

        private MethodSpan enclosing(int position) {
            MethodSpan trouvee = null;
            for (MethodSpan method : methods) {
                if (position > method.bodyFrom()
                        && position < method.bodyTo()
                        && (trouvee == null || method.bodyFrom() > trouvee.bodyFrom())) {
                    trouvee = method;
                }
            }
            return trouvee;
        }

        private int line(int position) {
            return (int) text.substring(0, position)
                            .chars()
                            .filter(c -> c == '\n')
                            .count()
                    + 1;
        }
    }

    /** The spans of {@code [from, to)} separated by top-level {@code separator}. */
    private static List<int[]> split(Source source, int from, int to, char separator) {
        List<int[]> spans = new ArrayList<>();
        int niveau = 0;
        int debut = from;
        for (int i = from; i < to; i++) {
            char c = source.code.charAt(i);
            if (c == '(' || c == '[' || c == '{') {
                niveau++;
            } else if (c == ')' || c == ']' || c == '}') {
                niveau--;
            } else if (c == separator && niveau == 0) {
                spans.add(new int[] {debut, i});
                debut = i + 1;
            }
        }
        spans.add(new int[] {debut, to});
        return spans;
    }

    /** What an expression resolved to, and the fragments it could not resolve. */
    private record Resolution(String sql, List<String> irresoluble) {}

    /**
     * Resolves the expression in {@code [from, to)} into the SQL it produces.
     * Handles a literal, a text block, a name declared in this file, and any
     * concatenation of those. Everything else — a method call, a ternary, a
     * name declared elsewhere — comes back in {@code irresoluble}, never
     * dropped.
     *
     * <p>A fragment counts as a literal only when the skeleton of its whole
     * span is blank, that is when <b>nothing follows</b> the closing quote.
     * {@code "DELETE FROM ".concat(TABLE)} and {@code "…".formatted(x)} start
     * like a literal and are not one: reading them as one would drop the
     * constant that carries the table name and let a statement through with no
     * business table left in it — silently, which is the one failure mode this
     * scan must not have.</p>
     */
    private static Resolution resolve(Source source, int from, int to, Set<String> seen) {
        StringBuilder sql = new StringBuilder();
        List<String> irresoluble = new ArrayList<>();
        for (int[] span : split(source, from, to, '+')) {
            int a = span[0];
            int b = span[1];
            while (a < b && Character.isWhitespace(source.text.charAt(a))) {
                a++;
            }
            while (b > a && Character.isWhitespace(source.text.charAt(b - 1))) {
                b--;
            }
            if (a >= b) {
                continue;
            }
            String morceau = source.text.substring(a, b);
            boolean litteral = source.code.substring(a, b).isBlank();
            if (litteral && morceau.startsWith("\"\"\"")) {
                int fin = source.text.indexOf("\"\"\"", a + 3);
                sql.append(source.text, a + 3, fin < 0 ? b : fin).append(' ');
            } else if (litteral && morceau.startsWith("\"")) {
                sql.append(unescape(morceau.substring(1, morceau.length() - 1))).append(' ');
            } else if (IDENTIFIANT.matcher(morceau).matches()) {
                Declaration declaration = source.visible(morceau, a);
                if (declaration == null || seen.contains(morceau)) {
                    irresoluble.add(morceau);
                } else if (source.reassigned(morceau, declaration, a)) {
                    irresoluble.add(morceau + " (réaffecté)");
                } else {
                    // A copy per branch, not one set for the whole expression:
                    // it must stop a constant defined in terms of itself, not a
                    // constant legitimately used twice in the same concatenation.
                    Set<String> branche = new HashSet<>(seen);
                    branche.add(morceau);
                    Resolution referencee = resolve(source, declaration.from(), declaration.to(), branche);
                    sql.append(referencee.sql()).append(' ');
                    irresoluble.addAll(referencee.irresoluble());
                }
            } else {
                irresoluble.add(morceau.replaceAll("\\s+", " "));
            }
        }
        return new Resolution(sql.toString().replaceAll("\\s+", " ").trim(), irresoluble);
    }

    private static String unescape(String literal) {
        return literal.replace("\\n", "\n")
                .replace("\\t", " ")
                .replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }

    /** Is {@code [from, to)} a parameter list — {@code Type name, Type name} — rather than arguments? */
    private static boolean isParameterList(Source source, int from, int to) {
        for (int[] span : split(source, from, to, ',')) {
            if (!PARAMETRE
                    .matcher(source.text.substring(span[0], span[1]).trim())
                    .matches()) {
                return false;
            }
        }
        return true;
    }

    /**
     * The statement is the {@code sql} parameter of a private helper: read it
     * at that helper's call sites instead. Only for a <b>private</b> method —
     * a package-private or public one can be called from another file, and
     * resolving from the callers this file happens to hold would claim a
     * coverage the scan does not have.
     */
    private static List<Enonce> atCallSites(Source source, Resolution resolution, int position) {
        if (!resolution.sql().isBlank() || resolution.irresoluble().size() != 1) {
            return List.of();
        }
        String nom = resolution.irresoluble().get(0);
        MethodSpan hote = source.enclosing(position);
        if (!IDENTIFIANT.matcher(nom).matches() || hote == null || !hote.prive()) {
            return List.of();
        }
        List<int[]> parametres = split(source, hote.paramsFrom(), hote.paramsTo(), ',');
        int index = -1;
        for (int i = 0; i < parametres.size(); i++) {
            if (source.text
                    .substring(parametres.get(i)[0], parametres.get(i)[1])
                    .trim()
                    .endsWith(" " + nom)) {
                index = i;
            }
        }
        if (index < 0) {
            return List.of();
        }
        List<Enonce> enonces = new ArrayList<>();
        Matcher appel =
                Pattern.compile("\\b" + Pattern.quote(hote.name()) + "\\s*\\(").matcher(source.code);
        while (appel.find()) {
            if (appel.start() == hote.at()) {
                continue;
            }
            int fin = source.closingParenthesis(appel.end());
            if (source.text.substring(appel.end(), fin).isBlank() || isParameterList(source, appel.end(), fin)) {
                continue;
            }
            List<int[]> arguments = split(source, appel.end(), fin, ',');
            if (arguments.size() <= index) {
                continue;
            }
            Resolution argument =
                    resolve(source, arguments.get(index)[0], arguments.get(index)[1], new HashSet<>());
            MethodSpan appelant = source.enclosing(appel.start());
            enonces.add(new Enonce(
                    source.name,
                    source.line(appel.start()),
                    appelant == null ? hote.name() : appelant.name(),
                    argument.sql(),
                    argument.irresoluble()));
        }
        return enonces;
    }

    /** Every statement the backend prepares, wherever its SQL is written. */
    private static List<Enonce> enonces(Path racine) throws IOException {
        List<Enonce> enonces = new ArrayList<>();
        try (Stream<Path> files = Files.walk(racine)) {
            for (Path file :
                    files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                enonces.addAll(enonces(new Source(file.getFileName().toString(), Files.readString(file))));
            }
        }
        return enonces;
    }

    private static List<Enonce> enonces(Source source) {
        List<Enonce> enonces = new ArrayList<>();
        for (Anchor anchor : ANCHORS) {
            Matcher appel = anchor.pattern().matcher(source.code);
            while (appel.find()) {
                int fin = source.closingParenthesis(appel.end());
                if (isParameterList(source, appel.end(), fin)) {
                    continue;
                }
                List<int[]> arguments = split(source, appel.end(), fin, ',');
                if (arguments.size() <= anchor.argIndex()) {
                    continue;
                }
                int[] argument = arguments.get(anchor.argIndex());
                Resolution resolution = resolve(source, argument[0], argument[1], new HashSet<>());
                List<Enonce> auxAppelants = atCallSites(source, resolution, appel.start());
                if (!auxAppelants.isEmpty()) {
                    enonces.addAll(auxAppelants);
                    continue;
                }
                MethodSpan method = source.enclosing(appel.start());
                enonces.add(new Enonce(
                        source.name,
                        source.line(appel.start()),
                        method == null ? "?" : method.name(),
                        resolution.sql(),
                        resolution.irresoluble()));
            }
        }
        return enonces;
    }

    /* ------------------------------- Verdicts ------------------------------- */

    /**
     * The edition must be <b>filtered on</b>, not merely read: {@code SELECT
     * edition_id … WHERE token = ?} brings the column back without partitioning
     * anything at all.
     */
    private static boolean isScoped(String sql) {
        String bas = sql.toLowerCase(Locale.ROOT);
        if (bas.contains("edition_id = ?")) {
            return true;
        }
        Matcher colonnes = COLONNES_INSERT.matcher(bas);
        return colonnes.find() && colonnes.group(1).contains("edition_id");
    }

    private static boolean targetsBusinessTable(String sql) {
        String bas = sql.toLowerCase(Locale.ROOT);
        return TABLES_METIER.stream().anyMatch(table -> bas.matches(".*\\b" + table + "\\b.*"));
    }

    /**
     * What the scan holds against a statement, or {@code null} when it holds
     * nothing.
     *
     * <p>A partially resolved statement that already carries {@code edition_id
     * = ?} passes: concatenating an unknown fragment can add a predicate, never
     * take one away. One that does not is reported as an unfollowed
     * indirection rather than as a missing predicate — the scan says what it
     * could not read, it does not guess.</p>
     */
    private static String grief(Enonce enonce) {
        if (isScoped(enonce.sql())) {
            return null;
        }
        if (!enonce.irresoluble().isEmpty() || enonce.sql().isBlank()) {
            return INDIRECTIONS_ASSUMEES.containsKey(enonce.indirection())
                    ? null
                    : "indirection non suivie " + enonce.irresoluble() + " — lu : « " + enonce.sql() + " »";
        }
        if (!targetsBusinessTable(enonce.sql()) || EXCEPTIONS_ASSUMEES.contains(enonce.sql())) {
            return null;
        }
        return "prédicat edition_id absent — « " + enonce.sql() + " »";
    }

    /* --------------------------------- Tests -------------------------------- */

    @Test
    void touteRequeteSurUneTableMetierPorteSonPredicatDEdition() throws IOException {
        List<String> manquants = new ArrayList<>();
        for (Enonce enonce : enonces(SOURCES)) {
            String grief = grief(enonce);
            if (grief != null) {
                manquants.add(enonce.file() + ":" + enonce.line() + " (" + enonce.method() + ") " + grief);
            }
        }

        assertThat(manquants)
                .as("requêtes visant une table métier sans prédicat edition_id — soit il manque, "
                        + "soit l'omission est délibérée et sa raison doit rejoindre EXCEPTIONS_ASSUMEES ; "
                        + "une requête que le scan ne sait pas lire rejoint INDIRECTIONS_ASSUMEES, motif écrit")
                .isEmpty();
    }

    /**
     * The test above is only worth what its scan is worth: a regular expression
     * that stopped matching anything would turn green for the worst possible
     * reason.
     */
    @Test
    void leScanTrouveBienLeSqlDuBackend() throws IOException {
        assertThat(enonces(SOURCES))
                .as("nombre de requêtes préparées trouvées dans le backend")
                .hasSizeGreaterThan(150);
    }

    /**
     * And it is worth what it <b>resolves</b>: the whole point of following
     * indirection is that a statement handed over by a variable is read, not
     * counted. Anything left unresolved must be a named, argued exception.
     */
    @Test
    void leScanResoutLeSqlPasseParVariable() throws IOException {
        List<String> aveugles = enonces(SOURCES).stream()
                .filter(enonce -> enonce.sql().isBlank())
                .map(Enonce::indirection)
                .distinct()
                .toList();

        assertThat(aveugles)
                .as("appels dont le scan ne lit aucun SQL")
                .allSatisfy(cle -> assertThat(INDIRECTIONS_ASSUMEES).containsKey(cle));
    }

    /** An exception that no longer exists in the code must leave the list. */
    @Test
    void chaqueExceptionAssumeeCorrespondAUneRequeteReelle() throws IOException {
        List<String> toutes = enonces(SOURCES).stream().map(Enonce::sql).toList();

        assertThat(toutes).containsAll(EXCEPTIONS_ASSUMEES);
    }

    /**
     * Same for an unfollowed indirection, counted call by call: a helper
     * rewritten must leave the list, and a statement added to an already
     * excused method must <b>not</b> ride on its excuse. The exception list is
     * the only place where "this is deliberate" is a verified claim; an entry
     * that can widen on its own is a comment again.
     */
    @Test
    void chaqueIndirectionAssumeeCorrespondAUnAppelReel() throws IOException {
        Map<String, Long> aveugles = new TreeMap<>();
        for (Enonce enonce : enonces(SOURCES)) {
            if (!enonce.irresoluble().isEmpty() && !isScoped(enonce.sql())) {
                aveugles.merge(enonce.indirection(), 1L, Long::sum);
            }
        }
        Map<String, Long> attendus = new TreeMap<>();
        INDIRECTIONS_ASSUMEES.forEach((cle, indirection) -> attendus.put(cle, (long) indirection.appels()));

        assertThat(aveugles)
                .as("appels aveugles par site et par fragment — une indirection assumée couvre "
                        + "exactement le nombre d'appels déclaré, ni un de moins (helper réécrit) "
                        + "ni un de plus (requête ajoutée sous une excuse existante)")
                .isEqualTo(attendus);
    }

    /**
     * And the excuse does not spread to the neighbours: the same method, one
     * other unreadable fragment, must still be reported.
     */
    @Test
    void uneIndirectionAssumeeNeCouvrePasUnAutreFragment() {
        Enonce autreFragment = new Enonce(
                "JdbcEditionScope.java", 155, "delete", "DELETE FROM stand WHERE id = ?", List.of("suffixe"));
        Enonce celuiQuiEstAssume = new Enonce("JdbcEditionScope.java", 155, "delete", "", List.of("sql"));

        assertThat(grief(autreFragment))
                .as("un fragment irrésoluble non déclaré, dans une méthode par ailleurs excusée")
                .isNotNull();
        assertThat(grief(celuiQuiEstAssume))
                .as("le fragment effectivement déclaré, lui, passe")
                .isNull();
    }

    /**
     * A table named by a <b>resolvable prepared statement</b> is either
     * partitioned by edition or listed as global with a reason. Without this,
     * the guard above stays silent on a whole table: it used to watch
     * {@code animateur_indisponibilite} and {@code espace_code}, two names no
     * migration ever created, while the real {@code animateur_jour_indispo}
     * and {@code espace_acces} went unwatched.
     *
     * <p>The wording is deliberately that narrow, because the net is. Two
     * families stay out of it and must not be claimed: SQL run through a plain
     * {@code Statement} — {@code DatabaseDumpService} replaying a dump,
     * {@code EditionRepository} creating its temporary mapping table — is not
     * an anchor of this scan at all; and a statement whose table name <em>is</em>
     * the unknown ({@code "SELECT … FROM " + table}) is skipped right below,
     * since there is no name to classify. {@code EditionRepository.copyTable}
     * and {@code DatabaseDumpService.TABLES} are therefore checked by reading,
     * not by this test.</p>
     */
    @Test
    void chaqueTableInterrogeeEstClassee() throws IOException {
        Set<String> inconnues = new TreeSet<>();
        for (Enonce enonce : enonces(SOURCES)) {
            if (!enonce.irresoluble().isEmpty()) {
                continue;
            }
            Matcher table = TABLE.matcher(enonce.sql());
            while (table.find()) {
                String nom = table.group(1).toLowerCase(Locale.ROOT);
                if (!MOTS_CLES_SQL.contains(nom)
                        && !TABLES_METIER.contains(nom)
                        && !TABLES_HORS_EDITION.contains(nom)) {
                    inconnues.add(nom);
                }
            }
        }

        assertThat(inconnues)
                .as("tables interrogées par le backend et classées nulle part — "
                        + "cloisonnée par édition (TABLES_METIER) ou globale avec son motif (TABLES_HORS_EDITION)")
                .isEmpty();
    }

    /** A global table that nothing queries any more must leave the list. */
    @Test
    void chaqueTableHorsEditionEstEncoreInterrogee() throws IOException {
        Set<String> interrogees = new LinkedHashSet<>();
        for (Enonce enonce : enonces(SOURCES)) {
            Matcher table = TABLE.matcher(enonce.sql());
            while (table.find()) {
                interrogees.add(table.group(1).toLowerCase(Locale.ROOT));
            }
        }

        assertThat(interrogees).containsAll(TABLES_HORS_EDITION);
    }

    /* --------------------- The guard of the guard --------------------------- */

    /**
     * A statement handed over by a local variable, by a constant, or by the
     * concatenation of the two, without an edition predicate: each must be
     * caught. Before this scan followed indirection all three passed, which is
     * exactly the hole these fixtures prove closed.
     */
    @Test
    void leScanVoitUneRequeteNonCloisonneePasseeParVariable(@TempDir Path racine) throws IOException {
        assertThat(griefs(racine, "Locale", """
                class Locale {
                    void lire() {
                        String sql = "SELECT id FROM stand WHERE nom = ?";
                        scope.prepareScoped(connection, sql);
                    }
                }
                """))
                .as("SQL passé par une variable locale")
                .isNotEmpty();

        assertThat(griefs(racine, "Constante", """
                class Constante {
                    private static final String SELECT_SQL = "SELECT id FROM stand WHERE nom = ?";
                    void lire() {
                        scope.prepareScoped(connection, SELECT_SQL);
                    }
                }
                """))
                .as("SQL passé par une constante du même fichier")
                .isNotEmpty();

        assertThat(griefs(racine, "Concatenation", """
                class Concatenation {
                    private static final String SELECT_SQL = "SELECT id FROM stand";
                    void lire() {
                        scope.prepareScoped(connection, SELECT_SQL + " ORDER BY nom");
                    }
                }
                """))
                .as("SQL concaténé d'une constante et d'un littéral")
                .isNotEmpty();
    }

    /**
     * And the same three, scoped this time, must pass: a scan that flagged
     * everything would be no more useful than one that flagged nothing.
     */
    @Test
    void leScanLaissePasserUneRequeteCloisonneePasseeParVariable(@TempDir Path racine) throws IOException {
        assertThat(griefs(racine, "Cloisonne", """
                class Cloisonne {
                    private static final String SELECT_SQL = "SELECT id FROM stand WHERE edition_id = ?";
                    void lire() {
                        String sql = "DELETE FROM stand WHERE edition_id = ? AND id = ?";
                        scope.prepareScoped(connection, sql);
                        scope.prepareScoped(connection, SELECT_SQL + " ORDER BY nom");
                    }
                }
                """)).isEmpty();
    }

    /**
     * A form the scan cannot follow — assembled by a {@code StringBuilder}, by
     * {@code String.join}, or in a loop — must be <b>reported</b>. Skipping it
     * would be the same silence the scan was extended to break, and it is the
     * silence that hid the ninja bug for two releases.
     */
    @Test
    void leScanSignaleUneFormeQuIlNeSaitPasSuivre(@TempDir Path racine) throws IOException {
        assertThat(griefs(racine, "Builder", """
                class Builder {
                    void lire() {
                        StringBuilder sql = new StringBuilder("SELECT id FROM stand");
                        for (String colonne : colonnes) {
                            sql.append(" AND ").append(colonne).append(" = ?");
                        }
                        scope.prepareScoped(connection, sql.toString());
                    }
                }
                """))
                .as("SQL assemblé par un StringBuilder")
                .isNotEmpty();

        assertThat(griefs(racine, "Jointure", """
                class Jointure {
                    void lire() {
                        scope.prepareScoped(connection, "SELECT id FROM stand WHERE " + String.join(" AND ", filtres));
                    }
                }
                """))
                .as("SQL assemblé par String.join")
                .isNotEmpty();

        assertThat(griefs(racine, "Heritee", """
                class Heritee {
                    void lire() {
                        scope.prepareScoped(connection, AutreClasse.SELECT_SQL);
                    }
                }
                """))
                .as("SQL repris d'une constante d'une autre classe")
                .isNotEmpty();
    }

    /**
     * A literal followed by a method call is <b>not</b> a literal.
     * {@code "DELETE FROM ".concat(TABLE_STAND)} used to be read as one, quote
     * to quote, so the table name left with the constant and the scan found no
     * business table left to ask a predicate of: a cross-edition DELETE went
     * through without a word. The same for {@code "…".formatted(x)} and for a
     * text block with anything after its closing quotes.
     */
    @Test
    void leScanRefuseUnLitteralSuiviDUnAppelDeMethode(@TempDir Path racine) throws IOException {
        assertThat(griefs(racine, "Concat", """
                class Concat {
                    private static final String TABLE_STAND = "stand";
                    void supprimer() {
                        scope.prepareScoped(connection, "DELETE FROM ".concat(TABLE_STAND) + " WHERE id = ?");
                    }
                }
                """))
                .as("littéral suivi de .concat(…)")
                .isNotEmpty();

        assertThat(griefs(racine, "Formatte", """
                class Formatte {
                    void lire() {
                        scope.prepareScoped(connection, "SELECT id FROM %s WHERE nom = ?".formatted(table));
                    }
                }
                """))
                .as("littéral suivi de .formatted(…)")
                .isNotEmpty();

        assertThat(griefs(racine, "BlocSuivi", """
                class BlocSuivi {
                    void lire() {
                        scope.prepareScoped(connection, \"""
                                SELECT id FROM stand
                                WHERE nom = ?\""".stripIndent());
                    }
                }
                """))
                .as("bloc de texte suivi de .stripIndent()")
                .isNotEmpty();
    }

    /**
     * A variable reassigned after its declaration is read on its first value
     * only, and the branch actually taken can hand over another statement
     * entirely. The scan must say it cannot read it rather than trust the
     * declaration — this is the shape the A/B comparator would have taken had
     * it been written in a single method.
     */
    @Test
    void leScanRefuseUneVariableReaffecteeApresSaDeclaration(@TempDir Path racine) throws IOException {
        assertThat(griefs(racine, "Reaffecte", """
                class Reaffecte {
                    void lire(boolean toutesEditions) {
                        String sql = "SELECT id FROM stand WHERE edition_id = ?";
                        if (toutesEditions) {
                            sql = "SELECT id FROM stand";
                        }
                        scope.prepareScoped(connection, sql);
                    }
                }
                """))
                .as("variable réaffectée entre sa déclaration et son usage")
                .isNotEmpty();

        assertThat(griefs(racine, "Concatene", """
                class Concatene {
                    void lire(boolean trie) {
                        String sql = "SELECT id FROM stand WHERE edition_id = ?";
                        sql += " ORDER BY nom";
                        scope.prepareScoped(connection, sql);
                    }
                }
                """))
                .as("variable étendue par += entre sa déclaration et son usage")
                .isNotEmpty();
    }

    /**
     * A {@code final} constant declared <b>below</b> the method that uses it is
     * legal Java and perfectly readable. Refusing it would fail the build on
     * scoped code and leave no way out but {@link #INDIRECTIONS_ASSUMEES},
     * that is switching the guard off on that file.
     */
    @Test
    void leScanLitUneConstanteDeclareeApresSonUsage(@TempDir Path racine) throws IOException {
        assertThat(griefs(racine, "ConstanteEnBas", """
                class ConstanteEnBas {
                    void lire() {
                        scope.prepareScoped(connection, SELECT_STANDS);
                    }

                    private static final String SELECT_STANDS = "SELECT id FROM stand WHERE edition_id = ?";
                }
                """))
                .as("constante finale déclarée après la méthode qui l'utilise")
                .isEmpty();

        assertThat(griefs(racine, "ConstanteEnBasNue", """
                class ConstanteEnBasNue {
                    void lire() {
                        scope.prepareScoped(connection, SELECT_STANDS);
                    }

                    private static final String SELECT_STANDS = "SELECT id FROM stand";
                }
                """))
                .as("la même, sans prédicat : elle doit être lue et refusée")
                .isNotEmpty();
    }

    /**
     * {@code catch (SQLException e) {} reads like a one-parameter method
     * declaration. Taken for one, it becomes the innermost scope around a
     * statement written inside it: the private helper hosting the call is no
     * longer seen as private, {@link #atCallSites} gives up, and a statement
     * written in full at its call site is reported as unreadable.
     */
    @Test
    void unBlocCatchNestPasUneMethode(@TempDir Path racine) throws IOException {
        assertThat(griefs(racine, "Reprise", """
                class Reprise {
                    void lire() {
                        preparer("SELECT id FROM stand WHERE edition_id = ?");
                    }

                    private void preparer(String sql) {
                        try {
                            premierEssai();
                        } catch (SQLException e) {
                            scope.prepareScoped(connection, sql);
                        }
                    }
                }
                """))
                .as("helper privé dont l'appel est dans un catch : le SQL est lu chez l'appelant")
                .isEmpty();

        assertThat(griefs(racine, "RepriseNue", """
                class RepriseNue {
                    void lire() {
                        preparer("SELECT id FROM stand");
                    }

                    private void preparer(String sql) {
                        try {
                            premierEssai();
                        } catch (SQLException e) {
                            scope.prepareScoped(connection, sql);
                        }
                    }
                }
                """))
                .as("la même, sans prédicat : elle doit remonter")
                .isNotEmpty();
    }

    /**
     * {@code creneau_remap} is the temporary mapping table of a duplication
     * transaction, two columns and no {@code edition_id}: a statement naming
     * only it cannot carry the predicate, and asking one would only push it
     * into the exception list.
     */
    @Test
    void uneTableTemporaireNeReclamePasDePredicat(@TempDir Path racine) throws IOException {
        assertThat(griefs(racine, "Remap", """
                class Remap {
                    void lire() {
                        scope.prepareScoped(connection, "SELECT nouvel_id FROM creneau_remap WHERE ancien_id = ?");
                    }
                }
                """))
                .as("table temporaire de duplication, sans colonne edition_id")
                .isEmpty();
    }

    /**
     * The {@code scope.delete(…)} anchor keys on the <b>field name</b>, not on
     * the type: a {@code JdbcEditionScope} injected under any other name would
     * hand its DELETE to no anchor at all, and the SQL would leave the scan
     * without a sound. Until the anchor learns to resolve the type, the
     * convention itself is what has to hold.
     */
    @Test
    void toutJdbcEditionScopeSAppelleScope() throws IOException {
        Pattern injection = Pattern.compile("\\bJdbcEditionScope\\s+([A-Za-z_$][\\w$]*)");
        List<String> autrementNommes = new ArrayList<>();
        int trouves = 0;
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file :
                    files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                Source source = new Source(file.getFileName().toString(), Files.readString(file));
                Matcher champ = injection.matcher(source.code);
                while (champ.find()) {
                    trouves++;
                    if (!"scope".equals(champ.group(1))) {
                        autrementNommes.add(source.name + ":" + source.line(champ.start()) + " " + champ.group(1));
                    }
                }
            }
        }

        assertThat(trouves)
                .as("déclarations de JdbcEditionScope trouvées — un motif qui ne trouve plus rien "
                        + "rendrait l'assertion suivante vide de sens")
                .isGreaterThan(15);
        assertThat(autrementNommes)
                .as("l'ancre du scan est « scope.delete( » : un JdbcEditionScope nommé autrement "
                        + "sortirait ses DELETE du scan sans que rien ne le signale")
                .isEmpty();
    }

    /**
     * Writes one fake backend file and returns what the scan holds against it.
     * Through {@link #enonces(Path)}, not around it: the walk, the
     * {@code .java} filter and the read are the path the real guard takes, and
     * a fixture that bypassed them would leave that path covered by nothing
     * but a threshold.
     */
    private static List<String> griefs(Path racine, String nom, String source) throws IOException {
        Path file = racine.resolve(nom + ".java");
        Files.writeString(file, source);
        try {
            List<String> griefs = new ArrayList<>();
            for (Enonce enonce : enonces(racine)) {
                String grief = grief(enonce);
                if (grief != null) {
                    griefs.add(grief);
                }
            }
            return griefs;
        } finally {
            Files.delete(file);
        }
    }
}
