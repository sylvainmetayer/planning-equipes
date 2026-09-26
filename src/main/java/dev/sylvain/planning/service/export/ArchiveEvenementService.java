package dev.sylvain.planning.service.export;

import dev.sylvain.planning.config.ConfigMentionsLegales;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.EditionContext;
import dev.sylvain.planning.service.analyse.EquiteService;
import dev.sylvain.planning.service.analyse.PlanningHoursService;
import dev.sylvain.planning.service.edition.EditionService;
import dev.sylvain.planning.service.journal.CurrentAction;
import dev.sylvain.planning.service.publication.PlanPublicationService;
import dev.sylvain.planning.service.referentiel.ReferentielCsvExportService;
import dev.sylvain.planning.service.solve.ConstraintAnalysisStore;
import dev.sylvain.planning.service.solve.PlanSnapshotService;
import dev.sylvain.planning.service.solve.PlanningPersistenceService;
import dev.sylvain.planning.service.solve.PlanningService;
import dev.sylvain.planning.service.solve.SolverJobService;
import dev.sylvain.planning.service.validation.ValidationPrerequisService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The end-of-event archive: the files an edition leaves behind, chosen part by
 * part, in one ZIP with a plain-text manifest that says where they come from.
 *
 * <p>Every part is written by the <b>generator of its own export</b> — the
 * global PDF, the equity and hours CSVs, the referentials, the scenario, the
 * publication review, the individual documents — never by a copy of it: a file
 * of the archive is the file its own screen would have downloaded at that
 * moment, and a test holds the four text parts to it byte for byte.</p>
 *
 * <p>Two things are deliberately absent. The SQL dump covers the whole
 * database, every edition and every espace token included: that is not the
 * archive of one edition, and the scenario is its partitioned, re-importable
 * equivalent. And the espace token: the individual PDFs print the espace link
 * as a URL and a QR code, a credential that has no business in a file kept for
 * a year, so the archive renders them from a plan whose animateurs carry none.</p>
 *
 * <p>Written as a stream: everything that can refuse — the choice, the
 * scenario, the manifest's reads — runs in {@link #prepare}, before the first
 * byte, and the returned writer produces the entries one after the other into
 * the response, so the individual documents of a whole roster never sit in
 * memory at once.</p>
 */
@ApplicationScoped
public class ArchiveEvenementService {

    /**
     * Written by the download, as {@code CsvDownload} does for the isolated
     * exports: the mark is what makes a spreadsheet read the accents, and an
     * archive file must be the very bytes its own screen would have saved.
     */
    private static final String CSV_BOM = "﻿";

    private static final String MANIFEST = "LISEZMOI.txt";
    private static final String REFERENTIALS_FOLDER = "referentiels/";
    private static final String INDIVIDUAL_FOLDER = "plannings-individuels/";

    private static final DateTimeFormatter HUMAN_DATE_TIME = DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm");

    /** One part of the archive: its entry, how the history names it, and what the manifest says of it. */
    public enum ArchivePart {
        PDF_GLOBAL("planning-global.pdf", "pdfGlobal", "le planning global de l'organisation, stand par stand", true),
        EQUITE("equite.csv", "equite", "le tableau d'équité, une ligne par animateur affecté", true),
        HEURES(
                "heures.csv",
                "heures",
                "les heures travaillées par animateur et par semaine ISO, dimanche, férié et nuit (paie, après 22 h)",
                true),
        REFERENTIELS(
                REFERENTIALS_FOLDER,
                "referentiels",
                "les six référentiels en CSV, dans la forme que les onglets d'import relisent",
                false),
        SCENARIO(
                "scenario.yaml",
                "scenario",
                "l'édition entière, réimportable par l'onglet « Scénario » des imports",
                false),
        PUBLICATION(
                "publication.csv",
                "publication",
                "la relecture de publication : qui la prochaine publication préviendrait, et de quoi",
                false),
        INDIVIDUELS(
                INDIVIDUAL_FOLDER,
                "individuels",
                "le planning de chaque animateur, en PDF et en calendrier (ICS), sans lien vers son espace",
                true);

        private final String entry;
        private final String journalName;
        private final String description;
        private final boolean needsPlan;

        ArchivePart(String entry, String journalName, String description, boolean needsPlan) {
            this.entry = entry;
            this.journalName = journalName;
            this.description = description;
            this.needsPlan = needsPlan;
        }

