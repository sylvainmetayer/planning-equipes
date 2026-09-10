package dev.sylvain.planning.service.referentiel;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.referentiel.GrilleHorairesStands.SaisieCellule;
import dev.sylvain.planning.service.referentiel.StandGrilleImportReport.ImportGrilleAction;
import dev.sylvain.planning.service.referentiel.StandGrilleImportReport.ImportedColumn;
import dev.sylvain.planning.service.referentiel.StandGrilleImportReport.ImportedGrilleRow;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer;
import dev.sylvain.planning.service.solve.SolverJobService;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.ReferenceDataChangeTracker;

/**
 * Turns the organiser's stand matrix — one row per stand, one column per
 * (date, band), an integer per cell — into the stands' schedules, in two
 * calls and only then: {@link #preview} reads and reports, {@link #apply}
 * re-reads, re-checks and writes in one transaction. The contract of the
 * animateur import ({@code docs/decisions/0021}), transposed:
 *
 * <ul>
 *   <li><b>Columns</b> land on <em>every</em> créneau of the same date and
 *       hours — a grid staggered into families holds one per family, and each
 *       gets the cell. A column no créneau matches is <em>ignored and
 *       listed</em>, not a reason to refuse the file: a workbook often carries a
 *       band the edition does not have. A créneau the file has no column for
 *       keeps each stand's current cell — the import only overrides what the
 *       file states, so an opening covering part of such a créneau comes back
 *       widened to the whole of it, which the report warns about.</li>
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

    /**
     * The file is read in memory in one piece, as for the animateur import and
     * under the same character cap. The row cap is lower: a matrix holds one
     * row per stand, where a roster holds one per person.
     */
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
                bandes.append(';').append(bandeCsv(creneau));
                premier = false;
            }
        }
        csv.append('\n').append(bandes).append('\n');
        Map<String, Map<Long, CelluleActuelle>> cellules = cellulesActuelles(edition);
        for (Stand stand : stands.list()) {
            csv.append(csv(stand.getId()));
            Map<Long, CelluleActuelle> ligne = cellules.getOrDefault(stand.getId(), Map.of());
            for (List<Creneau> duJour : parJour.values()) {
                for (Creneau creneau : duJour) {
                    CelluleActuelle actuelle = ligne.get(creneau.getId());
                    Integer effectif = actuelle == null ? null : actuelle.effectif();
                    csv.append(';').append(effectif == null ? "" : effectif);
                }
            }
            csv.append('\n');
        }
        return csv.toString();
    }

    /* --------------------------------- analysis -------------------------------- */

    private record Analyse(String separator, List<ImportedColumn> columns, List<String> creneauxAbsents,
            List<ImportedGrilleRow> rows, List<String> warnings, List<Stand> aEcrire) {

        StandGrilleImportReport report(boolean applied) {
            int accepted = (int) rows.stream().filter(row -> row.action() == ImportGrilleAction.UPDATED).count();
            return new StandGrilleImportReport(applied, separator, columns, creneauxAbsents, rows.size(), accepted,
                    rows.size() - accepted, rows, warnings);
        }
    }

    private Analyse analyse(StandGrilleImportRequest request) {
        if (request == null) {
            throw new BusinessError.Invalid("Aucun fichier reçu : déposez le CSV de la matrice.");
        }
        refuseSpreadsheet(request.fileName(), request.content());
        String content = request.content() == null ? "" : request.content();
        if (content.isBlank()) {
            throw new BusinessError.Invalid("Le fichier est vide.");
        }
        if (content.length() > MAX_CHARACTERS) {
            throw new BusinessError.Invalid("Fichier trop volumineux : " + grouped(MAX_CHARACTERS)
                    + " caractères au maximum.");
        }
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

        // Each column lands on the créneaux of the edition sharing its date and hours, or on none.
        List<ImportedColumn> columns = new ArrayList<>();
        Map<Integer, List<Long>> creneauParColonne = new HashMap<>();
        Set<Long> dejaPris = new HashSet<>();
        for (GrilleCsv.Colonne colonne : matrice.colonnes()) {
            if (!colonne.namesCreneau()) {
                columns.add(new ImportedColumn(colonne.index(), colonne.libelle(), colonne.date(),
                        colonne.heureDebut(), colonne.heureFin(), null, 0,
                        "En-tête illisible : attendu une date et une bande « 10:00-12:00 » ou « 10h00-12h00 ». "
                                + "Une bande devenue une date, « 30/11/1999 13:16:00 », est le signe d'un tableur qui "
                                + "l'a convertie : repartez du modèle téléchargé, dont les bandes sont écrites avec "
                                + "un « h » pour cette raison."));
                continue;
            }
            // Every créneau of that date and those hours, not the first: a grid
            // staggered into families holds several, one per family, and landing
            // on one of them left every stand of the others untouched — reported
            // as updated all the same.
            List<Creneau> cibles = edition.stream()
                    .filter(creneau -> colonne.date().equals(creneau.getDate())
                            && colonne.heureDebut().equals(creneau.getHeureDebut())
                            && Objects.equals(colonne.heureFin(), creneau.getHeureFin()))
                    .toList();
            if (cibles.isEmpty()) {
                columns.add(new ImportedColumn(colonne.index(), colonne.libelle(), colonne.date(),
                        colonne.heureDebut(), colonne.heureFin(), null, cibles.size(),
                        "Aucun créneau de l'édition à cette date et ces heures : colonne ignorée."));
                continue;
            }
            if (cibles.stream().anyMatch(creneau -> dejaPris.contains(creneau.getId()))) {
                columns.add(new ImportedColumn(colonne.index(), colonne.libelle(), colonne.date(),
                        colonne.heureDebut(), colonne.heureFin(), null, cibles.size(),
                        colonne.dateHeritee()
                                ? "Cette colonne n'a pas de date à elle et reprend le " + colonne.date()
                                        + " de la colonne précédente, qui nomme déjà ce créneau : elle est ignorée. "
                                        + "Écrivez la date sur chaque première colonne de journée."
                                : "Une colonne précédente nomme déjà ce créneau : celle-ci est ignorée."));
                continue;
            }
            List<Long> ids = cibles.stream().map(Creneau::getId).toList();
            dejaPris.addAll(ids);
            creneauParColonne.put(colonne.index(), ids);
            columns.add(new ImportedColumn(colonne.index(), colonne.libelle(), colonne.date(),
                    colonne.heureDebut(), colonne.heureFin(), ids.get(0), ids.size(), null));
        }
        if (creneauParColonne.isEmpty()) {
            throw new BusinessError.Invalid("Aucune colonne du fichier ne correspond à un créneau de l'édition. "
                    + "Attendu : une ligne de dates, une ligne de bandes « 10:00-12:00 », ou des en-têtes "
                    + "« 2026-07-08 10:00-12:00 ».");
        }
        List<String> creneauxAbsents = new ArrayList<>();
        for (Creneau creneau : edition) {
            if (!dejaPris.contains(creneau.getId())) {
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
        Map<String, Map<Long, CelluleActuelle>> actuelles = cellulesActuelles(edition);
        // Stands whose kept cells — those the file has no column for — hold an
        // opening covering only part of a créneau: rewritten from a headcount
        // alone, it comes back covering the whole of it.
        List<String> elargis = new ArrayList<>();
        List<ImportedGrilleRow> rows = new ArrayList<>();
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
                rows.add(new ImportedGrilleRow(ligne.line(), ligne.stand(), null, ImportGrilleAction.REJECTED, reasons, 0, 0, 0,
                        null, null));
                continue;
            }
            if (dejaVus.containsKey(stand.getId())) {
                reasons.add("Le stand " + stand.getId() + " est déjà décrit ligne " + dejaVus.get(stand.getId())
                        + " : cette ligne est ignorée.");
                rows.add(new ImportedGrilleRow(ligne.line(), ligne.stand(), stand.getId(), ImportGrilleAction.REJECTED, reasons,
                        0, 0, 0, null, null));
                continue;
            }
            Map<Long, CelluleActuelle> avant = actuelles.getOrDefault(stand.getId(), Map.of());
            Map<Long, Integer> cellules = new TreeMap<>();
            avant.forEach((creneauId, cellule) -> cellules.put(creneauId, cellule.effectif()));
            int ouvertes = 0;
            for (int index = 0; index < matrice.colonnes().size(); index++) {
                GrilleCsv.Colonne colonne = matrice.colonnes().get(index);
                List<Long> creneauIds = creneauParColonne.get(colonne.index());
                if (creneauIds == null) {
                    continue;
                }
                String brut = index < ligne.cellules().size() ? ligne.cellules().get(index) : "";
                GrilleCsv.CelluleLue lue = GrilleCsv.cellule(brut);
                if (!lue.lisible()) {
                    reasons.add("Colonne « " + colonne.libelle() + " » : « " + brut.trim()
                            + " » n'est pas un effectif (un entier, vide ou « - » pour fermé).");
                    continue;
                }
                for (Long creneauId : creneauIds) {
                    cellules.put(creneauId, lue.effectif());
                }
                if (lue.effectif() != null) {
                    ouvertes++;
                }
            }
            if (!reasons.isEmpty()) {
                rows.add(new ImportedGrilleRow(ligne.line(), ligne.stand(), stand.getId(), ImportGrilleAction.REJECTED, reasons,
                        ouvertes, 0, 0, null, null));
                continue;
            }
            List<SaisieCellule> saisie = new ArrayList<>();
            for (Creneau creneau : edition) {
                saisie.add(new SaisieCellule(creneau.getId(), cellules.get(creneau.getId())));
                CelluleActuelle actuelle = avant.get(creneau.getId());
                if (!dejaPris.contains(creneau.getId()) && actuelle != null && actuelle.partiel()
                        && !elargis.contains(stand.getId())) {
                    elargis.add(stand.getId());
                }
            }
            try {
                GrilleHorairesStands.LigneGrille grille = GrilleHorairesStands.apply(stand, edition, saisie);
                StandValidator.check(stand);
                dejaVus.put(stand.getId(), ligne.line());
                aEcrire.add(stand);
                rows.add(new ImportedGrilleRow(ligne.line(), ligne.stand(), stand.getId(), ImportGrilleAction.UPDATED, List.of(),
                        ouvertes, grille.regles(), grille.exceptions(), grille.effectifMin(), grille.effectifMax()));
            } catch (BusinessError e) {
                rows.add(new ImportedGrilleRow(ligne.line(), ligne.stand(), stand.getId(), ImportGrilleAction.REJECTED,
                        List.of(e.getMessage()), ouvertes, 0, 0, null, null));
            }
        }
        if (!elargis.isEmpty()) {
            warnings.add(elargis.size() + " stand(s) n'ouvrent qu'une partie d'un créneau sans colonne dans le "
                    + "fichier (" + citer(elargis) + ") : l'import réécrit tout leur horaire depuis des effectifs, "
                    + "et ces ouvertures seront élargies au créneau entier. Donnez une colonne à ces créneaux pour "
                    + "dire ce qu'ils doivent devenir.");
        }
        return new Analyse(matrice.separator(), columns, creneauxAbsents, rows, warnings, aEcrire);
    }

    /**
     * What every stand currently does on every créneau, read off the same report
     * the entry grid reads — {@code partiel} kept, because a headcount alone
     * does not say that the stand only covers part of the créneau.
     */
    private Map<String, Map<Long, CelluleActuelle>> cellulesActuelles(List<Creneau> edition) {
        Map<String, Map<Long, CelluleActuelle>> cellules = new HashMap<>();
        OuvertureStandsAnalyzer.RapportOuvertures rapport = OuvertureStandsAnalyzer.analyze(stands.listSolved(), edition);
        for (OuvertureStandsAnalyzer.LigneStand ligne : rapport.stands()) {
            Map<Long, CelluleActuelle> parCreneau = new HashMap<>();
            for (OuvertureStandsAnalyzer.CelluleJour jour : ligne.jours()) {
                for (OuvertureStandsAnalyzer.CelluleCreneau cellule : jour.creneaux()) {
                    parCreneau.put(cellule.creneauId(), new CelluleActuelle(cellule.effectif(), cellule.partiel()));
                }
            }
            cellules.put(ligne.standId(), parCreneau);
        }
        return cellules;
    }

    /** A stand's current cell on one créneau: its headcount, and whether it only covers part of it. */
    private record CelluleActuelle(Integer effectif, boolean partiel) {
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

    /**
     * A band written {@code 09h00-12h00}, not {@code 09:00-12:00}.
     *
     * <p>Both forms are read back, but only this one survives a spreadsheet:
     * Excel retypes {@code 13:00-16:00} into the date-time {@code 13:16:00},
     * and the band is then lost for good — the file comes back with headers
     * naming no créneau at all. Written with an {@code h}, the cell stays the
     * text it is.</p>
     */
    private static String bandeCsv(Creneau creneau) {
        return court(creneau.getHeureDebut()).replace(':', 'h') + '-'
                + court(creneau.getHeureFin()).replace(':', 'h');
    }

    /** Thousands spaced out, the way the animateur import writes its own caps. */
    private static String grouped(int value) {
        return String.valueOf(value).replaceAll("(?<=\\d)(?=(\\d{3})+$)", " ");
    }

    /** The first few of a list, and how many are left: a warning names examples, never forty ids. */
    private static String citer(List<String> ids) {
        int cites = Math.min(3, ids.size());
        String debut = String.join(", ", ids.subList(0, cites));
        return ids.size() > cites ? debut + " et " + (ids.size() - cites) + " autre(s)" : debut;
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
        if (name.endsWith(".xlsx") || name.endsWith(".xls") || name.endsWith(".ods") || text.startsWith(ZIP_SIGNATURE)
                || text.indexOf('\0') >= 0) {
            throw new BusinessError.Invalid("Ce format n'est pas accepté : seul le CSV est lu. Dans votre tableur, "
                    + "choisissez « Enregistrer sous » puis « CSV (séparateur : point-virgule) », et déposez ce "
                    + "fichier-là.");
        }
    }
}
