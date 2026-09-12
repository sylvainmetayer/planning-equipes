package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.ReferenceDataChangeTracker;
import dev.sylvain.planning.service.referentiel.ReferentielCsvImportReport.ActionImport;
import dev.sylvain.planning.service.referentiel.ReferentielCsvImportReport.ImportTarget;
import dev.sylvain.planning.service.referentiel.ReferentielCsvImportReport.LigneImportee;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The three small referentials read from a CSV: typologies, emplacements and
 * stands, so an edition starts from the spreadsheet the organiser already has
 * rather than from sixty dialogs.
 *
 * <p><b>A column the file does not carry leaves the field alone</b>, and so
 * does an empty cell — the doctrine of the compétences grid
 * ({@code docs/decisions/0030}) applied to a fiche: a three-column file
 * renaming stands must not erase their opening hours, their emplacement or
 * their flags. Only what is written is written.</p>
 *
 * <p>Previewing and writing run the same analysis; the write re-reads the file
 * rather than trusting what the browser was shown, exactly as
 * {@link AnimateurCsvImportService} does.</p>
 */
@ApplicationScoped
public class ReferentielCsvImportService {

    /** A stand the file does not size holds one person: enough to produce a seat, small enough to be obviously provisional. */
    static final int EFFECTIF_PAR_DEFAUT = 1;

    private static final int MAX_CHARACTERS = 1_000_000;

    private static final int MAX_ROWS = 5_000;

    private static final String ZIP_SIGNATURE = "PK";

    /** How a cell lists several values, as the animateur import already reads them — never a slash, a date uses it. */
    private static final String SEPARATEUR_MULTI = "[|;,\\n]";

    @Inject
    TypologieService typologies;

    @Inject
    EmplacementService emplacements;

    @Inject
    StandService stands;

    @Inject
    ReferenceDataChangeTracker changeTracker;

    /* ------------------------------- Entry points ------------------------------- */

    public ReferentielCsvImportReport preview(ImportTarget cible, ReferentielCsvImportRequest request) {
        return analyse(cible, request).report(false);
    }

    /** Writes what the preview announced, row by row, after reading the file again. */
    public ReferentielCsvImportReport apply(ImportTarget cible, ReferentielCsvImportRequest request) {
        Analyse analyse = analyse(cible, request);
        for (String typologieId : analyse.typologiesACreer()) {
            typologies.importer(new TypologieItem(typologieId, typologieId, false, null));
        }
        for (Ecriture ecriture : analyse.ecritures()) {
            ecriture.ecrire(this);
        }
        if (!analyse.ecritures().isEmpty() || !analyse.typologiesACreer().isEmpty()) {
            changeTracker.markModified();
        }
        return analyse.report(true);
    }

    /** The file the screen offers to download, so the shape is shown rather than described. */
    public String exemple(ImportTarget cible) {
        return switch (cible) {
            case TYPOLOGIES -> """
                    id;libelle;ninja
                    AMBIANCE;Jeux d'ambiance;
                    STRATEGIE;Jeux de stratégie;
                    POLYVALENT;Polyvalent;oui
                    """;
            case EMPLACEMENTS -> """
                    id;nom;latitude;longitude
                    PAVILLON;Pavillon central;46.6503;2.2539
                    CHAPITEAU;Chapiteau nord;46.6511;2.2553
                    EXTERIEUR;Esplanade;;
                    """;
            case STANDS -> """
                    id;nom;typologies;effectifMin;effectifMax
                    S1;Stand des familles;AMBIANCE;2;3
                    S2;Stand stratégie;STRATEGIE|AMBIANCE;1;2
                    S3;Stand découverte;AMBIANCE;;
                    """;
        };
    }

    public static String exampleFileName(ImportTarget cible) {
        return switch (cible) {
            case TYPOLOGIES -> "exemple-typologies.csv";
            case EMPLACEMENTS -> "exemple-emplacements.csv";
            case STANDS -> "exemple-stands.csv";
        };
    }

    /* ------------------------------- Analysis ------------------------------- */

