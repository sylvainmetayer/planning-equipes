package dev.sylvain.planning.service.publication;

import dev.sylvain.planning.domain.PlanningEvenement;
import dev.sylvain.planning.domain.PosteAffectation;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Who has to be told, and what to tell them (issue #245): compares an
 * animateur's seats in the working plan with their seats in the last published
 * plan, and puts the difference into words.
 *
 * <p>Deliberately <b>not</b> in {@code SnapshotComparisonService}, which
 * compares KPI and is non-nominative on purpose — « la fairness est une
 * dispersion d'heures, jamais un classement de personnes nommées ». This
 * computation is of another nature: it ranks nobody, it answers « votre emploi
 * du temps a-t-il changé depuis ce qu'on vous a envoyé ? », one person at a
 * time, and only for the people it is about to write to.</p>
 *
 * <p>Pure function of its inputs: no database, no mail, no clock. What it
 * returns feeds both the confirmation screen and the mail body, so the admin
 * reads beforehand exactly the sentences the animateur will receive.</p>
 */
@ApplicationScoped
public class PublicationDiffService {

    /** « samedi 11/07 » — the day named, then dated, as a planning is read aloud. */
    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("EEEE dd/MM", Locale.FRENCH);

    /** What changed about one seat. */
    public enum TypeChangement {

        /** A seat that was not in the published plan. */
        AJOUT,

        /** A seat of the published plan the animateur no longer holds. */
        RETRAIT,

        /**
         * A seat replaced by another one the same day — another stand, other
         * hours, or both. Kept apart from an {@link #AJOUT} plus a
         * {@link #RETRAIT} because that is how a human reads it: « Ninja
         * 14h-18h remplace Cirque 14h-18h » is one change, not two.
         */
        DEPLACEMENT
    }

    /**
     * One seat of an animateur, as the diff sees it: the identity of a
     * vacation for a person is its day, its hours and its stand — a poste id
     * is not, since a resolve renumbers them without moving anybody.
     */
    public record Vacation(LocalDate date, LocalTime debut, LocalTime fin, String standId, String standNom) {

        /** Same day, same hours, same stand: the same vacation for the person holding it. */
        String cle() {
            return date + "|" + debut + "|" + fin + "|" + standId;
        }
    }

    /**
     * One change, already worded.
     *
     * @param precedente the seat this one replaces, {@code null} unless the
     *                   type is {@link TypeChangement#DEPLACEMENT}
     * @param libelle    the sentence shown to the admin and mailed to the
     *                   animateur — the same one, so nothing is announced that
     *                   was not reviewed
     */
    public record ChangementVacation(TypeChangement type, Vacation vacation, Vacation precedente, String libelle) {

        /**
         * Whether this change belongs to {@code jour}: the day of the vacation
         * it announces. The one rule the Journée's « Changements » and the
         * Diffuser screen's day filter share, so the two count the same people
         * for the same day.
         */
        public boolean touches(LocalDate jour) {
            return vacation != null && jour != null && jour.equals(vacation.date());
        }
    }

    /**
     * The days one person's changes belong to — see
     * {@link ChangementVacation#touches}, sorted.
     */
    public static List<LocalDate> joursTouches(List<ChangementVacation> changements) {
        return changements.stream()
                .map(changement -> changement.vacation() == null
                        ? null
                        : changement.vacation().date())
                .filter(java.util.Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * How far a vacation may slide on the same stand before it is worth
     * alarming somebody (issue #503).
     *
     * <p>Fifteen minutes, and written here rather than configured: it is not a
     * tuning knob but the definition of « ces trois-là ne bougent que de dix
     * minutes, inutile de les alarmer ». A quarter of an hour is the
     * granularity a volunteer plans their arrival on; below it, the person
     * shows up when they meant to and reads a mail that tells them nothing.</p>
     *
     * <p>It never suppresses anything on its own: it only lets the screen
     * group those rows behind one filter, and the admin decides.</p>
     */
    public static final Duration DECALAGE_MINEUR = Duration.ofMinutes(15);

    /**
     * Whether a change is one nobody needs to be alarmed about: the same
     * vacation, on the same stand and the same day, sliding by no more than
     * {@link #DECALAGE_MINEUR} at either end.
     *
     * <p>Only a déplacement can qualify. An ajout and a retrait are never
     * minor whatever their length — a seat somebody does not know they hold,
     * or no longer holds, is the thing this whole feature exists to say.</p>
     */
    public static boolean isMinor(ChangementVacation changement) {
        if (changement == null || changement.type() != TypeChangement.DEPLACEMENT || changement.precedente() == null) {
            return false;
        }
        Vacation apres = changement.vacation();
        Vacation avant = changement.precedente();
        if (apres == null || avant == null) {
            return false;
        }
        return Objects.equals(apres.standId(), avant.standId())
                && Objects.equals(apres.date(), avant.date())
                && within(apres.debut(), avant.debut())
                && within(apres.fin(), avant.fin());
    }

    /** Two hours no further apart than the threshold; an unreadable one never is. */
    private static boolean within(LocalTime gauche, LocalTime droite) {
        if (gauche == null || droite == null) {
            return false;
        }
        return Duration.between(gauche, droite).abs().compareTo(DECALAGE_MINEUR) <= 0;
    }

    /**
     * Everything one person has to be told.
     *
     * @param premiereDiffusion true when this animateur has no published plan
     *                          to compare against — their whole planning is
     *                          new to them, so it is announced as a planning
     *                          rather than as a list of corrections
     */
    public record ChangementAnimateur(
            String animateurId,
            String nomAffiche,
            String email,
            boolean premiereDiffusion,
            List<ChangementVacation> changements) {}

    /**
     * The people whose schedule differs between the two plans, named, sorted
     * by display name. An animateur missing from the result has nothing to
     * read: renaming a stand or reshuffling seat ids moves nobody, so nobody
     * is written to.
     *
     * @param publie             seats per animateur id in the last published
     *                           plan; empty when nothing was ever published
     * @param courant            seats per animateur id in the working plan
     * @param identites          display name and address per animateur id;
     *                           an id absent from it is skipped — the fiche is
     *                           gone, there is nobody left to write to
     * @param jamaisPublie       true when the edition has no published plan at
     *                           all, which makes every recipient a first
     *                           delivery
     */
    public List<ChangementAnimateur> comparer(
            Map<String, List<Vacation>> publie,
            Map<String, List<Vacation>> courant,
            Map<String, Identite> identites,
            boolean jamaisPublie) {
        Set<String> concernes = new LinkedHashSet<>();
        concernes.addAll(publie.keySet());
        concernes.addAll(courant.keySet());

        List<ChangementAnimateur> resultat = new ArrayList<>();
        for (String animateurId : concernes) {
            Identite identite = identites.get(animateurId);
            if (identite == null) {
                continue;
            }
            List<ChangementVacation> changements = changements(
                    publie.getOrDefault(animateurId, List.of()), courant.getOrDefault(animateurId, List.of()));
            if (!changements.isEmpty()) {
                resultat.add(new ChangementAnimateur(
                        animateurId,
                        identite.nomAffiche(),
                        identite.email(),
                        jamaisPublie || !publie.containsKey(animateurId),
                        changements));
            }
        }
        resultat.sort(Comparator.comparing(ChangementAnimateur::nomAffiche, String.CASE_INSENSITIVE_ORDER));
        return List.copyOf(resultat);
    }

    /** Display name and address of one animateur — all the diff needs of a fiche. */
    public record Identite(String nomAffiche, String email) {}

    /**
     * An animateur's schedule as this diff defines it: their seats, keyed by
     * animateur id. Unassigned seats are ignored — an empty chair concerns
     * nobody.
     *
     * <p>Seats naming a stand the referential no longer holds have been
     * dropped upstream, on both sides alike. A deleted <b>créneau</b> used to
     * go the same way, and that was issue #576: it took the seat out of the
     * working plan and out of the published one at the same instant, so it
     * produced no écart and warned nobody — the very person who lost their
     * Tuesday afternoon was the one the publication skipped. The published side
     * now carries its own day and hours, so a deleted créneau leaves a seat on
     * the published side only, which is exactly what a {@link
     * TypeChangement#RETRAIT} is.</p>
     */
    public static Map<String, List<Vacation>> vacationsByAnimateur(PlanningEvenement planning) {
        Map<String, List<Vacation>> byAnimateur = new LinkedHashMap<>();
        if (planning == null || planning.getPostes() == null) {
            return byAnimateur;
        }
        for (PosteAffectation poste : planning.getPostes()) {
            if (poste.getAnimateur() == null || poste.getStand() == null || poste.getCreneau() == null) {
                continue;
            }
            byAnimateur
                    .computeIfAbsent(poste.getAnimateur().getId(), unused -> new ArrayList<>())
                    .add(new Vacation(
                            poste.getCreneau().getDate(),
                            poste.heureDebutEffectif(),
                            poste.heureFinEffectif(),
                            poste.getStand().getId(),
                            poste.getStand().getNom()));
        }
        return byAnimateur;
    }

    /** « samedi 11/07 : Cirque 14h-18h » — one vacation, worded as the change lines word it. */
    public String libelleVacation(Vacation vacation) {
        return jour(vacation) + " : " + creneau(vacation);
    }

    /**
     * « Cirque 14h-18h » — the same vacation without its day, for a message
     * whose subject line already carries the date (the J-1 reminder of issue
     * #298). Exposed rather than re-derived at the call site so the hour
     * formatting — « 14h » and not « 14h00 » — has exactly one definition.
     */
    public String libelleCreneauSeul(Vacation vacation) {
        return creneau(vacation);
    }

    /**
     * The changes of one person, ordered as their planning reads: by day, then
     * by hour.
     *
     * <p>Seats present on both sides cancel out first. What is left is paired
     * into déplacements before being reported: same day and same hours on
     * another stand, then — only when a single seat is a candidate on each
     * side — same day and same stand at other hours. Anything still unpaired
     * is an ajout or a retrait, which is also what a change of day is: moving
     * somebody from Saturday to Sunday is not a détail to fold into one
     * sentence.</p>
     */
    private List<ChangementVacation> changements(List<Vacation> publiees, List<Vacation> courantes) {
        Map<String, Vacation> retraits = index(publiees);
        Map<String, Vacation> ajouts = index(courantes);
        Set<String> communes = new LinkedHashSet<>(retraits.keySet());
        communes.retainAll(ajouts.keySet());
        retraits.keySet().removeAll(communes);
        ajouts.keySet().removeAll(communes);

        List<ChangementVacation> changements = new ArrayList<>();
        apparierMemesHeures(retraits, ajouts, changements);
        apparierMemeStand(retraits, ajouts, changements);
        for (Vacation ajout : ajouts.values()) {
            changements.add(new ChangementVacation(TypeChangement.AJOUT, ajout, null, libelleAjout(ajout)));
        }
        for (Vacation retrait : retraits.values()) {
            changements.add(new ChangementVacation(TypeChangement.RETRAIT, retrait, null, libelleRetrait(retrait)));
        }
        changements.sort(Comparator.comparing(
                        (ChangementVacation changement) -> changement.vacation().date())
                .thenComparing(
                        changement -> changement.vacation().debut(), Comparator.nullsLast(Comparator.naturalOrder())));
        return List.copyOf(changements);
    }

    /** Same day, same hours, another stand: the swap case, and the most frequent one. */
    private void apparierMemesHeures(
            Map<String, Vacation> retraits, Map<String, Vacation> ajouts, List<ChangementVacation> changements) {
        apparier(
                retraits,
                ajouts,
                changements,
                vacation -> vacation.date() + "|" + vacation.debut() + "|" + vacation.fin(),
                false);
    }

    /**
     * Same day, same stand, other hours: a vacation whose slot was stretched or
     * shifted. Only paired when exactly one seat is a candidate on each side —
     * two seats on the same stand the same day (morning and evening) are two
     * distinct vacations, and pairing them would invent a move nobody made.
     */
    private void apparierMemeStand(
            Map<String, Vacation> retraits, Map<String, Vacation> ajouts, List<ChangementVacation> changements) {
        apparier(retraits, ajouts, changements, vacation -> vacation.date() + "|" + vacation.standId(), true);
    }

    private void apparier(
            Map<String, Vacation> retraits,
            Map<String, Vacation> ajouts,
            List<ChangementVacation> changements,
            Function<Vacation, String> cle,
            boolean exigerUnicite) {
        Map<String, List<Vacation>> retraitsParCle = grouper(retraits.values(), cle);
        Map<String, List<Vacation>> ajoutsParCle = grouper(ajouts.values(), cle);
        for (Map.Entry<String, List<Vacation>> entree : ajoutsParCle.entrySet()) {
            List<Vacation> candidatsRetraits = retraitsParCle.getOrDefault(entree.getKey(), List.of());
            List<Vacation> candidatsAjouts = entree.getValue();
            boolean uniques = candidatsAjouts.size() == 1 && candidatsRetraits.size() == 1;
            if (candidatsRetraits.isEmpty() || (exigerUnicite && !uniques)) {
                continue;
            }
            int apparies = Math.min(candidatsAjouts.size(), candidatsRetraits.size());
            for (int i = 0; i < apparies; i++) {
                Vacation ajout = candidatsAjouts.get(i);
                Vacation retrait = candidatsRetraits.get(i);
                ajouts.remove(ajout.cle());
                retraits.remove(retrait.cle());
                changements.add(new ChangementVacation(
                        TypeChangement.DEPLACEMENT, ajout, retrait, libelleDeplacement(ajout, retrait)));
            }
        }
    }

    private Map<String, List<Vacation>> grouper(Collection<Vacation> vacations, Function<Vacation, String> cle) {
        Map<String, List<Vacation>> groupes = new LinkedHashMap<>();
        for (Vacation vacation : vacations) {
            groupes.computeIfAbsent(cle.apply(vacation), unused -> new ArrayList<>())
                    .add(vacation);
        }
        return groupes;
    }

    private Map<String, Vacation> index(List<Vacation> vacations) {
        Map<String, Vacation> parCle = new LinkedHashMap<>();
        for (Vacation vacation : vacations) {
            parCle.putIfAbsent(vacation.cle(), vacation);
        }
        return parCle;
    }

    /* ------------------------------ Wording ------------------------------- */

    private String libelleAjout(Vacation vacation) {
        return jour(vacation) + " : " + creneau(vacation) + " (nouveau)";
    }

    private String libelleRetrait(Vacation vacation) {
        return jour(vacation) + " : " + creneau(vacation) + " (retiré)";
    }

    private String libelleDeplacement(Vacation ajout, Vacation retrait) {
        return jour(ajout) + " : " + creneau(ajout) + " remplace " + creneau(retrait);
    }

    private static String jour(Vacation vacation) {
        return vacation.date() == null ? "date inconnue" : JOUR.format(vacation.date());
    }

    private static String creneau(Vacation vacation) {
        String nom =
                vacation.standNom() == null || vacation.standNom().isBlank() ? vacation.standId() : vacation.standNom();
        return nom + " " + heure(vacation.debut()) + "-" + heure(vacation.fin());
    }

    /** « 14h » rather than « 14h00 »: the quarter hours are the ones worth writing out. */
    private static String heure(LocalTime heure) {
        if (heure == null) {
            return "?";
        }
        return heure.getMinute() == 0
                ? heure.getHour() + "h"
                : String.format(Locale.FRENCH, "%dh%02d", heure.getHour(), heure.getMinute());
    }
}
