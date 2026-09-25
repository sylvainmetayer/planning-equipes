package dev.sylvain.planning.domain;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * One legal break an animateur owes inside a working stretch: the seat held
 * when it falls due, and the window it may start in.
 *
 * <p>Art. L3121-16 owes an adult twenty minutes after six hours of continuous
 * work (a minor thirty after four and a half, art. L3162-3). A break is taken
 * one of two ways, and the application knows no third: as a hole in the grid —
 * which splits the stretch, so no break is owed here at all — or by relay, a
 * colleague of the same stand holding a place while the person steps out. A
 * break this class says is due and {@link #relayableBy} finds no relay for is
 * a hard breach of {@code travailContinuMaxMajeur} /
 * {@code travailContinuMaxMineur} (ADR 0048). The Pauses screen, the
 * animateur's PDF and the ICS feed read the same arithmetic, here, so that no
 * screen describes a break the solver did not owe.</p>
 *
 * <p>The arithmetic: a stretch is a run of seats separated by less than the
 * minimum break; a stretch of {@code L} minutes owes
 * {@code ceil((L − cap) / (cap + break))} breaks past the cap; the k-th one
 * starts at the latest when the stretch reaches its k-th cap, and at the
 * earliest when what remains after it — breaks to come included — still
 * fits under the cap.</p>
 *
 * @param tenu         the seat held at the latest start, the one a relay must cover
 * @param auPlusTot    earliest start
 * @param auPlusTard   latest start — the deadline the Pauses screen calls {@code heureLimite}
 * @param dureeMinutes the break's length
 */
public record PauseSurPoste(
        PosteAffectation tenu, LocalDateTime auPlusTot, LocalDateTime auPlusTard, int dureeMinutes) {

    /** A run of seats without a gap of at least the minimum break between them. */
    public record Sequence(LocalDateTime debut, LocalDateTime fin, List<PosteAffectation> postes) {

        public Sequence {
            postes = List.copyOf(postes);
        }

        public int minutes() {
            return (int) Duration.between(debut, fin).toMinutes();
        }

        /** The seat held at an instant; at a boundary, the one starting next, else the last one started. */
        public PosteAffectation posteA(LocalDateTime instant) {
            PosteAffectation avant = null;
            PosteAffectation apres = null;
            for (PosteAffectation poste : postes) {
                LocalDateTime debutPoste = PauseSurPoste.debut(poste);
                if (!debutPoste.isAfter(instant) && PauseSurPoste.fin(poste).isAfter(instant)) {
                    return poste;
                }
                if (!debutPoste.isAfter(instant)) {
                    avant = poste;
                } else if (apres == null || debutPoste.isBefore(PauseSurPoste.debut(apres))) {
                    apres = poste;
                }
            }
            if (apres != null) {
                return apres;
            }
            return avant != null ? avant : postes.get(0);
        }
    }

    public Animateur animateur() {
        return tenu.getAnimateur();
    }

    public Stand stand() {
        return tenu.getStand();
    }

    public LocalDate date() {
        return tenu.getCreneau().getDate();
    }

    /**
     * True when {@code autre} can relay this break: another animateur, on the
     * same stand, holding a seat over the whole break at its latest start.
     * The screen may place the break earlier to rotate a stand's breaks; the
     * solver asks the simpler question — is anybody there when it falls due.
     */
    public boolean relayableBy(PosteAffectation autre) {
        if (autre == null
                || autre.getAnimateur() == null
                || autre.getStand() == null
                || autre.getCreneau() == null
                || autre.heureDebutEffectif() == null) {
            return false;
        }
        if (autre.getAnimateur().equals(animateur())
                || !autre.getStand().getId().equals(stand().getId())) {
            return false;
        }
        LocalDateTime finPause = auPlusTard.plusMinutes(dureeMinutes);
        return !debut(autre).isAfter(auPlusTard) && !fin(autre).isBefore(finPause);
    }

    /**
     * The breaks one animateur owes over one day, from the seats they hold that
     * day. Empty when no stretch exceeds the cap. The seats must all belong to
     * the same animateur and date and carry a start time.
     */
    public static List<PauseSurPoste> dues(List<PosteAffectation> postesDuJour) {
        return dues(postesDuJour, new ParametresLegaux());
    }

    /**
     * The same, with the break lengths this edition grants — at or above the
     * legal floor, never under it (issue #592). The no-argument variant above
     * reads the defaults, which are exactly those floors.
     */
    public static List<PauseSurPoste> dues(List<PosteAffectation> postesDuJour, ParametresLegaux parametres) {
        if (postesDuJour == null || postesDuJour.isEmpty()) {
            return List.of();
        }
        Animateur animateur = postesDuJour.get(0).getAnimateur();
        LocalDate date = postesDuJour.get(0).getCreneau().getDate();
        boolean mineur = animateur.isMineurOn(date);
        int cap = mineur
                ? PlafondsLegauxMineurs.TRAVAIL_CONTINU_MAX_MINUTES
                : PlafondsLegauxMajeurs.TRAVAIL_CONTINU_MAX_MINUTES;
        int pause = parametres.dureePauseMinutes(mineur);
        List<PauseSurPoste> dues = new ArrayList<>();
        for (Sequence sequence : sequences(postesDuJour, pause)) {
            dues.addAll(dues(sequence, cap, pause));
        }
        return List.copyOf(dues);
    }

    /** The breaks one stretch owes, in order. */
    public static List<PauseSurPoste> dues(Sequence sequence, int cap, int pause) {
        int minutes = sequence.minutes();
        int pauses = minutes <= cap ? 0 : Math.ceilDiv(minutes - cap, cap + pause);
        List<PauseSurPoste> dues = new ArrayList<>(pauses);
        LocalDateTime finPrecedente = sequence.debut();
        for (int k = 1; k <= pauses; k++) {
            LocalDateTime limite = sequence.debut().plusMinutes((long) k * cap + (long) (k - 1) * pause);
            int restantes = pauses - k;
            LocalDateTime auPlusTot =
                    sequence.fin().minusMinutes((long) (restantes + 1) * cap + (long) (restantes + 1) * pause);
            PosteAffectation tenu = sequence.posteA(limite);
            LocalDateTime plancher = maxOf(maxOf(auPlusTot, debut(tenu)), finPrecedente);
            dues.add(new PauseSurPoste(tenu, plancher.isAfter(limite) ? limite : plancher, limite, pause));
            finPrecedente = limite.plusMinutes(pause);
        }
        return dues;
    }

    /** The stretches of a day: seats sorted by start, split where the gap reaches the minimum break. */
    public static List<Sequence> sequences(List<PosteAffectation> postes, int pauseMinimale) {
        List<PosteAffectation> tries = postes.stream()
                .sorted(Comparator.comparing(PauseSurPoste::debut))
                .toList();
        List<Sequence> sequences = new ArrayList<>();
        LocalDateTime debut = null;
        LocalDateTime fin = null;
        List<PosteAffectation> courants = new ArrayList<>();
        for (PosteAffectation poste : tries) {
            LocalDateTime debutPoste = PauseSurPoste.debut(poste);
            LocalDateTime finPoste = fin(poste);
            if (debut == null || Duration.between(fin, debutPoste).toMinutes() >= pauseMinimale) {
                if (debut != null) {
                    sequences.add(new Sequence(debut, fin, courants));
                }
                debut = debutPoste;
                fin = finPoste;
                courants = new ArrayList<>();
            } else if (finPoste.isAfter(fin)) {
                fin = finPoste;
            }
            courants.add(poste);
        }
        if (debut != null) {
            sequences.add(new Sequence(debut, fin, courants));
        }
        return sequences;
    }

    public static LocalDateTime debut(PosteAffectation poste) {
        return LocalDateTime.of(poste.getCreneau().getDate(), poste.heureDebutEffectif());
    }

    public static LocalDateTime fin(PosteAffectation poste) {
        return debut(poste).plusMinutes(poste.getDureeEffectiveMinutes());
    }

    private static LocalDateTime maxOf(LocalDateTime a, LocalDateTime b) {
        return a.isAfter(b) ? a : b;
    }
}
