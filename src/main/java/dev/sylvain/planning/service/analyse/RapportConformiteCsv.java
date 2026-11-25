package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.service.analyse.RapportConformite.Personne;
import dev.sylvain.planning.service.referentiel.CsvFormulaGuard;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The compliance report as a {@code ;}-separated file for reprocessing: one line
 * per animateur, the same rows as the PDF's table, the hours with a comma like
 * the Heures file. The first line says which plan it comes from — a file read
 * away from the screen has nothing else to say it.
 */
public final class RapportConformiteCsv {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm");
    private static final ZoneId PARIS = ZoneId.of("Europe/Paris");

    private RapportConformiteCsv() {}

    public static String write(RapportConformite rapport) {
        StringBuilder csv = new StringBuilder();
        csv.append("Rapport de conformité : plan publié le ")
                .append(DATE.format(rapport.publieLe().atZone(PARIS)))
                .append(", édité le ")
                .append(DATE.format(rapport.genereLe().atZone(PARIS)))
                .append('\n');
        csv.append("animateur;mineur");
        for (String semaine : rapport.semaines()) {
            csv.append(';').append(semaine);
        }
        csv.append(";total (h);plafond hebdomadaire (h);semaines au-dessus du plafond;dimanche (h);jours feries (h);"
                + "nuit apres 22h (h);repos quotidien minimal observe (h);detail des ecarts\n");
        for (Personne p : rapport.personnes()) {
            csv.append(CsvFormulaGuard.cell(p.nom())).append(';').append(p.mineur() ? "oui" : "non");
            for (String semaine : rapport.semaines()) {
                csv.append(';').append(hours(p.heuresParSemaine().getOrDefault(semaine, 0.0)));
            }
            csv.append(';')
                    .append(hours(p.heuresTotal()))
                    .append(';')
                    .append(hours(p.plafondHebdomadaire()))
                    .append(';')
                    .append(CsvFormulaGuard.cell(String.join(" ", p.semainesEnDepassement())))
                    .append(';')
                    .append(hours(p.heuresDimanche()))
                    .append(';')
                    .append(hours(p.heuresFerie()))
                    .append(';')
                    .append(hours(p.heuresNuit()))
                    .append(';')
                    .append(p.reposQuotidienMinHeures() == null ? "" : hours(p.reposQuotidienMinHeures()))
                    .append(';')
                    .append(CsvFormulaGuard.cell(p.ecartsDetail().stream()
                            .map(e -> e.libelle() + " : " + e.texte())
                            .collect(Collectors.joining(" | "))))
                    .append('\n');
        }
        return csv.toString();
    }

    /** A comma, the decimal separator of the spreadsheets this opens in (see {@link PlanningHoursService}). */
    private static String hours(double value) {
        return String.format(Locale.ROOT, "%.2f", value).replace('.', ',');
    }
}