    private Analyse analyse(ImportTarget cible, ReferentielCsvImportRequest request) {
        refuseSpreadsheet(request.fileName(), request.content());
        String contenu = request.content() == null ? "" : request.content();
        if (contenu.isBlank()) {
            throw new BusinessError.Invalid("Le fichier est vide.");
        }
        if (contenu.length() > MAX_CHARACTERS) {
            throw new BusinessError.Invalid(
                    "Fichier trop volumineux : " + groupe(MAX_CHARACTERS) + " caractères au maximum.");
        }
        CsvParser.Table table = CsvParser.parse(contenu);
        if (table.rows().size() > MAX_ROWS) {
            throw new BusinessError.Invalid("Fichier trop long : " + groupe(MAX_ROWS) + " lignes au maximum.");
        }
        Colonnes colonnes = Colonnes.of(cible, table.columns());
        return switch (cible) {
            case TYPOLOGIES -> analyseTypologies(table, colonnes);
            case EMPLACEMENTS -> analyseEmplacements(table, colonnes);
            case STANDS -> analyseStands(table, colonnes);
        };
    }

    private Analyse analyseTypologies(CsvParser.Table table, Colonnes colonnes) {
        Map<String, TypologieItem> existantes = new LinkedHashMap<>();
        typologies.list().forEach(typologie -> existantes.put(typologie.id(), typologie));
        List<LigneImportee> lignes = new ArrayList<>();
        List<Ecriture> ecritures = new ArrayList<>();
        Set<String> vus = new LinkedHashSet<>();
        for (CsvParser.Row row : table.rows()) {
            String id = colonnes.valeur(row, "id");
            String libelle = colonnes.valeur(row, "libelle");
            List<String> raisons = new ArrayList<>();
            if (id.isBlank()) {
                raisons.add("La colonne « id » est vide : c'est elle que les stands et les compétences citeront.");
            }
            if (libelle.isBlank()) {
                raisons.add("La colonne « libelle » est vide : c'est le nom lu à l'écran.");
            }
            if (!id.isBlank() && !vus.add(id)) {
                raisons.add("L'identifiant « " + id + " » apparaît déjà plus haut dans le fichier.");
            }
            if (!raisons.isEmpty()) {
                lignes.add(new LigneImportee(
                        row.line(), blankAsNull(id), blankAsNull(libelle), ActionImport.REFUSE, raisons, List.of()));
                continue;
            }
            boolean existe = existantes.containsKey(id);
            boolean ninja = readFlag(colonnes.valeur(row, "ninja"));
            List<String> details = new ArrayList<>();
            if (ninja) {
                details.add("Marquée polyvalente : elle retire le drapeau à la typologie qui le portait.");
            }
            TypologieItem ecrite = new TypologieItem(id, libelle, ninja, null);
            ecritures.add(service -> service.typologies.importer(ecrite));
            lignes.add(new LigneImportee(
                    row.line(), id, libelle, existe ? ActionImport.MIS_A_JOUR : ActionImport.CREE, List.of(), details));
        }
        return new Analyse(ImportTarget.TYPOLOGIES, table, lignes, ecritures, List.of());
    }

