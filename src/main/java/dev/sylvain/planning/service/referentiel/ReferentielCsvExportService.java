package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.JourneeType;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.BusinessError;
import dev.sylvain.planning.service.referentiel.JourneesTypesMaterialisation.Affectation;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The edition's referentials written back out as CSV, in the very shape the
 * import tabs read.
 *
 * <p>That symmetry is the whole point: what comes out here goes back in
 * unchanged, so an edition can be copied, corrected in a spreadsheet and
 * replayed. A column the import does not read would break the promise as
 * surely as a missing one, so each file carries exactly the columns of its
 * tab — and {@code ReferentielCsvExportServiceTest} feeds every export back
 * through its own import to keep it that way.</p>
 *
 * <p>The animateur file is the one whose header is not this service's to
 * choose: it mirrors {@code scenarios/exemple-animateurs.csv}, the columns
 * {@link AnimateurCsvMapping} proposes on its own.</p>
 */
@ApplicationScoped
public class ReferentielCsvExportService {

    /** How a cell lists several values, as every import of this application reads them. */
    private static final char SEPARATEUR_MULTI = '|';

    private static final char SEPARATEUR = ';';

    /**
     * Written by the download, never here: a mark inside a zip entry is what
     * makes a spreadsheet read the accents, and the caller owns the bytes.
     */
    private static final String BOM = "﻿";

    @Inject
    TypologieService typologies;

    @Inject
    EmplacementService emplacements;

    @Inject
    StandService stands;

    @Inject
    AnimateurService animateurs;

    @Inject
    CreneauService creneaux;

    @Inject
    JourneeTypeService journeesTypes;

    /** One referential of the edition, as the matching import tab would read it. */
    public enum ExportTarget {
        TYPOLOGIES("typologies.csv"),
        EMPLACEMENTS("emplacements.csv"),
        STANDS("stands.csv"),
        // Before the animateurs, as on the import screen: an off day only
        // survives in an edition that already has the matching dates.
        CRENEAUX("creneaux.csv"),
        JOURNEES_TYPES("journees-types.csv"),
        ANIMATEURS("animateurs.csv");

        private final String fileName;

        ExportTarget(String fileName) {
            this.fileName = fileName;
        }

        public String fileName() {
            return fileName;
        }
    }

    /** How many rows each referential would write — what the screen shows before downloading. */
    public Map<ExportTarget, Integer> counts() {
        return Map.of(
                ExportTarget.TYPOLOGIES, typologies.list().size(),
                ExportTarget.EMPLACEMENTS, emplacements.list().size(),
                ExportTarget.STANDS, stands.list().size(),
                ExportTarget.CRENEAUX, creneaux.list().size(),
                ExportTarget.JOURNEES_TYPES, journeesTypes.list().size(),
                ExportTarget.ANIMATEURS, animateurs.list().size());
    }

    public String csv(ExportTarget cible) {
        return switch (cible) {
            case TYPOLOGIES -> csvTypologies();
            case EMPLACEMENTS -> csvEmplacements();
            case STANDS -> csvStands();
            case CRENEAUX -> csvCreneaux();
            case JOURNEES_TYPES -> csvJourneesTypes();
            case ANIMATEURS -> csvAnimateurs();
        };
    }

