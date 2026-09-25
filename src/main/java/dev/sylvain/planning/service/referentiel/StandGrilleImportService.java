package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.ReferenceDataChangeTracker;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer;
import dev.sylvain.planning.service.referentiel.GrilleHorairesStands.SaisieCellule;
import dev.sylvain.planning.service.referentiel.StandGrilleImportReport.ImportGrilleAction;
import dev.sylvain.planning.service.referentiel.StandGrilleImportReport.ImportedColumn;
import dev.sylvain.planning.service.referentiel.StandGrilleImportReport.ImportedGrilleRow;
import dev.sylvain.planning.service.solve.SolverJobService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.text.Normalizer;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns the organiser's stand matrix — one row per stand, one column per
 * (date, band), an integer per cell — into the stands' schedules, in two
 * calls and only then: {@link #preview} reads and reports, {@link #apply}
 * re-reads, re-checks and writes in one transaction. The contract of the
 * animateur import ({@code docs/decisions/0021}), transposed:
 *
 * <ul>
 *   <li><b>Columns</b> land on the créneau of the same date that contains
 *       their hours. A column narrower than its créneau
 *       (the workbook's 19h-20h under a créneau 14-20) writes a window at its
 *       own bounds, the rest of the créneau left as it was: the columns are
 *       the workbook's, the créneaux the solver's. A column no créneau
 *       contains is <em>ignored and listed</em>, not a reason to refuse the
 *       file. A créneau the file has no column for
 *       keeps each stand's current cell — the import only overrides what the
 *       file states, and a cell kept unchanged keeps its segments even when
 *       they cover part of the créneau only
 *       ({@link GrilleHorairesStands#apply}).</li>
 *   <li><b>Rows</b> name a stand by its id or its code, else by its exact name; a name two
 *       stands share, or a stand the edition does not have, rejects the row.
 *       A stand is not created here: it needs typologies the matrix does not
 *       carry.</li>
 *   <li><b>Cells</b> read as {@link GrilleCsv#cellule}: blank, dash or zero
 *       close the stand, digits are a headcount, anything else rejects the row
 *       naming the cell.</li>
 *   <li><b>Writing</b> a stand is {@link GrilleHorairesStands#apply}: its whole
 *       schedule rewritten from its cells, folded into rules, bounds derived —
 *       exactly what a save from the entry grid does. Stands absent from the
 *       file are untouched; nothing is ever deleted.</li>
 * </ul>
 *
 * <p>An edition without a créneau refuses the import outright: there is no
 * column to land on.</p>
 */
@ApplicationScoped
public class StandGrilleImportService {

    /**
     * The file is read in memory in one piece, as for the animateur import and
     * under the same character cap. The row cap is lower: a matrix holds one
     * row per stand, where a roster holds one per person.
     */
    static final int MAX_CHARACTERS = 1_000_000;

    static final int MAX_ROWS = 2_000;

    public static final String EXEMPLE_FICHIER = "grille-stands.csv";

    private static final String ZIP_SIGNATURE = "PK";

    private final StandService stands;

    private final CreneauService creneaux;

    private final StandRepository repository;

    private final ReferenceDataChangeTracker changeTracker;

    private final SolverJobService solverJobs;

    @Inject
    public StandGrilleImportService(
            StandService stands,
            CreneauService creneaux,
            StandRepository repository,
            ReferenceDataChangeTracker changeTracker,
            SolverJobService solverJobs) {
        this.stands = stands;
        this.creneaux = creneaux;
        this.repository = repository;
        this.changeTracker = changeTracker;
        this.solverJobs = solverJobs;
    }

    public StandGrilleImportReport preview(StandGrilleImportRequest request) {
        return analyse(request).report(false);
    }

    /**
     * Writes the accepted rows, all in one transaction — a report announcing
     * forty stands after a rollback would be a lie nothing could catch up on.
     */
    public StandGrilleImportReport apply(StandGrilleImportRequest request) {
        solverJobs.refuseIfSolving();
        Analyse analyse = analyse(request);
        if (analyse.aEcrire.isEmpty()) {
            throw new BusinessError.Invalid("Aucune ligne acceptée : rien à importer.");
        }
        repository.saveStands(analyse.aEcrire);
        changeTracker.markModified();
        return analyse.report(true);
    }

    /**
     * A file the operator can start from: the edition's own créneaux as
     * columns, every stand as a row with its current cells — re-importable as
     * is, which is what proves the format.
     */
    public String exemple() {
        List<Creneau> edition = creneaux.list();
        List<Stand> tous = stands.listSolved();
        OuvertureStandsAnalyzer.RapportOuvertures rapport = OuvertureStandsAnalyzer.analyze(tous, edition);
        // A row names its stand by code, else by name — never by id, drawn per
        // edition (ADR 0050): the file must read back in any edition.
        Map<String, String> libelles = new HashMap<>();
        tous.forEach(stand -> libelles.put(stand.getId(), stand.getCode() != null ? stand.getCode() : stand.getNom()));
        StringBuilder csv = new StringBuilder();
        appendHeaders(csv, rapport.jours());
        for (OuvertureStandsAnalyzer.LigneStand ligne : rapport.stands()) {
            appendStand(csv, ligne, libelles.getOrDefault(ligne.standId(), ligne.standId()));
        }
        return csv.toString();
    }

    /** The two header lines: the date over each day's first column, then every column's band. */
    private static void appendHeaders(StringBuilder csv, List<OuvertureStandsAnalyzer.JourAmplitude> jours) {
        csv.append("stand");
        StringBuilder bandes = new StringBuilder();
        for (OuvertureStandsAnalyzer.JourAmplitude jour : jours) {
            boolean premier = true;
            for (OuvertureStandsAnalyzer.ColonneCreneau colonne : jour.creneaux()) {
                csv.append(';').append(premier ? jour.date().toString() : "");
                bandes.append(';').append(bandeCsv(colonne.heureDebut(), colonne.heureFin()));
                premier = false;
            }
        }
        csv.append('\n').append(bandes).append('\n');
    }

    private static void appendStand(StringBuilder csv, OuvertureStandsAnalyzer.LigneStand ligne, String libelle) {
        csv.append(csv(libelle));
        for (OuvertureStandsAnalyzer.CelluleJour jour : ligne.jours()) {
            for (OuvertureStandsAnalyzer.CelluleCreneau cellule : jour.creneaux()) {
                csv.append(';').append(cellule.effectif() == null ? "" : cellule.effectif());
            }
        }
        csv.append('\n');
    }

    /* --------------------------------- analysis -------------------------------- */

    private record Analyse(
            String separator,
            List<ImportedColumn> columns,
            List<String> creneauxAbsents,
            List<ImportedGrilleRow> rows,
            List<String> warnings,
            List<Stand> aEcrire) {

        StandGrilleImportReport report(boolean applied) {
            int accepted = (int) rows.stream()
                    .filter(row -> row.action() == ImportGrilleAction.UPDATED)
                    .count();
            return new StandGrilleImportReport(
                    applied,
                    separator,
                    columns,
                    creneauxAbsents,
                    rows.size(),
                    accepted,
                    rows.size() - accepted,
                    rows,
                    warnings);
        }
    }

    private Analyse analyse(StandGrilleImportRequest request) {
        String content = checkedContent(request);
        List<Creneau> edition = creneaux.list();
        if (edition.isEmpty()) {
            throw new BusinessError.Invalid("L'édition n'a aucun créneau : les colonnes du fichier n'auraient rien "
                    + "sur quoi se poser. Créez les créneaux d'abord — un par un, en série, ou dérivés des "
                    + "horaires des stands.");
        }
        GrilleCsv.Matrice matrice = GrilleCsv.parse(content);
        if (matrice.lignes().size() > MAX_ROWS) {
            throw new BusinessError.Invalid("Fichier trop long : " + grouped(MAX_ROWS) + " lignes au maximum.");
        }
        List<String> warnings = new ArrayList<>();

        ColumnMapping colonnes = readColumns(matrice.colonnes(), edition);
        if (colonnes.creneauParColonne().isEmpty()) {
            throw new BusinessError.Invalid("Aucune colonne du fichier ne correspond à un créneau de l'édition. "
                    + "Attendu : une ligne de dates, une ligne de bandes « 10:00-12:00 », ou des en-têtes "
                    + "« 2026-07-08 10:00-12:00 ».");
        }
        List<String> creneauxAbsents = new ArrayList<>();
        for (Creneau creneau : edition) {
            if (!colonnes.dejaPris().contains(creneau.getId())) {
                creneauxAbsents.add(
                        creneau.getDate() + " " + court(creneau.getHeureDebut()) + "-" + court(creneau.getHeureFin()));
            }
        }
        if (!creneauxAbsents.isEmpty()) {
            warnings.add(creneauxAbsents.size() + " créneau(x) de l'édition n'ont pas de colonne dans le fichier : "
                    + "leurs cases sont conservées telles quelles pour les stands importés.");
        }

        // Each row names one stand of the edition, or none.
        // Resolved: a kept cell keeps its segments, which only the effective windows say.
        RowReader lecture =
                new RowReader(edition, matrice.colonnes(), colonnes, stands.listSolved(), cellulesActuelles(edition));
        List<ImportedGrilleRow> rows = new ArrayList<>();
        for (GrilleCsv.Ligne ligne : matrice.lignes()) {
            rows.add(lecture.read(ligne));
        }
        return new Analyse(matrice.separator(), colonnes.columns(), creneauxAbsents, rows, warnings, lecture.aEcrire);
    }

    /** The upload's text, once the refusals that cost the whole file are passed. */
    private static String checkedContent(StandGrilleImportRequest request) {
        if (request == null) {
            throw new BusinessError.Invalid("Aucun fichier reçu : déposez le CSV de la matrice.");
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

    /**
     * The header read: which créneaux each column lands on, and which créneaux
     * some column claims.
     */
    private record ColumnMapping(
            List<ImportedColumn> columns, Map<Integer, List<Long>> creneauParColonne, Set<Long> dejaPris) {}

    /**
     * Each column lands on the créneaux of the edition that contain its date
     * and hours, or on none; two columns may share a créneau as long as their
     * hours do not overlap.
     */
    private static ColumnMapping readColumns(List<GrilleCsv.Colonne> colonnes, List<Creneau> edition) {
        List<ImportedColumn> columns = new ArrayList<>();
        Map<Integer, List<Long>> creneauParColonne = new HashMap<>();
        Set<Long> dejaPris = new HashSet<>();
        Map<Long, List<int[]>> prisParCreneau = new HashMap<>();
        for (GrilleCsv.Colonne colonne : colonnes) {
            columns.add(readColumn(colonne, edition, prisParCreneau, creneauParColonne, dejaPris));
        }
        return new ColumnMapping(columns, creneauParColonne, dejaPris);
    }

    private static ImportedColumn readColumn(
            GrilleCsv.Colonne colonne,
            List<Creneau> edition,
            Map<Long, List<int[]>> prisParCreneau,
            Map<Integer, List<Long>> creneauParColonne,
            Set<Long> dejaPris) {
        if (!colonne.namesCreneau()) {
            return ignoredColumn(
                    colonne,
                    0,
                    "En-tête illisible : attendu une date et une bande « 10:00-12:00 » ou « 10h00-12h00 ». "
                            + "Une bande devenue une date, « 30/11/1999 13:16:00 », est le signe d'un tableur qui "
                            + "l'a convertie : repartez du modèle téléchargé, dont les bandes sont écrites avec "
                            + "un « h » pour cette raison.");
        }
        // Every créneau of that date containing those hours — one, since
        // the grid refuses two créneaux with the same hours.
        List<Creneau> cibles = edition.stream()
                .filter(creneau -> colonne.date().equals(creneau.getDate())
                        && contient(creneau, colonne.heureDebut(), colonne.heureFin()))
                .toList();
        if (cibles.isEmpty()) {
            return ignoredColumn(
                    colonne,
                    cibles.size(),
                    "Aucun créneau de l'édition ne contient ces heures à cette date : colonne ignorée.");
        }
        boolean recouvre = cibles.stream().anyMatch(creneau -> {
            int[] bornes = OuvertureStandsAnalyzer.boundsWithin(creneau, colonne.heureDebut(), colonne.heureFin());
            return prisParCreneau.getOrDefault(creneau.getId(), List.of()).stream()
                    .anyMatch(pris -> bornes[0] < pris[1] && pris[0] < bornes[1]);
        });
        if (recouvre) {
            return ignoredColumn(colonne, cibles.size(), overlapReason(colonne));
        }
        List<Long> ids = cibles.stream().map(Creneau::getId).toList();
        dejaPris.addAll(ids);
        for (Creneau creneau : cibles) {
            prisParCreneau
                    .computeIfAbsent(creneau.getId(), key -> new ArrayList<>())
                    .add(OuvertureStandsAnalyzer.boundsWithin(creneau, colonne.heureDebut(), colonne.heureFin()));
        }
        creneauParColonne.put(colonne.index(), ids);
        return new ImportedColumn(
                colonne.index(),
                colonne.libelle(),
                colonne.date(),
                colonne.heureDebut(),
                colonne.heureFin(),
                ids.get(0),
                ids.size(),
                null);
    }

    private static ImportedColumn ignoredColumn(GrilleCsv.Colonne colonne, int creneaux, String motif) {
        return new ImportedColumn(
                colonne.index(),
                colonne.libelle(),
                colonne.date(),
                colonne.heureDebut(),
                colonne.heureFin(),
                null,
                creneaux,
                motif);
    }

    private static String overlapReason(GrilleCsv.Colonne colonne) {
        if (colonne.dateHeritee()) {
            return "Cette colonne n'a pas de date à elle et reprend le " + colonne.date()
                    + " de la colonne précédente, qui nomme déjà ce créneau : elle est ignorée. "
                    + "Écrivez la date sur chaque première colonne de journée.";
        }
        return "Une colonne précédente nomme déjà ce créneau : celle-ci est ignorée.";
    }

    /** The data rows read so far: what the next row is resolved and checked against. */
    private static final class RowReader {

        private final List<Creneau> edition;
        private final List<GrilleCsv.Colonne> colonnes;
        private final ColumnMapping lues;
        /** A stand by its code: what a row names it by first — never by its id (ADR 0050). */
        private final Map<String, Stand> parCode = new LinkedHashMap<>();

        private final Map<String, List<Stand>> parNom = new HashMap<>();
        private final Map<String, Map<Long, Integer>> actuelles;
        private final Map<String, Integer> dejaVus = new HashMap<>();
        private final List<Stand> aEcrire = new ArrayList<>();

        RowReader(
                List<Creneau> edition,
                List<GrilleCsv.Colonne> colonnes,
                ColumnMapping lues,
                List<Stand> tous,
                Map<String, Map<Long, Integer>> actuelles) {
            this.edition = edition;
            this.colonnes = colonnes;
            this.lues = lues;
            this.actuelles = actuelles;
            for (Stand stand : tous) {
                if (stand.getCode() != null) {
                    parCode.put(stand.getCode(), stand);
                }
                parNom.computeIfAbsent(normalise(stand.getNom()), key -> new ArrayList<>())
                        .add(stand);
            }
        }

        ImportedGrilleRow read(GrilleCsv.Ligne ligne) {
            Stand stand = resolve(ligne.stand(), parCode, parNom);
            if (stand == null) {
                List<Stand> candidats = parNom.getOrDefault(normalise(ligne.stand()), List.of());
                return rejected(ligne, null, List.of(unknownStandReason(ligne.stand(), candidats)), 0);
            }
            if (dejaVus.containsKey(stand.getId())) {
                return rejected(
                        ligne,
                        stand.getId(),
                        List.of("Le stand « " + stand.getNom() + " » est déjà décrit ligne "
                                + dejaVus.get(stand.getId()) + " : cette ligne est ignorée."),
                        0);
            }
            // The créneaux the file has no column for keep their cell, in one
            // piece; the others get one cell per column, at the column's own
            // bounds, and what no column covers keeps what the stand had.
            Map<Long, Integer> actuellesDuStand = actuelles.getOrDefault(stand.getId(), Map.of());
            List<SaisieCellule> saisie = new ArrayList<>();
            for (Creneau creneau : edition) {
                if (!lues.dejaPris().contains(creneau.getId())) {
                    saisie.add(new SaisieCellule(creneau.getId(), actuellesDuStand.get(creneau.getId())));
                }
            }
            List<String> reasons = new ArrayList<>();
            int ouvertes = readCells(ligne, saisie, reasons);
            if (!reasons.isEmpty()) {
                return rejected(ligne, stand.getId(), reasons, ouvertes);
            }
            try {
                GrilleHorairesStands.LigneGrille grille = GrilleHorairesStands.apply(stand, edition, saisie);
                StandValidator.check(stand);
                dejaVus.put(stand.getId(), ligne.line());
                aEcrire.add(stand);
                return new ImportedGrilleRow(
                        ligne.line(),
                        ligne.stand(),
                        stand.getId(),
                        ImportGrilleAction.UPDATED,
                        List.of(),
                        ouvertes,
                        grille.regles(),
                        grille.exceptions(),
                        grille.effectifMin(),
                        grille.effectifMax());
            } catch (BusinessError e) {
                return rejected(ligne, stand.getId(), List.of(e.getMessage()), ouvertes);
            }
        }

        /** Adds the row's cells to {@code saisie}; returns how many columns it opens. */
        private int readCells(GrilleCsv.Ligne ligne, List<SaisieCellule> saisie, List<String> reasons) {
            int ouvertes = 0;
            for (int index = 0; index < colonnes.size(); index++) {
                GrilleCsv.Colonne colonne = colonnes.get(index);
                List<Long> creneauIds = lues.creneauParColonne().get(colonne.index());
                if (creneauIds != null) {
                    String brut =
                            index < ligne.cellules().size() ? ligne.cellules().get(index) : "";
                    GrilleCsv.CelluleLue lue = GrilleCsv.cellule(brut);
                    if (!lue.lisible()) {
                        reasons.add("Colonne « " + colonne.libelle() + " » : « " + brut.trim()
                                + " » n'est pas un effectif (un entier, vide ou « - » pour fermé).");
                    } else {
                        addCells(saisie, creneauIds, colonne, lue.effectif());
                        ouvertes += lue.effectif() != null ? 1 : 0;
                    }
                }
            }
            return ouvertes;
        }

        private static void addCells(
                List<SaisieCellule> saisie, List<Long> creneauIds, GrilleCsv.Colonne colonne, Integer effectif) {
            for (Long creneauId : creneauIds) {
                saisie.add(new SaisieCellule(creneauId, colonne.heureDebut(), colonne.heureFin(), effectif));
            }
        }

        private static String unknownStandReason(String texte, List<Stand> candidats) {
            if (candidats.size() > 1) {
                return "Ce nom désigne " + candidats.size()
                        + " stands : donnez un code à chacun et nommez la ligne par ce code.";
            }
            return "Aucun stand « " + texte + " » dans l'édition : créez le stand d'abord, "
                    + "l'import ne crée pas de stand (typologies et emplacement lui manqueraient).";
        }

        private static ImportedGrilleRow rejected(
                GrilleCsv.Ligne ligne, String standId, List<String> reasons, int ouvertes) {
            return new ImportedGrilleRow(
                    ligne.line(),
                    ligne.stand(),
                    standId,
                    ImportGrilleAction.REJECTED,
                    reasons,
                    ouvertes,
                    0,
                    0,
                    null,
                    null);
        }
    }

    /**
     * What every stand currently does on every créneau, read off the same report
     * the entry grid reads: the headcount, {@code null} when closed.
     */
    private Map<String, Map<Long, Integer>> cellulesActuelles(List<Creneau> edition) {
        Map<String, Map<Long, Integer>> cellules = new HashMap<>();
        OuvertureStandsAnalyzer.RapportOuvertures rapport =
                OuvertureStandsAnalyzer.analyze(stands.listSolved(), edition);
        for (OuvertureStandsAnalyzer.LigneStand ligne : rapport.stands()) {
            Map<Long, Integer> parCreneau = new HashMap<>();
            for (OuvertureStandsAnalyzer.CelluleJour jour : ligne.jours()) {
                for (OuvertureStandsAnalyzer.CelluleCreneau cellule : jour.creneaux()) {
                    // A créneau cut into tranches reads as its highest cell:
                    // sent back unchanged, the rewrite keeps every tranche.
                    Integer courant = parCreneau.get(cellule.creneauId());
                    Integer lu = cellule.effectif();
                    Integer fusion = courant;
                    if (courant == null) {
                        fusion = lu;
                    } else if (lu != null) {
                        fusion = Math.max(courant, lu);
                    }
                    parCreneau.put(cellule.creneauId(), fusion);
                }
            }
            cellules.put(ligne.standId(), parCreneau);
        }
        return cellules;
    }

    private static Stand resolve(String texte, Map<String, Stand> parCode, Map<String, List<Stand>> parNom) {
        Stand parLeCode = parCode.get(texte.trim());
        if (parLeCode != null) {
            return parLeCode;
        }
        List<Stand> candidats = parNom.getOrDefault(normalise(texte), List.of());
        return candidats.size() == 1 ? candidats.get(0) : null;
    }

    /** Case and accents aside, the way a name is compared by the animateur import too. */
    static String normalise(String texte) {
        if (texte == null) {
            return "";
        }
        String sansAccents = Normalizer.normalize(texte, Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sansAccents.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /**
     * A band written {@code 09h00-12h00}, not {@code 09:00-12:00}.
     *
     * <p>Both forms are read back, but only this one survives a spreadsheet:
     * Excel retypes {@code 13:00-16:00} into the date-time {@code 13:16:00},
     * and the band is then lost for good — the file comes back with headers
     * naming no créneau at all. Written with an {@code h}, the cell stays the
     * text it is.</p>
     */
    private static String bandeCsv(LocalTime heureDebut, LocalTime heureFin) {
        return court(heureDebut).replace(':', 'h') + '-' + court(heureFin).replace(':', 'h');
    }

    /** Whether {@code [heureDebut, heureFin)} lies inside the créneau, a {@code 00:00} end counting as midnight. */
    private static boolean contient(Creneau creneau, LocalTime heureDebut, LocalTime heureFin) {
        int[] bornes = OuvertureStandsAnalyzer.boundsWithin(creneau, heureDebut, heureFin);
        return bornes[0] >= 0 && bornes[1] <= creneau.getDureeMinutes() && bornes[0] < bornes[1];
    }

    /** Thousands spaced out, the way the animateur import writes its own caps. */
    private static String grouped(int value) {
        return String.format(Locale.ROOT, "%,d", value).replace(',', ' ');
    }

    /** A field a spreadsheet reads back as one: quoted as soon as it carries the separator, a quote or a newline. */
    private static String csv(String valeur) {
        String texte = valeur == null ? "" : valeur;
        if (texte.indexOf(';') < 0 && texte.indexOf('"') < 0 && texte.indexOf('\n') < 0 && texte.indexOf('\r') < 0) {
            return texte;
        }
        return '"' + texte.replace("\"", "\"\"") + '"';
    }

    private static String court(LocalTime heure) {
        return heure.toString().length() > 5 ? heure.toString().substring(0, 5) : heure.toString();
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
