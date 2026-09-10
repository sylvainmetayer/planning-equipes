package dev.sylvain.planning.service.analyse;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

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
     * One break a stretch owes, placed in the stand's rotation.
     *
     * @param debut           when the break starts, as the rotation places it:
     *                        as late as its window allows, and after the
     *                        colleague's break on the same stand
     * @param fin             {@code debut} plus the duration
     * @param heureLimite     latest possible start — the stretch reaches the
     *                        legal mark then; {@code debut} never exceeds it
     * @param dureeMinutes    20 for an adult, 30 for a minor
     * @param standId         stand the animateur holds during the break
     * @param standNom        its name
     * @param relais          colleagues holding a seat on that stand for the
     *                        whole break, in name order
     * @param relaisDisponible false when nobody else is on the stand: the
     *                        relay must come from elsewhere, or the stand
     *                        closes for the break
     * @param simultanee      true when the rotation could not keep this break
     *                        apart from another one on the stand — the
     *                        windows left no room — so two people are out at
     *                        once; the break is then placed as early as its
     *                        window allows
     */
    public record PauseDueView(LocalTime debut, LocalTime fin, LocalTime heureLimite, int dureeMinutes,
            String standId, String standNom, List<RelaisView> relais, boolean relaisDisponible,
            boolean simultanee) {
    }

    /** An uninterrupted working stretch of the day, with the breaks it owes inside. */
    @Schema(requiredProperties = {"minutes"})
    public record SequenceView(LocalTime debut, LocalTime fin, int minutes, List<PauseDueView> pausesDues) {
    }

    /** A break the grid already schedules: the gap between two stretches. */
    @Schema(requiredProperties = {"minutes"})
    public record PausePlanifieeView(LocalTime debut, LocalTime fin, int minutes) {
    }

    /** One animateur on one day: their stretches, and the breaks between them. */
    @Schema(requiredProperties = {"jour", "mineur"})
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
    @Schema(requiredProperties = {"journeesAnalysees", "pauseSurPoste", "pausesDues", "relaisManquants"})
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

        // 1. Each animateur-day: its stretches, and the breaks they owe, with
        //    the window each break may fall in.
        List<Journee> journees = new ArrayList<>();
        for (List<PosteAffectation> postesDuJour : parJournee.values()) {
            journees.add(journee(postesDuJour));
        }
        // 2. Each stand-day: the rotation, one break after the other.
        Map<String, List<Demande>> parStandJour = new LinkedHashMap<>();
        for (Journee journee : journees) {
            for (Sequence sequence : journee.sequences) {
                for (Demande demande : sequence.demandes) {
                    String cle = demande.tenu.getStand().getId() + "|" + journee.date;
                    parStandJour.computeIfAbsent(cle, ignored -> new ArrayList<>()).add(demande);
                }
            }
        }
        parStandJour.values().forEach(PauseAnalyzer::rotation);
        // 3. The views, relays read on the placed breaks.
        List<JourneeAnimateurView> vues = new ArrayList<>();
        int pausesDues = 0;
        int relaisManquants = 0;
        for (Journee journee : journees) {
            JourneeAnimateurView vue = toView(journee, tenus);
            if (vue.sequences().stream().allMatch(sequence -> sequence.pausesDues().isEmpty())
                    && vue.pausesPlanifiees().isEmpty()) {
                continue;
            }
            vues.add(vue);
            for (SequenceView sequence : vue.sequences()) {
                pausesDues += sequence.pausesDues().size();
                relaisManquants += (int) sequence.pausesDues().stream()
                        .filter(pause -> !pause.relaisDisponible()).count();
            }
        }
        vues.sort(Comparator.comparing(JourneeAnimateurView::date)
                .thenComparing(JourneeAnimateurView::nomComplet, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(JourneeAnimateurView::animateurId));
        return new RapportPauses(pauseSurPoste, parJournee.size(), pausesDues, relaisManquants,
                List.copyOf(vues), buildMessage(pauseSurPoste, pausesDues, relaisManquants));
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
                    pauses.add(new PauseAnimateurView(journee.date(), pause.debut(), pause.fin(), pause.heureLimite(),
                            pause.dureeMinutes(), pause.standId(), pause.standNom(), pause.relaisDisponible()));
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
                    pauses.add(new PauseAnimateurView(journee.date(), pause.debut(), pause.fin(), pause.heureLimite(),
                            pause.dureeMinutes(), pause.standId(), pause.standNom(), pause.relaisDisponible()));
                }
            }
        }
        return List.copyOf(pauses);
    }

    /**
     * One break of one animateur, as their own planning prints it: « pause de
     * 18:20 à 18:40 ». {@code date} is the day of the seat it falls in, which a
     * break past midnight shares with the evening it belongs to.
     */
    @Schema(requiredProperties = {"dureeMinutes", "relaisDisponible"})
    public record PauseAnimateurView(LocalDate date, LocalTime debut, LocalTime fin, LocalTime heureLimite,
            int dureeMinutes, String standId, String standNom, boolean relaisDisponible) {

        /**
         * Whether this break falls inside that seat — what the PDF and the
         * calendar feed both need, written once so they can never attach the
         * same break to two different shifts. Compared on instants: a break past
         * midnight belongs to the evening seat, where bare clock times would
         * read it as earlier than the seat's own start and drop it.
         */
        public boolean fallsInside(PosteAffectation poste) {
            if (poste == null || poste.getCreneau() == null || poste.getStand() == null
                    || poste.getCreneau().getDate() == null || poste.heureDebutEffectif() == null
                    || !poste.getStand().getId().equals(standId)) {
                return false;
            }
            LocalDateTime debutPoste = LocalDateTime.of(poste.getCreneau().getDate(), poste.heureDebutEffectif());
            LocalDateTime finPoste = debutPoste.plusMinutes(poste.getDureeEffectiveMinutes());
            LocalDateTime debutPause = LocalDateTime.of(date, debut);
            if (debutPause.isBefore(debutPoste) && finPoste.toLocalDate().isAfter(date)) {
                debutPause = debutPause.plusDays(1);
            }
            return !debutPause.isBefore(debutPoste) && debutPause.isBefore(finPoste);
        }
    }

    /** The stretches of one animateur's day, with the breaks each owes and the window of each. */
    private static Journee journee(List<PosteAffectation> postesDuJour) {
        Animateur animateur = postesDuJour.get(0).getAnimateur();
        LocalDate date = postesDuJour.get(0).getCreneau().getDate();
        boolean mineur = animateur.isMineurOn(date);
        int travailContinuMax = mineur
                ? PlafondsLegauxMineurs.TRAVAIL_CONTINU_MAX_MINUTES : PlafondsLegauxMajeurs.TRAVAIL_CONTINU_MAX_MINUTES;
        int pauseMinimale = mineur
                ? PlafondsLegauxMineurs.PAUSE_MINIMALE_MINUTES : PlafondsLegauxMajeurs.PAUSE_MINIMALE_MINUTES;

        List<Sequence> sequences = sequences(postesDuJour, pauseMinimale);
        for (Sequence sequence : sequences) {
            long minutes = Duration.between(sequence.debut, sequence.fin).toMinutes();
            int pauses = minutes <= travailContinuMax ? 0
                    : Math.ceilDiv((int) minutes - travailContinuMax, travailContinuMax + pauseMinimale);
            LocalDateTime finPrecedente = sequence.debut;
            for (int k = 1; k <= pauses; k++) {
                // Latest start: the stretch reaches the legal mark then. Earliest
                // start: what remains after this break — and the breaks still
                // to come — must itself stay within the mark.
                LocalDateTime limite = sequence.debut.plusMinutes((long) k * travailContinuMax
                        + (long) (k - 1) * pauseMinimale);
                int restantes = pauses - k;
                LocalDateTime auPlusTot = sequence.fin.minusMinutes((long) (restantes + 1) * travailContinuMax
                        + (long) (restantes + 1) * pauseMinimale);
                PosteAffectation tenu = sequence.posteA(limite);
                LocalDateTime plancher = maxOf(maxOf(auPlusTot, debut(tenu)), finPrecedente);
                Demande demande = new Demande(animateur, tenu, plancher.isAfter(limite) ? limite : plancher,
                        limite, pauseMinimale);
                sequence.demandes.add(demande);
                finPrecedente = limite.plusMinutes(pauseMinimale);
            }
        }
        int jour = postesDuJour.stream().mapToInt(poste -> poste.getCreneau().getJour()).min().orElse(0);
        return new Journee(animateur, date, jour, mineur, sequences);
    }

    /**
     * Places the breaks of one stand-day one after the other, as late as their
     * windows allow: the latest deadline first, each following break ending
     * where the previous one starts. When the windows leave no room, the break
     * is placed at the start of its window and flagged as simultaneous —
     * the plan is not changed, the organiser is told.
     */
    private static void rotation(List<Demande> demandes) {
        demandes.sort(Comparator.comparing((Demande demande) -> demande.auPlusTard).reversed()
                .thenComparing(demande -> demande.animateur.getId()));
        LocalDateTime curseur = null;
        for (Demande demande : demandes) {
            LocalDateTime fin = demande.auPlusTard.plusMinutes(demande.dureeMinutes);
            if (curseur != null && curseur.isBefore(fin)) {
                fin = curseur;
            }
            LocalDateTime debut = fin.minusMinutes(demande.dureeMinutes);
            if (debut.isBefore(demande.auPlusTot)) {
                debut = demande.auPlusTot;
                demande.simultanee = true;
            }
            demande.debut = debut;
            // The cursor never moves forward: a break clamped up to its floor
            // would otherwise leave room it does not have, and the next one —
            // whose deadline is earlier — would be placed over it without the
            // rotation saying two people are out at once.
            curseur = curseur == null || debut.isBefore(curseur) ? debut : curseur;
        }
    }

    private static JourneeAnimateurView toView(Journee journee, List<PosteAffectation> tousLesPostes) {
        List<SequenceView> vues = new ArrayList<>();
        for (Sequence sequence : journee.sequences) {
            List<PauseDueView> dues = new ArrayList<>();
            for (Demande demande : sequence.demandes) {
                LocalDateTime fin = demande.debut.plusMinutes(demande.dureeMinutes);
                List<RelaisView> relais = relais(tousLesPostes, demande.tenu, demande.debut, fin);
                dues.add(new PauseDueView(demande.debut.toLocalTime(), fin.toLocalTime(),
                        demande.auPlusTard.toLocalTime(), demande.dureeMinutes, demande.tenu.getStand().getId(),
                        demande.tenu.getStand().getNom(), relais, !relais.isEmpty(), demande.simultanee));
            }
            dues.sort(Comparator.comparing(PauseDueView::heureLimite));
            vues.add(new SequenceView(sequence.debut.toLocalTime(), sequence.fin.toLocalTime(),
                    (int) Duration.between(sequence.debut, sequence.fin).toMinutes(), List.copyOf(dues)));
        }
        List<PausePlanifieeView> planifiees = new ArrayList<>();
        for (int i = 1; i < journee.sequences.size(); i++) {
            LocalDateTime debut = journee.sequences.get(i - 1).fin;
            LocalDateTime fin = journee.sequences.get(i).debut;
            planifiees.add(new PausePlanifieeView(debut.toLocalTime(), fin.toLocalTime(),
                    (int) Duration.between(debut, fin).toMinutes()));
        }
        return new JourneeAnimateurView(journee.animateur.getId(), journee.animateur.nomAffiche(), journee.mineur,
                journee.date, journee.jour, List.copyOf(vues), List.copyOf(planifiees));
    }

    private static LocalDateTime maxOf(LocalDateTime a, LocalDateTime b) {
        return a.isAfter(b) ? a : b;
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

    /** Colleagues holding a seat on the same stand for the whole break, the animateur excluded. */
    private static List<RelaisView> relais(List<PosteAffectation> postes, PosteAffectation tenu,
            LocalDateTime debutPause, LocalDateTime finPause) {
        String animateurId = tenu.getAnimateur().getId();
        Map<String, RelaisView> parId = new LinkedHashMap<>();
        for (PosteAffectation autre : postes) {
            if (autre.getStand().getId().equals(tenu.getStand().getId())
                    && !autre.getAnimateur().getId().equals(animateurId)
                    && !debut(autre).isAfter(debutPause) && !fin(autre).isBefore(finPause)) {
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

    /** One animateur's day, before the rotation places its breaks. */
    private record Journee(Animateur animateur, LocalDate date, int jour, boolean mineur, List<Sequence> sequences) {
    }

    /**
     * One break to place: its window, the seat it falls in, and — once the
     * rotation has run — where it starts.
     */
    private static final class Demande {
        private final Animateur animateur;
        private final PosteAffectation tenu;
        private final LocalDateTime auPlusTot;
        private final LocalDateTime auPlusTard;
        private final int dureeMinutes;
        private LocalDateTime debut;
        private boolean simultanee;

        private Demande(Animateur animateur, PosteAffectation tenu, LocalDateTime auPlusTot,
                LocalDateTime auPlusTard, int dureeMinutes) {
            this.animateur = animateur;
            this.tenu = tenu;
            this.auPlusTot = auPlusTot;
            this.auPlusTard = auPlusTard;
            this.dureeMinutes = dureeMinutes;
            this.debut = auPlusTard;
        }
    }

    /** A stretch under construction: mutable end, the seats it is made of, and the breaks it owes. */
    private static final class Sequence {
        private final LocalDateTime debut;
        private LocalDateTime fin;
        private final List<PosteAffectation> postes = new ArrayList<>();
        private final List<Demande> demandes = new ArrayList<>();

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
