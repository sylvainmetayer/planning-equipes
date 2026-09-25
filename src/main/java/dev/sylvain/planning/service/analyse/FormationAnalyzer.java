package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.NiveauCompetence;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.NaturalOrder;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.AnimateurFragilite;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.CompetenceRare;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.FragiliteFindings;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.PosteFragile;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.StaffingSummary;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.TypologieStaffing;
import dev.sylvain.planning.service.referentiel.TypologieItem;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Who to train, game category by game category — the « À former » tab of the
 * Diagnostic.
 *
 * <p>It <b>defines no shortage of its own</b>. A category is listed when the
 * staffing need says its pool is short ({@link TypologieStaffing#manque()}) or
 * when the fragility report finds at least one stand × timeslot of it held by
 * one specialist or none ({@link CompetenceRare}); the figures shown are those
 * two reports' own, read from their untruncated lists. Two tabs of one screen
 * telling two different stories about the same shortage would be worse than
 * either.</p>
 *
 * <p>A candidate holds the category at one of the two lower levels
 * ({@code DEBUTANT}, {@code AUTONOME}) — somebody who can move up one step.
 * Nobody without the competence (that is recruiting, not training), no
 * {@code REFERENT} (already at the top), and no polyvalent (already competent
 * everywhere, and a reinforcement rather than a specialist — ADR 0017). A
 * minor is left out of a category whose every stand is adults-only, if they
 * are a minor on every day of the event: trained, they still could not hold
 * those stands.</p>
 *
 * <p>The order is shown, not scored: the tension days the candidate is free on,
 * then a declared wish, then the level — {@code AUTONOME} before
 * {@code DEBUTANT}, one step from {@code REFERENT}. Pure Java on reports
 * already computed: <b>no solve, no what-if</b>, the same cost as the
 * neighbouring tabs.</p>
 */
public final class FormationAnalyzer {

    private FormationAnalyzer() {}

    /**
     * One candidate for a category.
     *
     * @param nom                      shown on the screen and in the CSV, as the
     *                                 Équité export does; the MCP tool drops it
     * @param joursTensionDisponibles  tension days of the category the candidate
     *                                 has not declared off
     */
    @Schema(requiredProperties = {"animateurId", "joursTensionDisponibles", "niveau", "souhait"})
    public record CandidatFormation(
            String animateurId, String nom, NiveauCompetence niveau, boolean souhait, int joursTensionDisponibles) {}

    /**
     * One category in shortage, with the two reports' figures and its candidates.
     *
     * @param manque                 the staffing need's shortfall for it, {@code 0}
     *                               when it signals none
     * @param specialistes           the pool the staffing need counts
     * @param competencesRares       stand × timeslot groups of it the fragility
     *                               report lists — one specialist or none
     * @param groupesSansSpecialiste the part of them with no specialist at all
     * @param postesIrremplacables   seat groups of it the persisted plan would
     *                               lose with nobody to step in
     * @param joursTension           the dates of those scarce groups
     * @param candidats              ranked; empty means « recrutement »
     */
    @Schema(
            requiredProperties = {
                "candidats",
                "competencesRares",
                "groupesSansSpecialiste",
                "joursTension",
                "manque",
                "ninja",
                "postesIrremplacables",
                "specialistes"
            })
    public record TypologieAFormer(
            String typologie,
            String label,
            boolean ninja,
            int manque,
            int specialistes,
            int competencesRares,
            int groupesSansSpecialiste,
            int postesIrremplacables,
            List<LocalDate> joursTension,
            List<CandidatFormation> candidats) {}

    /**
     * @param planPersiste     a plan is persisted: without one the fragility
     *                         half is empty and only the staffing need speaks
     * @param aucuneCompetence nobody holds any appreciation — the screen sends
     *                         to the Compétences grid rather than listing nothing
     * @param aucunAnimateur   the edition has no animateur at all
     */
    @Schema(requiredProperties = {"aucunAnimateur", "aucuneCompetence", "planPersiste", "typologies"})
    public record PlanFormation(
            List<TypologieAFormer> typologies,
            boolean planPersiste,
            boolean aucuneCompetence,
            boolean aucunAnimateur) {}

    /** Identity of a seat group, to count one irreplaceable group once whoever holds it. */
    private record GroupKey(String standId, long creneauId, LocalTime debut, LocalTime fin) {}

    private static final Comparator<CandidatFormation> ORDRE_CANDIDATS = Comparator.comparingInt(
                    CandidatFormation::joursTensionDisponibles)
            .reversed()
            .thenComparing(CandidatFormation::souhait, Comparator.reverseOrder())
            // AUTONOME before DEBUTANT: the enum runs DEBUTANT < AUTONOME < REFERENT.
            .thenComparing(CandidatFormation::niveau, Comparator.reverseOrder())
            .thenComparing(CandidatFormation::animateurId, NaturalOrder.OF_IDS);

    private static final Comparator<TypologieAFormer> ORDRE_TYPOLOGIES = Comparator.comparingInt(
                    TypologieAFormer::manque)
            .reversed()
            .thenComparing(Comparator.comparingInt(TypologieAFormer::groupesSansSpecialiste)
                    .reversed())
            .thenComparing(
                    Comparator.comparingInt(TypologieAFormer::competencesRares).reversed())
            .thenComparing(TypologieAFormer::label)
            .thenComparing(TypologieAFormer::typologie);

    /**
     * @param staffing       the staffing need, as {@code GET /api/staffing} serves it
     * @param fragilite      the fragility findings of the persisted plan, untruncated
     * @param planPersiste   whether a plan is persisted at all
     * @param animateurs     the referential's animateurs
     * @param stands         the referential's stands
     * @param typologies     the referential's categories, for labels and the ninja flag
     * @param joursEvenement the dates carrying a timeslot, for the minors' rule
     */
    public static PlanFormation compute(
            StaffingSummary staffing,
            FragiliteFindings fragilite,
            boolean planPersiste,
            Collection<Animateur> animateurs,
            Collection<Stand> stands,
            Collection<TypologieItem> typologies,
            Collection<LocalDate> joursEvenement) {
        String ninjaId = typologies.stream()
                .filter(TypologieItem::ninja)
                .map(TypologieItem::id)
                .findFirst()
                .orElse(null);
        Map<String, String> labels = new HashMap<>();
        typologies.forEach(typologie -> labels.put(typologie.id(), typologie.label()));
        Map<String, Set<String>> typologiesParStand = new HashMap<>();
        stands.forEach(stand -> typologiesParStand.put(
                stand.getId(), stand.getTypologiesProposees() == null ? Set.of() : stand.getTypologiesProposees()));

        // The staffing need's rows, by category.
        Map<String, TypologieStaffing> besoin = new LinkedHashMap<>();
        if (staffing != null && staffing.parCompetence() != null) {
            staffing.parCompetence().parTypologie().forEach(ligne -> besoin.put(ligne.typologie(), ligne));
        }

        // The fragility report's scarce groups, attributed to every category of
        // their stand: a group with at most one specialist across all its
        // categories has at most one in each of them.
        Map<String, List<CompetenceRare>> rares = new LinkedHashMap<>();
        for (CompetenceRare rare : fragilite.competencesRares()) {
            for (String typologie : rare.typologies()) {
                rares.computeIfAbsent(typologie, id -> new ArrayList<>()).add(rare);
            }
        }

        // Irreplaceable seat groups, counted once each whoever holds them.
        Map<String, Set<GroupKey>> irremplacables = new HashMap<>();
        for (AnimateurFragilite ligne : fragilite.animateurs()) {
            for (PosteFragile poste : ligne.postes()) {
                if (!poste.irremplacable()) {
                    continue;
                }
                GroupKey cle = new GroupKey(poste.standId(), poste.creneauId(), poste.heureDebut(), poste.heureFin());
                for (String typologie : typologiesParStand.getOrDefault(poste.standId(), Set.of())) {
                    irremplacables
                            .computeIfAbsent(typologie, id -> new HashSet<>())
                            .add(cle);
                }
            }
        }

        Set<String> retenues = new TreeSet<>();
        besoin.values().stream().filter(ligne -> ligne.manque() > 0).forEach(ligne -> retenues.add(ligne.typologie()));
        retenues.addAll(rares.keySet());

        List<TypologieAFormer> lignes = new ArrayList<>();
        for (String typologie : retenues) {
            TypologieStaffing ligneBesoin = besoin.get(typologie);
            List<CompetenceRare> sesRares = rares.getOrDefault(typologie, List.of());
            List<LocalDate> joursTension = sesRares.stream()
                    .map(CompetenceRare::date)
                    .filter(date -> date != null)
                    .distinct()
                    .sorted(Comparator.naturalOrder())
                    .toList();
            lignes.add(new TypologieAFormer(
                    typologie,
                    labels.getOrDefault(typologie, typologie),
                    typologie.equals(ninjaId),
                    ligneBesoin == null ? 0 : ligneBesoin.manque(),
                    ligneBesoin == null ? specialistsOf(typologie, animateurs) : ligneBesoin.specialistes(),
                    sesRares.size(),
                    (int) sesRares.stream()
                            .filter(rare -> rare.specialistes() == 0)
                            .count(),
                    irremplacables.getOrDefault(typologie, Set.of()).size(),
                    joursTension,
                    candidates(typologie, ninjaId, animateurs, stands, joursTension, joursEvenement)));
        }
        lignes.sort(ORDRE_TYPOLOGIES);

        boolean aucuneCompetence = animateurs.stream()
                .allMatch(animateur -> animateur.getCompetences() == null
                        || animateur.getCompetences().isEmpty());
        return new PlanFormation(List.copyOf(lignes), planPersiste, aucuneCompetence, animateurs.isEmpty());
    }

    private static int specialistsOf(String typologie, Collection<Animateur> animateurs) {
        return (int) animateurs.stream()
                .filter(animateur -> animateur.getCompetences() != null
                        && animateur.getCompetences().containsKey(typologie))
                .count();
    }

    private static List<CandidatFormation> candidates(
            String typologie,
            String ninjaId,
            Collection<Animateur> animateurs,
            Collection<Stand> stands,
            List<LocalDate> joursTension,
            Collection<LocalDate> joursEvenement) {
        List<Stand> proposants = stands.stream()
                .filter(stand -> stand.getTypologiesProposees() != null
                        && stand.getTypologiesProposees().contains(typologie))
                .toList();
        boolean reserveeAuxMajeurs =
                !proposants.isEmpty() && proposants.stream().allMatch(Stand::isReserveMajeurs);
        List<CandidatFormation> candidats = new ArrayList<>();
        for (Animateur animateur : animateurs) {
            Map<String, NiveauCompetence> competences =
                    animateur.getCompetences() == null ? Map.of() : animateur.getCompetences();
            NiveauCompetence niveau = competences.get(typologie);
            if (niveau != NiveauCompetence.DEBUTANT && niveau != NiveauCompetence.AUTONOME) {
                continue;
            }
            if (animateur.isNinja() || (ninjaId != null && competences.containsKey(ninjaId))) {
                continue;
            }
            if (reserveeAuxMajeurs && minorThroughout(animateur, joursEvenement)) {
                continue;
            }
            int disponibles = (int) joursTension.stream()
                    .filter(jour -> !animateur.isIndisponibleOn(jour))
                    .count();
            candidats.add(new CandidatFormation(
                    animateur.getId(),
                    animateur.nomAffiche(),
                    niveau,
                    animateur.getSouhaits() != null && animateur.getSouhaits().contains(typologie),
                    disponibles));
        }
        candidats.sort(ORDRE_CANDIDATS);
        return List.copyOf(candidats);
    }

    /**
     * A minor on every day of the event — never on none, when the edition has
     * no timeslot yet: without dates there is nothing to decide on, and leaving
     * somebody out on a guess is worse than listing them.
     */
    private static boolean minorThroughout(Animateur animateur, Collection<LocalDate> joursEvenement) {
        if (joursEvenement.isEmpty() || animateur.getDateNaissance() == null) {
            return false;
        }
        return joursEvenement.stream().noneMatch(animateur::isMajeurOn);
    }

    /* ---------------------------------- CSV ---------------------------------- */

    /**
     * The tab as a CSV, one line per (category, candidate) — and one line with
     * the « recrutement » mention for a category nobody can be trained on,
     * since the screen shows that row too. {@code ;}, byte order mark added by
     * the resource, like every other export.
     */
    public static String generateCsv(PlanFormation plan) {
        StringBuilder csv =
                new StringBuilder("typologie;manque besoin;competences rares;sans specialiste;postes irremplacables;"
                        + "jours en tension;animateur;niveau;souhait;jours en tension disponibles\n");
        for (TypologieAFormer ligne : plan.typologies()) {
            String deficit = String.join(
                    ";",
                    escape(ligne.label()),
                    String.valueOf(ligne.manque()),
                    String.valueOf(ligne.competencesRares()),
                    String.valueOf(ligne.groupesSansSpecialiste()),
                    String.valueOf(ligne.postesIrremplacables()),
                    String.valueOf(ligne.joursTension().size()));
            if (ligne.candidats().isEmpty()) {
                csv.append(deficit).append(";aucun candidat : recrutement;;;\n");
                continue;
            }
            for (CandidatFormation candidat : ligne.candidats()) {
                csv.append(deficit)
                        .append(';')
                        .append(escape(candidat.nom()))
                        .append(';')
                        .append(candidat.niveau())
                        .append(';')
                        .append(candidat.souhait() ? "oui" : "non")
                        .append(';')
                        .append(candidat.joursTensionDisponibles())
                        .append('\n');
            }
        }
        return csv.toString();
    }

    private static String escape(String valeur) {
        if (valeur == null) {
            return "";
        }
        if (valeur.contains(";") || valeur.contains("\"") || valeur.contains("\n")) {
            return "\"" + valeur.replace("\"", "\"\"") + "\"";
        }
        return valeur;
    }
}
