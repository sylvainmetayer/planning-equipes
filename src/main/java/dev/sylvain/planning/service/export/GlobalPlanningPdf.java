package dev.sylvain.planning.service.export;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.Emplacement;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.referentiel.TypologieLibelles;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.openpdf.text.Chunk;
import org.openpdf.text.Document;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.Image;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;

/**
 * The whole planning in one landscape PDF, for the organiser rather than for
 * the animateurs — and, since the redesign, a <b>document</b> rather than two
 * tables: a summary that links to everything, a grid of who holds which stand
 * on which day, then the same assignments read three ways.
 *
 * <p>Four questions are asked of this file in the field, and each is a part of
 * its own: « how is the event staffed » (the overview grid, stands × days),
 * « who is where right now » (by day, stand by stand and window by window),
 * « who keeps this stand running » (by stand, day by day) and « what does this
 * person work » (animateurs A to Z, hours per day). The seats nobody holds are
 * written in the accent colour rather than silently absent: an unstaffed stand
 * is exactly what the organiser opens this document to find.</p>
 *
 * <p>The summary's entries, the day chips, the stand names and the letters are
 * <b>internal links</b>: a sixty-page planning that has to be scrolled is a
 * planning nobody reads to the end.</p>
 */
@ApplicationScoped
public class GlobalPlanningPdf {

    private static final float LARGEUR = PageSize.A4.getHeight() - 68f;

    /** Beyond that many days, the overview grid is cut into several pages rather than shrunk to nothing. */
    private static final int JOURS_PAR_GRILLE = 24;

    /** Beyond that many distinct windows, a day reads as a list of lines rather than as a wide table. */
    private static final int FENETRES_MAX = 8;

    private static final DateTimeFormatter JOUR_COURT = DateTimeFormatter.ofPattern("EEE d", Locale.FRENCH);
    private static final DateTimeFormatter INITIALE = DateTimeFormatter.ofPattern("EEEEE", Locale.FRENCH);

    private final PdfTheme theme;
    private final TypologieLibelles typologies;

    @Inject
    public GlobalPlanningPdf(PdfTheme theme, TypologieLibelles typologies) {
        this.theme = theme;
        this.typologies = typologies;
    }

    byte[] construire(PlanningEvenement planning, ExportProvenance.Provenance provenance) {
        List<LigneAffectation> lignes = lignesAffectation(planning);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4.rotate(), 34, 34, 34, 50);
        PdfWriter writer = PdfWriter.getInstance(document, output);
        writer.setPageEvent(theme.footerEvent("planning global", Instant.now(), provenance));
        document.open();

        if (lignes.isEmpty()) {
            document.add(theme.brandHeader(document, 420f, "PLANNING GLOBAL", "Toutes les affectations", 18f));
            document.add(theme.emptyState());
            document.close();
            return output.toByteArray();
        }

        PlanView plan = new PlanView(planning, lignes, TypologiePalette.of(typologies));

        addSommaire(document, plan);
        document.newPage();
        addOverview(document, plan);
        document.newPage();
        addByDay(document, plan);
        document.newPage();
        addByStand(document, plan);
        document.newPage();
        addAnimateurs(document, plan);

