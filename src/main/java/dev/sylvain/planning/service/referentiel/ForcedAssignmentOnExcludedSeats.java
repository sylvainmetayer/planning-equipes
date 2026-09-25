package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.TypeContrainteAdHoc;
import dev.sylvain.planning.solver.EligibleAnimateurMoveFilter;
import dev.sylvain.planning.solver.EligibleAnimateurMoveFilter.Motif;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * A forced assignment no seat of its scope can hold: every seat the exception
 * could be satisfied on refuses every animateur it names, for a reason read on
 * the (seat, animateur) pair alone — a minor on a night slot, on a public
 * holiday, on an adults-only stand, past their daily cap, or simply a day they
 * declared off.
 *
 * <p>The sibling of {@link ForcedAssignmentOnDayOff}, and deliberately not a
 * merge of it. That one compares dates with the days people declared and says
 * so in those terms, which is the case an organiser fixes by talking to the
 * person; this one names a rule of the catalogue, which is the case they fix
 * by moving the exception. An exception {@code ForcedAssignmentOnDayOff}
 * already reports is therefore skipped here rather than reported twice — the
 * day off is one of the reasons this class would find, and the more precise
 * sentence wins.</p>
 *
 * <p>Warned, never refused, for the reason {@link Avertissement} states: the
 * birth date, the night window or the stand's adults-only flag may all move
 * after the exception was written, and a refusal that only holds until then is
 * worse than a warning that keeps standing. The pre-solve analysis reports it
 * as a blocking cause for as long as it stands, whichever came first.</p>
 *
 * <p>Pure and static, read the same way by the write and by the analysis. The
 * seats of the scope are built exactly as {@code ProblemBuilder} generates
 * them — one per (stand open on the timeslot, timeslot) — so a scope whose
 * stands open nowhere carries no seat and yields no conflict. An unknown
 * animateur or an unknown stand yields none either: there is nothing to
 * compare with, and this class never invents a conflict.</p>
 */
public final class ForcedAssignmentOnExcludedSeats {

    /** How many dates a message spells out before it says "and n others". */
    private static final int DATES_CITEES = 5;

    private ForcedAssignmentOnExcludedSeats() {}

    /**
     * The legal parameters the eligibility motifs are read under: the domain's
     * defaults, as {@code FeasibilityAnalyzer.capacite} reads them and for the
     * same reason — this check does not hold the edition's, and the default
     * break is the legal floor, so an edition granting more only opens seats
     * this reading closed. A conflict reported here is one the solver would
     * meet whatever the edition declares.
     */
    private static final ParametresLegaux PAUSES_PAR_DEFAUT = new ParametresLegaux();

    /**
     * @param contraintes the rules of the catalogue every (seat, animateur)
     *                    pair of the scope broke, sorted and deduplicated
     * @param dates       the dates the scope spans
     */
    public record Conflit(ContrainteAdHoc contrainte, List<String> contraintesCassees, List<LocalDate> dates) {

        public String message() {
            String cites =
                    dates.stream().limit(DATES_CITEES).map(LocalDate::toString).collect(Collectors.joining(", "));
            String reste = dates.size() > DATES_CITEES ? " et " + (dates.size() - DATES_CITEES) + " autre(s)" : "";
            boolean plusieurs = contrainte.getAnimateursConcernes().size() > 1;
            return "L'affectation forcée " + contrainte.getId() + " ne peut pas être tenue : aucune des places de sa "
                    + "portée (" + cites + reste + ") n'accepte "
                    + (plusieurs ? "l'un des animateurs qu'elle nomme" : "l'animateur qu'elle nomme")
                    + ", au titre de " + citerContraintes() + ".";
        }

        private String citerContraintes() {
            return contraintesCassees.size() == 1
                    ? "la règle " + contraintesCassees.getFirst()
                    : "les règles " + String.join(", ", contraintesCassees);
        }
    }

