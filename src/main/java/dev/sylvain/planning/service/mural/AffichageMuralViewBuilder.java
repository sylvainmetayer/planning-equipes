package dev.sylvain.planning.service.mural;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ConsigneEdition;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.service.analyse.PauseAnalyzer.RapportPauses;
import dev.sylvain.planning.service.espace.TimeslotWindows;
import dev.sylvain.planning.service.mural.AffichageMuralView.MuralAlert;
import dev.sylvain.planning.service.mural.AffichageMuralView.MuralAlertType;
import dev.sylvain.planning.service.mural.AffichageMuralView.MuralConsigne;
import dev.sylvain.planning.service.mural.AffichageMuralView.MuralShift;
import dev.sylvain.planning.service.mural.AffichageMuralView.MuralStand;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The wall display, computed from what the service read: pure and static, so
 * the rules below are tested without a database or a clock.
 *
 * <p>Three rules carry the screen.</p>
 *
 * <ul>
 *   <li><b>The day under way is the one whose timeslots span now</b> — the
 *       evening that opened a 22:00-02:00 shift is still « today » at one in
 *       the morning, the way the mode jour J screen reads it. Outside any
 *       journée, it is the calendar date.</li>
 *   <li><b>A shift is the seats of one stand sharing one window</b> — the
 *       effective hours of the seat, a window whose end is not after its start
 *       ending the next day.</li>
 *   <li><b>Names are minimised unless the link says otherwise</b>: the first
 *       name and the initial of the last one, on a screen anyone in the room
 *       reads. Never a phone number, an address, an age or a reason.</li>
 * </ul>
 */
public final class AffichageMuralViewBuilder {

    /** How far ahead an empty seat becomes an alert. */
    static final Duration HORIZON_EMPTY_SEATS = Duration.ofHours(2);

    private AffichageMuralViewBuilder() {}

    /**
     * What one link is allowed to show.
     *
     * @param fullNames    full names rather than the first name and an initial
     * @param restricted   the link was created for a few emplacements: show
     *                     those of {@code emplacements} only — and nothing once
     *                     they were all deleted, never the whole edition
     * @param emplacements the emplacements a restricted link shows
     */
    public record Settings(boolean fullNames, boolean restricted, Set<String> emplacements) {

        public Settings {
            emplacements = emplacements == null ? Set.of() : Set.copyOf(emplacements);
        }

        /** Every stand of the edition, the unrestricted link's view. */
        public static Settings wholeEdition(boolean fullNames) {
            return new Settings(fullNames, false, Set.of());
        }

        boolean shows(Stand stand) {
            if (!restricted) {
                return true;
            }
            return stand.getEmplacement() != null
                    && emplacements.contains(stand.getEmplacement().getId());
        }
    }

    /**
     * Everything the service read, in one place.
     *
     * @param jour the journée under way, as {@link #currentDay} reads it — the
     *             service needs it first, to look the consigne and the breaks
     *             of that day up
     */
    public record Inputs(
            String edition,
            String libelle,
            LocalDateTime now,
            LocalDate jour,
            List<Creneau> creneaux,
            List<PosteAffectation> postes,
            RapportPauses pauses,
            ConsigneEdition consigne) {}

    public static AffichageMuralView build(Inputs inputs, Settings settings) {
        LocalDateTime now = inputs.now();
        List<Creneau> creneaux = inputs.creneaux() == null ? List.of() : inputs.creneaux();
        LocalDate jour = inputs.jour();

        List<PosteAffectation> postesDuJour = (inputs.postes() == null ? List.<PosteAffectation>of() : inputs.postes())
                .stream()
                        .filter(poste -> poste.getStand() != null
                                && poste.getCreneau() != null
                                && jour.equals(poste.getCreneau().getDate())
                                && poste.heureDebutEffectif() != null
                                && poste.heureFinEffectif() != null)
                        .filter(poste -> settings.shows(poste.getStand()))
                        .toList();

        List<MuralStand> stands = stands(postesDuJour, settings);
        List<MuralAlert> alerts = new ArrayList<>(emptySeatAlerts(stands, now));
        alerts.addAll(breakAlerts(inputs.pauses(), jour, now, postesDuJour, settings));
        alerts.sort(Comparator.comparing(MuralAlert::start)
                .thenComparing(MuralAlert::type)
                .thenComparing(MuralAlert::standNom, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));

        ConsigneEdition consigne = inputs.consigne();
        return new AffichageMuralView(
                inputs.edition(),
                inputs.libelle(),
                jour,
                now,
                nextDay(creneaux, jour),
                stands,
                List.copyOf(alerts),
                consigne == null
                        ? null
                        : new MuralConsigne(consigne.fermetureDebut(), consigne.fermetureFin(), consigne.motif()));
    }

