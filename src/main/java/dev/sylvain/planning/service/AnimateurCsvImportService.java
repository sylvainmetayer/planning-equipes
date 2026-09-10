package dev.sylvain.planning.service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.StatutDeclaration;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import dev.sylvain.planning.service.espace.DeclarationDisponibiliteRepository;
import dev.sylvain.planning.service.espace.DeclarationDisponibiliteService;

/**
 * Turns a spreadsheet export into animateur fiches — in two calls, and only
 * the second one writes.
 *
 * <h2>Nothing is written before the operator has read the report</h2>
 *
 * <p>{@link #preview} and {@link #apply} take the same request and run the
 * same analysis; {@code apply} then opens one transaction. The analysis is
 * recomputed from the file, never read back from what the browser was shown,
 * so a replayed call cannot smuggle a row past a check.</p>
 *
 * <h2>Who a row is</h2>
 *
 * <p>Three keys, tried in that order: the {@code id} column when the file
 * carries one, the e-mail address, then first name + last name compared
 * without case or accents. A real file of 150 volunteers has homonyms, so the
 * name is the last resort and an <b>ambiguous</b> name is a refusal, not a
 * guess: the row is rejected naming the fiches it could have been, and the
 * operator adds an id or an address column. Two rows resolving to the same
 * person is likewise a refusal for the second one.</p>
 *
 * <h2>What a row must carry</h2>
 *
 * <p>A name (or an id), and a <b>birth date</b>. The second one is not
 * negotiable: minor / adult is derived from it at each créneau's date, so a
 * roster imported without it would put children under the adult regime without
 * a word. A row updating an existing fiche may leave it out — the fiche
 * already has one.</p>
 *
 * <h2>Off days</h2>
 *
 * <p>They are the reason this import needs an opinion. A day outside the
 * event's dates has no checkbox in the espace and is wiped by the first
 * accepted declaration ({@code DeclarationDisponibiliteService.accepter}
 * replaces the set wholesale), so it is <b>refused at the row</b> — the same
 * rule {@code checkDays} already applies to a declaration. And since that
 * check needs dates to compare against, an edition without a single créneau
 * refuses the import outright rather than accepting days nobody will ever see.
 * On a fiche that already exists, the file's days are <b>added</b> unless the
 * operator asked for a replacement: a catch-up import must not erase what the
 * animateurs declared themselves.</p>
 */
@ApplicationScoped
public class AnimateurCsvImportService {

    /**
     * The file is read into memory in one piece, so this cap is what stands
     * between the endpoint and an upload sized to exhaust the heap. A roster
     * of 150 animateurs with every column filled weighs some 15 kB.
     */
    static final int MAX_CHARACTERS = 1_000_000;

    /** Same reasoning, one level down: a file may be small and still absurd. */
    static final int MAX_ROWS = 5_000;

    /**
     * The example roster shipped with the application, on the classpath next
     * to the scenario it is derived from.
     *
     * <p>One file, one place: the screen offers it for download through this
     * service rather than a copy of it living in the front-end bundle, and
     * {@code AnimateurCsvExempleTest} re-imports <b>this very resource</b>
     * through the real parser. An example that has drifted from the format it
     * illustrates is worse than no example at all, so the guard is a test that
     * reads it, not a comment asking to keep it up to date.</p>
     */
    static final String EXEMPLE_RESSOURCE = "scenarios/festival-realiste-animateurs.csv";

    /** The name the browser saves the example under. */
    public static final String EXEMPLE_FICHIER = "festival-realiste-animateurs.csv";

    /**
     * The widths {@code animateur} is declared with — {@code id VARCHAR(64)},
     * {@code prenom} / {@code nom VARCHAR(128)} (V1__init.sql) and
     * {@code email VARCHAR(255)} (V41).
     *
     * <p>They are checked <b>at the row</b> rather than left to Postgres. A
     * column mapped by mistake — a « Commentaires » or an « Adresse » dropped
     * on {@code nom} — previews all green, and the write then dies on a
     * {@code value too long}, which rolls the whole transaction back and
     * answers 500. That accident is precisely what this preview exists to
     * catch, so it is caught here, with the row and its measurement.</p>
     */
    static final int MAX_ID = 64;

    static final int MAX_NOM = 128;

    static final int MAX_EMAIL = 255;

    /** The four bytes every ZIP archive — hence every {@code .xlsx} — starts with. */
    private static final String ZIP_SIGNATURE = "PK";

