package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.AnimateurFragilite;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.CompetenceRare;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.FragiliteFindings;
import dev.sylvain.planning.service.analyse.FragiliteAnalyzer.PosteFragile;
import dev.sylvain.planning.service.analyse.MargeAnalyzer.CelluleMarge;
import dev.sylvain.planning.service.analyse.MargeAnalyzer.JourMarge;
import dev.sylvain.planning.service.analyse.MargeAnalyzer.RapportMarge;
import dev.sylvain.planning.service.analyse.MargeAnalyzer.TrancheMarge;
import dev.sylvain.planning.service.analyse.StaffingAnalyzer.ReferentielManquant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The « Tension » reading of the Marge screen: the margin after the solve and
 * the fragility of the persisted plan, crossed on one day × timeslot grid.
 *
 * <p>A cell of the margin says how many people are left; the fragility report
 * says which seats nobody could take over. Neither says, alone, how critical
 * a moment is: a « +3 » can hide a seat nobody else is competent for, and an
 * irreplaceable seat matters less when the timeslot has people to spare. This
 * class puts the two on the same cell, keyed as the margin keys it — the date
 * and the hours of the timeslot — and rates it with <b>named rules</b> rather
 * than a weighted score, so every cell can say in one sentence why it is
 * red.</p>
 *
 * <p>It reads the fragility <b>untruncated</b> ({@link FragiliteFindings}): the
 * screen's report caps the seats it details per animateur and the scarcity
 * rows it lists, and a map rebuilt from the capped lists would read calm on
 * exactly the large editions it exists for. It redefines nothing: the margin
 * is {@link MargeAnalyzer}'s « après » cell, the seat thresholds are the
 * fragility report's own {@code irremplacable} and {@code remplacants}. Pure
 * Java on reports already computed — no solve.</p>
 */
public final class TensionAnalyzer {

    private TensionAnalyzer() {}

    /** A seat whose withdrawal leaves at most this many substitutes is fragile. */
    static final int REMPLACANTS_FRAGILE = 1;

    /** Four levels, worst first — the order the synthesis under the grid sorts by. */
    public enum GraviteTension {
        CRITIQUE,
        ELEVEE,
        SURVEILLEE,
        CALME
    }

    /** Why a cell sits at its level; the screen words each one. */
    public enum MotifTension {
        /** {@code CRITIQUE}: seats left empty, and nobody free to fill them ({@code marge < 0}). */
        SIEGES_VIDES_NON_COUVRABLES,
        /** {@code CRITIQUE}: a filled seat nobody could take over if its holder withdrew. */
        SIEGE_IRREMPLACABLE,
        /** {@code CRITIQUE}: a stand of the timeslot nobody is competent for. */
        STAND_SANS_SPECIALISTE,
        /** {@code ELEVEE}: empty seats and exactly nobody to spare. */
        MARGE_NULLE_AVEC_SIEGES_VIDES,
        /** {@code ELEVEE}: more fragile seats than people to spare. */
        FRAGILES_AU_DELA_DE_LA_MARGE,
        /** {@code SURVEILLEE}: fragile seats, with enough people to spare. */
        SIEGES_FRAGILES,
        /** {@code SURVEILLEE}: a stand resting on one specialist, and no ninja to back them up. */
        SPECIALISTE_UNIQUE_SANS_RENFORT
    }

    /**
     * One cell of the tension map.
     *
     * @param creneauId                         the margin cell's, for the bench link
     * @param siegesVides                       seats the plan left empty
     * @param siegesFragiles                    filled seats whose withdrawal leaves the group
     *                                          short with at most one substitute
     * @param siegesIrremplacables              the part of them with no substitute at all —
     *                                          <b>all</b> of them, never capped
     * @param competencesRaresSansSpecialiste   stand groups of the timeslot nobody is competent for
     * @param animateursIrremplacables          who holds the irreplaceable seats, by id
     * @param standsSpecialisteUnique           stands of the timeslot resting on one
     *                                          specialist, by id
     * @param standsSansSpecialiste             stands of the timeslot nobody is competent for, by id
     * @param passee                            the timeslot has started (ADR 0044): nothing
     *                                          there is actionable, and {@code gravite} is
     *                                          {@code null}
     */
    @Schema(
            requiredProperties = {
                "animateursIrremplacables",
                "competencesRaresSansSpecialiste",
                "creneauId",
                "debut",
                "fin",
                "jour",
                "marge",
                "motifs",
                "passee",
                "siegesFragiles",
                "siegesIrremplacables",
                "siegesVides",
                "standsSansSpecialiste",
                "standsSpecialisteUnique"
            })
    public record CelluleTension(
            LocalDate date,
            int jour,
            LocalTime debut,
            LocalTime fin,
            long creneauId,
            int marge,
            int siegesVides,
            int siegesFragiles,
            int siegesIrremplacables,
            int competencesRaresSansSpecialiste,
            List<String> animateursIrremplacables,
            List<String> standsSpecialisteUnique,
            List<String> standsSansSpecialiste,
            boolean passee,
            GraviteTension gravite,
            List<MotifTension> motifs) {}