        document.close();
        return output.toByteArray();
    }

    /**
     * One line per stand × vacation × open segment: the seats of a same stand
     * on a same window are one line holding every name, not one line each.
     * Mirrors how the calendars group them, and is what makes the document
     * readable at event scale (3 500 seats becoming ~2 000 lines).
     */
    private List<LigneAffectation> lignesAffectation(PlanningEvenement planning) {
        Map<String, LigneAffectation> parCle = new LinkedHashMap<>();
        for (PosteAffectation poste : planning.getPostes()) {
            Creneau creneau = poste.getCreneau();
            Stand stand = poste.getStand();
            if (creneau == null || stand == null) {
                continue;
            }
            String key = stand.getId() + "@" + creneau.getId() + "#" + poste.heureDebutEffectif() + "-"
                    + poste.heureFinEffectif();
            LigneAffectation ligne = parCle.computeIfAbsent(
                    key,
                    ignored -> new LigneAffectation(
                            stand,
                            creneau,
                            poste.heureDebutEffectif(),
                            poste.heureFinEffectif(),
                            new ArrayList<>(),
                            new int[] {0},
                            new int[] {0},
                            new int[] {0}));
            if (poste.isOptionnel()) {
                ligne.renforts()[0]++;
            } else {
                ligne.sieges()[0]++;
            }
            if (poste.getAnimateur() != null) {
                ligne.animateurs().add(poste.getAnimateur().nomAffiche());
                if (!poste.isOptionnel()) {
                    ligne.pourvus()[0]++;
                }
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
     * @param renforts optional seats of the same line (issue #505), counted
     *               apart: they are a capacity the organiser may leave unused,
     *               so a line without them is complete
     * @param pourvus owed seats somebody holds — counted apart from
     *               {@link #animateurs}, which lists everybody printed on the
     *               line, renfort holders included. Reading the list's size as
     *               the owed staffing would let a staffed renfort hide a seat
     *               nobody is on: « 2/2 +1 » where the truth is « 1/2 +1 »,
     *               and without the alert font
     */
    private record LigneAffectation(
            Stand stand,
            Creneau creneau,
            LocalTime debut,
            LocalTime fin,
            List<String> animateurs,
            int[] sieges,
            int[] renforts,
            int[] pourvus) {

        boolean incomplete() {
            return pourvus[0] < sieges[0];
        }

        LocalDate date() {
            return creneau.getDate();
        }
    }

    /**
     * The plan arranged the way the document reads it: the days, the stands by
     * place, and the two counts every page of the overview is built on.
     */
    private static final class PlanView {

        private final List<LigneAffectation> lignes;
        private final TypologiePalette palette;
        private final List<LocalDate> dates = new ArrayList<>();
        private final Map<LocalDate, Integer> numeroDuJour = new LinkedHashMap<>();
        /** Stands by place, each list sorted by name: the reading order of the whole document. */
        private final Map<String, List<Stand>> standsByLieu = new LinkedHashMap<>();
        /** Distinct animateurs on a stand on a day, the figure the overview grid shows. */
        private final Map<String, Integer> effectifParStandEtJour = new LinkedHashMap<>();

        private final Map<LocalDate, Integer> presentsParJour = new LinkedHashMap<>();
        private final Map<String, Integer> minutesParAnimateurEtJour = new LinkedHashMap<>();
        private final Map<String, int[]> totauxParAnimateur = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private final int sieges;
        private final int pourvus;
        private final int minutes;
        private final int animateursDuPlan;

        PlanView(PlanningEvenement planning, List<LigneAffectation> lignes, TypologiePalette palette) {
            this.lignes = lignes;
            this.palette = palette;
            this.animateursDuPlan = planning.getAnimateurs() == null
                    ? 0
                    : planning.getAnimateurs().size();

            Map<LocalDate, Set<String>> presents = new TreeMap<>();
            Map<String, Set<String>> parStandEtJour = new LinkedHashMap<>();
            Map<String, Stand> stands = new LinkedHashMap<>();
            int compteSieges = 0;
            int comptePourvus = 0;
            for (LigneAffectation ligne : lignes) {
                LocalDate date = ligne.date();
                if (date == null) {
                    continue;
                }
                numeroDuJour.putIfAbsent(date, ligne.creneau().getJour());
                stands.putIfAbsent(ligne.stand().getId(), ligne.stand());
                compteSieges += ligne.sieges()[0];
                comptePourvus += ligne.animateurs().size();
                int duree = minutes(ligne);
                for (String animateur : ligne.animateurs()) {
                    presents.computeIfAbsent(date, ignored -> new TreeSet<>()).add(animateur);
                    parStandEtJour
                            .computeIfAbsent(key(ligne.stand().getId(), date), ignored -> new TreeSet<>())
                            .add(animateur);
                    minutesParAnimateurEtJour.merge(key(animateur, date), duree, Integer::sum);
                    int[] totaux = totauxParAnimateur.computeIfAbsent(animateur, ignored -> new int[2]);
                    totaux[0] += duree;
                    totaux[1]++;
                }
            }
            this.sieges = compteSieges;
            this.pourvus = comptePourvus;
            this.minutes = totauxParAnimateur.values().stream()
                    .mapToInt(totaux -> totaux[0])
                    .sum();
            dates.addAll(new TreeSet<>(numeroDuJour.keySet()));
            presents.forEach((date, noms) -> presentsParJour.put(date, noms.size()));
            parStandEtJour.forEach((cle, noms) -> effectifParStandEtJour.put(cle, noms.size()));

            Map<String, List<Stand>> parLieu = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            for (Stand stand : stands.values()) {
                parLieu.computeIfAbsent(lieu(stand), ignored -> new ArrayList<>())
                        .add(stand);
            }
            parLieu.forEach((nom, liste) -> {
                liste.sort(Comparator.comparing(Stand::getNom, String.CASE_INSENSITIVE_ORDER));
                standsByLieu.put(nom, liste);
            });
        }

        static String key(String premier, LocalDate date) {
            return premier + "@" + date;
        }

        static int minutes(LigneAffectation ligne) {
            if (ligne.debut() == null || ligne.fin() == null) {
                return 0;
            }
            int debut = ligne.debut().toSecondOfDay() / 60;
            int fin = ligne.fin().toSecondOfDay() / 60;
            return fin > debut ? fin - debut : 24 * 60 - debut + fin;
        }

        static String lieu(Stand stand) {
            Emplacement emplacement = stand.getEmplacement();
            return emplacement == null
                            || emplacement.getNom() == null
                            || emplacement.getNom().isBlank()
                    ? AnimateurPlanningView.LIEU_INCONNU
                    : emplacement.getNom();
        }

        List<Stand> tousLesStands() {
            List<Stand> tous = new ArrayList<>();
            standsByLieu.values().forEach(tous::addAll);
            return tous;
        }

        List<LigneAffectation> duJour(LocalDate date) {
            return lignes.stream()
                    .filter(ligne -> date.equals(ligne.date()))
                    .sorted(Comparator.comparing(LigneAffectation::debut)
                            .thenComparing(ligne -> ligne.stand().getNom(), String.CASE_INSENSITIVE_ORDER))
                    .toList();
        }

        List<LigneAffectation> duStand(Stand stand) {
            return lignes.stream()
                    .filter(ligne -> ligne.stand().getId().equals(stand.getId()))
                    .sorted(Comparator.comparing(LigneAffectation::date).thenComparing(LigneAffectation::debut))
                    .toList();
        }

        /** The distinct windows of a set of lines, in chronological order — the columns of a table. */
        static List<Fenetre> fenetres(List<LigneAffectation> lignes) {
            Set<Fenetre> distinctes = new LinkedHashSet<>();
            for (LigneAffectation ligne : lignes) {
                distinctes.add(new Fenetre(ligne.debut(), ligne.fin()));
            }
            return distinctes.stream()
                    .sorted(Comparator.comparing(Fenetre::debut).thenComparing(Fenetre::fin))
                    .toList();
        }
    }

    /** One opening window, as the column of a table: « 10:00–12:00 ». */
    private record Fenetre(LocalTime debut, LocalTime fin) {

        String libelle() {
            return PdfTheme.TIME_FORMAT.format(debut) + "–" + PdfTheme.TIME_FORMAT.format(fin);
        }
    }

    // --- 1 · Summary ---------------------------------------------------------------

    private void addSommaire(Document document, PlanView plan) {
        Image logo = theme.strip();
        if (logo != null) {
            float largeur = 130f;
            logo.scaleToFit(largeur, largeur * logo.getHeight() / logo.getWidth());
            logo.setAbsolutePosition(
                    document.getPageSize().getWidth() - 34f - logo.getScaledWidth(),
                    document.getPageSize().getHeight() - 18f - logo.getScaledHeight());
            document.add(logo);
        }
        Paragraph ancre = new Paragraph(ancrage(DEST_SOMMAIRE));
        ancre.setLeading(0f);
        document.add(ancre);
        document.add(theme.brandHeader(document, 480f, "PLANNING GLOBAL · TOUTES LES AFFECTATIONS", periode(plan), 8f));
        document.add(chiffres(plan));

        PdfPTable table = new PdfPTable(new float[] {150f, 624f});
        table.setTotalWidth(LARGEUR);
        table.setLockedWidth(true);

        table.addCell(entreeSommaire(1, "Vue d'ensemble", DEST_ENSEMBLE));
        table.addCell(summaryText("La grille des " + plan.tousLesStands().size() + " stands × " + plan.dates.size()
                + " jours : combien d'animateurs, où et quand."));

        table.addCell(entreeSommaire(2, "Par journée", destJour(plan.dates.get(0))));
        PdfPCell jours = new PdfPCell();
        jours.setBorder(Rectangle.NO_BORDER);
        jours.setPaddingBottom(8f);
        jours.addElement(new Paragraph("Chaque jour, stand par stand, créneau par créneau.", theme.sousTitreFont()));
        jours.addElement(pastillesJours(plan));
        table.addCell(jours);

        table.addCell(
                entreeSommaire(3, "Par stand", destStand(plan.tousLesStands().get(0))));
        PdfPCell stands = new PdfPCell();
        stands.setBorder(Rectangle.NO_BORDER);
        stands.setPaddingBottom(8f);
        stands.addElement(new Paragraph("Chaque stand, jour par jour. Le nom mène à son bloc.", theme.sousTitreFont()));
        stands.addElement(listeStands(plan));
        table.addCell(stands);

        table.addCell(entreeSommaire(4, "Animateurs de A à Z", DEST_ANIMATEURS));
        PdfPCell animateurs = new PdfPCell();
        animateurs.setBorder(Rectangle.NO_BORDER);
        animateurs.addElement(new Paragraph(
                plan.totauxParAnimateur.size() + " animateurs : heures, créneaux et jours de présence.",
                theme.sousTitreFont()));
        animateurs.addElement(pastillesLettres(plan));
        table.addCell(animateurs);

        document.add(table);
    }

    private String periode(PlanView plan) {
        return PdfTheme.formatPeriode(plan.dates.get(0), plan.dates.get(plan.dates.size() - 1));
    }

    private PdfPTable chiffres(PlanView plan) {
        PdfPTable table = new PdfPTable(5);
        table.setTotalWidth(LARGEUR);
        table.setLockedWidth(true);
        table.setSpacingAfter(16f);
        table.addCell(chiffre(String.valueOf(plan.dates.size()), plan.dates.size() > 1 ? "jours" : "jour"));
        table.addCell(chiffre(String.valueOf(plan.tousLesStands().size()), "stands"));
        table.addCell(chiffre(
                String.valueOf(plan.sieges),
                plan.sieges == plan.pourvus
                        ? "sièges, tous pourvus"
                        : "sièges, " + nonPourvus(plan.sieges - plan.pourvus)));
        table.addCell(chiffre(String.valueOf(plan.animateursDuPlan), "animateurs"));
        table.addCell(chiffre(AnimateurPlanningPdf.heures(plan.minutes) + " h", "travaillées"));
        return table;
    }

    /** « 1 non pourvu », « 12 non pourvus » — a figure the organiser quotes out loud. */
    private static String nonPourvus(int manquants) {
        return manquants + (manquants > 1 ? " non pourvus" : " non pourvu");
    }

    private PdfPCell chiffre(String valeur, String libelle) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColorBottom(theme.headline());
        cell.setBorderWidthBottom(1f);
        cell.setPaddingBottom(8f);
        Paragraph nombre = new Paragraph(valeur, theme.chiffreFont());
        nombre.setSpacingAfter(1f);
        cell.addElement(nombre);
        cell.addElement(new Paragraph(libelle, theme.chiffreLabelFont()));
        return cell;
    }

    private PdfPCell entreeSommaire(int numero, String titre, String destination) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPaddingBottom(8f);
        Paragraph ligne = new Paragraph();
        ligne.add(new Chunk(numero + "  ", theme.chiffreFont()));
        Chunk lien = new Chunk(titre, theme.lienFont());
        lien.setLocalGoto(destination);
        ligne.add(lien);
        cell.addElement(ligne);
        return cell;
    }

    private PdfPCell summaryText(String texte) {
        PdfPCell cell = new PdfPCell(new Phrase(texte, theme.sousTitreFont()));
        cell.setBorder(Rectangle.NO_BORDER);
        cell.setPaddingTop(6f);
        cell.setPaddingBottom(8f);
        return cell;
    }

    private PdfPTable pastillesJours(PlanView plan) {
        PdfPTable table = new PdfPTable(8);
        table.setWidthPercentage(100);
        table.setSpacingBefore(4f);
        for (LocalDate date : plan.dates) {
            Paragraph ligne = new Paragraph();
            Chunk lien = new Chunk("J" + plan.numeroDuJour.get(date) + " ", theme.tableMiniHeaderFont());
            lien.setLocalGoto(destJour(date));
            ligne.add(lien);
            Chunk jour = new Chunk(JOUR_COURT.format(date), theme.tableMiniFont());
            jour.setLocalGoto(destJour(date));
            ligne.add(jour);
            PdfPCell cell = new PdfPCell(ligne);
            cell.setBorderColor(theme.pill());
            cell.setPadding(3f);
            table.addCell(cell);
        }
        int reste = plan.dates.size() % 8;
        for (int i = 0; reste != 0 && i < 8 - reste; i++) {
            PdfPCell vide = new PdfPCell();
            vide.setBorder(Rectangle.NO_BORDER);
            table.addCell(vide);
        }
        return table;
    }

    private PdfPTable listeStands(PlanView plan) {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        table.setSpacingBefore(4f);
        for (Map.Entry<String, List<Stand>> entree : plan.standsByLieu.entrySet()) {
            PdfPCell cell = new PdfPCell();
            cell.setBorder(Rectangle.NO_BORDER);
            cell.setPaddingBottom(3f);
            Paragraph titre = new Paragraph();
            titre.add(new Chunk(entree.getKey() + "  ", theme.tableMiniHeaderFont()));
            titre.add(new Chunk(String.valueOf(entree.getValue().size()), theme.lienLabelFont()));
            cell.addElement(titre);
            Paragraph noms = new Paragraph();
            boolean premier = true;
            for (Stand stand : entree.getValue()) {
                if (!premier) {
                    noms.add(new Chunk("  ·  ", theme.lienLabelFont()));
                }
                premier = false;
                Chunk lien = new Chunk(stand.getNom(), theme.tableMiniFont());
                lien.setLocalGoto(destStand(stand));
                noms.add(lien);
            }
            cell.addElement(noms);
            table.addCell(cell);
        }
        return table;
    }

    private PdfPTable pastillesLettres(PlanView plan) {
        Set<String> lettres = new TreeSet<>();
        for (String nom : plan.totauxParAnimateur.keySet()) {
            lettres.add(nom.isEmpty() ? "?" : nom.substring(0, 1).toUpperCase(Locale.FRENCH));
        }
        PdfPTable table = new PdfPTable(26);
        table.setWidthPercentage(100);
        table.setSpacingBefore(4f);
        for (String lettre : lettres) {
            Chunk lien = new Chunk(lettre, theme.tableMiniHeaderFont());
            lien.setLocalGoto(destLettre(lettre));
            PdfPCell cell = new PdfPCell(new Phrase(lien));
            cell.setBorderColor(theme.pill());
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cell.setPadding(3f);
            table.addCell(cell);
        }
        for (int i = lettres.size(); i % 26 != 0; i++) {
            PdfPCell vide = new PdfPCell();
            vide.setBorder(Rectangle.NO_BORDER);
            table.addCell(vide);
        }
        return table;
    }

    // --- 2 · Overview ---------------------------------------------------------

    private static final String DEST_SOMMAIRE = "section-sommaire";
    private static final String DEST_ENSEMBLE = "section-ensemble";
    private static final String DEST_ANIMATEURS = "section-animateurs";

    private static String destJour(LocalDate date) {
        return "jour-" + date;
    }

    private static String destStand(Stand stand) {
        return "stand-" + stand.getId();
    }

    private static String destLettre(String lettre) {
        return "lettre-" + lettre;
    }

    private void addOverview(Document document, PlanView plan) {
        document.add(titreSection(
                plan,
                "Qui tient quel stand, chaque jour",
                "Nombre d'animateurs différents sur le stand dans la journée",
                DEST_ENSEMBLE,
                "Vue d'ensemble"));
        document.add(legendeChaleur());

        List<LocalDate> dates = plan.dates;
        for (int depart = 0; depart < dates.size(); depart += JOURS_PAR_GRILLE) {
            List<LocalDate> tranche = dates.subList(depart, Math.min(dates.size(), depart + JOURS_PAR_GRILLE));
            if (depart > 0) {
                document.newPage();
            }
            document.add(grille(plan, tranche));
        }
    }

    private PdfPTable legendeChaleur() {
        PdfPTable table = new PdfPTable(new float[] {120f, 44f, 44f, 44f, 44f, 478f});
        table.setTotalWidth(LARGEUR);
        table.setLockedWidth(true);
        table.setSpacingAfter(8f);
        PdfPCell titre = new PdfPCell(new Phrase("Animateurs par jour", theme.lienLabelFont()));
        titre.setBorder(Rectangle.NO_BORDER);
        table.addCell(titre);
        String[] paliers = {"1 – 2", "3 – 4", "5 – 8", "9 et +"};
        for (int niveau = 0; niveau < paliers.length; niveau++) {
            PdfPCell cell = new PdfPCell(new Phrase(paliers[niveau], surChaleur(theme.tableMiniHeaderFont(), niveau)));
            cell.setBackgroundColor(theme.chaleur(niveau));
            cell.setBorderColor(theme.pill());
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cell.setPadding(2f);
            table.addCell(cell);
        }
        PdfPCell vide =
                new PdfPCell(new Phrase("une case vide : le stand est fermé ce jour-là", theme.lienLabelFont()));
        vide.setBorder(Rectangle.NO_BORDER);
        vide.setPaddingLeft(8f);
        table.addCell(vide);
        return table;
    }

    private PdfPTable grille(PlanView plan, List<LocalDate> dates) {
        float colonneStand = 170f;
        float[] largeurs = new float[dates.size() + 1];
        largeurs[0] = colonneStand;
        float reste = (LARGEUR - colonneStand) / dates.size();
        for (int i = 1; i < largeurs.length; i++) {
            largeurs[i] = reste;
        }
        PdfPTable table = new PdfPTable(largeurs);
        table.setTotalWidth(LARGEUR);
        table.setLockedWidth(true);
        table.setHeaderRows(2);

        PdfPCell jour = new PdfPCell(new Phrase("Jour", theme.tableMiniHeaderFont()));
        jour.setBorderColor(theme.pill());
        jour.setPadding(2f);
        table.addCell(jour);
        for (LocalDate date : dates) {
            Chunk lien = new Chunk(String.valueOf(plan.numeroDuJour.get(date)), theme.tableMiniHeaderFont());
            lien.setLocalGoto(destJour(date));
            PdfPCell cell = new PdfPCell(new Phrase(lien));
            cell.setBorderColor(theme.pill());
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cell.setPadding(2f);
            table.addCell(cell);
        }
        PdfPCell vide = new PdfPCell(new Phrase("", theme.tableMiniFont()));
        vide.setBorderColor(theme.pill());
        table.addCell(vide);
        for (LocalDate date : dates) {
            PdfPCell cell =
                    new PdfPCell(new Phrase(INITIALE.format(date).toUpperCase(Locale.FRENCH), theme.lienLabelFont()));
            cell.setBorderColor(theme.pill());
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cell.setPadding(1f);
            table.addCell(cell);
        }

        for (Map.Entry<String, List<Stand>> entree : plan.standsByLieu.entrySet()) {
            PdfPCell lieu = new PdfPCell(new Phrase(entree.getKey(), theme.tableMiniHeaderFont()));
            lieu.setColspan(largeurs.length);
            lieu.setBackgroundColor(theme.voile());
            lieu.setBorderColor(theme.pill());
            lieu.setPadding(2f);
            table.addCell(lieu);
            for (Stand stand : entree.getValue()) {
                PdfPCell nom = new PdfPCell(standName(stand));
                nom.setBorderColor(theme.pill());
                nom.setPadding(2f);
                table.addCell(nom);
                for (LocalDate date : dates) {
                    Integer effectif = plan.effectifParStandEtJour.get(PlanView.key(stand.getId(), date));
                    PdfPCell cell = new PdfPCell(
                            new Phrase(effectif == null ? "" : String.valueOf(effectif), theme.tableMiniFont()));
                    if (effectif != null) {
                        cell.setBackgroundColor(theme.chaleur(palier(effectif)));
                    }
                    cell.setBorderColor(theme.pill());
                    cell.setHorizontalAlignment(Element.ALIGN_CENTER);
                    cell.setPadding(1.5f);
                    table.addCell(cell);
                }
            }
        }

        PdfPCell total = new PdfPCell(new Phrase("Animateurs présents", theme.tableMiniHeaderFont()));
        total.setBorderColor(theme.pill());
        total.setPadding(2f);
        table.addCell(total);
        for (LocalDate date : dates) {
            PdfPCell cell = new PdfPCell(new Phrase(
                    String.valueOf(plan.presentsParJour.getOrDefault(date, 0)), theme.tableMiniHeaderFont()));
            cell.setBorderColor(theme.pill());
            cell.setHorizontalAlignment(Element.ALIGN_CENTER);
            cell.setPadding(1.5f);
            table.addCell(cell);
        }
        return table;
    }

    /** The stand's name, as a link to its own block further down the document. */
    private Phrase standName(Stand stand) {
        Phrase phrase = new Phrase();
        Chunk lien = new Chunk(stand.getNom(), theme.tableMiniFont());
        lien.setLocalGoto(destStand(stand));
        phrase.add(lien);
        return phrase;
    }

    /**
     * The same font, in whichever of black or white reads on that step of the
     * heat scale: the darkest step carries dark text in no palette.
     */
    private Font surChaleur(Font font, int niveau) {
        Font lisible = new Font(font);
        lisible.setColor(PdfTheme.lisibleSur(theme.chaleur(niveau)));
        return lisible;
    }

    private static int palier(int effectif) {
        if (effectif <= 2) {
            return 0;
        }
        if (effectif <= 4) {
            return 1;
        }
        return effectif <= 8 ? 2 : 3;
    }

    // --- 3 · By day ------------------------------------------------------------

    private void addByDay(Document document, PlanView plan) {
        boolean premier = true;
        for (LocalDate date : plan.dates) {
            if (!premier) {
                document.newPage();
            }
            premier = false;
            List<LigneAffectation> duJour = plan.duJour(date);
            int sieges = duJour.stream().mapToInt(ligne -> ligne.sieges()[0]).sum();
            int pourvus =
                    duJour.stream().mapToInt(ligne -> ligne.animateurs().size()).sum();
            String etat =
                    sieges == pourvus ? "tous les postes pourvus" : (sieges - pourvus) + " siège(s) non pourvu(s)";
            document.add(titreSection(
                    plan,
                    "Jour " + plan.numeroDuJour.get(date) + " — " + PdfTheme.formatFrenchDayDate(date),
                    standCount(duJour) + " stands · " + duJour.size() + " créneaux · "
                            + plan.presentsParJour.getOrDefault(date, 0) + " animateurs · " + etat,
                    destJour(date),
                    "Par journée"));
            document.add(tableauFenetres(plan, duJour, true));
        }
    }

    private long standCount(List<LigneAffectation> lignes) {
        return lignes.stream().map(ligne -> ligne.stand().getId()).distinct().count();
    }

    /**
     * A day, or a stand, as a table of windows: one row per stand (or per day)
     * and one column per opening window, the names inside. « — » is a closed
     * window, which is a fact and not a hole.
     *
     * <p>Beyond {@link #FENETRES_MAX} distinct windows the table would be
     * unreadable, so the same lines are written one per row instead: a grid
     * nobody can read is worse than a long list.</p>
     */
    private PdfPTable tableauFenetres(PlanView plan, List<LigneAffectation> lignes, boolean parStand) {
        List<Fenetre> fenetres = PlanView.fenetres(lignes);
        if (fenetres.size() > FENETRES_MAX) {
            return tableauLignes(plan, lignes, parStand);
        }
        float colonne = 150f;
        float[] largeurs = new float[fenetres.size() + 1];
        largeurs[0] = colonne;
        for (int i = 1; i < largeurs.length; i++) {
            largeurs[i] = (LARGEUR - colonne) / fenetres.size();
        }
        PdfPTable table = new PdfPTable(largeurs);
        table.setTotalWidth(LARGEUR);
        table.setLockedWidth(true);
        table.setHeaderRows(1);
        table.setSpacingAfter(6f);

        PdfPCell entete = new PdfPCell(new Phrase(parStand ? "Stand" : "Jour", theme.tableMiniHeaderFont()));
        entete.setBackgroundColor(theme.highlight());
        entete.setBorderColor(theme.pill());
        entete.setPadding(3f);
        table.addCell(entete);
        for (Fenetre fenetre : fenetres) {
            PdfPCell cell = new PdfPCell(new Phrase(fenetre.libelle(), theme.tableMiniHeaderFont()));
            cell.setBackgroundColor(theme.highlight());
            cell.setBorderColor(theme.pill());
            cell.setPadding(3f);
            table.addCell(cell);
        }

        if (parStand) {
            Map<String, List<LigneAffectation>> parLieu = new LinkedHashMap<>();
            for (LigneAffectation ligne : lignes) {
                parLieu.computeIfAbsent(PlanView.lieu(ligne.stand()), ignored -> new ArrayList<>())
                        .add(ligne);
            }
            for (Map.Entry<String, List<LigneAffectation>> entree : parLieu.entrySet()) {
                PdfPCell lieu = new PdfPCell(new Phrase(entree.getKey(), theme.tableMiniHeaderFont()));
                lieu.setColspan(largeurs.length);
                lieu.setBackgroundColor(theme.voile());
                lieu.setBorderColor(theme.pill());
                lieu.setPadding(2f);
                table.addCell(lieu);
                Map<String, Stand> distincts = new LinkedHashMap<>();
                for (LigneAffectation ligne : entree.getValue()) {
                    distincts.putIfAbsent(ligne.stand().getId(), ligne.stand());
                }
                List<Stand> stands = distincts.values().stream()
                        .sorted(Comparator.comparing(Stand::getNom, String.CASE_INSENSITIVE_ORDER))
                        .toList();
                for (Stand stand : stands) {
                    table.addCell(celluleIntitule(standName(stand), plan.palette.libelleDuStand(stand)));
                    fillWindows(
                            table,
                            fenetres,
                            entree.getValue().stream()
                                    .filter(ligne -> ligne.stand().getId().equals(stand.getId()))
                                    .toList());
                }
            }
            return table;
        }

        List<LocalDate> dates = lignes.stream()
                .map(LigneAffectation::date)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
        for (LocalDate date : dates) {
            Phrase intitule = new Phrase(
                    "J" + plan.numeroDuJour.get(date) + " " + JOUR_COURT.format(date), theme.tableMiniHeaderFont());
            table.addCell(celluleIntitule(intitule, null));
            fillWindows(
                    table,
                    fenetres,
                    lignes.stream().filter(ligne -> date.equals(ligne.date())).toList());
        }
        return table;
    }

    private PdfPCell celluleIntitule(Phrase intitule, String detail) {
        PdfPCell cell = new PdfPCell();
        cell.setBorderColor(theme.pill());
        cell.setPadding(2.5f);
        cell.addElement(new Paragraph(intitule));
        if (detail != null) {
            cell.addElement(new Paragraph(detail, theme.lienLabelFont()));
        }
        return cell;
    }

    private void fillWindows(PdfPTable table, List<Fenetre> fenetres, List<LigneAffectation> lignes) {
        for (Fenetre fenetre : fenetres) {
            // Every line of that window, not the first: two créneaux of the
            // same day can carry the same hours, and the one dropped took its
            // animateurs — and its unfilled seats — out of the document.
            table.addCell(celluleAnimateurs(lignes.stream()
                    .filter(candidate -> new Fenetre(candidate.debut(), candidate.fin()).equals(fenetre))
                    .toList()));
        }
    }

    /** Names on the line, « — » when the stand is closed, the shortfall spelled out in the accent colour. */
    private PdfPCell celluleAnimateurs(LigneAffectation ligne) {
        return celluleAnimateurs(ligne == null ? List.of() : List.of(ligne));
    }

    /**
     * What a cell of the grid says about one window: every animateur of every
     * line landing there, and what the window still owes.
     */
    private PdfPCell celluleAnimateurs(List<LigneAffectation> lignes) {
        PdfPCell cell = new PdfPCell();
        cell.setBorderColor(theme.pill());
        cell.setPadding(2.5f);
        if (lignes.isEmpty()) {
            cell.setPhrase(new Phrase("—", theme.lienLabelFont()));
            return cell;
        }
        List<String> animateurs = lignes.stream()
                .flatMap(ligne -> ligne.animateurs().stream())
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        // Against the seats owed, never against the names: the list also holds
        // whoever sits on a renfort (issue #505), and counting them in would
        // let a staffed renfort hide an owed seat nobody is on.
        int manquants = lignes.stream()
                .mapToInt(ligne -> ligne.sieges()[0] - ligne.pourvus()[0])
                .sum();
        int renforts = lignes.stream()
                .mapToInt(ligne -> ligne.animateurs().size() - ligne.pourvus()[0])
                .sum();
        if (animateurs.isEmpty()) {
            cell.setPhrase(new Phrase("Aucun animateur affecté", theme.tableAlertFont()));
            return cell;
        }
        Paragraph paragraphe = new Paragraph(String.join(", ", animateurs), theme.tableMiniFont());
        if (manquants > 0) {
            paragraphe.add(new Chunk("  ·  " + nonPourvus(manquants), theme.tableAlertFont()));
        }
        // A bonus, in the ordinary font: it never makes the line look unmet.
        if (renforts > 0) {
            paragraphe.add(new Chunk("  ·  +" + renforts + " en renfort", theme.lienLabelFont()));
        }
        cell.addElement(paragraphe);
        return cell;
    }

    /** The fallback of a day (or a stand) with too many windows for a grid: one row per line. */
    private PdfPTable tableauLignes(PlanView plan, List<LigneAffectation> lignes, boolean parStand) {
        PdfPTable table = new PdfPTable(new float[] {70f, 180f, 524f});
        table.setTotalWidth(LARGEUR);
        table.setLockedWidth(true);
        table.setHeaderRows(1);
        table.setSpacingAfter(6f);
        for (String entete : List.of("Horaires", parStand ? "Stand" : "Jour", "Animateurs")) {
            PdfPCell cell = new PdfPCell(new Phrase(entete, theme.tableMiniHeaderFont()));
            cell.setBackgroundColor(theme.highlight());
            cell.setBorderColor(theme.pill());
            cell.setPadding(3f);
            table.addCell(cell);
        }
        for (LigneAffectation ligne : lignes) {
            PdfPCell horaires =
                    new PdfPCell(new Phrase(new Fenetre(ligne.debut(), ligne.fin()).libelle(), theme.tableMiniFont()));
            horaires.setBorderColor(theme.pill());
            horaires.setPadding(2.5f);
            table.addCell(horaires);
            table.addCell(celluleIntitule(
                    parStand
                            ? standName(ligne.stand())
                            : new Phrase(
                                    "J" + plan.numeroDuJour.get(ligne.date()) + " " + JOUR_COURT.format(ligne.date()),
                                    theme.tableMiniHeaderFont()),
                    parStand ? plan.palette.libelleDuStand(ligne.stand()) : null));
            table.addCell(celluleAnimateurs(ligne));
        }
        return table;
    }

    // --- 4 · By stand --------------------------------------------------------------

    private void addByStand(Document document, PlanView plan) {
        boolean premier = true;
        for (Map.Entry<String, List<Stand>> entree : plan.standsByLieu.entrySet()) {
            for (Stand stand : entree.getValue()) {
                List<LigneAffectation> duStand = plan.duStand(stand);
                if (duStand.isEmpty()) {
                    continue;
                }
                if (!premier) {
                    document.add(separateur());
                }
                premier = false;
                long jours =
                        duStand.stream().map(LigneAffectation::date).distinct().count();
                long personnes = duStand.stream()
                        .flatMap(ligne -> ligne.animateurs().stream())
                        .distinct()
                        .count();
                document.add(titreStand(
                        stand,
                        entree.getKey(),
                        jours + (jours > 1 ? " jours · " : " jour · ") + duStand.size() + " créneaux · " + personnes
                                + " animateurs"));
                document.add(tableauFenetres(plan, duStand, false));
            }
        }
    }

    private PdfPTable titreStand(Stand stand, String lieu, String compte) {
        PdfPTable table = new PdfPTable(1);
        table.setTotalWidth(LARGEUR);
        table.setLockedWidth(true);
        table.setSpacingAfter(4f);
        table.setKeepTogether(true);
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.NO_BORDER);
        Paragraph titre = new Paragraph();
        Chunk ancre = new Chunk(stand.getNom(), theme.sectionFont());
        ancre.setLocalDestination(destStand(stand));
        titre.add(ancre);
        cell.addElement(titre);
        cell.addElement(new Paragraph(lieu + "  ·  " + compte, theme.sousTitreFont()));
        table.addCell(cell);
        return table;
    }

    private Paragraph separateur() {
        Paragraph paragraphe = new Paragraph(" ", theme.lienLabelFont());
        paragraphe.setSpacingBefore(6f);
        return paragraphe;
    }

    // --- 5 · Animateurs, A to Z ----------------------------------------------------

    private void addAnimateurs(Document document, PlanView plan) {
        document.add(titreSection(
                plan,
                "Animateurs de A à Z",
                "Heures travaillées par jour · « Cr. » = nombre de créneaux",
                DEST_ANIMATEURS,
                "Animateurs"));

        List<LocalDate> dates = plan.dates;
        for (int depart = 0; depart < dates.size(); depart += JOURS_PAR_GRILLE) {
            List<LocalDate> tranche = dates.subList(depart, Math.min(dates.size(), depart + JOURS_PAR_GRILLE));
            if (depart > 0) {
                document.newPage();
            }
            float colonne = 150f;
            float[] largeurs = new float[tranche.size() + 3];
            largeurs[0] = colonne;
            largeurs[1] = 36f;
            largeurs[2] = 24f;
            for (int i = 3; i < largeurs.length; i++) {
                largeurs[i] = (LARGEUR - colonne - 60f) / tranche.size();
            }
            PdfPTable table = new PdfPTable(largeurs);
            table.setTotalWidth(LARGEUR);
            table.setLockedWidth(true);
            table.setHeaderRows(1);

            for (String entete : List.of("Animateur", "Total", "Cr.")) {
                PdfPCell cell = new PdfPCell(new Phrase(entete, theme.tableMiniHeaderFont()));
                cell.setBackgroundColor(theme.highlight());
                cell.setBorderColor(theme.pill());
                cell.setPadding(2f);
                table.addCell(cell);
            }
            for (LocalDate date : tranche) {
                Chunk lien = new Chunk(String.valueOf(plan.numeroDuJour.get(date)), theme.tableMiniHeaderFont());
                lien.setLocalGoto(destJour(date));
                PdfPCell cell = new PdfPCell(new Phrase(lien));
                cell.setBackgroundColor(theme.highlight());
                cell.setBorderColor(theme.pill());
                cell.setHorizontalAlignment(Element.ALIGN_CENTER);
                cell.setPadding(2f);
                table.addCell(cell);
            }

            Set<String> lettresAncrees = new TreeSet<>();
            for (Map.Entry<String, int[]> animateur : plan.totauxParAnimateur.entrySet()) {
                // The letter chips of the summary land on the first name they
                // name, so « S » opens where the S's start and not on page one
                // of the section.
                Chunk chunk = new Chunk(animateur.getKey(), theme.tableMiniFont());
                String lettre = animateur.getKey().isEmpty()
                        ? "?"
                        : animateur.getKey().substring(0, 1).toUpperCase(Locale.FRENCH);
                if (lettresAncrees.add(lettre)) {
                    chunk.setLocalDestination(destLettre(lettre));
                }
                PdfPCell nom = new PdfPCell(new Phrase(chunk));
                nom.setBorderColor(theme.pill());
                nom.setPadding(2f);
                table.addCell(nom);
                PdfPCell total = new PdfPCell(new Phrase(
                        AnimateurPlanningPdf.heures(animateur.getValue()[0]) + " h", theme.tableMiniHeaderFont()));
                total.setBorderColor(theme.pill());
                total.setHorizontalAlignment(Element.ALIGN_RIGHT);
                total.setPadding(2f);
                table.addCell(total);
                PdfPCell creneaux =
                        new PdfPCell(new Phrase(String.valueOf(animateur.getValue()[1]), theme.tableMiniFont()));
                creneaux.setBorderColor(theme.pill());
                creneaux.setHorizontalAlignment(Element.ALIGN_CENTER);
                creneaux.setPadding(2f);
                table.addCell(creneaux);
                for (LocalDate date : tranche) {
                    Integer minutes = plan.minutesParAnimateurEtJour.get(PlanView.key(animateur.getKey(), date));
                    int niveau = minutes == null ? 0 : palierHeures(minutes);
                    PdfPCell cell = new PdfPCell(new Phrase(
                            minutes == null ? "" : AnimateurPlanningPdf.heures(minutes),
                            minutes == null ? theme.tableMiniFont() : surChaleur(theme.tableMiniFont(), niveau)));
                    if (minutes != null) {
                        cell.setBackgroundColor(theme.chaleur(niveau));
                    }
                    cell.setBorderColor(theme.pill());
                    cell.setHorizontalAlignment(Element.ALIGN_CENTER);
                    cell.setPadding(1.5f);
                    table.addCell(cell);
                }
            }
            document.add(table);
        }
    }

    private static int palierHeures(int minutes) {
        if (minutes <= 180) {
            return 0;
        }
        if (minutes <= 300) {
            return 1;
        }
        return minutes <= 480 ? 2 : 3;
    }

    // --- Shared ---------------------------------------------------------------------

    /** A section's header: the navigation strip, the title, and the anchor the summary links to. */
    /** An invisible anchor a link can land on, where the title itself is drawn by another element. */
    private Chunk ancrage(String destination) {
        Chunk chunk = new Chunk(" ", theme.lienLabelFont());
        chunk.setLocalDestination(destination);
        return chunk;
    }

    private PdfPTable titreSection(PlanView plan, String titre, String sousTitre, String destination, String courant) {
        PdfPTable table = new PdfPTable(1);
        table.setTotalWidth(LARGEUR);
        table.setLockedWidth(true);
        table.setSpacingAfter(8f);
        table.setKeepTogether(true);

        PdfPCell navigation = new PdfPCell(navigation(plan, courant));
        navigation.setBorder(Rectangle.NO_BORDER);
        navigation.setPaddingBottom(6f);
        table.addCell(navigation);

        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.BOTTOM);
        cell.setBorderColorBottom(theme.headline());
        cell.setBorderWidthBottom(1f);
        cell.setPaddingBottom(5f);
        Paragraph ligne = new Paragraph();
        Chunk ancre = new Chunk(titre, theme.nameFont());
        ancre.setLocalDestination(destination);
        ligne.add(ancre);
        cell.addElement(ligne);
        cell.addElement(new Paragraph(sousTitre, theme.sousTitreFont()));
        table.addCell(cell);
        return table;
    }

    /** Sommaire · Vue d'ensemble · Par journée · Par stand · Animateurs, the current one in the accent colour. */
    private Paragraph navigation(PlanView plan, String courant) {
        Paragraph paragraphe = new Paragraph();
        Map<String, String> entrees = new LinkedHashMap<>();
        entrees.put("Sommaire", DEST_SOMMAIRE);
        entrees.put("Vue d'ensemble", DEST_ENSEMBLE);
        entrees.put("Par journée", destJour(plan.dates.get(0)));
        entrees.put("Par stand", destStand(plan.tousLesStands().get(0)));
        entrees.put("Animateurs", DEST_ANIMATEURS);
        boolean premier = true;
        for (Map.Entry<String, String> entree : entrees.entrySet()) {
            if (!premier) {
                paragraphe.add(new Chunk("   ·   ", theme.lienLabelFont()));
            }
            premier = false;
            Chunk lien = new Chunk(
                    entree.getKey(), entree.getKey().equals(courant) ? theme.lienCourantFont() : theme.lienFont());
            lien.setLocalGoto(entree.getValue());
            paragraphe.add(lien);
        }
        return paragraphe;
    }
}
