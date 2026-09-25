package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.ReferenceDataChangeTracker;
import dev.sylvain.planning.service.journal.CurrentAction;
import dev.sylvain.planning.service.referentiel.CompetencesGrilleImportReport.ImportCompetencesAction;
import dev.sylvain.planning.service.referentiel.CompetencesGrilleImportReport.ImportedCompetencesColumn;
import dev.sylvain.planning.service.referentiel.CompetencesGrilleImportReport.ImportedCompetencesRow;
import dev.sylvain.planning.service.referentiel.GrilleCompetences.LigneCompetences;
import dev.sylvain.planning.service.referentiel.GrilleCompetences.ResultatLigne;
import dev.sylvain.planning.service.referentiel.GrilleCompetences.SaisieCompetences;
import dev.sylvain.planning.service.solve.SolverJobService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The animateur × game-category grid of appreciations, saved from the screen
 * and exchanged as a CSV.
 *
 * <p><b>Saving</b> is one fiche write per submitted row, through the same
 * {@link AnimateurService#update} the form uses — typologies validated, refused
 * while a solve runs, change tracked — with the row's own precondition
 * (issue #362). The rows are independent: a fiche another session wrote
 * meanwhile is refused alone and reported as such, the others are written,
 * and the answer is a line per row rather than one status for the lot. The
 * grid rewrites a fiche's whole map of appreciations, as the form does: a
 * cell emptied on screen is an appreciation removed.</p>
 *
 * <p><b>Importing</b> transposes the stand matrix contract
 * ({@code docs/decisions/0022}), with one difference in what an empty cell
 * means: the file <em>adds and updates, and never removes</em> — a blank cell
 * leaves the stored appreciation as it is, removing stays a gesture of the
 * screen. Rows name an animateur by id, columns name a typologie by id, an
 * unknown column is ignored and listed, an unknown row or level is refused
 * alone, and the accepted rows are written in one transaction.</p>
 */
@ApplicationScoped
public class CompetencesGrilleService {

    /** Same caps as the stand matrix: one row per animateur, well under the roster cap. */
    static final int MAX_CHARACTERS = StandGrilleImportService.MAX_CHARACTERS;

    static final int MAX_ROWS = StandGrilleImportService.MAX_ROWS;

    public static final String EXPORT_FICHIER = "grille-competences.csv";

    private static final String ZIP_SIGNATURE = "PK";

    private final AnimateurService animateurs;

    private final TypologieService typologies;

    private final AnimateurRepository repository;

    private final ReferenceDataChangeTracker changeTracker;

    private final SolverJobService solverJobs;

    private final CurrentAction currentAction;

    @Inject
    public CompetencesGrilleService(
            AnimateurService animateurs,
            TypologieService typologies,
            AnimateurRepository repository,
            ReferenceDataChangeTracker changeTracker,
            SolverJobService solverJobs,
            CurrentAction currentAction) {
        this.animateurs = animateurs;
        this.typologies = typologies;
        this.repository = repository;
        this.changeTracker = changeTracker;
        this.solverJobs = solverJobs;
        this.currentAction = currentAction;
    }

    /* ---------------------------------- screen --------------------------------- */

    /**
     * Writes the rows typed in the grid, one fiche each, and says how each one
     * ended. Refused as a whole while a solve runs — the landing persist would
     * revert every row minutes later — and otherwise never as a whole: an
     * unknown animateur, an unknown typologie or a stale fiche costs its own
     * row and nothing else.
     *
     * Two things do cost the whole call, and neither can be answered row by
     * row: a level that is not one of the three names never reaches this method
     * — Jackson refuses the body before it — and a payload longer than the
     * import's own ceiling is refused outright, since the grid has one row per
     * animateur and nothing legitimate sends more of them than a CSV may carry.
     */
    public List<LigneCompetences> saveGrid(List<SaisieCompetences> saisies) {
        solverJobs.refuseIfSolving();
        if (saisies.size() > MAX_ROWS) {
            throw new BusinessError.Invalid("Trop de lignes envoyées : " + grouped(MAX_ROWS) + " lignes au maximum.");
        }
        Map<String, Animateur> parId = new LinkedHashMap<>();
        animateurs.list().forEach(animateur -> parId.put(animateur.getId(), animateur));
        Set<String> dejaVus = new HashSet<>();
        List<LigneCompetences> lignes = new ArrayList<>();
        boolean modifie = false;
        for (SaisieCompetences saisie : saisies) {
            String id = saisie.animateurId();
            Animateur source = id == null ? null : parId.get(id);
            String refus = gridRejection(id, source, saisie, dejaVus);
            if (refus != null) {
                lignes.add(new LigneCompetences(id, ResultatLigne.REJECTED, refus, null));
            } else {
                LigneCompetences ligne = writeGridRow(id, source, saisie);
                modifie |= ligne.resultat() == ResultatLigne.WRITTEN;
                lignes.add(ligne);
            }
        }
        if (modifie) {
            currentAction.champsModifies(List.of("competences"));
        }
        return lignes;
    }

    /** Why a grid row cannot be written at all, checked in this order; {@code null} when it can. */
    private static String gridRejection(String id, Animateur source, SaisieCompetences saisie, Set<String> dejaVus) {
        if (source == null) {
            return "Animateur inconnu dans la grille : " + id;
        }
        if (!dejaVus.add(id)) {
            return "L'animateur " + id + " apparaît deux fois dans la grille.";
        }
        if (saisie.competences() == null) {
            return "Aucune compétence transmise pour l'animateur " + id + ".";
        }
        return null;
    }

    private LigneCompetences writeGridRow(String id, Animateur source, SaisieCompetences saisie) {
        Animateur copie = GrilleCompetences.withCompetences(source, saisie.competences(), saisie.modifieLe());
        try {
            animateurs.update(id, copie);
            return new LigneCompetences(id, ResultatLigne.WRITTEN, null, copie.getModifieLe());
        } catch (BusinessError.Stale stale) {
            return new LigneCompetences(id, ResultatLigne.STALE, stale.getMessage(), stale.getModifieLe());
        } catch (BusinessError refus) {
            return new LigneCompetences(id, ResultatLigne.REJECTED, refus.getMessage(), null);
        }
    }

    /* ----------------------------------- CSV ----------------------------------- */

    /** The grid as it stands, in the format the import reads back. */
    public String exportCsv() {
        List<TypologieItem> referentiel = typologies.list();
        Map<String, String> entetes = new LinkedHashMap<>();
        referentiel.forEach(typologie -> {
            if (typologie.code() != null) {
                entetes.put(typologie.id(), typologie.code());
            }
        });
        return GrilleCompetences.csv(
                animateurs.list(), referentiel.stream().map(TypologieItem::id).toList(), entetes);
    }

    public CompetencesGrilleImportReport preview(CompetencesGrilleImportRequest request) {
        return analyse(request).report(false);
    }

    /**
     * Writes the accepted rows, all in one transaction — a report announcing
     * forty fiches after a rollback would be a lie nothing could catch up on.
     * No precondition on these writes: a file is a knowing overwrite, as the
     * animateur import already is.
     */
    public CompetencesGrilleImportReport apply(CompetencesGrilleImportRequest request) {
        solverJobs.refuseIfSolving();
        Analyse analyse = analyse(request);
        if (analyse.aEcrire.isEmpty()) {
            throw new BusinessError.Invalid("Aucune ligne acceptée : rien à importer.");
        }
        repository.importAnimateurs(analyse.aEcrire, List.of());
        changeTracker.markModified();
        currentAction.champsModifies(List.of("competences"));
        return analyse.report(true);
    }

    /* --------------------------------- analysis -------------------------------- */

    private record Analyse(
            String separator,
            List<ImportedCompetencesColumn> columns,
            List<ImportedCompetencesRow> rows,
            List<String> warnings,
            List<Animateur> aEcrire) {

        CompetencesGrilleImportReport report(boolean applied) {
            int accepted = (int) rows.stream()
                    .filter(row -> row.action() == ImportCompetencesAction.UPDATED)
                    .count();
            int unchanged = (int) rows.stream()
                    .filter(row -> row.action() == ImportCompetencesAction.UNCHANGED)
                    .count();
            return new CompetencesGrilleImportReport(
                    applied,
                    separator,
                    columns,
                    rows.size(),
                    accepted,
                    unchanged,
                    rows.size() - accepted - unchanged,
                    rows,
                    warnings);
        }
    }

    private Analyse analyse(CompetencesGrilleImportRequest request) {
        String content = checkedContent(request);
        List<TypologieItem> referentiel = typologies.list();
        if (referentiel.isEmpty()) {
            throw new BusinessError.Invalid("L'édition n'a aucune typologie : les colonnes du fichier n'auraient rien "
                    + "sur quoi se poser. Créez les typologies d'abord.");
        }
        CsvParser.Table table = CsvParser.parse(content);
        if (table.rows().size() > MAX_ROWS) {
            throw new BusinessError.Invalid("Fichier trop long : " + grouped(MAX_ROWS) + " lignes au maximum.");
        }
        List<String> warnings = new ArrayList<>();

        ColumnMapping colonnes = readColumns(table.columns(), referentiel);
        if (colonnes.typologieParColonne().isEmpty()) {
            throw new BusinessError.Invalid("Aucune colonne du fichier ne correspond à une typologie de l'édition. "
                    + "Attendu : une première colonne « animateur » portant l'identifiant, puis une colonne par "
                    + "typologie, nommée par son code ou son identifiant.");
        }
        long sansColonne = referentiel.stream()
                .filter(typologie -> !colonnes.dejaPrises().contains(typologie.id()))
                .count();
        if (sansColonne > 0) {
            warnings.add(sansColonne + " typologie(s) de l'édition n'ont pas de colonne dans le fichier : "
                    + "les appréciations correspondantes sont conservées telles quelles.");
        }

        // Each row names one animateur of the edition, by id, or none.
        AnimateurIndex index = new AnimateurIndex(new LinkedHashMap<>(), new HashMap<>());
        for (Animateur animateur : animateurs.list()) {
            index.parId().put(animateur.getId(), animateur);
            index.parIdNormalise().putIfAbsent(normalise(animateur.getId()), animateur);
        }
        List<ImportedCompetencesRow> rows = new ArrayList<>();
        List<Animateur> aEcrire = new ArrayList<>();
        Map<String, Integer> dejaVus = new HashMap<>();
        for (CsvParser.Row row : table.rows()) {
            rows.add(analyseRow(row, index, colonnes.typologieParColonne(), table.columns(), dejaVus, aEcrire));
        }
        return new Analyse(String.valueOf(table.separator()), colonnes.columns(), rows, warnings, aEcrire);
    }

    /** The upload's text, once the refusals that cost the whole file are passed. */
    private static String checkedContent(CompetencesGrilleImportRequest request) {
        if (request == null) {
            throw new BusinessError.Invalid("Aucun fichier reçu : déposez le CSV de la grille des compétences.");
        }
        refuseSpreadsheet(request.fileName(), request.content());
        String content = request.content() == null ? "" : request.content();
        if (content.isBlank()) {
            throw new BusinessError.Invalid("Le fichier est vide.");
        }
        if (content.length() > MAX_CHARACTERS) {
            throw new BusinessError.Invalid(
                    "Fichier trop volumineux : " + grouped(MAX_CHARACTERS) + " caractères au maximum.");
        }
        return content;
    }

    /** The header read: which column feeds which typologie, and the typologies already claimed. */
    private record ColumnMapping(
            List<ImportedCompetencesColumn> columns,
            Map<Integer, String> typologieParColonne,
            Set<String> dejaPrises) {}

    /** Each column past the first names one typologie of the edition, or none. */
    private static ColumnMapping readColumns(List<String> headers, List<TypologieItem> referentiel) {
        Map<String, String> typologieParCle = new HashMap<>();
        for (TypologieItem typologie : referentiel) {
            typologieParCle.putIfAbsent(normalise(typologie.id()), typologie.id());
            if (typologie.code() != null) {
                typologieParCle.putIfAbsent(normalise(typologie.code()), typologie.id());
            }
            typologieParCle.putIfAbsent(normalise(typologie.label()), typologie.id());
        }
        Map<String, String> typologieExacte = new HashMap<>();
        referentiel.forEach(typologie -> {
            if (typologie.code() != null) {
                typologieExacte.putIfAbsent(typologie.code(), typologie.id());
            }
        });
        referentiel.forEach(typologie -> typologieExacte.put(typologie.id(), typologie.id()));
        List<ImportedCompetencesColumn> columns = new ArrayList<>();
        Map<Integer, String> typologieParColonne = new LinkedHashMap<>();
        Set<String> dejaPrises = new HashSet<>();
        for (int index = 1; index < headers.size(); index++) {
            String libelle = headers.get(index);
            String typologieId = typologieExacte.get(libelle);
            if (typologieId == null) {
                typologieId = typologieParCle.get(normalise(libelle));
            }
            String motif = columnRejection(typologieId, dejaPrises);
            if (motif == null) {
                typologieParColonne.put(index, typologieId);
                columns.add(new ImportedCompetencesColumn(index, libelle, typologieId, null));
            } else {
                columns.add(new ImportedCompetencesColumn(index, libelle, null, motif));
            }
        }
        return new ColumnMapping(columns, typologieParColonne, dejaPrises);
    }

    /** Why a column is ignored; {@code null} when it claims its typologie. */
    private static String columnRejection(String typologieId, Set<String> dejaPrises) {
        if (typologieId == null) {
            return "Aucune typologie de l'édition ne porte ce code, cet identifiant ni ce libellé : colonne ignorée.";
        }
        if (!dejaPrises.add(typologieId)) {
            return "Une colonne précédente nomme déjà la typologie " + typologieId + " : celle-ci est ignorée.";
        }
        return null;
    }

    /** The edition's animateurs by id as written, then by id with case and accents aside. */
    private record AnimateurIndex(Map<String, Animateur> parId, Map<String, Animateur> parIdNormalise) {

        Animateur find(String label) {
            Animateur animateur = parId.get(label);
            return animateur != null ? animateur : parIdNormalise.get(normalise(label));
        }
    }

    private static ImportedCompetencesRow analyseRow(
            CsvParser.Row row,
            AnimateurIndex index,
            Map<Integer, String> typologieParColonne,
            List<String> headers,
            Map<String, Integer> dejaVus,
            List<Animateur> aEcrire) {
        String label = row.value(0).trim();
        Animateur animateur = index.find(label);
        if (animateur == null) {
            String motif = label.isEmpty()
                    ? "Première colonne vide : chaque ligne doit porter l'identifiant de l'animateur."
                    : "Aucun animateur « " + label + " » dans l'édition : créez la fiche d'abord, l'import "
                            + "ne crée pas d'animateur.";
            return new ImportedCompetencesRow(
                    row.line(), label, null, ImportCompetencesAction.REJECTED, List.of(motif), 0);
        }
        String id = animateur.getId();
        if (dejaVus.containsKey(id)) {
            return new ImportedCompetencesRow(
                    row.line(),
                    label,
                    id,
                    ImportCompetencesAction.REJECTED,
                    List.of("L'animateur " + id + " est déjà décrit ligne " + dejaVus.get(id)
                            + " : cette ligne est ignorée."),
                    0);
        }
        Map<String, NiveauCompetence> competences =
                new LinkedHashMap<>(animateur.getCompetences() == null ? Map.of() : animateur.getCompetences());
        List<String> reasons = new ArrayList<>();
        int changees = applyCells(row, typologieParColonne, headers, competences, reasons);
        dejaVus.put(id, row.line());
        if (!reasons.isEmpty()) {
            return new ImportedCompetencesRow(row.line(), label, id, ImportCompetencesAction.REJECTED, reasons, 0);
        }
        if (changees == 0) {
            return new ImportedCompetencesRow(row.line(), label, id, ImportCompetencesAction.UNCHANGED, List.of(), 0);
        }
        aEcrire.add(GrilleCompetences.withCompetences(animateur, competences, null));
        return new ImportedCompetencesRow(row.line(), label, id, ImportCompetencesAction.UPDATED, List.of(), changees);
    }

    /** Lays the row's readable cells over {@code competences}; returns how many levels moved. */
    private static int applyCells(
            CsvParser.Row row,
            Map<Integer, String> typologieParColonne,
            List<String> headers,
            Map<String, NiveauCompetence> competences,
            List<String> reasons) {
        int changees = 0;
        for (Map.Entry<Integer, String> colonne : typologieParColonne.entrySet()) {
            String brut = row.value(colonne.getKey());
            GrilleCompetences.CelluleCompetence lue = GrilleCompetences.cellule(brut);
            if (!lue.lisible()) {
                reasons.add("Colonne « " + headers.get(colonne.getKey()) + " » : « " + brut.trim()
                        + " » n'est pas un niveau (attendu : DEBUTANT, AUTONOME ou REFERENT, ou vide pour "
                        + "laisser l'appréciation telle quelle).");
            } else if (!lue.vide() && competences.get(colonne.getValue()) != lue.niveau()) {
                competences.put(colonne.getValue(), lue.niveau());
                changees++;
            }
        }
        return changees;
    }

    /** Case and accents aside, the way the stand matrix compares a name. */
    private static String normalise(String texte) {
        return StandGrilleImportService.normalise(texte);
    }

    /** Thousands spaced out, the way the other imports write their caps. */
    private static String grouped(int value) {
        return String.format(Locale.ROOT, "%,d", value).replace(',', ' ');
    }

    private static void refuseSpreadsheet(String fileName, String content) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String text = content == null ? "" : content;
        if (name.endsWith(".xlsx")
                || name.endsWith(".xls")
                || name.endsWith(".ods")
                || text.startsWith(ZIP_SIGNATURE)
                || text.indexOf('\0') >= 0) {
            throw new BusinessError.Invalid("Ce format n'est pas accepté : seul le CSV est lu. Dans votre tableur, "
                    + "choisissez « Enregistrer sous » puis « CSV (séparateur : point-virgule) », et déposez ce "
                    + "fichier-là.");
        }
    }
}