    private Analyse analyseEmplacements(CsvParser.Table table, Colonnes colonnes) {
        Map<String, Emplacement> existants = new LinkedHashMap<>();
        emplacements.list().forEach(emplacement -> existants.put(emplacement.getId(), emplacement));
        List<LigneImportee> lignes = new ArrayList<>();
        List<Ecriture> ecritures = new ArrayList<>();
        Set<String> vus = new LinkedHashSet<>();
        for (CsvParser.Row row : table.rows()) {
            String id = colonnes.valeur(row, "id");
            String nom = colonnes.valeur(row, "nom");
            List<String> raisons = new ArrayList<>();
            if (id.isBlank()) {
                raisons.add("La colonne « id » est vide.");
            }
            if (nom.isBlank() && !existants.containsKey(id)) {
                raisons.add("La colonne « nom » est vide, et l'emplacement n'existe pas encore.");
            }
            if (!id.isBlank() && !vus.add(id)) {
                raisons.add("L'identifiant « " + id + " » apparaît déjà plus haut dans le fichier.");
            }
            Double latitude = null;
            Double longitude = null;
            try {
                latitude = readCoordinate(colonnes.valeur(row, "latitude"), "latitude");
                longitude = readCoordinate(colonnes.valeur(row, "longitude"), "longitude");
            } catch (BusinessError.Invalid e) {
                raisons.add(e.getMessage());
            }
            if (!raisons.isEmpty()) {
                lignes.add(new LigneImportee(
                        row.line(), blankAsNull(id), blankAsNull(nom), ActionImport.REFUSE, raisons, List.of()));
                continue;
            }
            Emplacement existant = existants.get(id);
            Emplacement ecrit = new Emplacement();
            ecrit.setId(id);
            ecrit.setNom(nom.isBlank() ? existant.getNom() : nom);
            // An absent column, or an empty cell, never takes away what is there.
            ecrit.setLatitude(latitude != null ? latitude : (existant == null ? null : existant.getLatitude()));
            ecrit.setLongitude(longitude != null ? longitude : (existant == null ? null : existant.getLongitude()));
            List<String> details = new ArrayList<>();
            if (ecrit.getLatitude() == null || ecrit.getLongitude() == null) {
                details.add("Sans coordonnées : l'emplacement ne pèsera pas sur les distances entre stands.");
            }
            ecritures.add(
                    existant == null
                            ? service -> service.emplacements.create(ecrit)
                            : service -> service.emplacements.update(id, ecrit));
            lignes.add(new LigneImportee(
                    row.line(),
                    id,
                    ecrit.getNom(),
                    existant == null ? ActionImport.CREE : ActionImport.MIS_A_JOUR,
                    List.of(),
                    details));
        }
        return new Analyse(ImportTarget.EMPLACEMENTS, table, lignes, ecritures, List.of());
    }

    private Analyse analyseStands(CsvParser.Table table, Colonnes colonnes) {
        Map<String, Stand> existants = new LinkedHashMap<>();
        stands.list().forEach(stand -> existants.put(stand.getId(), stand));
        Set<String> typologiesConnues = new LinkedHashSet<>();
        typologies.list().forEach(typologie -> typologiesConnues.add(typologie.id()));
        Set<String> aCreer = new TreeSet<>();
        List<LigneImportee> lignes = new ArrayList<>();
        List<Ecriture> ecritures = new ArrayList<>();
        Set<String> vus = new LinkedHashSet<>();
        for (CsvParser.Row row : table.rows()) {
            String id = colonnes.valeur(row, "id");
            String nom = colonnes.valeur(row, "nom");
            Stand existant = existants.get(id);
            List<String> raisons = new ArrayList<>();
            List<String> details = new ArrayList<>();
            if (id.isBlank()) {
                raisons.add("La colonne « id » est vide.");
            }
            if (nom.isBlank() && existant == null) {
                raisons.add("La colonne « nom » est vide, et le stand n'existe pas encore.");
            }
            if (!id.isBlank() && !vus.add(id)) {
                raisons.add("L'identifiant « " + id + " » apparaît déjà plus haut dans le fichier.");
            }
            List<String> typologiesLues = valeursMultiples(colonnes.valeur(row, "typologies"));
            Set<String> typologiesStand = typologiesLues.isEmpty() && existant != null
                    ? new LinkedHashSet<>(existant.getTypologiesProposees())
                    : new LinkedHashSet<>(typologiesLues);
            if (typologiesStand.isEmpty()) {
                raisons.add("Aucune typologie : un stand est toujours rattaché à au moins une typologie de jeu.");
            }
            Integer effectifMin = null;
            Integer effectifMax = null;
            try {
                effectifMin = readHeadcount(colonnes.valeur(row, "effectifmin"), "effectifMin");
                effectifMax = readHeadcount(colonnes.valeur(row, "effectifmax"), "effectifMax");
            } catch (BusinessError.Invalid e) {
                raisons.add(e.getMessage());
            }
            if (!raisons.isEmpty()) {
                lignes.add(new LigneImportee(
                        row.line(), blankAsNull(id), blankAsNull(nom), ActionImport.REFUSE, raisons, List.of()));
                continue;
            }
            int min = effectifMin != null
                    ? effectifMin
                    : (existant != null ? existant.getEffectifMin() : EFFECTIF_PAR_DEFAUT);
            int max = effectifMax != null
                    ? effectifMax
                    : (existant != null ? existant.getEffectifMax() : EFFECTIF_PAR_DEFAUT);
            if (max < min) {
                lignes.add(new LigneImportee(
                        row.line(),
                        id,
                        blankAsNull(nom),
                        ActionImport.REFUSE,
                        List.of("effectifMax (" + max + ") est inférieur à effectifMin (" + min + ")."),
                        List.of()));
                continue;
            }
            // Only now: a typologie is created on the strength of the stand that
            // names it, so a row the checks above have refused must not leave one
            // behind — nothing would reference it.
            for (String typologie : typologiesStand) {
                if (!typologiesConnues.contains(typologie) && aCreer.add(typologie)) {
                    details.add(
                            "La typologie « " + typologie + " » sera créée, son libellé reprenant son identifiant.");
                }
            }
            if (existant == null && effectifMin == null && effectifMax == null) {
                details.add(
                        "Effectif non précisé : le stand tient à une personne, à ajuster sur la grille des ouvertures.");
            }
            Stand ecrit = existant == null ? new Stand() : copie(existant);
            ecrit.setId(id);
            ecrit.setNom(nom.isBlank() ? existant.getNom() : nom);
            ecrit.setTypologiesProposees(typologiesStand);
            ecrit.setEffectifMin(min);
            ecrit.setEffectifMax(max);
            boolean creation = existant == null;
            ecritures.add(
                    creation ? service -> service.stands.create(ecrit) : service -> service.stands.update(id, ecrit));
            lignes.add(new LigneImportee(
                    row.line(),
                    id,
                    ecrit.getNom(),
                    creation ? ActionImport.CREE : ActionImport.MIS_A_JOUR,
                    List.of(),
                    details));
        }
        return new Analyse(ImportTarget.STANDS, table, lignes, ecritures, List.copyOf(aCreer));
    }

