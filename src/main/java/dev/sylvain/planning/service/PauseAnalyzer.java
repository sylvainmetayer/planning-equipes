package dev.sylvain.planning.service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PlafondsLegauxMajeurs;
import dev.sylvain.planning.domain.PlafondsLegauxMineurs;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;

/**
 * Where the legal breaks of a plan fall, animateur by animateur and day by
 * day — the read-out behind {@code GET /api/pauses} and the « pause à prendre
 * avant 19:00 » line of an animateur's own planning.
 *
 * <p>The solver decides who holds which seat; it never schedules the break
 * art. L3121-16 (art. L3162-3 for a minor) owes once a working stretch reaches
 * six hours (4 h 30). When the organiser declares the break taken <b>on the
 * post</b> ({@link ParametresLegaux#isPauseSurPoste()}), a stretch may run
 * beyond that mark, and somebody has to organise the relay: this analysis says
 * for whom, at the latest when, on which stand, and who is there to relieve
 * them. Without the declaration a stretch beyond the mark is a violation the
 * constraints refuse; a persisted plan can still carry one, and it is then
 * reported the same way, flagged as not covered by any declaration.</p>
 *
 * <p>The stretches are the ones {@code LegalConstraints} reads: seats of one
 * animateur on one day, on their <em>effective</em> windows, merged when the
 * gap between two is shorter than the legal break — a fifteen-minute hole is
 * not a break. Gaps at least that long are breaks already scheduled by the
 * grid (the midday relay, typically) and are listed as such: the person
 * reading the day sees both what the plan gives them and what remains to
 * organise.</p>
 *
 * <p>Deadlines follow the arithmetic of {@code PlafondsLegauxMajeurs.onPostBreakMinutes}:
 * the first break falls at the latest {@code MAX} after the stretch starts,
 * the next one {@code MAX + PAUSE} later, and so on — {@code p} breaks let a
 * stretch run for {@code MAX × (p + 1) + PAUSE × p}. An animateur whose birth
 * date is unknown is read with the adult figures: the constraints skip them on
 * both sides, and the adult break is the one that is always owed.</p>
 */
@ApplicationScoped
public class PauseAnalyzer {

    /** A colleague on the same stand at the deadline, who can take the relay. */
    public record RelaisView(String animateurId, String nomComplet) {
    }

    /**
     * One break a stretch owes.
     *
     * @param heureLimite     latest start of the break — the stretch reaches
     *                        the legal mark then
     * @param dureeMinutes    20 for an adult, 30 for a minor
     * @param standId         stand the animateur holds at that moment
     * @param standNom        its name
     * @param relais          colleagues holding a seat on that stand at that
     *                        moment, in name order
     * @param relaisDisponible false when nobody else is on the stand: the
     *                        relay must come from elsewhere, or the stand
     *                        closes for the break
     */
    public record PauseDueView(LocalTime heureLimite, int dureeMinutes, String standId, String standNom,
            List<RelaisView> relais, boolean relaisDisponible) {
    }

    /** An uninterrupted working stretch of the day, with the breaks it owes inside. */
    public record SequenceView(LocalTime debut, LocalTime fin, int minutes, List<PauseDueView> pausesDues) {
    }

    /** A break the grid already schedules: the gap between two stretches. */
    public record PausePlanifieeView(LocalTime debut, LocalTime fin, int minutes) {
    }

    /** One animateur on one day: their stretches, and the breaks between them. */
    public record JourneeAnimateurView(String animateurId, String nomComplet, boolean mineur, LocalDate date,
            int jour, List<SequenceView> sequences, List<PausePlanifieeView> pausesPlanifiees) {
    }

    /**
     * The whole read-out.
     *
     * @param pauseSurPoste     the organiser's declaration, as it stands
     * @param journeesAnalysees animateur-days holding at least one seat
     * @param pausesDues        breaks owed inside a stretch, over the plan
     * @param relaisManquants   those with nobody else on the stand
     * @param journees          only the days that owe at least one break, or
     *                          list a scheduled one; a day of short stretches
     *                          with no gap has nothing to show
     */
    public record RapportPauses(boolean pauseSurPoste, int journeesAnalysees, int pausesDues,
            int relaisManquants, List<JourneeAnimateurView> journees, String message) {
    }

