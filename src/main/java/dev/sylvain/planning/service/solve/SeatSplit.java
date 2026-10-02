package dev.sylvain.planning.service.solve;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.PastHorizon;
import dev.sylvain.planning.domain.PosteAffectation;
import dev.sylvain.planning.service.espace.TimeslotWindows;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The repair of the timeslot under way (ADR 0066, amending 0044): the past
 * is frozen <b>to the minute</b>, not to the timeslot.
 *
 * <p>Somebody missing at 09:00, noticed at 09:20, used to leave their seat
 * frozen until noon: the timeslot had started, and « le passé ne se modifie
 * plus ». The seat is now <b>split at « now »</b> — two real seats. The
 * original is cut short at that minute ({@code heureFinEffective}) and keeps
 * whoever held it: the history still says who held 09:00-09:20. The
 * continuation covers the rest ({@code heureDebutEffective}), names its
 * origin ({@code suiteDe}), and is written to like any seat ahead.</p>
 *
 * <p>A seat <b>nobody held</b> is not split: there is no history to keep, and
 * an empty fragment would only count as one more seat « non pourvu ». It is
 * <b>narrowed</b> instead — its start moved to « now », the replacement
 * written on it — and its id takes the minute, as a continuation's does, so
 * that a rebuild knows to replay it ({@link #narrow}).</p>
 *
 * <p>Pure and static: the gestures of the day ({@code PlanningWhatIf}), the
 * rebuild of a problem ({@link #restore}) and their tests read the same
 * rules.</p>
 */
public final class SeatSplit {

    private static final DateTimeFormatter SUFFIXE = DateTimeFormatter.ofPattern("HHmm");

    /** Between an id and the minute it was cut or narrowed at: {@code poste-000017~0920}. */
    private static final String SEPARATEUR = "~";

    private SeatSplit() {}

    /** The minute « now » stands at — the split point, never a second inside it. */
    public static LocalTime minute(PastHorizon horizon) {
        return horizon.now().truncatedTo(ChronoUnit.MINUTES);
    }

    /**
     * The window the seat really covers, on the calendar. A seat's hours are
     * read on its timeslot's date, except the part of an overnight timeslot
     * past midnight: the remainder of a 22:00-02:00 shift split at 01:00
     * starts at 01:00 the <em>next</em> day, not 21 hours before the shift.
     * {@code null} for a seat without a dated, timed timeslot.
     */
    public static LocalDateTime[] window(PosteAffectation poste) {
        Creneau creneau = poste.getCreneau();
        LocalTime debut = poste.heureDebutEffectif();
        LocalTime fin = poste.heureFinEffectif();
        if (creneau == null || creneau.getDate() == null || debut == null || fin == null) {
            return null;
        }
        LocalDate date = creneau.getDate();
        if (creneau.getHeureDebut() != null && debut.isBefore(creneau.getHeureDebut())) {
            date = date.plusDays(1);
        }
        return TimeslotWindows.window(date, debut, fin);
    }

    /** Whether the seat's effective window had ended at {@code horizon}: over, not merely started. */
    public static boolean isOver(PosteAffectation poste, PastHorizon horizon) {
        LocalDateTime[] fenetre = window(poste);
        return fenetre != null && !fenetre[1].isAfter(moment(horizon));
    }

    /**
     * Whether a write on this seat has to split it: held, and under way —
     * see {@link #isUnderWay}. An empty seat under way is narrowed instead
     * ({@link #needsNarrowing}).
     */
    public static boolean needsSplit(PosteAffectation poste, PastHorizon horizon) {
        return poste.getAnimateur() != null && isUnderWay(poste, horizon);
    }

    /**
     * Whether seating somebody on this seat has to narrow it: held by nobody,
     * and under way. Its past part held nobody, and stays that way — the
     * replacement is written from « now », never over minutes already gone.
     */
    public static boolean needsNarrowing(PosteAffectation poste, PastHorizon horizon) {
        return poste.getAnimateur() == null && isUnderWay(poste, horizon);
    }

    /**
     * Started — the freeze's own reading — and not over yet, and started
     * before the current minute. A seat whose start <em>is</em> the current
     * minute is written as it is: nobody held any of it yet. Compared on the
     * calendar, so the seat of an overnight timeslot is under way after
     * midnight too.
     */
    static boolean isUnderWay(PosteAffectation poste, PastHorizon horizon) {
        if (horizon == null || !FrozenPast.isPast(poste, horizon) || isOver(poste, horizon)) {
            return false;
        }
        LocalDateTime[] fenetre = window(poste);
        return fenetre != null && fenetre[0].isBefore(LocalDateTime.of(horizon.today(), minute(horizon)));
    }

    private static LocalDateTime moment(PastHorizon horizon) {
        return LocalDateTime.of(horizon.today(), horizon.now());
    }

    /**
     * Cuts {@code poste} at {@code at} and returns its continuation, the same
     * holder on it: the caller then writes the new holder onto the
     * continuation only. The original's end becomes {@code at}; the
     * continuation runs from {@code at} to the original's end.
     */
    public static PosteAffectation split(PosteAffectation poste, LocalTime at) {
        PosteAffectation suite = new PosteAffectation(suiteId(poste.getId(), at), poste.getStand(), poste.getCreneau());
        suite.setHeureDebutEffective(at);
        suite.setHeureFinEffective(poste.getHeureFinEffective());
        suite.setSuiteDe(poste.getId());
        suite.setAnimateur(poste.getAnimateur());
        poste.setHeureFinEffective(at);
        return suite;
    }

    /**
     * Moves the start of an empty seat to {@code at}, the row kept: the
     * minutes before were held by nobody, and the seat no longer covers them
     * — no empty fragment is left behind to count as a seat « non pourvu ».
     * Its id takes the minute ({@code poste-000017~0920}) and it names no origin:
     * that pair is what {@link #isNarrowed} recognises, so that a rebuild
     * replays the narrowed window instead of folding the replacement back
     * onto the whole timeslot.
     */
    public static void narrow(PosteAffectation poste, LocalTime at) {
        poste.setId(suiteId(poste.getId(), at));
        poste.setHeureDebutEffective(at);
    }

    /** Whether a persisted seat was narrowed on the day: an id carrying a minute, and no origin. */
    static boolean isNarrowed(PlanningPersistenceService.Siege siege) {
        return siege.suiteDe() == null
                && siege.posteId() != null
                && siege.posteId().contains(SEPARATEUR);
    }

    /** {@code poste-000017~0920}: the origin's id and the minute of the split — never a generated id. */
    static String suiteId(String origine, LocalTime at) {
        return origine + SEPARATEUR + SUFFIXE.format(at);
    }

    /**
     * Replays the splits of the persisted plan over the seats a build just
     * generated from the referential, for every stand × créneau holding one.
     *
     * <p>A build renumbers its seats and knows nothing of a split: left
     * alone, the positional re-seeding of the past would fold the
     * continuation's holder back onto the origin's place and drop the other.
     * For a split cell, the persisted seats are therefore taken as they are —
     * windows and holders —, the originals mapped onto the generated seats in
     * order, the continuations added after the seats of their cell under an id
     * derived from their origin's new one; a narrowed seat keeps its minute
     * in the new id ({@link #narrow}). A seat already started is pinned and
     * marked past, like the freeze does.</p>
     *
     * @param cells the persisted seats of each split cell, by
     *              {@link PlanningPersistenceService#standCreneauKey}
     * @return how many continuations were added
     */
    public static int restore(
            List<PosteAffectation> postes,
            List<Animateur> animateurs,
            Map<String, List<PlanningPersistenceService.Siege>> cells,
            PastHorizon horizon) {
        if (cells == null || cells.isEmpty()) {
            return 0;
        }
        Map<String, Animateur> animateursById = new HashMap<>();
        animateurs.forEach(animateur -> animateursById.put(animateur.getId(), animateur));
        Set<String> ids = new HashSet<>();
        postes.forEach(poste -> ids.add(poste.getId()));
        int ajoutes = 0;
        for (Map.Entry<String, List<PlanningPersistenceService.Siege>> cell : cells.entrySet()) {
            List<PosteAffectation> generes = postes.stream()
                    .filter(poste -> poste.getStand() != null
                            && poste.getCreneau() != null
                            && cell.getKey()
                                    .equals(PlanningPersistenceService.standCreneauKey(
                                            poste.getStand().getId(),
                                            poste.getCreneau().getId())))
                    .toList();
            if (generes.isEmpty()) {
                continue;
            }
            List<PlanningPersistenceService.Siege> origines = cell.getValue().stream()
                    .filter(siege -> siege.suiteDe() == null)
                    .toList();
            List<PlanningPersistenceService.Siege> suites = cell.getValue().stream()
                    .filter(siege -> siege.suiteDe() != null)
                    .sorted(Comparator.comparing(
                            PlanningPersistenceService.Siege::heureDebutEffective,
                            Comparator.nullsFirst(Comparator.naturalOrder())))
                    .toList();
            Map<String, PosteAffectation> parAncienId = restoreOrigins(origines, generes, animateursById, ids, horizon);
            int insertion = postes.indexOf(generes.getLast()) + 1;
            List<PosteAffectation> ajouts = restoreContinuations(suites, parAncienId, animateursById, ids, horizon);
            postes.addAll(insertion, ajouts);
            ajoutes += ajouts.size();
        }
        return ajoutes;
    }

    /**
     * Lays the persisted originals of one cell, in order, over the seats the
     * build generated for it; returns those seats by their persisted id.
     */
    private static Map<String, PosteAffectation> restoreOrigins(
            List<PlanningPersistenceService.Siege> origines,
            List<PosteAffectation> generes,
            Map<String, Animateur> animateursById,
            Set<String> ids,
            PastHorizon horizon) {
        Map<String, PosteAffectation> parAncienId = new HashMap<>();
        for (int rang = 0; rang < Math.min(origines.size(), generes.size()); rang++) {
            PlanningPersistenceService.Siege siege = origines.get(rang);
            PosteAffectation poste = generes.get(rang);
            poste.setHeureDebutEffective(siege.heureDebutEffective());
            poste.setHeureFinEffective(siege.heureFinEffective());
            poste.setAnimateur(siege.animateurId() == null ? null : animateursById.get(siege.animateurId()));
            if (isNarrowed(siege)) {
                ids.remove(poste.getId());
                poste.setId(uniqueId(suiteId(poste.getId(), siege.heureDebutEffective()), ids));
                ids.add(poste.getId());
            }
            freezeIfPast(poste, horizon);
            parAncienId.put(siege.posteId(), poste);
        }
        return parAncienId;
    }

    /**
     * The continuations of one cell, rebuilt after their origin's new id;
     * each is recorded in {@code parAncienId} too, so a continuation of a
     * continuation finds its origin.
     */
    private static List<PosteAffectation> restoreContinuations(
            List<PlanningPersistenceService.Siege> suites,
            Map<String, PosteAffectation> parAncienId,
            Map<String, Animateur> animateursById,
            Set<String> ids,
            PastHorizon horizon) {
        List<PosteAffectation> ajouts = new ArrayList<>();
        for (PlanningPersistenceService.Siege siege : suites) {
            PosteAffectation origine = parAncienId.get(siege.suiteDe());
            if (origine == null) {
                continue;
            }
            PosteAffectation suite = new PosteAffectation(
                    uniqueId(suiteId(origine.getId(), siege.heureDebutEffective()), ids),
                    origine.getStand(),
                    origine.getCreneau());
            suite.setHeureDebutEffective(siege.heureDebutEffective());
            suite.setHeureFinEffective(siege.heureFinEffective());
            suite.setSuiteDe(origine.getId());
            suite.setAnimateur(siege.animateurId() == null ? null : animateursById.get(siege.animateurId()));
            freezeIfPast(suite, horizon);
            ids.add(suite.getId());
            parAncienId.put(siege.posteId(), suite);
            ajouts.add(suite);
        }
        return ajouts;
    }

    private static void freezeIfPast(PosteAffectation poste, PastHorizon horizon) {
        if (FrozenPast.isPast(poste, horizon)) {
            poste.setPasse(true);
            poste.setVerrouille(true);
        }
    }

    private static String uniqueId(String candidat, Set<String> pris) {
        String id = candidat;
        for (int rang = 2; pris.contains(id); rang++) {
            id = candidat + "-" + rang;
        }
        return id;
    }
}
