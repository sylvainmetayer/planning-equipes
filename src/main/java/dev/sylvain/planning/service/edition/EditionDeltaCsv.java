package dev.sylvain.planning.service.edition;

import dev.sylvain.planning.service.edition.EditionDelta.DeltaLine;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaTimeslotLine;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaValueLine;
import dev.sylvain.planning.service.edition.EditionDelta.DeltaVolumes;
import dev.sylvain.planning.service.referentiel.CsvFormulaGuard;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The delta of two editions as one CSV, a line per difference.
 *
 * <p><b>No person is named</b>: an animateur line carries the ids it has in
 * each edition and the names of the fields that differ, never a name, an
 * e-mail or a birth date — the file leaves the application, and can be
 * forwarded. The labels of every other family (a stand, a game category, a
 * location) describe things, not people, and are kept.</p>
 *
 * <p>Pure: written from the delta alone, unit-tested without a database.</p>
 */
public final class EditionDeltaCsv {

    private EditionDeltaCsv() {}

    static final String HEADER =
            "famille;changement;rapprochement;id reference;id cible;code;libelle;champs;valeur reference;valeur cible";

    public static String write(EditionDelta delta) {
        EditionDelta anonyme = delta.withoutPersonNames();
        StringBuilder csv = new StringBuilder(HEADER).append('\n');
        lines(csv, "typologie", anonyme.typologies());
        lines(csv, "emplacement", anonyme.emplacements());
        lines(csv, "stand", anonyme.stands());
        lines(csv, "animateur", anonyme.animateurs());
        lines(csv, "journee type", anonyme.journeesTypes());
        for (DeltaTimeslotLine line : anonyme.creneaux()) {
            String libelle = "jour " + line.day() + (line.start() == null ? "" : " " + line.start());
            row(
                    csv,
                    "creneau",
                    line.change().name(),
                    line.matching() == null ? null : line.matching().name(),
                    line.referenceId(),
                    line.targetId(),
                    null,
                    libelle,
                    String.join(",", line.fields()),
                    line.referenceDate() == null ? null : line.referenceDate().toString(),
                    line.targetDate() == null ? null : line.targetDate().toString());
        }
        values(csv, anonyme.parametres());
        values(csv, anonyme.ajustements());
        volumes(csv, anonyme.referenceVolumes(), anonyme.targetVolumes());
        return csv.toString();
    }

    private static void lines(StringBuilder csv, String famille, List<DeltaLine> lines) {
        for (DeltaLine line : lines) {
            row(
                    csv,
                    famille,
                    line.change().name(),
                    line.matching() == null ? null : line.matching().name(),
                    line.referenceId(),
                    line.targetId(),
                    line.code(),
                    line.label(),
                    String.join(",", line.fields()),
                    null,
                    null);
        }
    }

    private static void values(StringBuilder csv, List<DeltaValueLine> lines) {
        for (DeltaValueLine line : lines) {
            row(
                    csv,
                    line.group().name().toLowerCase(Locale.ROOT),
                    EditionDelta.DeltaChange.MODIFIED.name(),
                    null,
                    null,
                    null,
                    line.key(),
                    line.label(),
                    null,
                    line.referenceValue(),
                    line.targetValue());
        }
    }

    /** The volumetry of both editions, always written: the figures the delta is read against. */
    private static void volumes(StringBuilder csv, DeltaVolumes reference, DeltaVolumes target) {
        volume(csv, "sieges a pourvoir", reference.posteCount(), target.posteCount());
        volume(csv, "animateurs", reference.animateurCount(), target.animateurCount());
        volume(csv, "heures a pourvoir", decimal(reference.hoursToFill()), decimal(target.hoursToFill()));
        volume(csv, "heures disponibles", decimal(reference.hoursAvailable()), decimal(target.hoursAvailable()));
        volume(csv, "taux de remplissage", ratio(reference.fillRatio()), ratio(target.fillRatio()));
    }

    private static void volume(StringBuilder csv, String cle, Object reference, Object target) {
        row(
                csv,
                "volumes",
                null,
                null,
                null,
                null,
                null,
                cle,
                null,
                reference == null ? null : String.valueOf(reference),
                target == null ? null : String.valueOf(target));
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.2f", value).replace('.', ',');
    }

    private static String ratio(Double value) {
        return value == null ? null : decimal(value);
    }

    private static void row(StringBuilder csv, String... cells) {
        csv.append(Arrays.stream(cells).map(EditionDeltaCsv::escape).collect(Collectors.joining(";")))
                .append('\n');
    }

    /** A text cell, behind a quote first when a spreadsheet would run it as a formula. */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        String cell = CsvFormulaGuard.neutralise(value);
        if (cell.contains(";") || cell.contains("\"") || cell.contains("\n")) {
            return "\"" + cell.replace("\"", "\"\"") + "\"";
        }
        return cell;
    }
}
