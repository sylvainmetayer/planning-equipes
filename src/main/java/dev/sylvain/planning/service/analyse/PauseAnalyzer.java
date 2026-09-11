package dev.sylvain.planning.service.analyse;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.CoupureRepas;
import dev.sylvain.planning.domain.FenetreRepas;
import dev.sylvain.planning.domain.ParametresLegaux;
import dev.sylvain.planning.domain.PauseSurPoste;
import dev.sylvain.planning.domain.PlafondsLegauxMajeurs;
import dev.sylvain.planning.domain.PlafondsLegauxMineurs;
import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

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
    public record RelaisView(String animateurId, String nomComplet) {}

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
     * @param creneauId       the timeslot of the seat held during the break —
     *                        what a screen needs to open the bench on it
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
    public record PauseDueView(
            LocalTime debut,
            LocalTime fin,
            LocalTime heureLimite,
            int dureeMinutes,
            String standId,
            String standNom,
            Long creneauId,
            List<RelaisView> relais,
            boolean relaisDisponible,
            boolean simultanee) {}

    /**
     * The meal break a day owes on one window, and what the plan leaves for it
     * — the read-out of {@code coupureRepasObligatoire}, computed by the very
     * same {@link CoupureRepas}, so this screen and the score can never tell
     * two stories about the same day.
     *
     * @param libelle             « midi » / « soir »
     * @param dureeRequiseMinutes the break the window owes
     * @param debut               when it can be taken, at the earliest;
     *                            {@code null} when the day leaves no room
     * @param fin                 {@code debut} plus the duration
     * @param plusGrandTrouMinutes longest free stretch inside the window
     * @param satisfaite          false when that stretch is too short: the
     *                            missing minutes are what the solver is
     *                            penalising
     */
    @Schema(requiredProperties = {"dureeRequiseMinutes", "plusGrandTrouMinutes", "satisfaite"})
    public record CoupureRepasView(
            String libelle,
            LocalTime fenetreDebut,
            LocalTime fenetreFin,
            int dureeRequiseMinutes,
            LocalTime debut,
            LocalTime fin,
            int plusGrandTrouMinutes,
            boolean satisfaite) {}

    /** An uninterrupted working stretch of the day, with the breaks it owes inside. */
    @Schema(requiredProperties = {"minutes"})
    public record SequenceView(LocalTime debut, LocalTime fin, int minutes, List<PauseDueView> pausesDues) {}

    /** A break the grid already schedules: the gap between two stretches. */
    @Schema(requiredProperties = {"minutes"})
    public record PausePlanifieeView(LocalTime debut, LocalTime fin, int minutes) {}

    /**
     * One animateur on one day: their stretches, the breaks between them, and
     * the meal breaks the day owes.
     */
    @Schema(requiredProperties = {"jour", "mineur"})
    public record JourneeAnimateurView(
            String animateurId,
            String nomComplet,
            boolean mineur,
            LocalDate date,
            int jour,
            List<SequenceView> sequences,
            List<PausePlanifieeView> pausesPlanifiees,
            List<CoupureRepasView> coupuresRepas) {}

    /**
     * The whole read-out.
     *
     * @param pauseSurPoste     the organiser's declaration, as it stands
     * @param journeesAnalysees animateur-days holding at least one seat
     * @param pausesDues        breaks owed inside a stretch, over the plan
     * @param relaisManquants   those with nobody else on the stand
     * @param coupuresRepasDues     meal breaks owed over the plan — one per
     *                          animateur-day straddling a declared window
     * @param coupuresRepasManquantes those the plan leaves no room for: the
     *                          days {@code coupureRepasObligatoire} is
     *                          penalising, listed here so the two screens
     *                          agree
     * @param journees          only the days that owe at least one break, list
     *                          a scheduled one, or owe a meal break; a day of
     *                          short stretches with no gap has nothing to show
     */
    @Schema(
            requiredProperties = {
                "coupuresRepasDues",
                "coupuresRepasManquantes",
                "journeesAnalysees",
                "pauseSurPoste",
                "pausesDues",
                "relaisManquants"
            })
    public record RapportPauses(
            boolean pauseSurPoste,
            int journeesAnalysees,
            int pausesDues,
            int relaisManquants,
            int coupuresRepasDues,
            int coupuresRepasManquantes,
            List<JourneeAnimateurView> journees,
            String message) {}

    /** Same read-out, under the parameters and meal windows the plan itself carries (the protective default when it carries none). */
    public RapportPauses analyze(PlanningEvenement planning) {
        ParametresLegaux parametres = planning == null
                        || planning.getParametresLegaux() == null
                        || planning.getParametresLegaux().isEmpty()
                ? null
                : planning.getParametresLegaux().get(0);
        return analyze(
                planning,
                parametres,
                planning == null || planning.getFenetresRepas() == null ? List.of() : planning.getFenetresRepas());
    }

    public RapportPauses analyze(PlanningEvenement planning, ParametresLegaux parametres) {
        return analyze(
                planning,
                parametres,
                planning == null || planning.getFenetresRepas() == null ? List.of() : planning.getFenetresRepas());
    }

    /**
     * @param fenetres the meal windows to read the days against — the
     *                 organiser's <em>current</em> ones when the caller is the
     *                 Pauses screen, exactly as it already does for the legal
     *                 parameters: the question is « with what I declare today,
     *                 what is there to organise ».
     */
    public RapportPauses analyze(PlanningEvenement planning, ParametresLegaux parametres, List<FenetreRepas> fenetres) {
        boolean pauseSurPoste = parametres != null && parametres.isPauseSurPoste();
        List<FenetreRepas> fenetresRepas = fenetres == null ? List.of() : fenetres;
        List<PosteAffectation> postes =
                planning == null || planning.getPostes() == null ? List.of() : planning.getPostes();
        List<PosteAffectation> tenus = postes.stream()
                .filter(poste -> poste.getAnimateur() != null
                        && poste.getStand() != null
                        && poste.getCreneau() != null
                        && poste.getCreneau().getDate() != null
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
            journees.add(journee(postesDuJour, fenetresRepas));
        }
        // 2. Each stand-day: the rotation, one break after the other.
        Map<String, List<Demande>> parStandJour = new LinkedHashMap<>();
        for (Journee journee : journees) {
            for (SequenceDemandes sequence : journee.sequences) {
                for (Demande demande : sequence.demandes) {
                    String cle = demande.tenu.getStand().getId() + "|" + journee.date;
                    parStandJour
                            .computeIfAbsent(cle, ignored -> new ArrayList<>())
                            .add(demande);
                }
            }
        }
        parStandJour.values().forEach(PauseAnalyzer::rotation);
        // 3. The views, relays read on the placed breaks.
        List<JourneeAnimateurView> vues = new ArrayList<>();
        int pausesDues = 0;
        int relaisManquants = 0;
        int coupuresRepasDues = 0;
        int coupuresRepasManquantes = 0;
        for (Journee journee : journees) {
            JourneeAnimateurView vue = toView(journee, tenus);
            if (vue.sequences().stream()
                            .allMatch(sequence -> sequence.pausesDues().isEmpty())
                    && vue.pausesPlanifiees().isEmpty()
                    && vue.coupuresRepas().isEmpty()) {
                continue;
            }
            vues.add(vue);
            for (SequenceView sequence : vue.sequences()) {
                pausesDues += sequence.pausesDues().size();
                relaisManquants += (int) sequence.pausesDues().stream()
                        .filter(pause -> !pause.relaisDisponible())
                        .count();
            }
            coupuresRepasDues += vue.coupuresRepas().size();
            coupuresRepasManquantes += (int) vue.coupuresRepas().stream()
                    .filter(coupure -> !coupure.satisfaite())
                    .count();
        }
        vues.sort(Comparator.comparing(JourneeAnimateurView::date)
                .thenComparing(JourneeAnimateurView::nomComplet, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(JourneeAnimateurView::animateurId));
        return new RapportPauses(
                pauseSurPoste,
                parJournee.size(),
                pausesDues,
                relaisManquants,
                coupuresRepasDues,
                coupuresRepasManquantes,
                List.copyOf(vues),
                buildMessage(pauseSurPoste, pausesDues, relaisManquants, coupuresRepasManquantes));
    }

    /** The days of one animateur only — what their own planning shows. */
    public List<JourneeAnimateurView> journeesAnimateur(
            PlanningEvenement planning, ParametresLegaux parametres, String animateurId) {
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
            List<PauseAnimateurView> pauses =
                    parAnimateur.computeIfAbsent(journee.animateurId(), ignored -> new ArrayList<>());
            for (SequenceView sequence : journee.sequences()) {
                for (PauseDueView pause : sequence.pausesDues()) {
                    pauses.add(new PauseAnimateurView(
                            journee.date(),
                            pause.debut(),
                            pause.fin(),
                            pause.heureLimite(),
                            pause.dureeMinutes(),
                            pause.standId(),
                            pause.standNom(),
                            pause.relaisDisponible()));
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
                    pauses.add(new PauseAnimateurView(
                            journee.date(),
                            pause.debut(),
                            pause.fin(),
                            pause.heureLimite(),
                            pause.dureeMinutes(),
                            pause.standId(),
                            pause.standNom(),
                            pause.relaisDisponible()));
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
    public record PauseAnimateurView(
            LocalDate date,
            LocalTime debut,
            LocalTime fin,
            LocalTime heureLimite,
            int dureeMinutes,
            String standId,
            String standNom,
            boolean relaisDisponible) {

        /**
         * Whether this break falls inside that seat — what the PDF and the
         * calendar feed both need, written once so they can never attach the
         * same break to two different shifts. Compared on instants: a break past
         * midnight belongs to the evening seat, where bare clock times would
         * read it as earlier than the seat's own start and drop it.
         */
        public boolean fallsInside(PosteAffectation poste) {
            if (poste == null
                    || poste.getCreneau() == null
                    || poste.getStand() == null
                    || poste.getCreneau().getDate() == null
                    || poste.heureDebutEffectif() == null
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
    private static Journee journee(List<PosteAffectation> postesDuJour, List<FenetreRepas> fenetres) {
        Animateur animateur = postesDuJour.get(0).getAnimateur();
        LocalDate date = postesDuJour.get(0).getCreneau().getDate();
        boolean mineur = animateur.isMineurOn(date);
        int travailContinuMax = mineur
                ? PlafondsLegauxMineurs.TRAVAIL_CONTINU_MAX_MINUTES
                : PlafondsLegauxMajeurs.TRAVAIL_CONTINU_MAX_MINUTES;
        int pauseMinimale =
                mineur ? PlafondsLegauxMineurs.PAUSE_MINIMALE_MINUTES : PlafondsLegauxMajeurs.PAUSE_MINIMALE_MINUTES;

        // The stretches and the breaks they owe come from the domain: the
        // solver's pauseSurPosteSansRelais reads the very same ones, so the
        // screen and the score never disagree on what is due.
        List<SequenceDemandes> sequences = new ArrayList<>();
        for (PauseSurPoste.Sequence sequence : PauseSurPoste.sequences(postesDuJour, pauseMinimale)) {
            List<Demande> demandes = new ArrayList<>();
            for (PauseSurPoste due : PauseSurPoste.dues(sequence, travailContinuMax, pauseMinimale)) {
                demandes.add(new Demande(animateur, due.tenu(), due.auPlusTot(), due.auPlusTard(), due.dureeMinutes()));
            }
            sequences.add(new SequenceDemandes(sequence, demandes));
        }
        int jour = postesDuJour.stream()
                .mapToInt(poste -> poste.getCreneau().getJour())
                .min()
                .orElse(0);
        return new Journee(animateur, date, jour, mineur, sequences, coupuresRepas(postesDuJour, fenetres));
    }

    private static List<CoupureRepasView> coupuresRepas(
            List<PosteAffectation> postesDuJour, List<FenetreRepas> fenetres) {
        List<CoupureRepasView> vues = new ArrayList<>();
        for (FenetreRepas fenetre : fenetres) {
            CoupureRepas coupure = CoupureRepas.of(postesDuJour, fenetre);
            if (!coupure.due()) {
                continue;
            }
            vues.add(new CoupureRepasView(
                    fenetre.libelle(),
                    fenetre.debut(),
                    fenetre.fin(),
                    fenetre.dureeMinutes(),
                    coupure.debut(),
                    coupure.fin(),
                    coupure.plusGrandTrouMinutes(),
                    !coupure.manquante()));
        }
        return List.copyOf(vues);
    }

    /**
     * Places the breaks of one stand-day one after the other, as late as their
     * windows allow: the latest deadline first, each following break ending
     * where the previous one starts. When the windows leave no room, the break
     * is placed at the start of its window and flagged as simultaneous —
     * the plan is not changed, the organiser is told.
     */
    private static void rotation(List<Demande> demandes) {
        demandes.sort(Comparator.comparing((Demande demande) -> demande.auPlusTard)
                .reversed()
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
        for (SequenceDemandes sequence : journee.sequences) {
            List<PauseDueView> dues = new ArrayList<>();
            for (Demande demande : sequence.demandes) {
                LocalDateTime fin = demande.debut.plusMinutes(demande.dureeMinutes);
                List<RelaisView> relais = relais(tousLesPostes, demande.tenu, demande.debut, fin);
                dues.add(new PauseDueView(
                        demande.debut.toLocalTime(),
                        fin.toLocalTime(),
                        demande.auPlusTard.toLocalTime(),
                        demande.dureeMinutes,
                        demande.tenu.getStand().getId(),
                        demande.tenu.getStand().getNom(),
                        demande.tenu.getCreneau() == null
                                ? null
                                : demande.tenu.getCreneau().getId(),
                        relais,
                        !relais.isEmpty(),
                        demande.simultanee));
            }
            dues.sort(Comparator.comparing(PauseDueView::heureLimite));
            vues.add(new SequenceView(
                    sequence.sequence.debut().toLocalTime(),
                    sequence.sequence.fin().toLocalTime(),
                    sequence.sequence.minutes(),
                    List.copyOf(dues)));
        }
        List<PausePlanifieeView> planifiees = new ArrayList<>();
        for (int i = 1; i < journee.sequences.size(); i++) {
            LocalDateTime debut = journee.sequences.get(i - 1).sequence.fin();
            LocalDateTime fin = journee.sequences.get(i).sequence.debut();
            planifiees.add(new PausePlanifieeView(debut.toLocalTime(), fin.toLocalTime(), (int)
                    Duration.between(debut, fin).toMinutes()));
        }
        return new JourneeAnimateurView(
                journee.animateur.getId(),
                journee.animateur.nomAffiche(),
                journee.mineur,
                journee.date,
                journee.jour,
                List.copyOf(vues),
                List.copyOf(planifiees),
                journee.coupuresRepas);
    }

    private static List<RelaisView> relais(
            List<PosteAffectation> postes, PosteAffectation tenu, LocalDateTime debutPause, LocalDateTime finPause) {
        String animateurId = tenu.getAnimateur().getId();
        Map<String, RelaisView> parId = new LinkedHashMap<>();
        for (PosteAffectation autre : postes) {
            if (autre.getStand().getId().equals(tenu.getStand().getId())
                    && !autre.getAnimateur().getId().equals(animateurId)
                    && !PauseSurPoste.debut(autre).isAfter(debutPause)
                    && !PauseSurPoste.fin(autre).isBefore(finPause)) {
                parId.putIfAbsent(
                        autre.getAnimateur().getId(),
                        new RelaisView(
                                autre.getAnimateur().getId(),
                                autre.getAnimateur().nomAffiche()));
            }
        }
        return parId.values().stream()
                .sorted(Comparator.comparing(RelaisView::nomComplet, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    private static String buildMessage(
            boolean pauseSurPoste, int pausesDues, int relaisManquants, int coupuresRepasManquantes) {
        String repas = coupuresRepasManquantes == 0
                ? ""
                : " " + coupuresRepasManquantes
                        + (coupuresRepasManquantes > 1 ? " journées ne laissent" : " journée ne laisse")
                        + " aucune place à la coupure repas dans sa fenêtre.";
        if (pausesDues == 0) {
            return "Aucune séquence ne dépasse la durée légale de travail continu : rien à organiser." + repas;
        }
        String base = pausesDues + (pausesDues > 1 ? " pauses" : " pause")
                + (pauseSurPoste ? " à prendre sur le poste" : " due sans être déclarée sur le poste")
                + (relaisManquants > 0
                        ? ", dont " + relaisManquants + " sans relais possible sur le stand"
                        : ", chacune avec un relais possible sur le stand")
                + ".";
        return (pauseSurPoste
                        ? base
                        : base
                                + " Déclarez la pause prise sur le poste dans les paramètres légaux, ou planifiez un trou.")
                + repas;
    }

    private record Journee(
            Animateur animateur,
            LocalDate date,
            int jour,
            boolean mineur,
            List<SequenceDemandes> sequences,
            List<CoupureRepasView> coupuresRepas) {}

    /** A stretch of the day and the breaks it owes, as the rotation places them. */
    private record SequenceDemandes(PauseSurPoste.Sequence sequence, List<Demande> demandes) {}

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

        private Demande(
                Animateur animateur,
                PosteAffectation tenu,
                LocalDateTime auPlusTot,
                LocalDateTime auPlusTard,
                int dureeMinutes) {
            this.animateur = animateur;
            this.tenu = tenu;
            this.auPlusTot = auPlusTot;
            this.auPlusTard = auPlusTard;
            this.dureeMinutes = dureeMinutes;
            this.debut = auPlusTard;
        }
    }

    /** A stretch under construction: mutable end, the seats it is made of, and the breaks it owes. */
}