    /** What the browser leaves where a byte it could not decode as UTF-8 was. */
    private static final char REPLACEMENT = '\uFFFD';

    /** Inside one cell — never a slash, which a date needs. */
    private static final String VALUE_SEPARATORS = "[|;,\\r\\n]";

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d/M/uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d-M-uuuu").withResolverStyle(ResolverStyle.STRICT),
            DateTimeFormatter.ofPattern("d.M.uuuu").withResolverStyle(ResolverStyle.STRICT));

    /** {@code 12/09/26} and the like: a spreadsheet shortened the year, and the date is no longer readable. */
    private static final Pattern ANNEE_COURTE = Pattern.compile("^\\s*\\d{1,2}[/.-]\\d{1,2}[/.-]\\d{2}\\s*$");

    private static final Set<String> TRUE_CELLS =
            Set.of("1", "x", "o", "oui", "vrai", "true", "y", "yes");

    private static final Set<String> FALSE_CELLS =
            Set.of("0", "n", "non", "faux", "false", "no");

    @Inject
    AnimateurRepository animateurs;

    @Inject
    TypologieService typologies;

    @Inject
    DeclarationDisponibiliteService declarations;

    @Inject
    DeclarationDisponibiliteRepository declarationRepository;

    @Inject
    ReferenceDataChangeTracker changeTracker;

    @Inject
    SolverJobService solverJobs;

    @Inject
    ReferenceUsageService usages;

    /** The report, plus what applying it would write — never leaves this class. */
    private record Analysis(AnimateurCsvImportReport report, List<Animateur> toWrite, List<String> toDelete) {
    }

    /**
     * The example CSV, read from the classpath — the columns this import
     * accepts, filled with the anonymised roster of the {@code
     * festival-realiste} scenario.
     */
    public String exemple() {
        try (InputStream flux = Thread.currentThread().getContextClassLoader()
                .getResourceAsStream(EXEMPLE_RESSOURCE)) {
            if (flux == null) {
                throw new IllegalStateException("Ressource absente du classpath : " + EXEMPLE_RESSOURCE);
            }
            return new String(flux.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Lecture impossible de " + EXEMPLE_RESSOURCE, e);
        }
    }

    /** Reads the file and says what it would do. Writes nothing at all. */
    public AnimateurCsvImportReport preview(AnimateurCsvImportRequest request) {
        return analyse(request).report();
    }

    /**
     * Re-reads the file, re-runs every check, and writes the accepted rows in
     * <b>one</b> transaction.
     *
     * <p>All or nothing over what the report announces: a failure half-way
     * rolls the whole thing back and propagates, so the counts the operator
     * reads are never a story about rows a rollback took away.</p>
     *
     * @throws BusinessError.Invalid on a replacement asked for over a file
     *         that still has rejected rows — deleting the animateurs a
     *         malformed row failed to name is the accident this import exists
     *         to avoid
     */
    public AnimateurCsvImportReport apply(AnimateurCsvImportRequest request) {
        solverJobs.refuseIfSolving();
        Analysis analysis = analyse(request);
        AnimateurCsvImportReport report = analysis.report();
        if (!nameableMapping(report.mapping())) {
            throw new BusinessError.Invalid("Le mapping doit désigner au moins une colonne parmi "
                    + "l'identifiant, le prénom et le nom : sans elles, une ligne ne nomme personne.");
        }
        if (request.replaceAnimateurs() && report.rejected() > 0) {
            throw new BusinessError.Invalid(
                    "Remplacement complet demandé mais " + report.rejected()
                            + " ligne(s) sont rejetées : corrigez le fichier ou choisissez l'ajout, "
                            + "sinon les animateurs que ces lignes désignent seraient supprimés.");
        }
        animateurs.importAnimateurs(analysis.toWrite(), analysis.toDelete());
        changeTracker.markModified();
        return new AnimateurCsvImportReport(true, report.columns(), report.mapping(), report.separator(),
                report.total(), report.accepted(), report.rejected(), report.created(), report.updated(),
                report.deleted(), report.rows(), report.warnings());
    }

    /* ------------------------------- Analysis ------------------------------- */

    private Analysis analyse(AnimateurCsvImportRequest request) {
        String content = request == null ? null : request.content();
        refuseSpreadsheet(request == null ? null : request.fileName(), content);
        if (content != null && content.length() > MAX_CHARACTERS) {
            throw new BusinessError.Invalid("Fichier trop volumineux : "
                    + grouped(MAX_CHARACTERS) + " caractères au maximum.");
        }
        // After the cap, and not before: this one reads the whole body.
        refuseBrokenEncoding(content);
        List<LocalDate> joursEvenement = declarations.joursEvenement();
        if (joursEvenement.isEmpty()) {
            throw new BusinessError.Conflict(
                    "Aucun créneau n'existe dans cette édition : créez la grille de créneaux avant "
                            + "d'importer des animateurs. Sans dates d'événement, un jour "
                            + "d'indisponibilité importé ne serait ni affichable dans l'espace "
                            + "animateur, ni conservé à la première déclaration acceptée.");
        }
        CsvParser.Table table = CsvParser.parse(content);
        if (table.rows().size() > MAX_ROWS) {
            throw new BusinessError.Invalid(
                    "Fichier trop long : " + grouped(MAX_ROWS) + " lignes de données au maximum.");
        }
        // An unusable mapping is NOT refused here, and that is deliberate: the
        // screen needs the columns and a report to draw its mapping editor at
        // all. Refusing would leave the operator with an error and no way to
        // correct what caused it. Every row is then rejected on its own for
        // naming nobody, and apply() is what refuses.
        //
        // A mapping that maps nothing is honoured as such, and only a *missing*
        // one asks for a proposal. Re-proposing over an emptied mapping would
        // redraw the correspondence the operator had just cleared field by
        // field, leaving no way at all to reach "nothing is mapped".
        AnimateurCsvMapping mapping = request.mapping() == null
                ? AnimateurCsvMapping.propose(table.columns())
                : request.mapping();
        return build(request, table, mapping, Set.copyOf(joursEvenement));
    }

    /**
     * An {@code .xlsx} is a ZIP archive and an {@code .xls} is a compound
     * binary: both would parse as one nonsense column. The operator gets the
     * way out rather than a report of 400 rejected rows.
     *
     * <p>Recognised by extension, by the {@code PK} signature of a ZIP, and by
     * a body that is mostly undecodable. What it deliberately no longer
     * recognises is <b>one</b> unreadable character: the browser reads the
     * upload as UTF-8 with substitution, so an ordinary CSV exported in
     * Windows-1252 whose first header is « Équipe » used to land here and be
     * told to save it as CSV — which is exactly what its author had just done.
     * That file has an encoding problem, not a format one, and
     * {@link #refuseBrokenEncoding} says so.</p>
     */
    private void refuseSpreadsheet(String fileName, String content) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String text = content == null ? "" : content;
        if (name.endsWith(".xlsx") || name.endsWith(".xls") || name.endsWith(".ods")
                || text.startsWith(ZIP_SIGNATURE) || mostlyUndecodable(text)) {
            throw new BusinessError.Invalid("Ce format n'est pas accepté : seul le CSV est lu. "
                    + "Dans votre tableur, choisissez « Enregistrer sous » puis "
                    + "« CSV (séparateur : point-virgule) », et déposez ce fichier-là.");
        }
    }

    /**
     * A binary, as opposed to a CSV saved in the wrong code page: no text file
     * carries a NUL byte, and no spreadsheet export turns a tenth of itself
     * into unreadable characters.
     */
    private static boolean mostlyUndecodable(String text) {
        int window = Math.min(text.length(), 4096);
        if (window == 0) {
            return false;
        }
        String head = text.substring(0, window);
        return head.indexOf('\0') >= 0 || count(head, REPLACEMENT) * 10 > window;
    }

    /**
     * The file <i>is</i> a CSV, and it is not UTF-8.
     *
     * <p>Excel in a French locale still saves in Windows-1252 by default, and
     * the browser reads the upload as UTF-8: every accent is already lost by
     * the time a byte reaches this server. Nothing here can undo it — the
     * original bytes are gone — so what has to change is the export, not the
     * row, and the message names the encoding rather than the format.</p>
     *
     * <p>Refused rather than imported: a référentiel of first names with a
     * black diamond in the middle is damage nobody notices until a badge is
     * printed.</p>
     */
    private static void refuseBrokenEncoding(String content) {
        String text = content == null ? "" : content;
        long unreadable = count(text, REPLACEMENT);
        if (unreadable == 0) {
            return;
        }
        throw new BusinessError.Invalid("Ce fichier n'est pas encodé en UTF-8 : " + unreadable
                + " caractère(s) accentué(s) sont déjà illisibles, par exemple " + excerpt(text)
                + ". Dans votre tableur, réenregistrez-le en « CSV UTF-8 » puis redéposez-le : "
                + "sans cela, les accents des noms seraient importés abîmés.");
    }

    private static long count(String text, char character) {
        return text.chars().filter(value -> value == character).count();
    }

    /** A few characters around the first unreadable one, so the operator recognises the file. */
    private static String excerpt(String text) {
        int position = text.indexOf(REPLACEMENT);
        int start = Math.max(0, position - 12);
        int end = Math.min(text.length(), position + 12);
        return "« " + text.substring(start, end).replaceAll("[\\r\\n]+", " ").trim() + " »";
    }

    /** {@code 1000000} written « 1 000 000 » — the cap is read by a human, in a French sentence. */
    private static String grouped(int value) {
        return String.valueOf(value).replaceAll("(?<=\\d)(?=(\\d{3})+$)", " ");
    }

    private Analysis build(AnimateurCsvImportRequest request, CsvParser.Table table,
            AnimateurCsvMapping mapping, Set<LocalDate> joursEvenement) {
        List<Animateur> existants = animateurs.listAnimateurs();
        Index index = new Index(existants, pendingDeclarations());

        List<AnimateurCsvImportReport.ImportedRow> rows = new ArrayList<>();
        List<Animateur> toWrite = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        Set<String> idsTouches = new LinkedHashSet<>();
        int accepted = 0;
        int created = 0;
        int updated = 0;

        for (CsvParser.Row row : table.rows()) {
            RowOutcome outcome = analyseRow(row, table, mapping, request, index, joursEvenement, seen);
            rows.add(outcome.reported());
            if (outcome.animateur() != null) {
                toWrite.add(outcome.animateur());
                idsTouches.add(outcome.animateur().getId());
                accepted++;
                if (outcome.reported().action() == AnimateurCsvImportReport.ImportAction.CREATED) {
                    created++;
                } else {
                    updated++;
                }
            }
        }

        List<String> toDelete = request.replaceAnimateurs()
                ? existants.stream().map(Animateur::getId).filter(id -> !idsTouches.contains(id)).toList()
                : List.of();
        List<String> warnings = warnings(request, existants, toDelete);
        if (!nameableMapping(mapping)) {
            warnings.add(0, "Aucune colonne n'est associée à l'identifiant, au prénom ni au nom : "
                    + "associez-les ci-dessus, sinon aucune ligne ne nomme personne.");
        }
        AnimateurCsvImportReport report = new AnimateurCsvImportReport(false, table.columns(), mapping,
                String.valueOf(table.separator()), table.rows().size(), accepted,
                table.rows().size() - accepted, created, updated, toDelete.size(), rows, warnings);
        return new Analysis(report, toWrite, toDelete);
    }

    private Set<String> pendingDeclarations() {
        return declarationRepository.list().stream()
                .filter(declaration -> declaration.getStatut() == StatutDeclaration.EN_ATTENTE)
                .map(declaration -> declaration.getAnimateurId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** A row can only name somebody through one of these three columns. */
    private static boolean nameableMapping(AnimateurCsvMapping mapping) {
        return mapping.id() != null || mapping.prenom() != null || mapping.nom() != null;
    }

    private List<String> warnings(AnimateurCsvImportRequest request, List<Animateur> existants,
            List<String> toDelete) {
        List<String> warnings = new ArrayList<>();
        warnings.add(request.replaceJoursIndisponibles()
                ? "Jours d'indisponibilité : remplacement. Ceux déjà enregistrés sur une fiche "
                        + "présente dans le fichier sont écrasés."
                : "Jours d'indisponibilité : ajout. Ceux déjà enregistrés sont conservés et "
                        + "complétés par ceux du fichier.");
        if (request.replaceAnimateurs()) {
            warnings.add(replacementWarning(toDelete));
        } else if (!existants.isEmpty()) {
            warnings.add("Ajout : les animateurs déjà présents et absents du fichier sont conservés ("
                    + existants.size() + " fiches en base avant import).");
        }
        return warnings;
    }

    /**
     * What a replacement destroys — counted, not summarised.
     *
     * <p>Announcing only the seats it frees understates it by a long way:
     * deleting a fiche cascades onto the availability declaration its owner
     * filled in, their acknowledgement of the published planning, their
     * exchanges on the foire au planning, their espace access code, the locks
     * naming them, their competences and their wishes. The unitary delete path
     * shows {@link ReferenceUsageService} before confirming; an import
     * deleting a hundred fiches at once has more reason to, not less.</p>
     */
    private String replacementWarning(List<String> toDelete) {
        if (toDelete.isEmpty()) {
            return "Remplacement complet : aucun animateur n'est absent du fichier, il n'y a donc "
                    + "personne à supprimer.";
        }
        ReferenceUsage usage = usages.forAnimateurs(toDelete);
        return "Remplacement complet : " + toDelete.size() + " animateur(s) absent(s) du fichier "
                + "seront supprimés. Cela libère " + usage.affectations() + " place(s) du planning "
                + "persisté, supprime " + usage.verrouillages() + " verrouillage(s) et retire ces "
                + "personnes de " + usage.contraintesAdHoc() + " contrainte(s) ad hoc. La "
                + "suppression emporte aussi, définitivement, ce qu'elles ont saisi : déclaration "
                + "de disponibilités, accusé de réception du planning publié, échanges de la foire "
                + "au planning, code d'accès à l'espace animateur, compétences et souhaits.";
    }

    /** The three lookups a row is resolved by, built once for the whole file. */
    private static final class Index {

        private final Map<String, Animateur> parId = new LinkedHashMap<>();
        private final Map<String, List<Animateur>> parEmail = new LinkedHashMap<>();
        private final Map<String, List<Animateur>> parNom = new LinkedHashMap<>();
        private final Set<String> idsPris = new LinkedHashSet<>();
        private final Set<String> enAttente;

        private Index(List<Animateur> existants, Set<String> enAttente) {
            this.enAttente = enAttente;
            for (Animateur animateur : existants) {
                parId.put(animateur.getId(), animateur);
                idsPris.add(animateur.getId());
                if (animateur.getEmail() != null && !animateur.getEmail().isBlank()) {
                    parEmail.computeIfAbsent(animateur.getEmail().trim().toLowerCase(Locale.ROOT),
                            key -> new ArrayList<>()).add(animateur);
                }
                parNom.computeIfAbsent(nameKey(animateur.getPrenom(), animateur.getNom()),
                        key -> new ArrayList<>()).add(animateur);
            }
        }
    }

    /** One analysed row: what the report shows, and the fiche to write (null when refused). */
    private record RowOutcome(AnimateurCsvImportReport.ImportedRow reported, Animateur animateur) {
    }

    private RowOutcome analyseRow(CsvParser.Row row, CsvParser.Table table, AnimateurCsvMapping mapping,
            AnimateurCsvImportRequest request, Index index, Set<LocalDate> joursEvenement,
            Map<String, Integer> seen) {
        List<String> reasons = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        checkWidth(row, table, reasons);

        String idCell = cell(row, mapping.id());
        String prenom = cell(row, mapping.prenom());
        String nom = cell(row, mapping.nom());
        String email = cell(row, mapping.email());
        String label = label(prenom, nom, idCell, email);
        checkLength("Identifiant trop long", idCell, MAX_ID, reasons);
        checkLength("Prénom trop long", prenom, MAX_NOM, reasons);
        checkLength("Nom trop long", nom, MAX_NOM, reasons);
        checkLength("Adresse e-mail trop longue", email, MAX_EMAIL, reasons);

        Resolution resolution = resolve(idCell, email, prenom, nom, label, index, reasons);
        Animateur existant = resolution.existant();
        Integer precedente = resolution.identity() == null ? null : seen.get(resolution.identity());
        if (precedente != null) {
            reasons.add("Doublon dans le fichier : la même personne est déjà décrite ligne "
                    + precedente + ".");
        }
        if (prenom.isEmpty() && nom.isEmpty() && idCell.isEmpty()) {
            // Checked on the cells rather than on the label, which falls back to
            // the e-mail: a row carrying nothing but an address would otherwise
            // create a nameless fiche.
            reasons.add("La ligne ne nomme personne : prénom, nom et identifiant sont vides.");
        }

        int motifsAvantDate = reasons.size();
        LocalDate dateNaissance = readBirthDate(row, mapping, existant, reasons);
        if (dateNaissance == null && reasons.size() == motifsAvantDate) {
            reasons.add("Date de naissance absente : elle est obligatoire, tout le régime "
                    + "mineur / majeur en dépend.");
        }
        if (!email.isEmpty() && (!email.contains("@") || email.contains(" "))) {
            reasons.add("Adresse e-mail invalide : « " + email + " ».");
        }
        boolean manager = readManager(row, mapping, existant, reasons);

        Map<String, NiveauCompetence> competences = readCompetences(row, mapping, existant, reasons);
        Set<String> souhaits = existant != null
                ? new LinkedHashSet<>(existant.getSouhaits())
                : new LinkedHashSet<>();
        souhaits.addAll(values(cell(row, mapping.souhaits())));
        checkTypologies(competences, souhaits, reasons);

        Set<LocalDate> jours = readJours(row, mapping, request, existant, joursEvenement, reasons);
        if (existant != null && index.enAttente.contains(existant.getId())) {
            warnings.add("Cet animateur a une déclaration de disponibilités en attente : la décision "
                    + "de l'administrateur remplacera les jours importés.");
        }

        if (!reasons.isEmpty()) {
            return new RowOutcome(new AnimateurCsvImportReport.ImportedRow(row.line(), label,
                    existant != null ? existant.getId() : null,
                    AnimateurCsvImportReport.ImportAction.REJECTED, List.copyOf(reasons),
                    List.of(), List.of()), null);
        }

        // Only a row that is going in is remembered as an identity: a duplicate
        // must point at the line that wrote the fiche, never at one that was
        // itself refused — otherwise the good row is the one turned away, and
        // the operator is sent back to a line that imported nothing.
        if (resolution.identity() != null) {
            seen.put(resolution.identity(), row.line());
        }

        Animateur animateur = new Animateur();
        animateur.setId(existant != null ? existant.getId() : generateId(prenom, nom, idCell, index.idsPris));
        animateur.setPrenom(!prenom.isEmpty() || existant == null ? prenom : existant.getPrenom());
        animateur.setNom(!nom.isEmpty() || existant == null ? nom : existant.getNom());
        animateur.setDateNaissance(dateNaissance);
        animateur.setManager(manager);
        animateur.setEmail(!email.isEmpty() ? email : (existant != null ? existant.getEmail() : null));
        animateur.setCompetences(competences);
        animateur.setSouhaits(souhaits);
        animateur.setJoursIndisponibles(jours);
        return new RowOutcome(new AnimateurCsvImportReport.ImportedRow(row.line(), label,
                animateur.getId(),
                existant != null ? AnimateurCsvImportReport.ImportAction.UPDATED
                        : AnimateurCsvImportReport.ImportAction.CREATED,
                List.of(), List.copyOf(warnings), List.copyOf(jours)), animateur);
    }

    /* ---------------------------- Row-level reads ---------------------------- */

    /**
     * A row wider than the header is the classic un-quoted separator inside a
     * cell: every field after it is one column off, so the row is refused
     * rather than silently written shifted. Extra <i>empty</i> cells — a line
     * ending on a separator — are harmless and say nothing.
     */
    private static void checkWidth(CsvParser.Row row, CsvParser.Table table, List<String> reasons) {
        int extra = row.values().size() - table.columns().size();
        if (extra > 0 && row.values().subList(table.columns().size(), row.values().size()).stream()
                .anyMatch(value -> !value.isBlank())) {
            reasons.add("La ligne porte " + extra + " valeur(s) de plus que l'en-tête : les colonnes "
                    + "sont probablement décalées.");
        }
    }

    /**
     * A cell longer than the column that will hold it, refused with its
     * measurement — see {@link #MAX_ID}. The wording points at the mapping
     * rather than at the person: a 214-character « nom » is a column picked by
     * mistake, never a name.
     */
    private static void checkLength(String probleme, String valeur, int max, List<String> reasons) {
        if (valeur.length() > max) {
            reasons.add(probleme + " : " + valeur.length() + " caractères, " + max
                    + " au maximum — la colonne associée n'est probablement pas la bonne.");
        }
    }

    private static String label(String prenom, String nom, String idCell, String email) {
        String complet = (prenom + " " + nom).trim();
        if (!complet.isEmpty()) {
            return complet;
        }
        return !idCell.isEmpty() ? idCell : email;
    }

    /** Which fiche the row lands on, and the key that makes it a duplicate of another row. */
    private record Resolution(Animateur existant, String identity) {
    }

    private static Resolution resolve(String idCell, String email, String prenom, String nom, String label,
            Index index, List<String> reasons) {
        if (!idCell.isEmpty()) {
            return new Resolution(index.parId.get(idCell), idCell);
        }
        if (!email.isEmpty()) {
            String key = email.toLowerCase(Locale.ROOT);
            List<Animateur> candidats = index.parEmail.getOrDefault(key, List.of());
            if (candidats.size() > 1) {
                reasons.add("Plusieurs animateurs portent l'adresse « " + email + " » (" + ids(candidats)
                        + ") : ajoutez une colonne identifiant pour trancher.");
                return new Resolution(null, "email:" + key);
            }
            if (candidats.size() == 1) {
                return new Resolution(candidats.get(0), candidats.get(0).getId());
            }
        }
        if (label.isEmpty()) {
            return new Resolution(null, null);
        }
        String key = nameKey(prenom, nom);
        List<Animateur> candidats = index.parNom.getOrDefault(key, List.of());
        if (candidats.size() > 1) {
            reasons.add("Plusieurs animateurs se nomment « " + label + " » (" + ids(candidats)
                    + ") : ajoutez une colonne identifiant ou adresse e-mail pour trancher.");
            return new Resolution(null, "nom:" + key);
        }
        if (candidats.size() == 1) {
            return new Resolution(candidats.get(0), candidats.get(0).getId());
        }
        return new Resolution(null, email.isEmpty() ? "nom:" + key : "email:" + email.toLowerCase(Locale.ROOT));
    }

    /**
     * The birth date is <b>required</b>, and not only because the column is
     * {@code NOT NULL}: the minor / adult regime is derived from it at every
     * créneau's date, so a fiche without one would silently be treated as an
     * adult by the legal constraints. A row that cannot supply one — and whose
     * fiche does not already carry one — is refused.
     */
    private static LocalDate readBirthDate(CsvParser.Row row, AnimateurCsvMapping mapping,
            Animateur existant, List<String> reasons) {
        String dateCell = cell(row, mapping.dateNaissance());
        if (dateCell.isEmpty()) {
            return existant != null ? existant.getDateNaissance() : null;
        }
        LocalDate parsed = parseDate(dateCell);
        if (parsed == null) {
            reasons.add("Date de naissance illisible : « " + dateCell
                    + " » (formats acceptés : JJ/MM/AAAA ou AAAA-MM-JJ)." + indiceAnneeCourte(dateCell));
            return existant != null ? existant.getDateNaissance() : null;
        }
        LocalDate today = LocalDate.now();
        if (parsed.isAfter(today) || parsed.isBefore(today.minusYears(120))) {
            reasons.add("Date de naissance invraisemblable : « " + dateCell + " ».");
            return existant != null ? existant.getDateNaissance() : null;
        }
        return parsed;
    }

    private static boolean readManager(CsvParser.Row row, AnimateurCsvMapping mapping, Animateur existant,
            List<String> reasons) {
        boolean manager = existant != null && existant.isManager();
        String managerCell = cell(row, mapping.manager());
        if (managerCell.isEmpty()) {
            return manager;
        }
        String lu = managerCell.toLowerCase(Locale.ROOT);
        if (TRUE_CELLS.contains(lu)) {
            return true;
        }
        if (FALSE_CELLS.contains(lu)) {
            return false;
        }
        reasons.add("Valeur « manager » non reconnue : « " + managerCell + " » (attendu : oui / non).");
        return manager;
    }

    /**
     * Competences are <b>added</b> to an existing fiche, never substituted for
     * it: a file listing three of somebody's five game categories is a partial
     * roster, not a decision to unlearn two. Removing one stays a gesture of
     * the referential screen.
     */
    private static Map<String, NiveauCompetence> readCompetences(CsvParser.Row row,
            AnimateurCsvMapping mapping, Animateur existant, List<String> reasons) {
        Map<String, NiveauCompetence> competences = existant != null
                ? new LinkedHashMap<>(existant.getCompetences())
                : new LinkedHashMap<>();
        for (String valeur : values(cell(row, mapping.competences()))) {
            String[] parts = valeur.split(":", 2);
            NiveauCompetence niveau = NiveauCompetence.AUTONOME;
            if (parts.length == 2 && !parts[1].isBlank()) {
                try {
                    niveau = NiveauCompetence.valueOf(parts[1].trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    reasons.add("Niveau de compétence inconnu : « " + parts[1].trim()
                            + " » (attendu : DEBUTANT, AUTONOME ou REFERENT).");
                    continue;
                }
            }
            competences.put(parts[0].trim(), niveau);
        }
        return competences;
    }

    /**
     * The same referential check the fiche form and the scenario import run —
     * called per row so one unknown game category costs one row, not the file.
     */
    private void checkTypologies(Map<String, NiveauCompetence> competences, Set<String> souhaits,
            List<String> reasons) {
        Set<String> ids = new TreeSet<>(competences.keySet());
        ids.addAll(souhaits);
        try {
            typologies.validateIds(ids);
        } catch (BusinessError.Invalid e) {
            reasons.add(e.getMessage());
        }
    }

    /**
     * Off days after the import — which is what the report shows, not what the
     * cell said. Unmapped column: the fiche keeps what it had, whatever the
     * replacement option says; there is nothing to replace it with.
     */
    private static Set<LocalDate> readJours(CsvParser.Row row, AnimateurCsvMapping mapping,
            AnimateurCsvImportRequest request, Animateur existant, Set<LocalDate> joursEvenement,
            List<String> reasons) {
        Set<LocalDate> connus = existant != null ? existant.getJoursIndisponibles() : Set.of();
        if (mapping.joursIndisponibles() == null) {
            return new TreeSet<>(connus);
        }
        Set<LocalDate> jours = request.replaceJoursIndisponibles()
                ? new TreeSet<>()
                : new TreeSet<>(connus);
        for (String valeur : values(cell(row, mapping.joursIndisponibles()))) {
            LocalDate jour = parseDate(valeur);
            if (jour == null) {
                reasons.add("Jour d'indisponibilité illisible : « " + valeur
                        + " » (formats acceptés : JJ/MM/AAAA ou AAAA-MM-JJ)." + indiceAnneeCourte(valeur));
            } else if (!joursEvenement.contains(jour)) {
                reasons.add("Jour d'indisponibilité hors des dates de l'événement : " + jour
                        + " — l'espace animateur ne peut pas l'afficher, et la première déclaration "
                        + "acceptée l'effacerait.");
            } else {
                jours.add(jour);
            }
        }
        return jours;
    }

    /* ------------------------------- Helpers ------------------------------- */

    private static String ids(List<Animateur> candidats) {
        return candidats.stream().map(Animateur::getId).sorted().collect(Collectors.joining(", "));
    }

    private static String cell(CsvParser.Row row, Integer column) {
        return column == null ? "" : row.value(column).trim();
    }

    /** Cells holding a list: split on the separators a quoted cell may carry, blanks dropped. */
    private static List<String> values(String cell) {
        if (cell == null || cell.isBlank()) {
            return List.of();
        }
        return Arrays.stream(cell.split(VALUE_SEPARATORS))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    /** {@code AAAA-MM-JJ} and the three separators a French spreadsheet writes a date with. */
    /**
     * The sentence to add when a date looks like one a spreadsheet shortened.
     *
     * <p>Excel and LibreOffice retype a date cell and write it back in their
     * own format, often on two digits: the file then arrives with rows nobody
     * can explain. The example ships its dates in {@code AAAA-MM-JJ}, which
     * both tools give back unchanged.</p>
     */
    private static String indiceAnneeCourte(String cellule) {
        return ANNEE_COURTE.matcher(cellule == null ? "" : cellule).matches()
                ? " L'année n'a que deux chiffres : un tableur a réécrit la date en l'ouvrant. Repartez du fichier "
                        + "d'exemple, dont les dates sont en AAAA-MM-JJ, une forme que les tableurs rendent intacte."
                : "";
    }

    static LocalDate parseDate(String value) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return LocalDate.parse(cleaned, format);
            } catch (DateTimeParseException ignored) {
                // Try the next dialect.
            }
        }
        return null;
    }

    /** Case- and accent-insensitive first name + last name, the last-resort identity key. */
    static String nameKey(String prenom, String nom) {
        String complet = ((prenom == null ? "" : prenom) + " " + (nom == null ? "" : nom)).trim();
        return Normalizer.normalize(complet, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
    }

    /**
     * A readable id for a new fiche, derived from the name and suffixed until
     * it is free — including against the ids this very import just handed out.
     *
     * <p>Truncated to {@link #MAX_ID}, suffix included: two names of 128
     * characters each build a key of 257 and the column holds 64. The id is
     * derived rather than read, so no row can be refused over it and the cap
     * belongs here.</p>
     */
    private static String generateId(String prenom, String nom, String idCell, Set<String> taken) {
        if (!idCell.isEmpty()) {
            taken.add(idCell);
            return idCell;
        }
        String base = nameKey(prenom, nom).replace(' ', '-');
        if (base.isEmpty()) {
            base = "animateur";
        }
        String candidate = truncate(base, MAX_ID);
        int suffix = 2;
        while (taken.contains(candidate)) {
            String marque = "-" + suffix;
            candidate = truncate(base, MAX_ID - marque.length()) + marque;
            suffix++;
        }
        taken.add(candidate);
        return candidate;
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