        /** The file, or the folder, the part is written to. */
        public String entry() {
            return entry;
        }

        /** How the history names this part — also the query parameter that asks for it. */
        public String journalName() {
            return journalName;
        }

        /** Whether the part reads the persisted plan, and is therefore empty without one. */
        public boolean needsPlan() {
            return needsPlan;
        }
    }

    /**
     * What the caller asked for.
     *
     * @param parts  the parts to write; {@link #MANIFEST} is always added
     * @param format the layout of the individual PDFs
     */
    public record ArchiveRequest(Set<ArchivePart> parts, FormatPlanning format) {}

    /** Writes the archive into the response — see {@link #prepare}. */
    @FunctionalInterface
    public interface ArchiveWriter {
        void writeTo(OutputStream output) throws IOException;
    }

    /** The archive ready to be written: its file name and the writer producing it. */
    public record PreparedArchive(String fileName, ArchiveWriter writer) {}

    /**
     * What the screen needs to grey out the parts that would come out empty.
     *
     * @param planResolu         a plan is persisted for this edition
     * @param publie             this edition published at least once
     * @param resolutionEnCours  a solve holds this edition: the archive will
     *                           carry the last persisted plan, not the one on its way
     */
    @Schema(requiredProperties = {"planResolu", "publie", "resolutionEnCours"})
    public record ArchiveAvailability(boolean planResolu, boolean publie, boolean resolutionEnCours) {}

    private final EditionContext editionContext;
    private final EditionService editionService;
    private final CurrentAction currentAction;
    private final PlanningPersistenceService persistence;
    private final PlanningExportService exports;
    private final EquiteService equite;
    private final PlanningHoursService hours;
    private final ReferentielCsvExportService referentials;
    private final PlanningService planningService;
    private final PlanPublicationService publication;
    private final PlanSnapshotService snapshots;
    private final ValidationPrerequisService validations;
    private final ConstraintAnalysisStore analysisStore;
    private final SolverJobService solverJobs;
    private final ConfigMentionsLegales legal;
    private final String applicationVersion;

    @Inject
    public ArchiveEvenementService(
            EditionContext editionContext,
            EditionService editionService,
            CurrentAction currentAction,
            PlanningPersistenceService persistence,
            PlanningExportService exports,
            EquiteService equite,
            PlanningHoursService hours,
            ReferentielCsvExportService referentials,
            PlanningService planningService,
            PlanPublicationService publication,
            PlanSnapshotService snapshots,
            ValidationPrerequisService validations,
            ConstraintAnalysisStore analysisStore,
            SolverJobService solverJobs,
            ConfigMentionsLegales legal,
            @ConfigProperty(name = "quarkus.application.version") String applicationVersion) {
        this.editionContext = editionContext;
        this.editionService = editionService;
        this.currentAction = currentAction;
        this.persistence = persistence;
        this.exports = exports;
        this.equite = equite;
        this.hours = hours;
        this.referentials = referentials;
        this.planningService = planningService;
        this.publication = publication;
        this.snapshots = snapshots;
        this.validations = validations;
        this.analysisStore = analysisStore;
        this.solverJobs = solverJobs;
        this.legal = legal;
        this.applicationVersion = applicationVersion;
    }

    /** Which parts would come out empty right now. */
    public ArchiveAvailability availability() {
        return new ArchiveAvailability(
                persistence.countPersistedAssignments() > 0,
                snapshots.lastPublication() != null,
                solveRunning(editionContext.editionIdCourant()));
    }