    /**
     * {@code Camille D.} by default, {@code Camille Dupont} when the link was
     * created with full names. A missing half falls back on what is there.
     */
    static String displayName(Animateur animateur, boolean fullNames) {
        String prenom = trimmed(animateur.getPrenom());
        String nom = trimmed(animateur.getNom());
        if (prenom.isEmpty() && nom.isEmpty()) {
            return trimmed(animateur.getId());
        }
        if (nom.isEmpty()) {
            return prenom;
        }
        String lastName = fullNames ? nom : nom.substring(0, nom.offsetByCodePoints(0, 1)) + ".";
        return prenom.isEmpty() ? lastName : prenom + " " + lastName;
    }

    /* ------------------------------ The day ------------------------------ */

    /**
     * The journée under way at {@code now} — the mode jour J screen's own
     * reading ({@link TimeslotWindows#currentDay}).
     */
    public static LocalDate currentDay(Collection<Creneau> creneaux, LocalDateTime now) {
        return TimeslotWindows.currentDay(creneaux, now);
    }

    /** The first day after {@code jour} that carries a timeslot, {@code null} when none does. */
    static LocalDate nextDay(Collection<Creneau> creneaux, LocalDate jour) {
        return creneaux.stream()
                .map(Creneau::getDate)
                .filter(Objects::nonNull)
                .filter(date -> date.isAfter(jour))
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    /* ----------------------------- The stands ---------------------------- */

    private static List<MuralStand> stands(List<PosteAffectation> postes, Settings settings) {
        Map<String, Map<List<LocalDateTime>, List<PosteAffectation>>> byStand = new LinkedHashMap<>();
        Map<String, Stand> standsById = new LinkedHashMap<>();
        for (PosteAffectation poste : postes) {
            Stand stand = poste.getStand();
            standsById.putIfAbsent(stand.getId(), stand);
            LocalDateTime[] window = TimeslotWindows.window(
                    poste.getCreneau().getDate(), poste.heureDebutEffectif(), poste.heureFinEffectif());
            byStand.computeIfAbsent(stand.getId(), unused -> new LinkedHashMap<>())
                    .computeIfAbsent(List.of(window[0], window[1]), unused -> new ArrayList<>())
                    .add(poste);
        }
        List<MuralStand> stands = new ArrayList<>();
        byStand.forEach((standId, shifts) -> {
            Stand stand = standsById.get(standId);
            List<MuralShift> vacations = shifts.entrySet().stream()
                    .map(shift -> shift(shift.getKey(), shift.getValue(), settings))
                    .sorted(Comparator.comparing(MuralShift::start).thenComparing(MuralShift::end))
                    .toList();
            stands.add(new MuralStand(
                    standId,
                    stand.getNom() == null ? standId : stand.getNom(),
                    stand.getEmplacement() == null
                            ? null
                            : stand.getEmplacement().getId(),
                    stand.getEmplacement() == null
                            ? null
                            : stand.getEmplacement().getNom(),
                    vacations));
        });
        stands.sort(Comparator.comparing(
                        (MuralStand stand) -> stand.emplacementNom() == null ? "" : stand.emplacementNom(),
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(MuralStand::standNom, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(MuralStand::standId));
        return List.copyOf(stands);
    }

    private static MuralShift shift(List<LocalDateTime> window, List<PosteAffectation> seats, Settings settings) {
        List<String> noms = seats.stream()
                .map(PosteAffectation::getAnimateur)
                .filter(Objects::nonNull)
                .map(animateur -> displayName(animateur, settings.fullNames()))
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
        int empty =
                (int) seats.stream().filter(seat -> seat.getAnimateur() == null).count();
        return new MuralShift(window.get(0), window.get(1), noms, empty);
    }

    /* ----------------------------- The alerts ---------------------------- */

    /** Empty seats on a shift under way, or starting within {@link #HORIZON_EMPTY_SEATS}. */
    private static List<MuralAlert> emptySeatAlerts(List<MuralStand> stands, LocalDateTime now) {
        LocalDateTime horizon = now.plus(HORIZON_EMPTY_SEATS);
        List<MuralAlert> alerts = new ArrayList<>();
        for (MuralStand stand : stands) {
            for (MuralShift shift : stand.vacations()) {
                if (shift.emptySeats() > 0
                        && shift.end().isAfter(now)
                        && shift.start().isBefore(horizon)) {
                    alerts.add(new MuralAlert(
                            MuralAlertType.EMPTY_SEATS,
                            stand.standNom(),
                            shift.start(),
                            shift.end(),
                            shift.emptySeats(),
                            null));
                }
            }
        }
        return alerts;
    }

    /**
     * The breaks of the day nobody on the stand can relay, not yet over. The
     * break report is the Pauses screen's own ({@code PauseAnalyzer}), read
     * over the same persisted plan, so the two never disagree.
     *
     * <p>The report gives a break as bare clock times. One that reads earlier
     * than the stretch it belongs to falls after midnight, on the next day —
     * the 01:00 break of a 22:00-04:00 night shift — which is how
     * {@code PauseAnimateurView.fallsInside} reads it too.</p>
     */
    private static List<MuralAlert> breakAlerts(
            RapportPauses pauses,
            LocalDate jour,
            LocalDateTime now,
            List<PosteAffectation> postesDuJour,
            Settings settings) {
        if (pauses == null || pauses.journees() == null) {
            return List.of();
        }
        Map<String, Stand> shown = new LinkedHashMap<>();
        Map<String, Animateur> animateurs = new LinkedHashMap<>();
        for (PosteAffectation poste : postesDuJour) {
            shown.putIfAbsent(poste.getStand().getId(), poste.getStand());
            if (poste.getAnimateur() != null) {
                animateurs.putIfAbsent(poste.getAnimateur().getId(), poste.getAnimateur());
            }
        }
        List<MuralAlert> alerts = new ArrayList<>();
        for (var journee : pauses.journees()) {
            if (!jour.equals(journee.date())) {
                continue;
            }
            for (var sequence : journee.sequences()) {
                for (var pause : sequence.pausesDues()) {
                    if (pause.relaisDisponible() || pause.debut() == null || pause.fin() == null) {
                        continue;
                    }
                    Stand stand = shown.get(pause.standId());
                    if (stand == null) {
                        continue;
                    }
                    LocalDateTime[] window = breakWindow(jour, sequence.debut(), pause.debut(), pause.fin());
                    if (!window[1].isAfter(now)) {
                        continue;
                    }
                    Animateur animateur = animateurs.get(journee.animateurId());
                    alerts.add(new MuralAlert(
                            MuralAlertType.BREAK_WITHOUT_RELAY,
                            stand.getNom() == null ? stand.getId() : stand.getNom(),
                            window[0],
                            window[1],
                            0,
                            animateur == null ? null : displayName(animateur, settings.fullNames())));
                }
            }
        }
        return alerts;
    }

    /**
     * A break of the journée {@code jour}, on the wall clock: moved to the next
     * day when it starts before the stretch it belongs to.
     */
    static LocalDateTime[] breakWindow(LocalDate jour, LocalTime stretchStart, LocalTime start, LocalTime end) {
        LocalDate date = stretchStart != null && start.isBefore(stretchStart) ? jour.plusDays(1) : jour;
        return TimeslotWindows.window(date, start, end);
    }

    private static String trimmed(String value) {
        return value == null ? "" : value.trim();
    }
}
