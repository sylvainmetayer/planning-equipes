package dev.sylvain.planning.service;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.GrilleHorairesStands.SaisieCellule;
import dev.sylvain.planning.service.StandGrilleImportReport.ImportAction;
import dev.sylvain.planning.service.StandGrilleImportReport.ImportedColumn;
import dev.sylvain.planning.service.StandGrilleImportReport.ImportedRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Turns the organiser's stand matrix — one row per stand, one column per
 * (date, band), an integer per cell — into the stands' schedules, in two
 * calls and only then: {@link #preview} reads and reports, {@link #apply}
 * re-reads, re-checks and writes in one transaction. The contract of the
 * animateur import ({@code docs/decisions/0021}), transposed:
 *
 * <ul>
 *   <li><b>Columns</b> land on the créneau of the same date and hours. A
 *       column no créneau matches is <em>ignored and listed</em>, not a reason
 *       to refuse the file — a workbook often carries a band the edition does
 *       not have. A créneau the file has no column for keeps each stand's
 *       current cell: the import only overrides what the file states.</li>
 *   <li><b>Rows</b> name a stand by its id, else by its exact name; a name two
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

    /** Same caps as the animateur import: the file is read in memory in one piece. */
    static final int MAX_CHARACTERS = 1_000_000;
    static final int MAX_ROWS = 2_000;

    public static final String EXEMPLE_FICHIER = "grille-stands.csv";

    private static final String ZIP_SIGNATURE = "PK";

    @Inject
    StandService stands;

    @Inject
    CreneauService creneaux;

    @Inject
    StandRepository repository;

    @Inject
    ReferenceDataChangeTracker changeTracker;

    @Inject
    SolverJobService solverJobs;

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
        StringBuilder csv = new StringBuilder();
        Map<LocalDate, List<Creneau>> parJour = OuvertureStandsAnalyzer.creneauxByDay(edition);
        csv.append("stand");
        StringBuilder bandes = new StringBuilder();
        for (Map.Entry<LocalDate, List<Creneau>> jour : parJour.entrySet()) {
            boolean premier = true;
            for (Creneau creneau : jour.getValue()) {
                csv.append(';').append(premier ? jour.getKey().toString() : "");
                bandes.append(';').append(court(creneau.getHeureDebut())).append('-').append(court(creneau.getHeureFin()));
                premier = false;
            }
        }
        csv.append('\n').append(bandes).append('\n');
        Map<String, Map<Long, Integer>> cellules = cellulesActuelles(edition);
        for (Stand stand : stands.list()) {
            csv.append(stand.getId());
            Map<Long, Integer> ligne = cellules.getOrDefault(stand.getId(), Map.of());
            for (List<Creneau> duJour : parJour.values()) {
                for (Creneau creneau : duJour) {
                    Integer effectif = ligne.get(creneau.getId());
                    csv.append(';').append(effectif == null ? "" : effectif);
                }
            }
            csv.append('\n');
        }
        return csv.toString();
    }

    /* --------------------------------- analysis -------------------------------- */

    private record Analyse(String separator, List<ImportedColumn> columns, List<String> creneauxAbsents,
            List<ImportedRow> rows, List<String> warnings, List<Stand> aEcrire) {

        StandGrilleImportReport report(boolean applied) {
            int accepted = (int) rows.stream().filter(row -> row.action() == ImportAction.UPDATED).count();
            return new StandGrilleImportReport(applied, separator, columns, creneauxAbsents, rows.size(), accepted,
                    rows.size() - accepted, rows, warnings);
        }
    }

    private Analyse analyse(StandGrilleImportRequest request) {
        refuseSpreadsheet(request.fileName(), request.content());
        String content = request.content() == null ? "" : request.content();
        if (content.isBlank()) {
            throw new BusinessError.Invalid("Le fichier est vide.");
        }
        if (content.length() > MAX_CHARACTERS) {
            throw new BusinessError.Invalid("Fichier trop volumineux : " + MAX_CHARACTERS / 1000
                    + " ko de texte au maximum.");
        }
        List<Creneau> edition = creneaux.list();
        if (edition.isEmpty()) {
            throw new BusinessError.Invalid("L'édition n'a aucun créneau : les colonnes du fichier n'auraient rien "
                    + "sur quoi se poser. Créez les créneaux d'abord — un par un, en série, ou dérivés des "
                    + "horaires des stands.");
        }
        GrilleCsv.Matrice matrice = GrilleCsv.parse(content);
        if (matrice.lignes().size() > MAX_ROWS) {
            throw new BusinessError.Invalid("Fichier trop long : " + MAX_ROWS + " lignes au maximum.");
        }
        List<String> warnings = new ArrayList<>();

        // Each column lands on one créneau of the edition, or on none.
        List<ImportedColumn> columns = new ArrayList<>();
        Map<Integer, Long> creneauParColonne = new HashMap<>();
        for (GrilleCsv.Colonne colonne : matrice.colonnes()) {
            if (!colonne.namesCreneau()) {
                columns.add(new ImportedColumn(colonne.index(), colonne.libelle(), colonne.date(),
                        colonne.heureDebut(), colonne.heureFin(), null,
                        "En-tête illisible : attendu une date et une bande « 10:00-12:00 »."));
                continue;
            }
            Creneau cible = edition.stream()
                    .filter(creneau -> colonne.date().equals(creneau.getDate())
                            && colonne.heureDebut().equals(creneau.getHeureDebut())
                            && memeFin(colonne.heureFin(), creneau.getHeureFin()))
                    .findFirst().orElse(null);
            if (cible == null) {
                columns.add(new ImportedColumn(colonne.index(), colonne.libelle(), colonne.date(),
                        colonne.heureDebut(), colonne.heureFin(), null,
                        "Aucun créneau de l'édition à cette date et ces heures : colonne ignorée."));
                continue;
            }
            if (creneauParColonne.containsValue(cible.getId())) {
                columns.add(new ImportedColumn(colonne.index(), colonne.libelle(), colonne.date(),
                        colonne.heureDebut(), colonne.heureFin(), null,
                        "Une colonne précédente nomme déjà ce créneau : celle-ci est ignorée."));
                continue;
            }
            creneauParColonne.put(colonne.index(), cible.getId());
            columns.add(new ImportedColumn(colonne.index(), colonne.libelle(), colonne.date(),
                    colonne.heureDebut(), colonne.heureFin(), cible.getId(), null));
        }
        if (creneauParColonne.isEmpty()) {
            throw new BusinessError.Invalid("Aucune colonne du fichier ne correspond à un créneau de l'édition. "
                    + "Attendu : une ligne de dates, une ligne de bandes « 10:00-12:00 », ou des en-têtes "
                    + "« 2026-07-08 10:00-12:00 ».");
        }
        List<String> creneauxAbsents = new ArrayList<>();
        for (Creneau creneau : edition) {
            if (!creneauParColonne.containsValue(creneau.getId())) {
                creneauxAbsents.add(creneau.getDate() + " " + court(creneau.getHeureDebut()) + "-"
                        + court(creneau.getHeureFin()));
            }
        }
        if (!creneauxAbsents.isEmpty()) {
            warnings.add(creneauxAbsents.size() + " créneau(x) de l'édition n'ont pas de colonne dans le fichier : "
                    + "leurs cases sont conservées telles quelles pour les stands importés.");
        }

        // Each row names one stand of the edition, or none.
        List<Stand> tous = stands.list();
        Map<String, Stand> parId = new LinkedHashMap<>();
        Map<String, List<Stand>> parNom = new HashMap<>();
        for (Stand stand : tous) {
            parId.put(stand.getId(), stand);
            parNom.computeIfAbsent(normalise(stand.getNom()), key -> new ArrayList<>()).add(stand);
        }
        Map<String, Map<Long, Integer>> actuelles = cellulesActuelles(edition);
        List<ImportedRow> rows = new ArrayList<>();
        List<Stand> aEcrire = new ArrayList<>();
        Map<String, Integer> dejaVus = new HashMap<>();
        for (GrilleCsv.Ligne ligne : matrice.lignes()) {
            Stand stand = resolve(ligne.stand(), parId, parNom);
            List<String> reasons = new ArrayList<>();
            if (stand == null) {
                List<Stand> candidats = parNom.getOrDefault(normalise(ligne.stand()), List.of());
                reasons.add(candidats.size() > 1
                        ? "Ce nom désigne " + candidats.size() + " stands (" + String.join(", ",
                                candidats.stream().map(Stand::getId).toList()) + ") : nommez le stand par son identifiant."
                        : "Aucun stand « " + ligne.stand() + " » dans l'édition : créez le stand d'abord, "
                                + "l'import ne crée pas de stand (typologies et emplacement lui manqueraient).");
                rows.add(new ImportedRow(ligne.line(), ligne.stand(), null, ImportAction.REJECTED, reasons, 0, 0, 0,
                        null, null));
                continue;
            }
            if (dejaVus.containsKey(stand.getId())) {
                reasons.add("Le stand " + stand.getId() + " est déjà décrit ligne " + dejaVus.get(stand.getId())
                        + " : cette ligne est ignorée.");
                rows.add(new ImportedRow(ligne.line(), ligne.stand(), stand.getId(), ImportAction.REJECTED, reasons,
                        0, 0, 0, null, null));
                continue;
            }
            Map<Long, Integer> cellules = new TreeMap<>(actuelles.getOrDefault(stand.getId(), Map.of()));
            int ouvertes = 0;
            for (int index = 0; index < matrice.colonnes().size(); index++) {
                GrilleCsv.Colonne colonne = matrice.colonnes().get(index);
                Long creneauId = creneauParColonne.get(colonne.index());
                if (creneauId == null) {
                    continue;
                }
                String brut = index < ligne.cellules().size() ? ligne.cellules().get(index) : "";
                GrilleCsv.CelluleLue lue = GrilleCsv.cellule(brut);
                if (!lue.lisible()) {
                    reasons.add("Colonne « " + colonne.libelle() + " » : « " + brut.trim()
                            + " » n'est pas un effectif (un entier, vide ou « - » pour fermé).");
                    continue;
                }
                cellules.put(creneauId, lue.effectif());
                if (lue.effectif() != null) {
                    ouvertes++;
                }
            }
            if (!reasons.isEmpty()) {
                rows.add(new ImportedRow(ligne.line(), ligne.stand(), stand.getId(), ImportAction.REJECTED, reasons,
                        ouvertes, 0, 0, null, null));
                continue;
            }
            List<SaisieCellule> saisie = new ArrayList<>();
            for (Creneau creneau : edition) {
                saisie.add(new SaisieCellule(creneau.getId(), cellules.get(creneau.getId())));
            }
            try {
                GrilleHorairesStands.LigneGrille grille = GrilleHorairesStands.apply(stand, edition, saisie);
                StandValidator.check(stand);
                dejaVus.put(stand.getId(), ligne.line());
                aEcrire.add(stand);
                rows.add(new ImportedRow(ligne.line(), ligne.stand(), stand.getId(), ImportAction.UPDATED, List.of(),
                        ouvertes, grille.regles(), grille.exceptions(), grille.effectifMin(), grille.effectifMax()));
            } catch (BusinessError e) {
                rows.add(new ImportedRow(ligne.line(), ligne.stand(), stand.getId(), ImportAction.REJECTED,
                        List.of(e.getMessage()), ouvertes, 0, 0, null, null));
            }
        }
        return new Analyse(matrice.separator(), columns, creneauxAbsents, rows, warnings, aEcrire);
    }

    /** What every stand currently does on every créneau, read off the same report the entry grid reads. */
    private Map<String, Map<Long, Integer>> cellulesActuelles(List<Creneau> edition) {
        Map<String, Map<Long, Integer>> cellules = new HashMap<>();
        OuvertureStandsAnalyzer.RapportOuvertures rapport = OuvertureStandsAnalyzer.analyze(stands.listSolved(), edition);
        for (OuvertureStandsAnalyzer.LigneStand ligne : rapport.stands()) {
            Map<Long, Integer> parCreneau = new HashMap<>();
            for (OuvertureStandsAnalyzer.CelluleJour jour : ligne.jours()) {
                for (OuvertureStandsAnalyzer.CelluleCreneau cellule : jour.creneaux()) {
                    parCreneau.put(cellule.creneauId(), cellule.effectif());
                }
            }
            cellules.put(ligne.standId(), parCreneau);
        }
        return cellules;
    }

    private static Stand resolve(String texte, Map<String, Stand> parId, Map<String, List<Stand>> parNom) {
        Stand parIdentifiant = parId.get(texte.trim());
        if (parIdentifiant != null) {
            return parIdentifiant;
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

    /** Two ends agree when equal, {@code 24:00} having been read as {@code 00:00} on both sides. */
    private static boolean memeFin(LocalTime lue, LocalTime creneau) {
        return Objects.equals(lue, creneau);
    }

    private static String court(LocalTime heure) {
        return heure.toString().length() > 5 ? heure.toString().substring(0, 5) : heure.toString();
    }

    private static void refuseSpreadsheet(String fileName, String content) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String text = content == null ? "" : content;
        if (name.endsWith(".xlsx") || name.endsWith(".xls") || name.endsWith(".ods") || text.startsWith(ZIP_SIGNATURE)
                || text.indexOf('\0') >= 0) {
            throw new BusinessError.Invalid("Ce format n'est pas accepté : seul le CSV est lu. Dans votre tableur, "
                    + "choisissez « Enregistrer sous » puis « CSV (séparateur : point-virgule) », et déposez ce "
                    + "fichier-là.");
        }
    }
}