    /**
     * @param pireCellule the worst cell of the day still ahead, {@code null}
     *                    when every cell of it is past
     */
    @Schema(requiredProperties = {"cellules", "jour"})
    public record JourTension(LocalDate date, int jour, List<CelluleTension> cellules, CelluleTension pireCellule) {}

    /**
     * @param ninjaConfigure  the referential marks a ninja category: without
     *                        one, « no reinforcement » is the rule, not a
     *                        finding, and the screen says so
     * @param cellulesCritiques cells rated {@code CRITIQUE}
     */
    @Schema(
            requiredProperties = {
                "animateursTotal",
                "cellulesCritiques",
                "jours",
                "message",
                "ninjaConfigure",
                "referentielsManquants",
                "tranches"
            })
    public record RapportTension(
            List<TrancheMarge> tranches,
            List<JourTension> jours,
            CelluleTension pireCellule,
            int animateursTotal,
            int cellulesCritiques,
            boolean ninjaConfigure,
            List<ReferentielManquant> referentielsManquants,
            String message)
            implements MarginReading {}

    /** Identity of a cell, as {@link MargeAnalyzer} keys it. */
    private record CelluleKey(LocalDate date, LocalTime debut, LocalTime fin) {}

    /** What the fragility findings put on one cell. */
    private static final class Constats {
        private int fragiles;
        private int irremplacables;
        private int sansSpecialiste;
        private final Set<String> animateurs = new LinkedHashSet<>();
        private final Set<String> specialisteUnique = new TreeSet<>();
        private final Set<String> sansSpecialisteStands = new TreeSet<>();
        private boolean specialisteUniqueSansRenfort;
    }