    /**
     * Checks the request, reads everything the manifest states and returns
     * the writer. Nothing is written yet: a refusal here is still a clean
     * {@code 400}, where a failure halfway through a stream can only cut it.
     *
     * @throws BusinessError.Invalid when no part is chosen — an archive holding
     *         nothing but its manifest is a download that looks like it worked
     */
    public PreparedArchive prepare(ArchiveRequest request) {
        Set<ArchivePart> parts = request.parts() == null || request.parts().isEmpty()
                ? EnumSet.noneOf(ArchivePart.class)
                : EnumSet.copyOf(request.parts());
        if (parts.isEmpty()) {
            throw new BusinessError.Invalid("Cochez au moins une partie à mettre dans l'archive.");
        }
        FormatPlanning format = request.format() == null ? FormatPlanning.DEFAUT : request.format();
        // Names only, in the archive's own order: the history says what left,
        // never a line of what it held.
        currentAction.champsModifies(
                parts.stream().map(ArchivePart::journalName).toList());

        String editionId = editionContext.editionIdCourant();
        var edition = editionService.editionCourante();
        // Read before the stream opens: the scenario is the one part that can
        // refuse (an edition with nothing to export), and it is small.
        String scenario = parts.contains(ArchivePart.SCENARIO) ? planningService.exportScenarioYaml() : null;
        Instant generatedAt = Instant.now();
        String manifest = manifest(edition.getNom(), editionId, generatedAt, parts, format);
        String fileName = fileName(edition.getNom(), editionId, LocalDate.ofInstant(generatedAt, zone()));

        ArchiveWriter writer = output -> editionContext.executeIn(editionId, () -> {
            try {
                write(output, parts, format, manifest, scenario);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        return new PreparedArchive(fileName, writer);
    }

    /* ------------------------------- Writing -------------------------------- */

    private void write(
            OutputStream output, Set<ArchivePart> parts, FormatPlanning format, String manifest, String scenario)
            throws IOException {
        // Closing the ZIP closes the response stream too, as the other ZIP exports
        // do: nothing is written after the central directory.
        try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
            entry(zip, MANIFEST, manifest.getBytes(StandardCharsets.UTF_8));
            // Read once, and only when a part needs it.
            PlanningEvenement planning = null;
            if (parts.contains(ArchivePart.PDF_GLOBAL)) {
                planning = loadedPlan(planning);
                entry(zip, ArchivePart.PDF_GLOBAL.entry(), exports.exportGlobalPdf(planning));
            }
            if (parts.contains(ArchivePart.EQUITE)) {
                csvEntry(zip, ArchivePart.EQUITE.entry(), EquiteService.generateCsv(equite.rapport()));
            }
            if (parts.contains(ArchivePart.HEURES)) {
                planning = loadedPlan(planning);
                csvEntry(zip, ArchivePart.HEURES.entry(), hours.generateCsv(hours.compute(planning)));
            }
            if (parts.contains(ArchivePart.REFERENTIELS)) {
                referentials.writeEntries(
                        EnumSet.allOf(ReferentielCsvExportService.ExportTarget.class), zip, REFERENTIALS_FOLDER);
            }
            if (scenario != null) {
                entry(zip, ArchivePart.SCENARIO.entry(), scenario.getBytes(StandardCharsets.UTF_8));
            }
            if (parts.contains(ArchivePart.PUBLICATION)) {
                csvEntry(
                        zip, ArchivePart.PUBLICATION.entry(), PlanPublicationService.generateCsv(publication.apercu()));
            }
            if (parts.contains(ArchivePart.INDIVIDUELS)) {
                // Last, and the largest: one document at a time into the response.
                exports.writeAllBundle(withoutEspaceTokens(loadedPlan(planning)), format, zip, INDIVIDUAL_FOLDER);
            }
        }
    }

    private PlanningEvenement loadedPlan(PlanningEvenement alreadyRead) {
        return alreadyRead != null ? alreadyRead : persistence.loadPersistedPlanning();
    }

    /**
     * The plan with no espace token on any animateur, so the individual
     * documents print neither the link nor its QR code. The plan was read for
     * this archive alone, so clearing it touches nobody else's copy.
     */
    private static PlanningEvenement withoutEspaceTokens(PlanningEvenement planning) {
        for (Animateur animateur : planning.getAnimateurs()) {
            animateur.setAccessToken(null);
        }
        return planning;
    }

    private static void entry(ZipOutputStream zip, String name, byte[] content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content);
        zip.closeEntry();
    }

    private static void csvEntry(ZipOutputStream zip, String name, String csv) throws IOException {
        entry(zip, name, (CSV_BOM + csv).getBytes(StandardCharsets.UTF_8));
    }

    /* ------------------------------- Manifest ------------------------------- */

    /**
     * The manifest, plain text for whoever reopens the ZIP in a year without
     * the application: which edition, when, from which plan, what each file
     * is, and what the archive holds of people.
     */
    String manifest(
            String editionNom, String editionId, Instant generatedAt, Set<ArchivePart> parts, FormatPlanning format) {
        StringBuilder text = new StringBuilder();
        line(text, "Archive de fin d'événement");
        line(text, "==========================");
        line(text, "");
        line(text, "Édition : " + editionNom + " (" + editionId + ")");
        line(text, "Générée le : " + format(generatedAt));
        line(text, "Version de l'application : " + applicationVersion);
        line(text, "");

        line(text, "Provenance du plan");
        line(text, "------------------");
        PlanningPersistenceService.PlanningResolution resolution = persistence.loadResolution();
        boolean planResolu = persistence.countPersistedAssignments() > 0;
        if (!planResolu) {
            line(text, "- Aucun plan résolu : les fichiers tirés du plan (PDF, équité, heures) sont vides.");
        } else {
            String date = resolution == null || resolution.resoluLe() == null
                    ? "date inconnue"
                    : format(resolution.resoluLe());
            line(text, "- Dernière résolution : " + date + ", score " + score());
        }
        if (solveRunning(editionId)) {
            line(
                    text,
                    "- Une résolution était en cours : l'archive porte sur le dernier plan enregistré"
                            + " avant elle.");
        }
        PlanSnapshotService.SnapshotMeta derniere = snapshots.lastPublication();
        line(
                text,
                derniere == null || derniere.publieLe() == null
                        ? "- Dernière publication : aucune publication"
                        : "- Dernière publication : " + format(derniere.publieLe()));
        ValidationPrerequisService.ProgressionValidations relecture = validations.progression();
        line(text, "- Journées relues : " + relecture.journeesValidees() + " sur " + relecture.journees());
        line(text, "");

        line(text, "Fichiers");
        line(text, "--------");
        line(text, "- " + MANIFEST + " : ce manifeste.");
        for (ArchivePart part : parts) {
            String description = part.description;
            if (part == ArchivePart.INDIVIDUELS) {
                description += format == FormatPlanning.FEUILLE ? " (format feuille)" : " (format livret)";
            }
            line(text, "- " + part.entry() + " : " + description + ".");
        }
        line(text, "");

        line(text, "Données personnelles");
        line(text, "--------------------");
        line(
                text,
                "Cette archive contient des données personnelles : noms, dates de naissance, adresses"
                        + " e-mail, heures travaillées, dont celles de personnes mineures.");
        line(
                text,
                "Une fois téléchargée, elle échappe à la conservation que l'application applique : sa garde"
                        + " et sa suppression reviennent à qui la détient.");
        line(
                text,
                "Aucun jeton d'accès à l'espace animateur n'y figure : les plannings individuels ne portent"
                        + " ni le lien ni le QR code de l'espace.");
        line(text, "Responsable de traitement : " + orUnset(legal.responsableTraitement()));
        line(text, "Durée de conservation : " + orUnset(legal.donnees().conservation()));
        return text.toString();
    }

    private String score() {
        ConstraintAnalysisStore.StoredAnalysis analysis = analysisStore.latest();
        return analysis == null || analysis.diagnostic() == null
                ? "inconnu"
                : analysis.diagnostic().score();
    }

    private boolean solveRunning(String editionId) {
        return solverJobs
                .findActive()
                .map(SolverJobService.SolverJob::getEditionId)
                .filter(editionId::equals)
                .isPresent();
    }

    private static String orUnset(Optional<String> value) {
        return value.filter(v -> !v.isBlank()).orElse("non renseigné");
    }

    private static String format(Instant instant) {
        return HUMAN_DATE_TIME.format(instant.atZone(zone())) + " (" + zone().getId() + ")";
    }

    private static ZoneId zone() {
        return ZoneId.systemDefault();
    }

    private static void line(StringBuilder text, String line) {
        // CRLF: the manifest is opened by whatever text editor the archive
        // lands next to, and the one Windows ships with is the likeliest.
        text.append(line).append("\r\n");
    }

    /**
     * {@code archive-<edition>-<AAAA-MM-JJ>.zip}: the edition's name reduced to
     * what every file system and every {@code Content-Disposition} accept, its
     * id when nothing of the name is left.
     */
    static String fileName(String editionNom, String editionId, LocalDate date) {
        String slug = slug(editionNom);
        if (slug.isEmpty()) {
            slug = slug(editionId);
        }
        return "archive-" + (slug.isEmpty() ? "edition" : slug) + "-" + date + ".zip";
    }

    private static String slug(String value) {
        if (value == null) {
            return "";
        }
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "")
                .replaceAll("[^A-Za-z0-9]+", "-")
                .replaceAll("^-+|-+$", "")
                .toLowerCase(java.util.Locale.ROOT);
    }
}
