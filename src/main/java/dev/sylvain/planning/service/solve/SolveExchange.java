package dev.sylvain.planning.service.solve;

import ai.timefold.solver.core.api.domain.solution.ConstraintWeightOverrides;
import ai.timefold.solver.core.api.score.HardMediumSoftScore;
import ai.timefold.solver.core.api.solver.SolutionManager;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonIncludeProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.sylvain.planning.domain.AffectationPubliee;
import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.FenetreDateeStand;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.Month;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The form a <b>prepared</b> problem takes to be solved somewhere else, and
 * the form its answer comes back in (ADR 0085). Nothing calls it yet: it is
 * the piece a remote solve stands on, written and tested before the transport.
 *
 * <p><b>Not the API's JSON.</b> The public JSON of {@link PlanningEvenement}
 * leaves out, on purpose, what a caller must not be able to set: the published
 * plan, the constraint weights, the past horizon, the past flag of a seat, the
 * effective opening hours of a stand. A solver reads every one of them, so a
 * problem sent through that JSON would be solved under other rules without a
 * word. This class writes them, through mix-ins laid on a private copy of the
 * mapper — the application's own mapper, and the guards of its JSON, do not
 * move.</p>
 *
 * <p><b>Nobody is named.</b> An animateur leaves as an allow-list of what the
 * rules read: a new field on {@link Animateur} stays here until somebody adds
 * it to {@link AnimateurMixin}. The id is replaced by a pseudonym drawn for
 * this solve — the table back to the real ids never leaves the application —
 * and the birth date by a stand-in of the same age bracket
 * ({@link #standInBirthDate}). Free text (the reason of a lock, of an ad hoc
 * exception, of a stand's dated window) does not leave either: somebody typed it, and may have typed a
 * name.</p>
 *
 * <p><b>The answer is not trusted.</b> It carries who holds each seat and the
 * score it claims; {@link #land} lays the holders on the application's own
 * problem and scores it again. A different score, a seat that was pinned and
 * moved, a seat or a person the problem does not know: the plan is refused and
 * the problem left as it was. That is also the net under this class itself —
 * a field the solver reads and this format forgot shows up as a score that
 * does not match.</p>
 */
public final class SolveExchange {

    /** Bumped when the envelope changes shape: a reader refuses a format it does not know. */
    static final int FORMAT = 1;

    private static final LocalDate ADULT = LocalDate.of(1970, Month.JANUARY, 1);

    private final ObjectMapper mapper;

    private final SecureRandom random = new SecureRandom();

    /** @param base the application's mapper; copied, so the mix-ins below never reach it */
    public SolveExchange(ObjectMapper base) {
        this.mapper = base.copy()
                .findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .addMixIn(PlanningEvenement.class, ProblemMixin.class)
                .addMixIn(PosteAffectation.class, SeatMixin.class)
                .addMixIn(Animateur.class, AnimateurMixin.class)
                .addMixIn(Stand.class, StandMixin.class)
                .addMixIn(ContrainteAdHoc.class, FreeTextMixin.class)
                .addMixIn(VerrouillagePlanning.class, FreeTextMixin.class)
                .addMixIn(FenetreDateeStand.class, FreeTextMixin.class)
                .addMixIn(HoraireStand.class, FreeTextMixin.class);
    }

    /** What travels: the problem, and its weights beside it — Timefold's own type is not a bean. */
    record Envelope(int format, PlanningEvenement problem, Map<String, String> weights) {}

    /**
     * A problem ready to leave, and the way back from its pseudonyms. A class
     * rather than a record: a record would compare its payload by reference.
     */
    public static final class Outbound {
        private final byte[] payload;
        private final Map<String, String> realIds;

        Outbound(byte[] payload, Map<String, String> realIds) {
            this.payload = payload;
            this.realIds = realIds;
        }

        /** What is sent: the envelope as JSON, gzipped. */
        public byte[] payload() {
            return payload;
        }

        /** The real id of each pseudonym; stays in the application. */
        public Map<String, String> realIds() {
            return realIds;
        }
    }

    /**
     * What comes back.
     *
     * @param holders the pseudonym holding each seat, by seat id; {@code null} for an empty seat
     * @param score   the score the solving side claims, under the problem's own weights
     */
    public record Result(Map<String, String> holders, String score) {}

    /** A plan that came back and cannot be the answer to the problem that left. */
    public static final class Refused extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Refused(String message) {
            super(message);
        }
    }

    /**
     * {@code prepared} as it is sent: pseudonyms for ids, stand-ins for birth
     * dates, no identity, no free text. {@code prepared} itself is not touched.
     */
    public Outbound outbound(PlanningEvenement prepared) {
        PlanningEvenement copy = read(write(prepared));
        Map<String, String> realIds = pseudonymise(copy);
        return new Outbound(write(copy), realIds);
    }

    /** The problem a payload carries, its references tied back together and its weights laid on. */
    public PlanningEvenement read(byte[] payload) {
        Envelope envelope;
        try (InputStream json = new GZIPInputStream(new ByteArrayInputStream(payload))) {
            envelope = mapper.readValue(json, Envelope.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        if (envelope.format() != FORMAT || envelope.problem() == null) {
            throw new Refused("Unknown exchange format " + envelope.format() + ", this side reads " + FORMAT);
        }
        PlanningEvenement problem = envelope.problem();
        reattach(problem);
        Map<String, HardMediumSoftScore> weights = new HashMap<>();
        if (envelope.weights() != null) {
            envelope.weights().forEach((name, weight) -> weights.put(name, HardMediumSoftScore.parseScore(weight)));
        }
        problem.setPonderationsContraintes(
                weights.isEmpty() ? ConstraintWeightOverrides.none() : ConstraintWeightOverrides.of(weights));
        return problem;
    }

    /**
     * The answer to send back for {@code solved}. The score is computed here,
     * under the weights the problem came with: a two-stage solve stopped in
     * its first stage returns a plan still scored with stability suspended.
     */
    public Result resultOf(
            PlanningEvenement solved, SolutionManager<PlanningEvenement, HardMediumSoftScore> solutionManager) {
        Map<String, String> holders = new LinkedHashMap<>();
        for (PosteAffectation poste : solved.getPostes()) {
            holders.put(
                    poste.getId(),
                    poste.getAnimateur() == null ? null : poste.getAnimateur().getId());
        }
        return new Result(holders, solutionManager.update(solved).toString());
    }

    /**
     * Lays {@code result} on {@code prepared} — the application's own problem,
     * real ids and all — and scores it. Refused, and {@code prepared} left as
     * it was, when the answer does not fit the problem or its score is not the
     * one claimed.
     *
     * @return the score of the plan now carried by {@code prepared}
     */
    public HardMediumSoftScore land(
            PlanningEvenement prepared,
            Result result,
            Map<String, String> realIds,
            SolutionManager<PlanningEvenement, HardMediumSoftScore> solutionManager) {
        List<PosteAffectation> postes = prepared.getPostes();
        List<Animateur> holders = holdersFor(prepared, result, realIds);
        List<Animateur> before =
                postes.stream().map(PosteAffectation::getAnimateur).toList();
        for (int i = 0; i < postes.size(); i++) {
            postes.get(i).setAnimateur(holders.get(i));
        }
        HardMediumSoftScore score = solutionManager.update(prepared);
        if (!score.toString().equals(result.score())) {
            for (int i = 0; i < postes.size(); i++) {
                postes.get(i).setAnimateur(before.get(i));
            }
            solutionManager.update(prepared);
            throw new Refused("The plan scores " + score + " here, not the " + result.score() + " it claims");
        }
        return score;
    }

    /** Who the answer puts on each seat of {@code prepared}, in seat order — or a refusal, before anything moved. */
    private static List<Animateur> holdersFor(PlanningEvenement prepared, Result result, Map<String, String> realIds) {
        List<PosteAffectation> postes = prepared.getPostes();
        Map<String, Animateur> byId = new HashMap<>();
        prepared.getAnimateurs().forEach(animateur -> byId.put(animateur.getId(), animateur));
        if (result.holders() == null || result.holders().size() != postes.size()) {
            throw new Refused("The plan does not carry the seats of the problem");
        }
        List<Animateur> holders = new ArrayList<>(postes.size());
        for (PosteAffectation poste : postes) {
            if (!result.holders().containsKey(poste.getId())) {
                throw new Refused("The plan does not carry seat " + poste.getId());
            }
            String pseudonym = result.holders().get(poste.getId());
            Animateur holder = pseudonym == null ? null : byId.get(realIds.get(pseudonym));
            if (pseudonym != null && holder == null) {
                throw new Refused("Seat " + poste.getId() + " is held by somebody the problem does not know");
            }
            if (poste.isVerrouille() && !Objects.equals(holder, poste.getAnimateur())) {
                throw new Refused("Seat " + poste.getId() + " was pinned and came back changed");
            }
            holders.add(holder);
        }
        return holders;
    }

    /** The envelope of {@code problem} as it stands, ids untouched: what {@link #outbound} reads back before pseudonymising. */
    byte[] write(PlanningEvenement problem) {
        Map<String, String> weights = new LinkedHashMap<>();
        ConstraintWeightOverrides<HardMediumSoftScore> overrides = problem.getPonderationsContraintes();
        if (overrides != null) {
            for (String name : new TreeSet<>(overrides.getKnownConstraintIds())) {
                weights.put(name, overrides.getConstraintWeight(name).toString());
            }
        }
        // Compressed here, not left to the transport: JSON writes the stand,
        // the timeslot and the holder again under every seat, so a real event
        // is thirteen megabytes raw — past the default HTTP body limit — and
        // a hundred times less zipped.
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (OutputStream json = new GZIPOutputStream(bytes)) {
            mapper.writeValue(json, new Envelope(FORMAT, problem, weights));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    /**
     * One {@link Animateur} instance per person again: JSON wrote a copy under
     * every seat and every ad hoc exception, and the pseudonymisation relies
     * on renaming each person once.
     */
    private static void reattach(PlanningEvenement problem) {
        Map<String, Animateur> byId = new HashMap<>();
        problem.getAnimateurs().forEach(animateur -> byId.put(animateur.getId(), animateur));
        for (PosteAffectation poste : problem.getPostes()) {
            if (poste.getAnimateur() != null) {
                poste.setAnimateur(known(byId, poste.getAnimateur()));
            }
        }
        for (ContrainteAdHoc contrainte : problem.getContraintesAdHoc()) {
            contrainte.setAnimateursConcernes(new ArrayList<>(contrainte.getAnimateursConcernes().stream()
                    .map(animateur -> known(byId, animateur))
                    .toList()));
        }
    }

    private static Animateur known(Map<String, Animateur> byId, Animateur copy) {
        Animateur animateur = byId.get(copy.getId());
        if (animateur == null) {
            throw new Refused("The problem names somebody who is not among its animateurs");
        }
        return animateur;
    }

    /** Renames everybody in {@code copy}, in place; returns the real id of each pseudonym. */
    private Map<String, String> pseudonymise(PlanningEvenement copy) {
        TreeSet<LocalDate> dates = new TreeSet<>();
        for (PosteAffectation poste : copy.getPostes()) {
            if (poste.getCreneau() != null && poste.getCreneau().getDate() != null) {
                dates.add(poste.getCreneau().getDate());
            }
        }
        Map<String, String> pseudonyms = new HashMap<>();
        for (Animateur animateur : copy.getAnimateurs()) {
            animateur.setDateNaissance(standInBirthDate(animateur.getDateNaissance(), dates));
            animateur.setId(pseudonymOf(animateur.getId(), pseudonyms));
        }
        for (VerrouillagePlanning verrouillage : copy.getVerrouillages()) {
            if (verrouillage.getAnimateurId() != null) {
                verrouillage.setAnimateurId(pseudonymOf(verrouillage.getAnimateurId(), pseudonyms));
            }
        }
        // A published seat may name somebody who has left the edition since:
        // still a person, still renamed.
        copy.setAffectationsPubliees(copy.getAffectationsPubliees().stream()
                .map(publiee -> new AffectationPubliee(
                        publiee.standId(),
                        publiee.date(),
                        publiee.heureDebut(),
                        publiee.heureFin(),
                        pseudonymOf(publiee.animateurId(), pseudonyms)))
                .toList());
        Map<String, String> realIds = new HashMap<>();
        pseudonyms.forEach((real, pseudonym) -> realIds.put(pseudonym, real));
        return realIds;
    }

    private String pseudonymOf(String realId, Map<String, String> pseudonyms) {
        return pseudonyms.computeIfAbsent(realId, id -> {
            byte[] bytes = new byte[12];
            random.nextBytes(bytes);
            return "p" + HexFormat.of().formatHex(bytes);
        });
    }

    /**
     * A birth date that answers every date of the event as {@code real} does,
     * and says nothing else.
     *
     * <p>The rules ask two things of a birth date, each at the date of a
     * timeslot: under 16, under 18. Sending « the day they turn 18 » would be
     * sending the birth date — it is 18 years away. So whoever stays in one
     * bracket for the whole event gets the bracket's stand-in, and only
     * somebody who turns 16 or 18 <i>during</i> the event keeps their real
     * date: the answer changes mid-event, and nothing else can say when. Each
     * stand-in is checked against every date before it is used, so a wrong
     * guess here costs a real date sent, never a wrong answer.</p>
     */
    static LocalDate standInBirthDate(LocalDate real, TreeSet<LocalDate> dates) {
        if (real == null || dates.isEmpty()) {
            return real;
        }
        for (LocalDate standIn :
                List.of(ADULT, dates.first().minusYears(17), dates.last().minusYears(10))) {
            if (sameBrackets(real, standIn, dates)) {
                return standIn;
            }
        }
        return real;
    }

    private static boolean sameBrackets(LocalDate real, LocalDate standIn, TreeSet<LocalDate> dates) {
        Animateur asReal = born(real);
        Animateur asStandIn = born(standIn);
        for (LocalDate date : dates) {
            if (asReal.isMineurOn(date) != asStandIn.isMineurOn(date)
                    || asReal.isUnder16On(date) != asStandIn.isUnder16On(date)) {
                return false;
            }
        }
        return true;
    }

    private static Animateur born(LocalDate date) {
        Animateur animateur = new Animateur();
        animateur.setDateNaissance(date);
        return animateur;
    }

    /** Brings back what the public JSON hides from a caller and a solver reads. */
    private abstract static class ProblemMixin {
        @JsonIgnore(false)
        @JsonProperty
        List<AffectationPubliee> affectationsPubliees;

        @JsonIgnore(false)
        @JsonProperty
        PastHorizon pastHorizon;

        @JsonIgnore(false)
        @JsonProperty
        abstract PastHorizon getPastHorizon();

        @JsonIgnore(false)
        @JsonProperty
        abstract void setPastHorizon(PastHorizon pastHorizon);

        // The weights travel beside the problem (Envelope), the score is
        // computed by whoever reads the plan.
        @JsonIgnore
        abstract HardMediumSoftScore getScore();
    }

    private abstract static class SeatMixin {
        @JsonIgnore(false)
        @JsonProperty
        abstract boolean isPasse();

        @JsonIgnore(false)
        @JsonProperty
        abstract void setPasse(boolean passe);
    }

    /** An allow-list, not an ignore-list: what the rules read of a person, and nothing a new field adds. */
    @JsonIncludeProperties({"id", "dateNaissance", "competences", "souhaits", "joursIndisponibles", "ninja"})
    private abstract static class AnimateurMixin {}

    /** The resolved opening hours the rules reason on, which the referential API recomputes instead of serving. */
    private abstract static class StandMixin {
        @JsonProperty
        List<IndisponibiliteStand> indisponibilitesEffectives;

        @JsonProperty
        List<OuvertureStand> ouverturesEffectives;

        @JsonIgnore(false)
        @JsonProperty
        abstract List<IndisponibiliteStand> getIndisponibilitesEffectives();

        @JsonIgnore(false)
        @JsonProperty
        abstract List<OuvertureStand> getOuverturesEffectives();
    }

    /** What somebody typed, and who typed it, stays home. */
    @JsonIgnoreProperties({"raison", "motif", "creeParUtilisateurId", "creeLe", "modifieLe"})
    private abstract static class FreeTextMixin {}
}