    /** Same read-out, under the legal parameters the plan itself carries (the protective default when it carries none). */
    public RapportPauses analyze(PlanningEvenement planning) {
        ParametresLegaux parametres = planning == null || planning.getParametresLegaux() == null
                || planning.getParametresLegaux().isEmpty() ? null : planning.getParametresLegaux().get(0);
        return analyze(planning, parametres);
    }

    public RapportPauses analyze(PlanningEvenement planning, ParametresLegaux parametres) {
        boolean pauseSurPoste = parametres != null && parametres.isPauseSurPoste();
        List<PosteAffectation> postes = planning == null || planning.getPostes() == null
                ? List.of() : planning.getPostes();
        List<PosteAffectation> tenus = postes.stream()
                .filter(poste -> poste.getAnimateur() != null && poste.getStand() != null
                        && poste.getCreneau() != null && poste.getCreneau().getDate() != null
                        && poste.heureDebutEffectif() != null)
                .toList();

        Map<String, List<PosteAffectation>> parJournee = new LinkedHashMap<>();
        for (PosteAffectation poste : tenus) {
            String cle = poste.getAnimateur().getId() + "|" + poste.getCreneau().getDate();
            parJournee.computeIfAbsent(cle, ignored -> new ArrayList<>()).add(poste);
        }

        List<JourneeAnimateurView> journees = new ArrayList<>();
        int pausesDues = 0;
        int relaisManquants = 0;
        for (List<PosteAffectation> journee : parJournee.values()) {
            JourneeAnimateurView vue = journee(journee, tenus);
            if (vue.sequences().stream().allMatch(sequence -> sequence.pausesDues().isEmpty())
                    && vue.pausesPlanifiees().isEmpty()) {
                continue;
            }
            journees.add(vue);
            for (SequenceView sequence : vue.sequences()) {
                pausesDues += sequence.pausesDues().size();
                relaisManquants += (int) sequence.pausesDues().stream()
                        .filter(pause -> !pause.relaisDisponible()).count();
            }
        }
        journees.sort(Comparator.comparing(JourneeAnimateurView::date)
                .thenComparing(JourneeAnimateurView::nomComplet, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(JourneeAnimateurView::animateurId));
        return new RapportPauses(pauseSurPoste, parJournee.size(), pausesDues, relaisManquants,
                List.copyOf(journees), buildMessage(pauseSurPoste, pausesDues, relaisManquants));
    }

    /** The days of one animateur only — what their own planning shows. */
    public List<JourneeAnimateurView> journeesAnimateur(PlanningEvenement planning, ParametresLegaux parametres,
            String animateurId) {
        return analyze(planning, parametres).journees().stream()
                .filter(journee -> journee.animateurId().equals(animateurId))
                .toList();
    }

    /**
     * The breaks of every animateur of the plan, by id — one analysis for the
     * whole plan. Exports that walk the roster (the ZIP bundles, a publication
     * mailing) build this once instead of re-analysing the plan per person.
     */
    public Map<String, List<PauseAnimateurView>> pausesByAnimateur(PlanningEvenement planning) {
        Map<String, List<PauseAnimateurView>> parAnimateur = new LinkedHashMap<>();
        for (JourneeAnimateurView journee : analyze(planning).journees()) {
            List<PauseAnimateurView> pauses = parAnimateur.computeIfAbsent(journee.animateurId(),
                    ignored -> new ArrayList<>());
            for (SequenceView sequence : journee.sequences()) {
                for (PauseDueView pause : sequence.pausesDues()) {
                    pauses.add(new PauseAnimateurView(journee.date(), pause.heureLimite(), pause.dureeMinutes(),
                            pause.standId(), pause.standNom(), pause.relaisDisponible()));
                }
            }
        }
        return parAnimateur;
    }

    /** The breaks one animateur owes, day by day, under the plan's own parameters — one line per break. */
    public List<PauseAnimateurView> pausesAnimateur(PlanningEvenement planning, String animateurId) {
        List<PauseAnimateurView> pauses = new ArrayList<>();
        for (JourneeAnimateurView journee : analyze(planning).journees()) {
            if (!journee.animateurId().equals(animateurId)) {
                continue;
            }
            for (SequenceView sequence : journee.sequences()) {
                for (PauseDueView pause : sequence.pausesDues()) {
                    pauses.add(new PauseAnimateurView(journee.date(), pause.heureLimite(), pause.dureeMinutes(),
                            pause.standId(), pause.standNom(), pause.relaisDisponible()));
                }
            }
        }
        return List.copyOf(pauses);
    }

    /** One break of one animateur, as their own planning prints it. */
    public record PauseAnimateurView(LocalDate date, LocalTime heureLimite, int dureeMinutes, String standId,
            String standNom, boolean relaisDisponible) {
    }

    private static JourneeAnimateurView journee(List<PosteAffectation> postesDuJour,
            List<PosteAffectation> tousLesPostes) {
        Animateur animateur = postesDuJour.get(0).getAnimateur();
        LocalDate date = postesDuJour.get(0).getCreneau().getDate();
        boolean mineur = animateur.isMineurOn(date);
        int travailContinuMax = mineur
                ? PlafondsLegauxMineurs.TRAVAIL_CONTINU_MAX_MINUTES : PlafondsLegauxMajeurs.TRAVAIL_CONTINU_MAX_MINUTES;
        int pauseMinimale = mineur
                ? PlafondsLegauxMineurs.PAUSE_MINIMALE_MINUTES : PlafondsLegauxMajeurs.PAUSE_MINIMALE_MINUTES;

        List<Sequence> sequences = sequences(postesDuJour, pauseMinimale);
        List<SequenceView> vues = new ArrayList<>();
        for (Sequence sequence : sequences) {
            List<PauseDueView> dues = new ArrayList<>();
            long minutes = Duration.between(sequence.debut, sequence.fin).toMinutes();
            int pauses = minutes <= travailContinuMax ? 0
                    : Math.ceilDiv((int) minutes - travailContinuMax, travailContinuMax + pauseMinimale);
            for (int k = 1; k <= pauses; k++) {
                LocalDateTime limite = sequence.debut.plusMinutes((long) k * travailContinuMax
                        + (long) (k - 1) * pauseMinimale);
                PosteAffectation tenu = sequence.posteA(limite);
                List<RelaisView> relais = relais(tousLesPostes, tenu, limite);
                dues.add(new PauseDueView(limite.toLocalTime(), pauseMinimale, tenu.getStand().getId(),
                        tenu.getStand().getNom(), relais, !relais.isEmpty()));
            }
            vues.add(new SequenceView(sequence.debut.toLocalTime(), sequence.fin.toLocalTime(), (int) minutes,
                    List.copyOf(dues)));
        }
        List<PausePlanifieeView> planifiees = new ArrayList<>();
        for (int i = 1; i < sequences.size(); i++) {
            LocalDateTime debut = sequences.get(i - 1).fin;
            LocalDateTime fin = sequences.get(i).debut;
            planifiees.add(new PausePlanifieeView(debut.toLocalTime(), fin.toLocalTime(),
                    (int) Duration.between(debut, fin).toMinutes()));
        }
        int jour = postesDuJour.stream().mapToInt(poste -> poste.getCreneau().getJour()).min().orElse(0);
        return new JourneeAnimateurView(animateur.getId(), animateur.nomAffiche(), mineur, date, jour,
                List.copyOf(vues), List.copyOf(planifiees));
    }

    /** The stretches of one day: seats closer than the legal break form one. */
    private static List<Sequence> sequences(List<PosteAffectation> postes, int pauseMinimale) {
        List<PosteAffectation> tries = postes.stream()
                .sorted(Comparator.comparing(PauseAnalyzer::debut))
                .toList();
        List<Sequence> sequences = new ArrayList<>();
        Sequence courante = null;
        for (PosteAffectation poste : tries) {
            LocalDateTime debut = debut(poste);
            LocalDateTime fin = fin(poste);
            if (courante == null || Duration.between(courante.fin, debut).toMinutes() >= pauseMinimale) {
                courante = new Sequence(debut, fin);
                sequences.add(courante);
            } else if (fin.isAfter(courante.fin)) {
                courante.fin = fin;
            }
            courante.postes.add(poste);
        }
        return sequences;
    }

    /** Colleagues holding a seat on the same stand at that instant, the animateur excluded. */
    private static List<RelaisView> relais(List<PosteAffectation> postes, PosteAffectation tenu,
            LocalDateTime instant) {
        String animateurId = tenu.getAnimateur().getId();
        Map<String, RelaisView> parId = new LinkedHashMap<>();
        for (PosteAffectation autre : postes) {
            if (autre.getStand().getId().equals(tenu.getStand().getId())
                    && !autre.getAnimateur().getId().equals(animateurId)
                    && !debut(autre).isAfter(instant) && fin(autre).isAfter(instant)) {
                parId.putIfAbsent(autre.getAnimateur().getId(),
                        new RelaisView(autre.getAnimateur().getId(), autre.getAnimateur().nomAffiche()));
            }
        }
        return parId.values().stream()
                .sorted(Comparator.comparing(RelaisView::nomComplet, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static String buildMessage(boolean pauseSurPoste, int pausesDues, int relaisManquants) {
        if (pausesDues == 0) {
            return "Aucune séquence ne dépasse la durée légale de travail continu : rien à organiser.";
        }
        String base = pausesDues + (pausesDues > 1 ? " pauses" : " pause")
                + (pauseSurPoste ? " à prendre sur le poste" : " due sans être déclarée sur le poste")
                + (relaisManquants > 0
                        ? ", dont " + relaisManquants + " sans relais possible sur le stand"
                        : ", chacune avec un relais possible sur le stand")
                + ".";
        return pauseSurPoste ? base
                : base + " Déclarez la pause prise sur le poste dans les paramètres légaux, ou planifiez un trou.";
    }

    private static LocalDateTime debut(PosteAffectation poste) {
        return LocalDateTime.of(poste.getCreneau().getDate(), poste.heureDebutEffectif());
    }

    private static LocalDateTime fin(PosteAffectation poste) {
        return debut(poste).plusMinutes(poste.getDureeEffectiveMinutes());
    }

    /** A stretch under construction: mutable end, and the seats it is made of. */
    private static final class Sequence {
        private final LocalDateTime debut;
        private LocalDateTime fin;
        private final List<PosteAffectation> postes = new ArrayList<>();

        private Sequence(LocalDateTime debut, LocalDateTime fin) {
            this.debut = debut;
            this.fin = fin;
        }

        /**
         * The seat held at that instant: the one covering it. A stretch merges
         * seats separated by less than the legal break, so the instant can fall
         * in such a sub-legal gap — the next seat to start is then the one the
         * person is about to hold, and the one the break belongs to. Falls back
         * on the last seat before the instant, then on the first of the stretch.
         */
        private PosteAffectation posteA(LocalDateTime instant) {
            PosteAffectation avant = null;
            PosteAffectation apres = null;
            for (PosteAffectation poste : postes) {
                LocalDateTime debut = PauseAnalyzer.debut(poste);
                LocalDateTime fin = PauseAnalyzer.fin(poste);
                if (!debut.isAfter(instant) && fin.isAfter(instant)) {
                    return poste;
                }
                if (!debut.isAfter(instant)) {
                    avant = poste;
                } else if (apres == null || debut.isBefore(PauseAnalyzer.debut(apres))) {
                    apres = poste;
                }
            }
            return apres != null ? apres : avant != null ? avant : postes.get(0);
        }
    }
}
