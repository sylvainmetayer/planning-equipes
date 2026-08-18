package dev.sylvain.planning.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.openpdf.text.Chunk;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.Image;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.ColumnText;
import org.openpdf.text.pdf.PdfAction;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPCellEvent;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfPTableEvent;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfTemplate;
import org.openpdf.text.pdf.PdfWriter;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.PlanningFestival;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
public class PlanningExportService {

    private static final String FESTIVAL_TIMEZONE = "Europe/Paris";
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter FRENCH_DAY_DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH);
    private static final DateTimeFormatter ICS_UTC_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter ICS_LOCAL_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final DateTimeFormatter GENERATED_AT_FORMAT = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final String LOGO_RESOURCE = "/branding/logo.png";
    private static final String STRIP_RESOURCE = "/branding/bandeau.png";

    // --- Palette, sampled from the festival brand mark: crimson red, golden yellow, warm dark ink ---
    private static final java.awt.Color HEADLINE = new java.awt.Color(43, 33, 24);
    private static final java.awt.Color MUTED = new java.awt.Color(146, 121, 87);
    private static final java.awt.Color RED = new java.awt.Color(200, 29, 37);
    private static final java.awt.Color YELLOW = new java.awt.Color(255, 214, 62);
    private static final java.awt.Color CARD_BACKGROUND = java.awt.Color.WHITE;
    private static final java.awt.Color PILL_BACKGROUND = new java.awt.Color(250, 235, 208);

    // --- Fonts: bold rounded sans for headline figures, plain sans for supporting text ---
    private static final Font BRAND_LABEL_FONT = new Font(Font.HELVETICA, 8.5f, Font.BOLD, RED);
    private static final Font NAME_FONT = new Font(Font.HELVETICA, 24, Font.BOLD, HEADLINE);
    private static final Font STAT_NUMBER_FONT = new Font(Font.HELVETICA, 19, Font.BOLD, HEADLINE);
    private static final Font STAT_LABEL_FONT = new Font(Font.HELVETICA, 7.5f, Font.BOLD, HEADLINE);
    private static final Font STAT_SUBLABEL_FONT = new Font(Font.HELVETICA, 6.5f, Font.BOLD, MUTED);
    private static final Font DATE_FONT = new Font(Font.HELVETICA, 13, Font.BOLD, HEADLINE);
    private static final Font CALLOUT_TITLE_FONT = new Font(Font.HELVETICA, 8.5f, Font.BOLD, RED);
    private static final Font CALLOUT_TEXT_FONT = new Font(Font.HELVETICA, 10.5f, Font.NORMAL, HEADLINE);
    private static final Font BADGE_FONT = new Font(Font.HELVETICA, 7.5f, Font.BOLD, java.awt.Color.WHITE);
    private static final Font TIME_FONT = new Font(Font.HELVETICA, 8.5f, Font.BOLD, MUTED);
    private static final Font STAND_FONT = new Font(Font.HELVETICA, 10.5f, Font.BOLD, HEADLINE);
    private static final Font LOCATION_FONT = new Font(Font.HELVETICA, 9, Font.NORMAL, MUTED);
    /** Teammates line under the stand name: present but secondary to the stand itself. */
    private static final Font TEAM_FONT = new Font(Font.HELVETICA, 9, Font.ITALIC, MUTED);
    private static final Font EMPTY_STATE_FONT = new Font(Font.HELVETICA, 10, Font.ITALIC, MUTED);
    private static final Font FOOTER_FONT = new Font(Font.HELVETICA, 8, Font.NORMAL, MUTED);
    // --- Global (organiser) export: dense tables rather than per-seat cards ---
    private static final Font TABLE_HEADER_FONT = new Font(Font.HELVETICA, 8, Font.BOLD, HEADLINE);
    private static final Font TABLE_BODY_FONT = new Font(Font.HELVETICA, 8, Font.NORMAL, HEADLINE);
    private static final Font TABLE_ALERT_FONT = new Font(Font.HELVETICA, 8, Font.BOLD, RED);

    public byte[] exportAnimateurPdf(PlanningFestival planning, String animateurId) {
        List<PosteAffectation> animateurPostes = planning.getPostes().stream()
                .filter(poste -> poste.getAnimateur() != null && animateurId.equals(poste.getAnimateur().getId()))
                .sorted(byCreneauThenStand())
                .toList();
        return buildPdf(resolveAnimateurName(planning, animateurId), animateurPostes,
                coequipiersParPoste(planning, animateurId));
    }

    /**
     * For each of this animateur's postes, the names of the others holding a
     * seat on the same stand, same créneau and same window — who they will
     * actually be working alongside, which is what someone reads their own
     * planning to find out. Keyed by poste id, empty list when they hold the
     * stand alone.
     *
     * <p>Same grouping key as the calendars: two segments of one stand split by
     * a mid-créneau closure are not the same line, and the people on either
     * side never meet.</p>
     *
     * <p>Package-private so the rule is unit-tested on plain objects rather
     * than through the bytes of a generated PDF.</p>
     */
    java.util.Map<String, List<String>> coequipiersParPoste(PlanningFestival planning, String animateurId) {
        java.util.Map<String, List<String>> equipeParLigne = new java.util.LinkedHashMap<>();
        for (PosteAffectation poste : planning.getPostes()) {
            if (poste.getAnimateur() == null || poste.getCreneau() == null || poste.getStand() == null) {
                continue;
            }
            equipeParLigne.computeIfAbsent(ligneKey(poste), ignored -> new ArrayList<>())
                    .add(toDisplayName(poste.getAnimateur()));
        }
        java.util.Map<String, List<String>> parPoste = new java.util.LinkedHashMap<>();
        for (PosteAffectation poste : planning.getPostes()) {
            if (poste.getAnimateur() == null || !animateurId.equals(poste.getAnimateur().getId())
                    || poste.getCreneau() == null || poste.getStand() == null) {
                continue;
            }
            List<String> equipe = new ArrayList<>(equipeParLigne.getOrDefault(ligneKey(poste), List.of()));
            equipe.remove(toDisplayName(poste.getAnimateur()));
            equipe.sort(String.CASE_INSENSITIVE_ORDER);
            parPoste.put(poste.getId(), equipe);
        }
        return parPoste;
    }

    private static String ligneKey(PosteAffectation poste) {
        return poste.getStand().getId() + "@" + poste.getCreneau().getId() + "#" + poste.heureDebutEffectif() + "-"
                + poste.heureFinEffectif();
    }

    /**
     * The whole planning in one landscape PDF, for the organiser rather than
     * for the animateurs: who holds which seat, everywhere, at once.
     *
     * <p>The same assignments are laid out twice, because the two questions an
     * organiser asks on site are not the same one: <b>par journée</b> answers
     * "who is where right now", <b>par stand</b> answers "who runs this stand
     * over the whole festival". Both read as compact tables with repeated
     * headers, one line per stand × vacation rather than one card per seat —
     * the per-animateur card layout of {@link #exportAnimateurPdf} covers a
     * dozen assignments and would run to hundreds of pages here.</p>
     *
     * <p>Seats nobody holds are spelled out in red on their line instead of
     * being silently absent: an unstaffed stand is exactly what the organiser
     * opens this document to find.</p>
     */
    public byte[] exportGlobalPdf(PlanningFestival planning) {
        List<LigneAffectation> lignes = lignesAffectation(planning);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4.rotate(), 34, 34, 34, 50);
        PdfWriter writer = PdfWriter.getInstance(document, output);
        writer.setPageEvent(new FooterEvent("Festival — Centre-ville · planning global généré le "
                + GENERATED_AT_FORMAT.format(Instant.now().atZone(ZoneOffset.systemDefault()))));
        document.open();

        addGlobalHeader(document, planning, lignes);
        if (lignes.isEmpty()) {
            document.add(emptyState());
        } else {
            addSectionParJournee(document, lignes);
            document.newPage();
            addSectionParStand(document, lignes);
        }

        document.close();
        return output.toByteArray();
    }

    /**
     * One line per stand × vacation × open segment: the seats of a same stand
     * on a same window are one line holding every name, not one line each.
     * Mirrors how the calendars group them, and is what makes the document
     * readable at festival scale (3 500 seats becoming ~2 000 lines).
     */
    private List<LigneAffectation> lignesAffectation(PlanningFestival planning) {
        java.util.Map<String, LigneAffectation> parCle = new java.util.LinkedHashMap<>();
        for (PosteAffectation poste : planning.getPostes()) {
            Creneau creneau = poste.getCreneau();
            Stand stand = poste.getStand();
            if (creneau == null || stand == null) {
                continue;
            }
            String cle = stand.getId() + "@" + creneau.getId() + "#" + poste.heureDebutEffectif() + "-"
                    + poste.heureFinEffectif();
            LigneAffectation ligne = parCle.computeIfAbsent(cle,
                    ignored -> new LigneAffectation(stand, creneau, poste.heureDebutEffectif(),
                            poste.heureFinEffectif(), new ArrayList<>(), new int[] { 0 }));
            ligne.sieges()[0]++;
            if (poste.getAnimateur() != null) {
                ligne.animateurs().add(toDisplayName(poste.getAnimateur()));
            }
        }
        List<LigneAffectation> lignes = new ArrayList<>(parCle.values());
        for (LigneAffectation ligne : lignes) {
            ligne.animateurs().sort(String::compareToIgnoreCase);
        }
        return lignes;
    }

    /**
     * One stand × vacation line of the global export.
     *
     * @param sieges seats generated for that line, as a single-element array so
     *               the count can be incremented while grouping — never the
     *               stand's {@code effectifMin}, which a meal-pause coverage
     *               vacation deliberately halves
     */
    private record LigneAffectation(Stand stand, Creneau creneau, LocalTime debut, LocalTime fin,
            List<String> animateurs, int[] sieges) {

        boolean incomplete() {
            return animateurs.size() < sieges[0];
        }
    }

    private void addGlobalHeader(Document document, PlanningFestival planning, List<LigneAffectation> lignes) {
        document.add(brandHeader(document, 420f, "PLANNING GLOBAL", "Toutes les affectations", 18f));

        PdfPTable stats = new PdfPTable(new float[] { 10f, 0.6f, 10f, 0.6f, 10f, 0.6f, 10f });
        stats.setWidthPercentage(100);
        stats.addCell(statCell(distinctDayCount(planning.getPostes()), "JOURS", null));
        stats.addCell(gapCell());
        stats.addCell(statCell(distinctStandCount(planning.getPostes()), "STANDS", null));
        stats.addCell(gapCell());
        int sieges = lignes.stream().mapToInt(ligne -> ligne.sieges()[0]).sum();
        int pourvus = lignes.stream().mapToInt(ligne -> ligne.animateurs().size()).sum();
        stats.addCell(statCell(sieges, "SIÈGES", String.format(Locale.FRENCH, "%d POURVUS", pourvus)));
        stats.addCell(gapCell());
        stats.addCell(statCell(planning.getAnimateurs().size(), "ANIMATEURS",
                String.format(Locale.FRENCH, "TOTAL %.0f H TRAVAILLÉES", totalHeures(planning.getPostes()))));
        stats.setSpacingAfter(20f);
        document.add(stats);
    }

    /** Section 1: chronological reading — one page per festival day. */
    private void addSectionParJournee(Document document, List<LigneAffectation> lignes) {
        document.add(sectionTitle("Planning par journée"));
        List<LocalDate> dates = lignes.stream()
                .map(ligne -> ligne.creneau().getDate())
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        boolean premier = true;
        for (LocalDate date : dates) {
            if (!premier) {
                document.newPage();
            }
            premier = false;
            List<LigneAffectation> duJour = lignes.stream()
                    .filter(ligne -> date.equals(ligne.creneau().getDate()))
                    .sorted(Comparator.comparing(LigneAffectation::debut)
                            .thenComparing(ligne -> ligne.stand().getNom(), String.CASE_INSENSITIVE_ORDER))
                    .toList();
            document.add(groupTitle("Jour " + duJour.get(0).creneau().getJour() + " — " + formatFrenchDayDate(date)));

            PdfPTable table = globalTable(new float[] { 1.3f, 3.2f, 2.4f, 6f, 1f },
                    "Horaires", "Stand", "Emplacement", "Animateurs", "Effectif");
            for (LigneAffectation ligne : duJour) {
                table.addCell(bodyCell(formatHoraires(ligne)));
                table.addCell(bodyCell(ligne.stand().getNom()));
                table.addCell(bodyCell(emplacementNom(ligne.stand())));
                table.addCell(animateursCell(ligne));
                table.addCell(effectifCell(ligne));
            }
            document.add(table);
        }
    }

    /** Section 2: per-stand reading — every vacation of a stand, in one block. */
    private void addSectionParStand(Document document, List<LigneAffectation> lignes) {
        document.add(sectionTitle("Planning par stand"));
        List<Stand> stands = lignes.stream()
                .map(LigneAffectation::stand)
                .collect(Collectors.toMap(Stand::getId, stand -> stand, (left, right) -> left,
                        java.util.LinkedHashMap::new))
                .values().stream()
                .sorted(Comparator.comparing(Stand::getNom, String.CASE_INSENSITIVE_ORDER))
                .toList();
        for (Stand stand : stands) {
            List<LigneAffectation> duStand = lignes.stream()
                    .filter(ligne -> ligne.stand().getId().equals(stand.getId()))
                    .sorted(Comparator.comparing((LigneAffectation ligne) -> ligne.creneau().getDate())
                            .thenComparing(LigneAffectation::debut))
                    .toList();
            document.add(groupTitle(stand.getNom() + "  ·  " + emplacementNom(stand)));

            PdfPTable table = globalTable(new float[] { 2.6f, 1.3f, 8.5f, 1f },
                    "Journée", "Horaires", "Animateurs", "Effectif");
            for (LigneAffectation ligne : duStand) {
                table.addCell(bodyCell("J" + ligne.creneau().getJour() + " · "
                        + DATE_FORMAT.format(ligne.creneau().getDate())));
                table.addCell(bodyCell(formatHoraires(ligne)));
                table.addCell(animateursCell(ligne));
                table.addCell(effectifCell(ligne));
            }
            document.add(table);
        }
    }

    private Paragraph sectionTitle(String text) {
        Paragraph paragraph = new Paragraph(text, NAME_FONT);
        paragraph.setSpacingAfter(12f);
        return paragraph;
    }

    private Paragraph groupTitle(String text) {
        Paragraph paragraph = new Paragraph(text, DATE_FONT);
        paragraph.setSpacingBefore(10f);
        paragraph.setSpacingAfter(6f);
        // Never leave a group heading alone at the bottom of a page.
        paragraph.setKeepTogether(true);
        return paragraph;
    }

    /** Table with a coloured header row repeated on every page it spills onto. */
    private PdfPTable globalTable(float[] widths, String... entetes) {
        PdfPTable table = new PdfPTable(widths);
        table.setWidthPercentage(100);
        table.setHeaderRows(1);
        table.setSpacingAfter(8f);
        for (String entete : entetes) {
            PdfPCell cell = new PdfPCell(new Phrase(entete, TABLE_HEADER_FONT));
            cell.setBackgroundColor(YELLOW);
            cell.setBorderColor(PILL_BACKGROUND);
            cell.setPadding(5f);
            table.addCell(cell);
        }
        return table;
    }

    private PdfPCell bodyCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text == null ? "—" : text, TABLE_BODY_FONT));
        cell.setBorderColor(PILL_BACKGROUND);
        cell.setPadding(4f);
        return cell;
    }

    /** Names on the line, or the shortfall spelled out in red when seats are left unfilled. */
    private PdfPCell animateursCell(LigneAffectation ligne) {
        if (ligne.animateurs().isEmpty()) {
            PdfPCell cell = new PdfPCell(new Phrase("Aucun animateur affecté", TABLE_ALERT_FONT));
            cell.setBorderColor(PILL_BACKGROUND);
            cell.setPadding(4f);
            return cell;
        }
        Paragraph paragraph = new Paragraph(String.join(", ", ligne.animateurs()), TABLE_BODY_FONT);
        if (ligne.incomplete()) {
            paragraph.add(new Chunk("  ·  " + (ligne.sieges()[0] - ligne.animateurs().size())
                    + " siège(s) non pourvu(s)", TABLE_ALERT_FONT));
        }
        PdfPCell cell = new PdfPCell(paragraph);
        cell.setBorderColor(PILL_BACKGROUND);
        cell.setPadding(4f);
        return cell;
    }

    private PdfPCell effectifCell(LigneAffectation ligne) {
        PdfPCell cell = new PdfPCell(new Phrase(ligne.animateurs().size() + "/" + ligne.sieges()[0],
                ligne.incomplete() ? TABLE_ALERT_FONT : TABLE_BODY_FONT));
        cell.setBorderColor(PILL_BACKGROUND);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setPadding(4f);
        return cell;
    }

    private String formatHoraires(LigneAffectation ligne) {
        return TIME_FORMAT.format(ligne.debut()) + "–" + TIME_FORMAT.format(ligne.fin());
    }

    private String emplacementNom(Stand stand) {
        Emplacement emplacement = stand.getEmplacement();
        return emplacement == null || emplacement.getNom() == null || emplacement.getNom().isBlank()
                ? "—"
                : emplacement.getNom();
    }

    /**
     * One PDF per animateur, bundled in a single ZIP. Replaces the former
     * global PDF: the planning is always handed out person by person.
     */
    public byte[] exportAllPdfZip(PlanningFestival planning) {
        return buildZip(planning, List.of(new NamedFileBuilder(".pdf", id -> exportAnimateurPdf(planning, id))));
    }

    public byte[] exportAllIcsZip(PlanningFestival planning) {
        return buildZip(planning, List.of(new NamedFileBuilder(".ics",
                id -> exportAnimateurIcs(planning, id).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    /**
     * Both the PDF and the ICS of every animateur, bundled in a single ZIP so
     * the whole planning can be handed out through one download.
     */
    public byte[] exportAllBundleZip(PlanningFestival planning) {
        return buildZip(planning, List.of(
                new NamedFileBuilder(".pdf", id -> exportAnimateurPdf(planning, id)),
                new NamedFileBuilder(".ics",
                        id -> exportAnimateurIcs(planning, id).getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    }

    /** Bundles one or more files per animateur, named after the animateur, into a ZIP. */
    private byte[] buildZip(PlanningFestival planning, List<NamedFileBuilder> fileBuilders) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            Set<String> usedFilenames = new LinkedHashSet<>();
            for (Animateur animateur : planning.getAnimateurs()) {
                String displayName = resolveAnimateurName(planning, animateur.getId());
                String baseName = (displayName == null || displayName.isBlank() ? animateur.getId() : displayName)
                        .replaceAll("[\\\\/\\r\\n\\\"]", "_");
                for (NamedFileBuilder fileBuilder : fileBuilders) {
                    String filename = baseName + fileBuilder.extension();
                    int suffix = 2;
                    while (!usedFilenames.add(filename)) {
                        filename = baseName + "-" + suffix + fileBuilder.extension();
                        suffix++;
                    }
                    zip.putNextEntry(new ZipEntry(filename));
                    zip.write(fileBuilder.builder().build(animateur.getId()));
                    zip.closeEntry();
                }
            }
        } catch (IOException e) {
            throw new RuntimeException("Unable to build ZIP export", e);
        }
        return output.toByteArray();
    }

    private record NamedFileBuilder(String extension, AnimateurFileBuilder builder) {
    }

    @FunctionalInterface
    private interface AnimateurFileBuilder {
        byte[] build(String animateurId);
    }

    public String exportAnimateurIcs(PlanningFestival planning, String animateurId) {
        List<PosteAffectation> postes = planning.getPostes().stream()
                .filter(poste -> poste.getAnimateur() != null && animateurId.equals(poste.getAnimateur().getId()))
                .sorted(byCreneauThenStand())
                .toList();

        StringBuilder builder = new StringBuilder();
        builder.append("BEGIN:VCALENDAR\r\n")
                .append("VERSION:2.0\r\n")
                .append("PRODID:-//planning-equipes//planning//EN\r\n")
                .append("CALSCALE:GREGORIAN\r\n");

        for (PosteAffectation poste : postes) {
            String uid = poste.getId() + "@planning-equipes";
            builder.append("BEGIN:VEVENT\r\n")
                    .append("UID:").append(uid).append("\r\n")
                    .append("DTSTAMP:").append(ICS_UTC_DATE_TIME.format(Instant.now())).append("\r\n")
                    .append("DTSTART;TZID=").append(FESTIVAL_TIMEZONE).append(":")
                    .append(poste.getCreneau().getDate().atTime(poste.heureDebutEffectif())
                            .format(ICS_LOCAL_DATE_TIME))
                    .append("\r\n")
                    .append("DTEND;TZID=").append(FESTIVAL_TIMEZONE).append(":")
                    .append(poste.getCreneau().getDate().atTime(poste.heureFinEffectif())
                            .format(ICS_LOCAL_DATE_TIME))
                    .append("\r\n")
                    .append("SUMMARY:").append(escapeIcs(poste.getStand().getNom())).append("\r\n")
                    .append("DESCRIPTION:")
                    .append(escapeIcs("Stand " + poste.getStand().getNom() + " - slot " + poste.getCreneau().getId()))
                    .append("\r\n");
            Emplacement emplacement = poste.getStand().getEmplacement();
            if (emplacement != null && emplacement.getNom() != null && !emplacement.getNom().isBlank()) {
                builder.append("LOCATION:").append(escapeIcs(emplacement.getNom())).append("\r\n");
            }
            if (emplacement != null && emplacement.getLatitude() != null && emplacement.getLongitude() != null) {
                builder.append("GEO:").append(emplacement.getLatitude()).append(";").append(emplacement.getLongitude())
                        .append("\r\n");
            }
            builder.append("END:VEVENT\r\n");
        }

        builder.append("END:VCALENDAR\r\n");
        return builder.toString();
    }

    public String resolveAnimateurName(PlanningFestival planning, String animateurId) {
        return planning.getAnimateurs().stream()
                .filter(animateur -> animateurId.equals(animateur.getId()))
                .findFirst()
                .map(this::toDisplayName)
                .orElse(animateurId);
    }

    private byte[] buildPdf(String animateurName, List<PosteAffectation> postes,
            java.util.Map<String, List<String>> coequipiersParPoste) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 40, 40, 40, 54);
        PdfWriter writer = PdfWriter.getInstance(document, output);
        String generatedAt = "Festival — Centre-ville · généré le "
                + GENERATED_AT_FORMAT.format(Instant.now().atZone(ZoneOffset.systemDefault()));
        writer.setPageEvent(new FooterEvent(generatedAt));
        document.open();

        addHeader(document, animateurName, postes);

        if (postes.isEmpty()) {
            document.add(emptyState());
        } else {
            document.add(buildStandsAffectesCard(postes));
            for (PosteAffectation poste : postes) {
                document.add(buildAssignmentCard(poste, coequipiersParPoste.getOrDefault(poste.getId(), List.of())));
            }
        }

        document.close();
        return output.toByteArray();
    }

    private void addHeader(Document document, String animateurName, List<PosteAffectation> postes) {
        float pageWidth = document.getPageSize().getWidth();
        float pageHeight = document.getPageSize().getHeight();

        Image strip = loadImage(STRIP_RESOURCE);
        float stripWidth = 210f;
        strip.scaleToFit(stripWidth, stripWidth * strip.getHeight() / strip.getWidth());
        strip.setAbsolutePosition(pageWidth - 22f - strip.getScaledWidth(), pageHeight - 20f - strip.getScaledHeight());
        document.add(strip);

        document.add(brandHeader(document, 320f, "PLANNING", animateurName, 22f));

        PdfPTable stats = statBlock(postes);
        stats.setSpacingAfter(24f);
        document.add(stats);
    }

    /**
     * Branded page header shared by the global and per-animateur PDFs: the
     * FESTIVAL logo, a spaced small-caps brand label and the page's title —
     * only the title-column width, the texts and the bottom spacing differ.
     */
    private PdfPTable brandHeader(Document document, float titleWidth, String brandText, String title,
            float spacingAfter) {
        Image logo = loadImage(LOGO_RESOURCE);
        logo.scaleToFit(46f, 46f);

        PdfPTable header = new PdfPTable(new float[] { 46f, titleWidth });
        header.setTotalWidth(document.getPageSize().getWidth() - document.leftMargin() - document.rightMargin());
        header.setLockedWidth(true);

        PdfPCell logoCell = new PdfPCell(logo, false);
        logoCell.setBorder(Rectangle.NO_BORDER);
        logoCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        logoCell.setPadding(0f);
        header.addCell(logoCell);

        PdfPCell titleCell = new PdfPCell();
        titleCell.setBorder(Rectangle.NO_BORDER);
        titleCell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        titleCell.setPaddingLeft(14f);
        Paragraph brandLabel = new Paragraph();
        Chunk brandChunk = new Chunk(brandText, BRAND_LABEL_FONT);
        brandChunk.setCharacterSpacing(1.4f);
        brandLabel.add(brandChunk);
        brandLabel.setSpacingAfter(3f);
        titleCell.addElement(brandLabel);
        titleCell.addElement(new Paragraph(title, NAME_FONT));
        header.addCell(titleCell);
        header.setSpacingAfter(spacingAfter);
        return header;
    }

    private PdfPTable statBlock(List<PosteAffectation> postes) {
        PdfPTable table = new PdfPTable(new float[] { 10f, 0.6f, 10f, 0.6f, 10f });
        table.setWidthPercentage(100);
        table.addCell(statCell(distinctCreneauCount(postes), "CRÉNEAUX", null));
        table.addCell(gapCell());
        table.addCell(statCell(distinctStandCount(postes), "STANDS", null));
        table.addCell(gapCell());
        String heuresSubLabel = String.format(Locale.FRENCH, "TOTAL %.1f H TRAVAILLÉES", totalHeures(postes));
        table.addCell(statCell(distinctDayCount(postes), "JOURS", heuresSubLabel));
        return table;
    }

    private PdfPCell gapCell() {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        return cell;
    }

    /** @param subLabel extra line below the main label (e.g. total hours), omitted when {@code null}. */
    private PdfPCell statCell(int value, String label, String subLabel) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPadding(14f);
        cell.setCellEvent(new RoundedCellFillEvent(YELLOW, 10f));

        Paragraph number = new Paragraph(String.valueOf(value), STAT_NUMBER_FONT);
        number.setAlignment(Element.ALIGN_CENTER);
        number.setSpacingAfter(2f);

        Paragraph labelParagraph = new Paragraph();
        labelParagraph.setAlignment(Element.ALIGN_CENTER);
        Chunk labelChunk = new Chunk(label, STAT_LABEL_FONT);
        labelChunk.setCharacterSpacing(1.1f);
        labelParagraph.add(labelChunk);

        cell.addElement(number);
        cell.addElement(labelParagraph);

        if (subLabel != null) {
            Paragraph subLabelParagraph = new Paragraph();
            subLabelParagraph.setAlignment(Element.ALIGN_CENTER);
            subLabelParagraph.setSpacingBefore(3f);
            Chunk subLabelChunk = new Chunk(subLabel, STAT_SUBLABEL_FONT);
            subLabelChunk.setCharacterSpacing(0.6f);
            subLabelParagraph.add(subLabelChunk);
            cell.addElement(subLabelParagraph);
        }
        return cell;
    }

    private double totalHeures(List<PosteAffectation> postes) {
        int totalMinutes = 0;
        for (PosteAffectation poste : postes) {
            totalMinutes += poste.getDureeEffectiveMinutes();
        }
        return totalMinutes / 60.0;
    }

    /**
     * Rounded callout listing every stand this animateur is assigned to at
     * least once over the whole event, in the order they first come up
     * chronologically. Placed between the stat block and the day-by-day
     * planning so the animateur can see at a glance which stands to revise.
     */
    private PdfPTable buildStandsAffectesCard(List<PosteAffectation> postes) {
        PdfPTable card = new PdfPTable(1);
        card.setWidthPercentage(100);
        card.setSpacingAfter(18f);
        card.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        card.setTableEvent(new RoundedBackgroundEvent(CARD_BACKGROUND, RED, 10f));

        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPadding(14f);

        Paragraph title = new Paragraph();
        Chunk titleChunk = new Chunk("VOS STANDS AFFECTÉS", CALLOUT_TITLE_FONT);
        titleChunk.setCharacterSpacing(1.1f);
        title.add(titleChunk);
        title.setSpacingAfter(5f);
        cell.addElement(title);

        cell.addElement(new Paragraph(String.join("  ·  ", distinctStandNames(postes)), CALLOUT_TEXT_FONT));

        card.addCell(cell);
        return card;
    }

    /** Stand names this animateur is assigned to, deduplicated, in first-appearance (chronological) order. */
    private List<String> distinctStandNames(List<PosteAffectation> postes) {
        List<String> names = new ArrayList<>();
        Set<String> seenIds = new LinkedHashSet<>();
        for (PosteAffectation poste : postes) {
            Stand stand = poste.getStand();
            if (stand != null && seenIds.add(stand.getId())) {
                names.add(stand.getNom());
            }
        }
        return names;
    }

    private int distinctStandCount(List<PosteAffectation> postes) {
        Set<String> ids = new LinkedHashSet<>();
        for (PosteAffectation poste : postes) {
            if (poste.getStand() != null) {
                ids.add(poste.getStand().getId());
            }
        }
        return ids.size();
    }

    private int distinctCreneauCount(List<PosteAffectation> postes) {
        Set<Long> ids = new LinkedHashSet<>();
        for (PosteAffectation poste : postes) {
            if (poste.getCreneau() != null) {
                ids.add(poste.getCreneau().getId());
            }
        }
        return ids.size();
    }

    private int distinctDayCount(List<PosteAffectation> postes) {
        Set<Integer> jours = new LinkedHashSet<>();
        for (PosteAffectation poste : postes) {
            if (poste.getCreneau() != null) {
                jours.add(poste.getCreneau().getJour());
            }
        }
        return jours.size();
    }

    private Paragraph emptyState() {
        Paragraph paragraph = new Paragraph("Aucune affectation pour ce festival.", EMPTY_STATE_FONT);
        paragraph.setAlignment(Element.ALIGN_CENTER);
        paragraph.setSpacingBefore(24f);
        return paragraph;
    }

    /** One rounded card per assignment: day/date with a "JOURx" badge, a time pill, the stand, its location and the teammates. */
    private PdfPTable buildAssignmentCard(PosteAffectation poste, List<String> coequipiers) {
        Creneau creneau = poste.getCreneau();

        PdfPTable card = new PdfPTable(new float[] { 2.4f, 1.8f, 2.5f, 2.3f });
        card.setWidthPercentage(100);
        card.setSpacingAfter(9f);
        card.getDefaultCell().setBorder(Rectangle.NO_BORDER);
        card.setTableEvent(new RoundedBackgroundEvent(CARD_BACKGROUND, RED, 10f));

        card.addCell(dayCell(creneau));
        card.addCell(timePillCell(poste.heureDebutEffectif(), poste.heureFinEffectif()));
        card.addCell(standCell(poste.getStand(), coequipiers));
        card.addCell(locationCell(poste.getStand()));
        return card;
    }

    private PdfPCell dayCell(Creneau creneau) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPadding(14f);

        cell.addElement(dayBadge(creneau.getJour()));

        Paragraph date = new Paragraph(formatFrenchDayDate(creneau.getDate()), DATE_FONT);
        date.setSpacingBefore(7f);

        cell.addElement(date);
        return cell;
    }

    /** A small pill-shaped "JOURx" badge, sized to hug its own text rather than stretching to the column width. */
    private PdfPTable dayBadge(int jour) {
        String text = "JOUR" + jour;
        float characterSpacing = 0.6f;
        BaseFont baseFont = BADGE_FONT.getCalculatedBaseFont(false);
        float textWidth = baseFont.getWidthPoint(text, BADGE_FONT.getCalculatedSize()) + characterSpacing * text.length();
        float pillHeight = 15f;
        float pillWidth = textWidth + 18f;

        PdfPTable table = new PdfPTable(1);
        table.setTotalWidth(pillWidth);
        table.setLockedWidth(true);
        table.setHorizontalAlignment(Element.ALIGN_LEFT);

        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setFixedHeight(pillHeight);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setCellEvent(new RoundedCellFillEvent(RED, pillHeight / 2f));
        Chunk chunk = new Chunk(text, BADGE_FONT);
        chunk.setCharacterSpacing(characterSpacing);
        cell.setPhrase(new Phrase(chunk));
        table.addCell(cell);
        return table;
    }

    private PdfPCell timePillCell(LocalTime heureDebut, LocalTime heureFin) {
        String text = heureDebut.format(TIME_FORMAT) + " - " + heureFin.format(TIME_FORMAT);
        BaseFont baseFont = TIME_FONT.getCalculatedBaseFont(false);
        float textWidth = baseFont.getWidthPoint(text, TIME_FONT.getCalculatedSize());
        float iconDiameter = 8f;
        float iconGap = 5f;
        float pillHeight = 20f;
        float pillWidth = iconDiameter + iconGap + textWidth + 24f;

        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setPadding(14f);
        cell.setCellEvent(new TimePillEvent(text, PILL_BACKGROUND, MUTED, pillWidth, pillHeight, iconDiameter, iconGap));
        return cell;
    }

    /** The stand, and under it who else is on it at that moment — the question every animateur asks about their own planning. */
    private PdfPCell standCell(Stand stand, List<String> coequipiers) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setPadding(14f);
        cell.addElement(new Paragraph("Stand " + stand.getNom(), STAND_FONT));

        Paragraph equipe = new Paragraph(coequipiers.isEmpty()
                ? "Seul(e) sur ce stand"
                : "Avec " + String.join(", ", coequipiers), TEAM_FONT);
        equipe.setSpacingBefore(3f);
        cell.addElement(equipe);
        return cell;
    }

    /** Location name with a pin icon, kept clickable to OpenStreetMap when the stand's emplacement is geocoded. */
    private PdfPCell locationCell(Stand stand) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
        cell.setPadding(14f);

        Emplacement emplacement = stand.getEmplacement();
        if (emplacement != null && emplacement.getNom() != null && !emplacement.getNom().isBlank()) {
            String url = emplacement.getLatitude() != null && emplacement.getLongitude() != null
                    ? osmUrl(emplacement)
                    : null;
            cell.setCellEvent(new LocationPinEvent(emplacement.getNom(), LOCATION_FONT, MUTED, 7f, 4f, url));
        }
        return cell;
    }

    private String osmUrl(Emplacement emplacement) {
        double lat = emplacement.getLatitude();
        double lon = emplacement.getLongitude();
        return "https://www.openstreetmap.org/?mlat=" + lat + "&mlon=" + lon + "#map=18/" + lat + "/" + lon;
    }

    private String formatFrenchDayDate(LocalDate date) {
        String raw = FRENCH_DAY_DATE_FORMAT.format(date);
        return raw.substring(0, 1).toUpperCase(Locale.FRENCH) + raw.substring(1);
    }

    private Comparator<PosteAffectation> byCreneauThenStand() {
        return Comparator
                .comparing((PosteAffectation poste) -> poste.getCreneau().getDate())
                .thenComparing(poste -> poste.getCreneau().getHeureDebut())
                .thenComparing(poste -> poste.getStand().getNom());
    }

    private String toDisplayName(Animateur animateur) {
        return List.of(animateur.getPrenom(), animateur.getNom()).stream()
                .filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining(" "));
    }

    private String escapeIcs(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace(",", "\\,")
                .replace(";", "\\;")
                .replace("\n", "\\n");
    }

    /** Loads a PNG bundled under {@code src/main/resources} (not the webui's own public/ folder, which isn't on the Java classpath). */
    private Image loadImage(String resourcePath) {
        try (InputStream in = getClass().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Missing classpath resource: " + resourcePath);
            }
            return Image.getInstance(in.readAllBytes());
        } catch (IOException e) {
            throw new RuntimeException("Unable to load image " + resourcePath, e);
        }
    }

    /** Draws a small clock face (circle + two hands) used ahead of a time-slot pill's text. */
    private static void drawClockIcon(PdfContentByte canvas, float centerX, float centerY, float radius, java.awt.Color color) {
        canvas.saveState();
        canvas.setColorStroke(color);
        canvas.setLineWidth(0.8f);
        canvas.circle(centerX, centerY, radius);
        canvas.stroke();
        canvas.moveTo(centerX, centerY);
        canvas.lineTo(centerX, centerY + radius * 0.55f);
        canvas.moveTo(centerX, centerY);
        canvas.lineTo(centerX + radius * 0.5f, centerY - radius * 0.15f);
        canvas.stroke();
        canvas.restoreState();
    }

    /** Draws a small outlined map-pin (circle head, pointed tail, center dot) used ahead of a location's text. */
    private static void drawPinIcon(PdfContentByte canvas, float centerX, float centerY, float radius, java.awt.Color color) {
        float headCenterY = centerY + radius * 0.55f;
        canvas.saveState();
        canvas.setColorStroke(color);
        canvas.setLineWidth(0.8f);
        canvas.circle(centerX, headCenterY, radius);
        canvas.stroke();
        canvas.moveTo(centerX - radius * 0.75f, headCenterY - radius * 0.6f);
        canvas.lineTo(centerX, headCenterY - radius * 2.1f);
        canvas.lineTo(centerX + radius * 0.75f, headCenterY - radius * 0.6f);
        canvas.stroke();
        canvas.setColorFill(color);
        canvas.circle(centerX, headCenterY, radius * 0.28f);
        canvas.fill();
        canvas.restoreState();
    }

    /** Fills a cell's own box with a rounded rectangle, used for stat tiles and pill badges. */
    private static final class RoundedCellFillEvent implements PdfPCellEvent {
        private final java.awt.Color fill;
        private final float radius;

        RoundedCellFillEvent(java.awt.Color fill, float radius) {
            this.fill = fill;
            this.radius = radius;
        }

        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            PdfContentByte background = canvases[PdfPTable.BACKGROUNDCANVAS];
            background.saveState();
            background.setColorFill(fill);
            background.roundRectangle(position.getLeft(), position.getBottom(), position.getWidth(), position.getHeight(),
                    radius);
            background.fill();
            background.restoreState();
        }
    }

    /** Draws a centered pill (background + clock icon + text) sized to its own content, ignoring the cell's own padding. */
    private static final class TimePillEvent implements PdfPCellEvent {
        private final String text;
        private final java.awt.Color background;
        private final java.awt.Color contentColor;
        private final float pillWidth;
        private final float pillHeight;
        private final float iconDiameter;
        private final float iconGap;

        TimePillEvent(String text, java.awt.Color background, java.awt.Color contentColor, float pillWidth,
                float pillHeight, float iconDiameter, float iconGap) {
            this.text = text;
            this.background = background;
            this.contentColor = contentColor;
            this.pillWidth = pillWidth;
            this.pillHeight = pillHeight;
            this.iconDiameter = iconDiameter;
            this.iconGap = iconGap;
        }

        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            float left = position.getLeft() + (position.getWidth() - pillWidth) / 2f;
            float bottom = position.getBottom() + (position.getHeight() - pillHeight) / 2f;
            float centerY = bottom + pillHeight / 2f;

            PdfContentByte background2 = canvases[PdfPTable.BACKGROUNDCANVAS];
            background2.saveState();
            background2.setColorFill(background);
            background2.roundRectangle(left, bottom, pillWidth, pillHeight, pillHeight / 2f);
            background2.fill();
            background2.restoreState();

            BaseFont baseFont = TIME_FONT.getCalculatedBaseFont(false);
            float textWidth = baseFont.getWidthPoint(text, TIME_FONT.getCalculatedSize());
            float contentLeft = left + (pillWidth - (iconDiameter + iconGap + textWidth)) / 2f;

            PdfContentByte line = canvases[PdfPTable.LINECANVAS];
            drawClockIcon(line, contentLeft + iconDiameter / 2f, centerY, iconDiameter / 2f, contentColor);

            PdfContentByte textCanvas = canvases[PdfPTable.TEXTCANVAS];
            textCanvas.saveState();
            textCanvas.beginText();
            textCanvas.setFontAndSize(baseFont, TIME_FONT.getCalculatedSize());
            textCanvas.setColorFill(contentColor);
            textCanvas.setTextMatrix(contentLeft + iconDiameter + iconGap, centerY - TIME_FONT.getCalculatedSize() * 0.35f);
            textCanvas.showText(text);
            textCanvas.endText();
            textCanvas.restoreState();
        }
    }

    /** Draws a right-aligned pin icon + location name as a single unit, clickable when a URL is supplied. */
    private static final class LocationPinEvent implements PdfPCellEvent {
        private final String text;
        private final Font font;
        private final java.awt.Color color;
        private final float iconDiameter;
        private final float iconGap;
        private final String url;

        LocationPinEvent(String text, Font font, java.awt.Color color, float iconDiameter, float iconGap, String url) {
            this.text = text;
            this.font = font;
            this.color = color;
            this.iconDiameter = iconDiameter;
            this.iconGap = iconGap;
            this.url = url;
        }

        @Override
        public void cellLayout(PdfPCell cell, Rectangle position, PdfContentByte[] canvases) {
            BaseFont baseFont = font.getCalculatedBaseFont(false);
            float textWidth = baseFont.getWidthPoint(text, font.getCalculatedSize());
            float right = position.getRight() - 14f;
            float centerY = position.getBottom() + position.getHeight() / 2f;
            float textLeft = right - textWidth;
            float iconCenterX = textLeft - iconGap - iconDiameter / 2f;

            PdfContentByte line = canvases[PdfPTable.LINECANVAS];
            drawPinIcon(line, iconCenterX, centerY, iconDiameter / 2f, color);

            PdfContentByte textCanvas = canvases[PdfPTable.TEXTCANVAS];
            textCanvas.saveState();
            textCanvas.beginText();
            textCanvas.setFontAndSize(baseFont, font.getCalculatedSize());
            textCanvas.setColorFill(color);
            textCanvas.setTextMatrix(textLeft, centerY - font.getCalculatedSize() * 0.35f);
            textCanvas.showText(text);
            textCanvas.endText();
            textCanvas.restoreState();

            if (url != null) {
                float left = iconCenterX - iconDiameter / 2f - 2f;
                textCanvas.setAction(new PdfAction(url), left, position.getBottom(), right + 2f, position.getTop());
            }
        }
    }

    /** Draws a single seamless rounded rectangle behind a whole (single-row) table, used for the assignment cards. */
    private static final class RoundedBackgroundEvent implements PdfPTableEvent {
        private final java.awt.Color fill;
        private final java.awt.Color border;
        private final float radius;

        RoundedBackgroundEvent(java.awt.Color fill, java.awt.Color border, float radius) {
            this.fill = fill;
            this.border = border;
            this.radius = radius;
        }

        @Override
        public void tableLayout(PdfPTable table, float[][] widths, float[] heights, int headerRows, int rowStart,
                PdfContentByte[] canvases) {
            float left = widths[0][0];
            float right = widths[0][widths[0].length - 1];
            float top = heights[0];
            float bottom = heights[heights.length - 1];

            // Painted on BASECANVAS rather than BACKGROUNDCANVAS: table-level backgrounds are drawn
            // after each row's cells, so using the same canvas as a cell event (e.g. the time pill)
            // would paint over it. BASECANVAS sits one layer below and is unaffected by draw order.
            PdfContentByte background = canvases[PdfPTable.BASECANVAS];
            background.saveState();
            background.setColorFill(fill);
            background.roundRectangle(left, bottom, right - left, top - bottom, radius);
            background.fill();
            background.restoreState();

            if (border != null) {
                PdfContentByte line = canvases[PdfPTable.LINECANVAS];
                line.saveState();
                line.setColorStroke(border);
                line.setLineWidth(1f);
                line.roundRectangle(left + 0.5f, bottom + 0.5f, right - left - 1f, top - bottom - 1f, radius);
                line.stroke();
                line.restoreState();
            }
        }
    }

    /** Draws the "généré le ..." footer and a "Page x/y" counter, back-filled once the total page count is known. */
    private static final class FooterEvent extends PdfPageEventHelper {
        private final String generatedAtText;
        private final List<PdfTemplate> pageCounterTemplates = new ArrayList<>();

        FooterEvent(String generatedAtText) {
            this.generatedAtText = generatedAtText;
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            Rectangle page = document.getPageSize();
            float y = document.bottomMargin() - 18;

            PdfContentByte canvas = writer.getDirectContent();
            Phrase generated = new Phrase(generatedAtText, FOOTER_FONT);
            ColumnText.showTextAligned(canvas, Element.ALIGN_LEFT, generated, document.leftMargin(), y, 0);

            float templateWidth = 70f;
            PdfTemplate template = canvas.createTemplate(templateWidth, 12f);
            canvas.addTemplate(template, page.getWidth() - document.rightMargin() - templateWidth, y - 3f);
            pageCounterTemplates.add(template);
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            // One template was stacked per page actually written; the writer's
            // own counter is already sitting on the next, not-yet-written page,
            // which is what made every document read "Page 1/2" at one page.
            int totalPages = pageCounterTemplates.size();
            BaseFont baseFont = FOOTER_FONT.getCalculatedBaseFont(false);
            for (int i = 0; i < pageCounterTemplates.size(); i++) {
                PdfTemplate template = pageCounterTemplates.get(i);
                String text = "Page " + (i + 1) + "/" + totalPages;
                template.beginText();
                template.setFontAndSize(baseFont, FOOTER_FONT.getSize());
                template.setColorFill(MUTED);
                template.showTextAligned(Element.ALIGN_RIGHT, text, 70f, 3f, 0);
                template.endText();
            }
        }
    }
}