    /* --------------------------------- Tools --------------------------------- */

    /** Everything but identity: a file that renames a stand must not erase its schedule. */
    private static Stand copie(Stand source) {
        Stand stand = new Stand();
        stand.setId(source.getId());
        stand.setNom(source.getNom());
        stand.setEmplacement(source.getEmplacement());
        stand.setTypologiesProposees(source.getTypologiesProposees());
        stand.setEffectifMin(source.getEffectifMin());
        stand.setEffectifMax(source.getEffectifMax());
        stand.setReserveMajeurs(source.isReserveMajeurs());
        stand.setPremium(source.isPremium());
        stand.setNiveauEffort(source.getNiveauEffort());
        stand.setHoraires(source.getHoraires());
        stand.setOuvertures(source.getOuvertures());
        stand.setIndisponibilites(source.getIndisponibilites());
        stand.setModifieLe(source.getModifieLe());
        return stand;
    }

    private static List<String> valeursMultiples(String cellule) {
        if (cellule == null || cellule.isBlank()) {
            return List.of();
        }
        return new ArrayList<>(new LinkedHashSet<>(List.of(cellule.split(SEPARATEUR_MULTI))))
                .stream().map(String::trim).filter(valeur -> !valeur.isEmpty()).toList();
    }

    private static Integer readHeadcount(String cellule, String champ) {
        if (cellule == null || cellule.isBlank()) {
            return null;
        }
        try {
            int valeur = Integer.parseInt(cellule.trim());
            if (valeur < 0) {
                throw new BusinessError.Invalid(champ + " ne peut pas être négatif : « " + cellule.trim() + " ».");
            }
            return valeur;
        } catch (NumberFormatException e) {
            throw new BusinessError.Invalid(champ + " n'est pas un nombre : « " + cellule.trim() + " ».");
        }
    }

    private static Double readCoordinate(String cellule, String champ) {
        if (cellule == null || cellule.isBlank()) {
            return null;
        }
        try {
            return Double.parseDouble(cellule.trim().replace(',', '.'));
        } catch (NumberFormatException e) {
            throw new BusinessError.Invalid(champ + " n'est pas un nombre décimal : « " + cellule.trim() + " ».");
        }
    }