    public static List<Conflit> detectAll(
            List<ContrainteAdHoc> contraintes,
            List<Animateur> animateurs,
            List<Stand> stands,
            List<Creneau> creneaux,
            PastHorizon horizon) {
        if (contraintes == null || contraintes.isEmpty()) {
            return List.of();
        }
        Map<String, Animateur> animateursParId = ForcedAssignmentScope.index(animateurs, Animateur::getId);
        Map<String, Stand> standsParId = ForcedAssignmentScope.index(stands, Stand::getId);
        List<Conflit> conflits = new ArrayList<>();
        for (ContrainteAdHoc contrainte : contraintes) {
            detect(contrainte, animateursParId, standsParId, creneaux, horizon).ifPresent(conflits::add);
        }
        return List.copyOf(conflits);
    }

    public static Optional<Conflit> detect(
            ContrainteAdHoc contrainte,
            Map<String, Animateur> animateursParId,
            Map<String, Stand> standsParId,
            List<Creneau> creneaux,
            PastHorizon horizon) {
        if (contrainte == null
                || contrainte.getType() != TypeContrainteAdHoc.AFFECTATION_FORCEE
                || contrainte.getAnimateursConcernes() == null
                || contrainte.getAnimateursConcernes().isEmpty()) {
            return Optional.empty();
        }
        // The day-off reading of the same impossibility, said in the terms of
        // the declaration rather than in those of the catalogue: it is the
        // more actionable of the two, so it keeps the case.
        if (ForcedAssignmentOnDayOff.detect(contrainte, animateursParId, standsParId, creneaux, horizon)
                .isPresent()) {
            return Optional.empty();
        }
        Optional<List<Animateur>> connus = namedAnimateurs(contrainte, animateursParId);
        if (connus.isEmpty()) {
            return Optional.empty();
        }
        List<Animateur> nommes = connus.get();
        TreeSet<String> cassees = new TreeSet<>();
        TreeSet<LocalDate> dates = new TreeSet<>();
        boolean aVenir = false;
        // One pass, and the dates collected on the way: the stream is walked
        // once and abandoned at the first pair a solve could take.
        for (Iterator<PosteAffectation> sieges = ForcedAssignmentScope.seats(contrainte, standsParId, creneaux)
                        .iterator();
                sieges.hasNext(); ) {
            PosteAffectation siege = sieges.next();
            if (anyEligible(siege, nommes, cassees)) {
                // One pair the solver could take: the exception is tenable.
                return Optional.empty();
            }
            LocalDate date = siege.getCreneau().getDate();
            dates.add(date);
            aVenir |= ForcedAssignmentPast.aVenir(date, horizon);
        }
        if (dates.isEmpty() || !aVenir) {
            // No seat at all, or a scope entirely behind us: `affectationForcee`
            // charges neither (ADR 0044), so neither does this.
            return Optional.empty();
        }
        return Optional.of(new Conflit(contrainte, List.copyOf(cassees), List.copyOf(dates)));
    }

    /** The animateurs the exception names, or nothing when one of them is unknown to the referential. */
    private static Optional<List<Animateur>> namedAnimateurs(
            ContrainteAdHoc contrainte, Map<String, Animateur> animateursParId) {
        List<Animateur> nommes = new ArrayList<>();
        for (Animateur reference : contrainte.getAnimateursConcernes()) {
            Animateur animateur = reference == null ? null : animateursParId.get(reference.getId());
            if (animateur == null) {
                // Somebody the referential does not know: nothing to compare with.
                return Optional.empty();
            }
            nommes.add(animateur);
        }
        return Optional.of(nommes);
    }

    /**
     * Whether one of {@code nommes} may hold {@code siege}; the constraints that
     * exclude the others seen before that one are added to {@code cassees}.
     */
    private static boolean anyEligible(PosteAffectation siege, List<Animateur> nommes, TreeSet<String> cassees) {
        for (Animateur animateur : nommes) {
            List<Motif> motifs = EligibleAnimateurMoveFilter.motifs(siege, animateur, PAUSES_PAR_DEFAUT);
            if (motifs.isEmpty()) {
                return true;
            }
            motifs.stream().map(Motif::contrainte).forEach(cassees::add);
        }
        return false;
    }
}