    /**
     * The chosen referentials, one CSV per entry of a zip.
     *
     * @throws BusinessError.Invalid when nothing is chosen — an empty archive
     *         is a download that looks like it worked and holds nothing
     */
    public byte[] zip(Set<ExportTarget> cibles) {
        if (cibles == null || cibles.isEmpty()) {
            throw new BusinessError.Invalid("Choisissez au moins un référentiel à exporter.");
        }
        ByteArrayOutputStream sortie = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(sortie, StandardCharsets.UTF_8)) {
            // Enum order, not the caller's: an archive whose entries move
            // around between two downloads is one nobody can diff.
            for (ExportTarget cible : ExportTarget.values()) {
                if (!cibles.contains(cible)) {
                    continue;
                }
                zip.putNextEntry(new ZipEntry(cible.fileName()));
                zip.write((BOM + csv(cible)).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Écriture de l'archive CSV impossible", e);
        }
        return sortie.toByteArray();
    }

    /* ------------------------------ Referentials ------------------------------ */

    private String csvTypologies() {
        StringBuilder csv = new StringBuilder();
        ligne(csv, "id", "libelle", "ninja");
        for (TypologieItem typologie : typologies.list()) {
            ligne(csv, typologie.id(), typologie.label(), typologie.ninja() ? "oui" : "");
        }
        return csv.toString();
    }

    private String csvEmplacements() {
        StringBuilder csv = new StringBuilder();
        ligne(csv, "id", "nom", "latitude", "longitude");
        for (Emplacement emplacement : emplacements.list()) {
            ligne(
                    csv,
                    emplacement.getId(),
                    emplacement.getNom(),
                    decimal(emplacement.getLatitude()),
                    decimal(emplacement.getLongitude()));
        }
        return csv.toString();
    }

    private String csvStands() {
        StringBuilder csv = new StringBuilder();
        ligne(csv, "id", "nom", "typologies", "effectifMin", "effectifMax");
        for (Stand stand : stands.list()) {
            ligne(
                    csv,
                    stand.getId(),
                    stand.getNom(),
                    joint(new TreeSet<>(stand.getTypologiesProposees())),
                    String.valueOf(stand.getEffectifMin()),
                    String.valueOf(stand.getEffectifMax()));
        }
        return csv.toString();
    }

    /**
     * The grid, one row per timeslot, ordered by date then by hour: a timeslot
     * has no identifier the file could carry — the database generates it and a
     * scenario import reassigns it — so what the import matches on is exactly
     * these three columns.
     */
    private String csvCreneaux() {
        StringBuilder csv = new StringBuilder();
        ligne(csv, "date", "heureDebut", "heureFin", "couverturePause");
        List<Creneau> tries = new ArrayList<>(creneaux.list());
        tries.sort(Comparator.comparing(Creneau::getDate, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Creneau::getHeureDebut, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(Creneau::getHeureFin, Comparator.nullsLast(Comparator.naturalOrder())));
        for (Creneau creneau : tries) {
            ligne(
                    csv,
                    creneau.getDate() == null ? "" : creneau.getDate().toString(),
                    heure(creneau.getHeureDebut()),
                    heure(creneau.getHeureFin()),
                    creneau.isCouverturePause() ? "oui" : "");
        }
        return csv.toString();
    }

    /**
     * One row per day template: its shifts on the compact line the import
     * reads back, and the dates it governs in a multi-value cell.
     *
     * <p>The calendar travels <b>with</b> the template rather than in a file of
     * its own: two files that must agree on a name are two files an operator
     * can desynchronise in a spreadsheet, and this one is already the shape
     * the recognition produces.</p>
     */
    private String csvJourneesTypes() {
        Map<Long, Set<LocalDate>> datesParJourneeType = new LinkedHashMap<>();
        for (Affectation affectation : journeesTypes.calendrier()) {
            datesParJourneeType
                    .computeIfAbsent(affectation.journeeTypeId(), id -> new TreeSet<>())
                    .add(affectation.date());
        }
        StringBuilder csv = new StringBuilder();
        ligne(csv, "nom", "vacations", "dates");
        for (JourneeType journeeType : journeesTypes.list()) {
            ligne(
                    csv,
                    journeeType.getNom(),
                    VacationsLigne.format(journeeType.getVacations()),
                    joint(datesParJourneeType.getOrDefault(journeeType.getId(), Set.of()).stream()
                            .map(LocalDate::toString)
                            .toList()));
        }
        return csv.toString();
    }

    /** {@code HH:MM}, never {@code HH:MM:SS}: the grid has no seconds and a spreadsheet shows them. */
    private static String heure(LocalTime heure) {
        return heure == null ? "" : String.format("%02d:%02d", heure.getHour(), heure.getMinute());
    }

    /**
     * The header of {@code exemple-animateurs.csv}, accents included: it is
     * what the mapping proposes without the operator touching a select.
     */
    private String csvAnimateurs() {
        StringBuilder csv = new StringBuilder();
        ligne(
                csv,
                "identifiant",
                "prénom",
                "nom",
                "date de naissance",
                "email",
                "manager",
                "compétences",
                "souhaits",
                "jours indisponibles");
        for (Animateur animateur : animateurs.list()) {
            ligne(
                    csv,
                    animateur.getId(),
                    animateur.getPrenom(),
                    animateur.getNom(),
                    animateur.getDateNaissance() == null
                            ? ""
                            : animateur.getDateNaissance().toString(),
                    animateur.getEmail() == null ? "" : animateur.getEmail(),
                    animateur.isManager() ? "oui" : "non",
                    competences(animateur),
                    joint(new TreeSet<>(animateur.getSouhaits())),
                    joint(new TreeSet<>(animateur.getJoursIndisponibles())
                            .stream().map(Object::toString).toList()));
        }
        return csv.toString();
    }

    /** {@code TYPOLOGIE:NIVEAU}, the form the import reads; a plain id means « autonome ». */
    private static String competences(Animateur animateur) {
        List<String> valeurs = new ArrayList<>();
        new TreeSet<>(animateur.getCompetences().keySet()).forEach(typologie -> {
            NiveauCompetence niveau = animateur.getCompetences().get(typologie);
            valeurs.add(niveau == null ? typologie : typologie + ":" + niveau.name());
        });
        return joint(valeurs);
    }

    /* --------------------------------- Writing --------------------------------- */

    private static String joint(Iterable<String> valeurs) {
        StringBuilder cellule = new StringBuilder();
        for (String valeur : new LinkedHashSet<>(toList(valeurs))) {
            if (cellule.length() > 0) {
                cellule.append(SEPARATEUR_MULTI);
            }
            cellule.append(valeur);
        }
        return cellule.toString();
    }

    private static List<String> toList(Iterable<String> valeurs) {
        List<String> liste = new ArrayList<>();
        valeurs.forEach(liste::add);
        return liste;
    }

    /**
     * A decimal with a point, never a comma: the cell separator is a semicolon,
     * but a file re-opened in a spreadsheet and saved again may come back with
     * commas — and the import reads both.
     */
    private static String decimal(Double valeur) {
        return valeur == null ? "" : valeur.toString();
    }

    private static void ligne(StringBuilder csv, String... cellules) {
        for (int i = 0; i < cellules.length; i++) {
            if (i > 0) {
                csv.append(SEPARATEUR);
            }
            csv.append(echappe(cellules[i]));
        }
        csv.append('\n');
    }

    /**
     * RFC 4180: a cell holding a separator, a quote or a newline travels quoted.
     *
     * <p><b>Every</b> separator, not only the one this file writes. {@code
     * CsvParser} recognises the dialect by counting {@code ;}, {@code ,} and
     * tabs outside quotes over the whole file, and picks the most frequent: a
     * column of shift lines — « 09:00-12:00, 12:00-13:00 R, 14:00-20:00 » —
     * puts three commas on every row against the header's two semicolons, and
     * the file we just wrote comes back read as comma-separated. Quoting takes
     * those characters out of the count entirely, which is the only reliable
     * way to keep the dialect ours.</p>
     */
    private static String echappe(String valeur) {
        String cellule = valeur == null ? "" : valeur;
        if (cellule.indexOf(SEPARATEUR) >= 0
                || cellule.indexOf(',') >= 0
                || cellule.indexOf('\t') >= 0
                || cellule.indexOf('"') >= 0
                || cellule.indexOf('\n') >= 0
                || cellule.indexOf('\r') >= 0) {
            return '"' + cellule.replace("\"", "\"\"") + '"';
        }
        return cellule;
    }
}