    /** « oui », « true », « x », « 1 » — what a spreadsheet writes in a flag column. */
    private static boolean readFlag(String cellule) {
        String valeur = cellule == null ? "" : cellule.trim().toLowerCase(Locale.ROOT);
        return valeur.equals("oui")
                || valeur.equals("true")
                || valeur.equals("vrai")
                || valeur.equals("x")
                || valeur.equals("1");
    }

    private static String blankAsNull(String valeur) {
        return valeur == null || valeur.isBlank() ? null : valeur;
    }

    private static String groupe(int valeur) {
        return String.valueOf(valeur).replaceAll("(?<=\\d)(?=(\\d{3})+$)", " ");
    }

    private static void refuseSpreadsheet(String fileName, String content) {
        String nom = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        String texte = content == null ? "" : content;
        if (nom.endsWith(".xlsx")
                || nom.endsWith(".xls")
                || nom.endsWith(".ods")
                || texte.startsWith(ZIP_SIGNATURE)
                || texte.indexOf('\0') >= 0) {
            throw new BusinessError.Invalid("Ce format n'est pas accepté : seul le CSV est lu. Dans votre tableur, "
                    + "choisissez « Enregistrer sous » puis « CSV (séparateur : point-virgule) », et déposez ce "
                    + "fichier-là.");
        }
    }

    /** One write of the analysis, replayed against the service when the operator confirms. */
    @FunctionalInterface
    private interface Ecriture {
        void ecrire(ReferentielCsvImportService service);
    }

    /** Where each awaited column sits, matched on its header whatever its case or accents. */
    private record Colonnes(Map<String, Integer> index) {

        private static final Map<ImportTarget, List<String>> REQUISES = Map.of(
                ImportTarget.TYPOLOGIES, List.of("id", "libelle"),
                ImportTarget.EMPLACEMENTS, List.of("id", "nom"),
                ImportTarget.STANDS, List.of("id", "nom", "typologies"));

        static Colonnes of(ImportTarget cible, List<String> entetes) {
            Map<String, Integer> index = new LinkedHashMap<>();
            for (int i = 0; i < entetes.size(); i++) {
                index.putIfAbsent(key(entetes.get(i)), i);
            }
            List<String> manquantes = REQUISES.get(cible).stream()
                    .filter(colonne -> !index.containsKey(colonne))
                    .toList();
            if (!manquantes.isEmpty()) {
                throw new BusinessError.Invalid(
                        "Colonne(s) absente(s) du fichier : " + String.join(", ", manquantes)
                                + ". La première ligne doit nommer les colonnes ; téléchargez l'exemple pour la forme attendue.");
            }
            return new Colonnes(index);
        }

        String valeur(CsvParser.Row row, String colonne) {
            Integer position = index.get(colonne);
            return position == null ? "" : row.value(position).trim();
        }

        /** `Effectif Min`, `effectif_min` and `EFFECTIFMIN` are the same column. */
        private static String key(String entete) {
            return StandGrilleImportService.normalise(entete).replaceAll("[^a-z0-9]", "");
        }
    }

    /** What the file would do, kept whole so the preview and the write cannot diverge. */
    private record Analyse(
            ImportTarget cible,
            CsvParser.Table table,
            List<LigneImportee> lignes,
            List<Ecriture> ecritures,
            List<String> typologiesACreer) {

        ReferentielCsvImportReport report(boolean applied) {
            int refusees = (int) lignes.stream()
                    .filter(ligne -> ligne.action() == ActionImport.REFUSE)
                    .count();
            int creees = (int) lignes.stream()
                    .filter(ligne -> ligne.action() == ActionImport.CREE)
                    .count();
            int misesAJour = (int) lignes.stream()
                    .filter(ligne -> ligne.action() == ActionImport.MIS_A_JOUR)
                    .count();
            return new ReferentielCsvImportReport(
                    applied,
                    cible,
                    table.columns(),
                    String.valueOf(table.separator()),
                    lignes.size(),
                    creees + misesAJour,
                    refusees,
                    creees,
                    misesAJour,
                    typologiesACreer,
                    lignes);
        }
    }
}