    /** Worst first, then the tightest margin, then the earliest. */
    static final Comparator<CelluleTension> ORDRE_PIRE = Comparator.comparing(
                    CelluleTension::gravite, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparingInt(CelluleTension::marge)
            .thenComparing(CelluleTension::date, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(CelluleTension::debut, Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * @param apres     the margin in « après » mode, on the persisted plan
     * @param fragilite the fragility findings of that same plan, untruncated
     * @param creneaux  the timeslots of the plan: a fragile seat is attached to
     *                  the cell of its timeslot's nominal window, whatever its
     *                  own effective window
     * @param horizon   the past-is-frozen horizon, {@code null} when the freeze is off
     */
    public static RapportTension compute(
            RapportMarge apres, FragiliteFindings fragilite, Collection<Creneau> creneaux, PastHorizon horizon) {
        Map<Long, CelluleKey> cellulesParCreneau = new HashMap<>();
        for (Creneau creneau : creneaux) {
            if (creneau.getId() != null && creneau.getDate() != null) {
                cellulesParCreneau.put(
                        creneau.getId(),
                        new CelluleKey(creneau.getDate(), creneau.getHeureDebut(), creneau.getHeureFin()));
            }
        }

        Map<CelluleKey, Constats> constats = new HashMap<>();
        for (AnimateurFragilite ligne : fragilite.animateurs()) {
            for (PosteFragile poste : ligne.postes()) {
                CelluleKey cle = cellulesParCreneau.get(poste.creneauId());
                if (cle == null || poste.remplacants() > REMPLACANTS_FRAGILE) {
                    continue;
                }
                Constats cellule = constats.computeIfAbsent(cle, ignore -> new Constats());
                cellule.fragiles += poste.siegesLiberes();
                if (poste.irremplacable()) {
                    cellule.irremplacables += poste.siegesLiberes();
                    cellule.animateurs.add(ligne.animateurId());
                }
            }
        }
        for (CompetenceRare rare : fragilite.competencesRares()) {
            CelluleKey cle = cellulesParCreneau.get(rare.creneauId());
            if (cle == null) {
                continue;
            }
            Constats cellule = constats.computeIfAbsent(cle, ignore -> new Constats());
            if (rare.specialistes() == 0) {
                cellule.sansSpecialiste++;
                cellule.sansSpecialisteStands.add(rare.standId());
            } else {
                cellule.specialisteUnique.add(rare.standId());
                if (rare.renforts() == 0) {
                    cellule.specialisteUniqueSansRenfort = true;
                }
            }
        }

        boolean sansAnimateur = apres.animateursTotal() == 0;
        List<JourTension> jours = new ArrayList<>();
        if (!sansAnimateur) {
            for (JourMarge jourMarge : apres.jours()) {
                List<CelluleTension> cellules = new ArrayList<>();
                for (CelluleMarge marge : jourMarge.cellules()) {
                    Constats cellule = constats.getOrDefault(
                            new CelluleKey(marge.date(), marge.debut(), marge.fin()), new Constats());
                    boolean passee = horizon != null && horizon.hasStarted(marge.date(), marge.debut());
                    cellules.add(rate(marge, cellule, passee));
                }
                CelluleTension pire = cellules.stream()
                        .filter(cellule -> !cellule.passee())
                        .min(ORDRE_PIRE)
                        .orElse(null);
                jours.add(new JourTension(jourMarge.date(), jourMarge.jour(), List.copyOf(cellules), pire));
            }
        }

        CelluleTension pire = jours.stream()
                .map(JourTension::pireCellule)
                .filter(cellule -> cellule != null)
                .min(ORDRE_PIRE)
                .orElse(null);
        int critiques = (int) jours.stream()
                .flatMap(jour -> jour.cellules().stream())
                .filter(cellule -> cellule.gravite() == GraviteTension.CRITIQUE)
                .count();
        return new RapportTension(
                sansAnimateur ? List.of() : apres.tranches(),
                List.copyOf(jours),
                pire,
                apres.animateursTotal(),
                critiques,
                fragilite.ninjaConfigure(),
                apres.referentielsManquants(),
                message(sansAnimateur, jours, critiques, pire));
    }

    private static CelluleTension rate(CelluleMarge marge, Constats constats, boolean passee) {
        int vides = marge.besoin();
        List<MotifTension> motifs = new ArrayList<>();
        GraviteTension gravite = null;
        if (!passee) {
            if (marge.marge() < 0) {
                motifs.add(MotifTension.SIEGES_VIDES_NON_COUVRABLES);
            }
            if (constats.irremplacables > 0) {
                motifs.add(MotifTension.SIEGE_IRREMPLACABLE);
            }
            if (constats.sansSpecialiste > 0) {
                motifs.add(MotifTension.STAND_SANS_SPECIALISTE);
            }
            if (!motifs.isEmpty()) {
                gravite = GraviteTension.CRITIQUE;
            } else {
                if (marge.marge() == 0 && vides > 0) {
                    motifs.add(MotifTension.MARGE_NULLE_AVEC_SIEGES_VIDES);
                }
                if (constats.fragiles > marge.marge()) {
                    motifs.add(MotifTension.FRAGILES_AU_DELA_DE_LA_MARGE);
                }
                if (!motifs.isEmpty()) {
                    gravite = GraviteTension.ELEVEE;
                } else {
                    if (constats.fragiles > 0) {
                        motifs.add(MotifTension.SIEGES_FRAGILES);
                    }
                    if (constats.specialisteUniqueSansRenfort) {
                        motifs.add(MotifTension.SPECIALISTE_UNIQUE_SANS_RENFORT);
                    }
                    gravite = motifs.isEmpty() ? GraviteTension.CALME : GraviteTension.SURVEILLEE;
                }
            }
        }
        return new CelluleTension(
                marge.date(),
                marge.jour(),
                marge.debut(),
                marge.fin(),
                marge.creneauId(),
                marge.marge(),
                vides,
                constats.fragiles,
                constats.irremplacables,
                constats.sansSpecialiste,
                List.copyOf(constats.animateurs),
                List.copyOf(constats.specialisteUnique),
                List.copyOf(constats.sansSpecialisteStands),
                passee,
                gravite,
                List.copyOf(motifs));
    }

    /** One sentence for the banner, like the margin's; the grid says the rest. */
    private static String message(boolean sansAnimateur, List<JourTension> jours, int critiques, CelluleTension pire) {
        if (sansAnimateur) {
            return "Aucun animateur n'est saisi : la tension du planning ne peut pas être mesurée.";
        }
        if (jours.isEmpty()) {
            return "Aucun planning persisté : lancez une résolution pour lire la tension du planning.";
        }
        if (pire == null) {
            return "Toutes les tranches sont passées : il ne reste rien à arbitrer.";
        }
        String situe = "J%d %s-%s".formatted(pire.jour(), pire.debut(), pire.fin());
        if (critiques == 0) {
            return "Aucune tranche critique : la plus tendue est %s.".formatted(situe);
        }
        return "%d tranche(s) critique(s), à commencer par %s.".formatted(critiques, situe);
    }
}
