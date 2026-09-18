package dev.sylvain.planning.service.referentiel;

import dev.sylvain.planning.domain.Animateur;
import dev.sylvain.planning.domain.ContrainteAdHoc;
import dev.sylvain.planning.domain.Creneau;
import dev.sylvain.planning.domain.FenetreHoraire;
import dev.sylvain.planning.domain.HoraireStand;
import dev.sylvain.planning.domain.IndisponibiliteStand;
import dev.sylvain.planning.domain.OuvertureStand;
import dev.sylvain.planning.domain.Stand;
import dev.sylvain.planning.domain.VerrouillagePlanning;
import dev.sylvain.planning.domain.VerrouillageTarget;
import dev.sylvain.planning.service.analyse.OuvertureStandsAnalyzer;
import dev.sylvain.planning.service.analyse.PlanningDiagnosticService;
import dev.sylvain.planning.service.analyse.ViolationFormatter;
import dev.sylvain.planning.solver.ConstraintCatalog;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The cross-field checks a write runs on top of {@link CreneauValidator} and
 * {@link StandValidator}: what those two refuse is malformed, what this one
 * reports is merely suspicious — and therefore written anyway.
 *
 * <p>Pure and static, like {@link OuvertureStandsAnalyzer} and
 * {@code VacationGeneratorService}: the caller brings the referential it needs
 * ({@link CoherenceService} does), so every rule is unit-testable on plain
 * objects with no database and no CDI.</p>
 *
 * <p>Two things it deliberately does <b>not</b> reinvent. The event's span is
 * {@link JoursEvenement}, derived from the créneaux, because an
 * {@code Edition} carries no dates. And a stand's opening span is read through
 * {@link Creneau#segmentsOuvertsMinutes(Stand)} on stands whose recurring
 * {@code HoraireStand} rules have already been expanded by
 * {@link HoraireStandResolver} — a check reading the dated windows alone would
 * flag every timeslot of every stand scheduled by rule, which is most of them
 * on the real fixtures.</p>
 */
public final class CoherenceAnalyzer {

    /**
     * Under this, a stretch of timeslot nobody opens is an artefact of the
     * clock rather than a schedule problem — the same floor
     * {@link OuvertureStandsAnalyzer} uses for an unusably short opening, and
     * for the same reason: an anomaly that fires on correct data stops being
     * read.
     */
    static final int DEBORDEMENT_MINIMAL_MINUTES = OuvertureStandsAnalyzer.DUREE_MINIMALE_EXPLOITABLE_MINUTES;

    /** How many dates a message spells out before it says "and n others". */
    private static final int DATES_CITEES = 5;

    private static final int MINUTES_PAR_JOUR = 24 * 60;

    private CoherenceAnalyzer() {}

    /* ------------------------------ Animateur ------------------------------ */

    /**
     * What is worth telling the operator about a fiche they are <b>creating</b>.
     * Empty — never a guess — when the edition has no créneau yet: see
     * {@link JoursEvenement}.
     */
    public static List<Avertissement> onAnimateur(Animateur animateur, JoursEvenement jours) {
        return onAnimateur(null, animateur, jours);
    }

    /**
     * What is worth telling the operator about the fiche they just wrote, given
     * the days the event spans and <b>what the write actually changed</b>.
     *
     * <p>{@code avant} is the fiche as it stood before this write, {@code null}
     * for a creation. Nothing is reported about a field the write left
     * untouched, and that is the whole point rather than a refinement: the bulk
     * edit issues one {@code PUT} per row carrying the <em>merged</em> fiche, so
     * ticking thirty volunteers to add a competence would otherwise re-raise
     * "is a minor" for every minor of the selection — on data nobody typed, and
     * on a state that is perfectly legitimate. An anomaly that fires on correct
     * data stops being read, and this class exists to be read.</p>
     */
    public static List<Avertissement> onAnimateur(Animateur avant, Animateur apres, JoursEvenement jours) {
        if (apres == null || jours == null || jours.isEmpty()) {
            return List.of();
        }
        List<Avertissement> avertissements = new ArrayList<>();
        indisponibilites(avant, apres, jours, avertissements);
        minoriteSurLEvenement(avant, apres, jours).ifPresent(avertissements::add);
        return List.copyOf(avertissements);
    }

    /**
     * Off days this write <b>added</b> that will never meet a créneau, split by
     * why. Availability is opt-out, so such a day excludes the animateur from
     * nothing at all.
     *
     * <p>Two distinct situations, deliberately not merged. Outside the span it
     * is the month-or-year slip that {@code CreneauGridService} flags on the
     * grid, seen from the roster. Inside the span but on a date carrying no
     * créneau, the date is right and the day is doomed all the same — the
     * declaration form only offers the dates of the créneaux and applying a
     * declaration replaces the whole list, exactly the case the CSV import
     * rejects a row over. Saying nothing there would be accepting data known to
     * be condemned.</p>
     *
     * <p>Days already declared before this write are left alone: they are not
     * news of this write, and repeating them on every unrelated edit is the
     * cry-wolf this class refuses.</p>
     */
    private static void indisponibilites(
            Animateur avant, Animateur apres, JoursEvenement jours, List<Avertissement> avertissements) {
        Collection<LocalDate> declares = apres.getJoursIndisponibles();
        if (declares == null || declares.isEmpty()) {
            return;
        }
        Collection<LocalDate> deja =
                avant == null || avant.getJoursIndisponibles() == null ? Set.of() : avant.getJoursIndisponibles();
        List<LocalDate> nouveaux = declares.stream()
                .filter(Objects::nonNull)
                .filter(jour -> !deja.contains(jour))
                .sorted(Comparator.naturalOrder())
                .toList();
        List<LocalDate> hors =
                nouveaux.stream().filter(jour -> !jours.covers(jour)).toList();
        if (!hors.isEmpty()) {
            avertissements.add(new Avertissement(
                    TypeAvertissement.INDISPONIBILITE_HORS_EVENEMENT,
                    (hors.size() == 1
                                    ? "Indisponibilité hors de l'événement : "
                                    : "Indisponibilités hors de l'événement : ")
                            + citer(hors) + ". L'événement court du " + jours.first() + " au " + jours.last()
                            + " (dates des créneaux de l'édition) : "
                            + (hors.size() == 1
                                    ? "ce jour ne recouvre aucun créneau"
                                    : "ces jours ne recouvrent aucun créneau")
                            + " et ne changera rien au planning. La saisie est enregistrée."));
        }
        List<LocalDate> creux = nouveaux.stream()
                .filter(jours::covers)
                .filter(jour -> !jours.hasCreneauOn(jour))
                .toList();
        if (!creux.isEmpty()) {
            avertissements.add(new Avertissement(
                    TypeAvertissement.INDISPONIBILITE_JOUR_SANS_CRENEAU,
                    (creux.size() == 1
                                    ? "Indisponibilité sur un jour sans créneau : "
                                    : "Indisponibilités sur des jours sans créneau : ")
                            + citer(creux) + ". "
                            + (creux.size() == 1
                                    ? "Ce jour est dans l'événement mais ne porte aucun créneau"
                                    : "Ces jours sont dans l'événement mais ne portent aucun créneau")
                            + " : l'espace animateur ne "
                            + (creux.size() == 1 ? "l'affichera pas" : "les affichera pas")
                            + ", et la première déclaration de disponibilités appliquée "
                            + (creux.size() == 1 ? "l'effacera" : "les effacera")
                            + ". La saisie est enregistrée."));
        }
    }

    /**
     * Whether the animateur is a jeune travailleur at some point of the event,
     * and from which day they stop being one.
     *
     * <p>Read day by day through {@code Animateur.isMineurOn} rather than from
     * a computed eighteenth birthday: that keeps the answer identical to the
     * solver's, February 29th included — {@code dateNaissance.plusYears(18)}
     * lands on the 28th in a common year, one day before
     * {@code Period.between} agrees the person turned 18.</p>
     *
     * <p>Raised only when this write <b>set or changed</b> the date de
     * naissance. Being a minor is a modelled, legitimate state, not a typo: an
     * edit that never touched the field has nothing new to say about it, and
     * saying it anyway on every bulk edit is how a warning gets dismissed
     * unread.</p>
     *
     * <p>The sentence names the animateur by their <b>id</b>, and never spells
     * out the date de naissance. This text is shown by a browser that keeps a
     * log of what it showed: an identity and a birth date have no business
     * being written there (see {@code docs/rgpd.md} §7). The id is the least
     * that keeps the warning actionable in a bulk edit, where the screen no
     * longer says which row it is about.</p>
     */
    private static Optional<Avertissement> minoriteSurLEvenement(
            Animateur avant, Animateur apres, JoursEvenement jours) {
        if (apres.getDateNaissance() == null
                || (avant != null && Objects.equals(avant.getDateNaissance(), apres.getDateNaissance()))) {
            return Optional.empty();
        }
        LocalDate debut = jours.first();
        LocalDate fin = jours.last();
        // Minority only ever ends, never starts: minor at some point of the
        // span means minor on its first day.
        if (!apres.isMineurOn(debut)) {
            return Optional.empty();
        }
        LocalDate majorite = null;
        for (LocalDate jour = debut; !jour.isAfter(fin); jour = jour.plusDays(1)) {
            if (apres.isMajeurOn(jour)) {
                majorite = jour;
                break;
            }
        }
        String qui = "L'animateur " + apres.getId();
        if (majorite == null) {
            return Optional.of(new Avertissement(
                    TypeAvertissement.MINEUR_PENDANT_EVENEMENT,
                    qui + " est mineur pendant tout l'événement (du " + debut + " au " + fin
                            + ") : les règles des jeunes travailleurs s'appliqueront à toutes ses affectations. "
                            + "Si la date de naissance est une erreur de saisie, corrigez-la."));
        }
        return Optional.of(new Avertissement(
                TypeAvertissement.MINEUR_PENDANT_EVENEMENT,
                qui + " est mineur du " + debut + " au " + majorite.minusDays(1) + " et devient majeur le "
                        + majorite + " : les règles des jeunes travailleurs ne s'appliqueront qu'aux créneaux "
                        + "antérieurs à cette date."));
    }

    /* -------------------------------- Timeslot ------------------------------ */

    /**
     * What is worth telling the operator about {@code creneau}, given the
     * stands as they will be read at solve time.
     *
     * @param stands stands whose recurring rules are <b>already resolved</b>
     *               for this timeslot's days — {@link CoherenceService} calls
     *               {@link HoraireStandResolver} first. An empty list yields no
     *               warning: with no stand to compare against, there is no
     *               opening span to be outside of.
     */
    public static List<Avertissement> onCreneau(Creneau creneau, List<Stand> stands) {
        if (creneau == null
                || creneau.getDate() == null
                || creneau.getHeureDebut() == null
                || creneau.getHeureFin() == null
                || stands == null
                || stands.isEmpty()) {
            return List.of();
        }
        int duree = creneau.getDureeMinutes();
        if (duree <= 0) {
            return List.of();
        }
        List<int[]> ouverts = union(stands.stream()
                .map(creneau::segmentsOuvertsMinutes)
                .flatMap(List::stream)
                .toList());
        if (ouverts.isEmpty()) {
            return List.of(new Avertissement(
                    TypeAvertissement.CRENEAU_HORS_OUVERTURE_STANDS,
                    "Aucun des " + stands.size() + " stands de l'édition n'est ouvert pendant le créneau "
                            + libelle(creneau) + " : il n'ouvrira aucun poste et le solveur n'y placera personne. "
                            + "Vérifiez les horaires d'ouverture des stands sur cette date. Le créneau est "
                            + "enregistré."));
        }
        int avant = ouverts.get(0)[0];
        int apres = duree - ouverts.get(ouverts.size() - 1)[1];
        List<String> debordements = new ArrayList<>();
        if (avant >= DEBORDEMENT_MINIMAL_MINUTES) {
            debordements.add("de " + heure(creneau, 0) + " à " + heure(creneau, avant) + " (" + avant + " min)");
        }
        if (apres >= DEBORDEMENT_MINIMAL_MINUTES) {
            debordements.add(
                    "de " + heure(creneau, duree - apres) + " à " + heure(creneau, duree) + " (" + apres + " min)");
        }
        if (debordements.isEmpty()) {
            return List.of();
        }
        return List.of(new Avertissement(
                TypeAvertissement.CRENEAU_DEBORDE_OUVERTURE_STANDS,
                "Le créneau " + libelle(creneau) + " déborde l'amplitude d'ouverture de tous les stands : aucun "
                        + "n'est ouvert " + String.join(" ni ", debordements)
                        + ". Personne ne pourra être placé sur ces minutes. Le créneau est enregistré."));
    }

    /* --------------------------------- Stand -------------------------------- */

    /**
     * What is worth telling the operator about the stand they just wrote,
     * given the edition's créneaux and <b>what the write actually changed</b>.
     *
     * <p>Nothing is said unless the schedule — rules, closures, openings —
     * differs from {@code avant} ({@code null} for a creation): renaming a
     * stand that has always opened on no day is not the moment to say so, and
     * a bulk edit of thirty premium flags would otherwise repeat it thirty
     * times. Same doctrine as {@link #onAnimateur(Animateur, Animateur,
     * JoursEvenement)}.</p>
     *
     * @param apres the stand as written, its recurring rules <b>already
     *              resolved</b> against {@code creneaux} —
     *              {@link CoherenceService} calls {@link HoraireStandResolver}
     *              first. An edition without a créneau yields no warning: there
     *              is nothing to be outside of.
     */
    public static List<Avertissement> onStand(Stand avant, Stand apres, List<Creneau> creneaux) {
        if (apres == null || creneaux == null || creneaux.isEmpty()) {
            return List.of();
        }
        if (avant != null && scheduleSignature(avant).equals(scheduleSignature(apres))) {
            return List.of();
        }
        JoursEvenement jours = JoursEvenement.of(creneaux);
        if (jours.isEmpty()) {
            return List.of();
        }
        List<Avertissement> avertissements = new ArrayList<>();

        // The span is not the whole answer: a créneau crossing midnight reads a
        // window dated the day after it (HoraireStandResolver#datesConcernees),
        // and on the event's last day that date sits outside [first, last].
        // Warning about it would be crying wolf on a window the solver honours.
        Set<LocalDate> lues = HoraireStandResolver.datesConcernees(creneaux);
        List<LocalDate> horsEvenement = new ArrayList<>();
        apres.getIndisponibilites().stream()
                .map(IndisponibiliteStand::getDate)
                .filter(Objects::nonNull)
                .filter(date -> !jours.covers(date) && !lues.contains(date))
                .forEach(horsEvenement::add);
        apres.getOuvertures().stream()
                .map(OuvertureStand::getDate)
                .filter(Objects::nonNull)
                .filter(date -> !jours.covers(date) && !lues.contains(date))
                .forEach(horsEvenement::add);
        if (!horsEvenement.isEmpty()) {
            List<LocalDate> triees = horsEvenement.stream().distinct().sorted().toList();
            avertissements.add(new Avertissement(
                    TypeAvertissement.STAND_EXCEPTION_HORS_EVENEMENT,
                    "Le stand " + apres.getId() + " porte " + horsEvenement.size() + " exception(s) datée(s) hors "
                            + "des jours de l'événement (" + jours.first() + " → " + jours.last() + "), sur "
                            + triees.size() + " date(s) : " + citer(triees)
                            + ". Aucun créneau ne les lira. Le stand est enregistré."));
        }

        List<OuvertureStandsAnalyzer.Anomaly> sansEffet =
                OuvertureStandsAnalyzer.fenetresWithoutEffect(apres, OuvertureStandsAnalyzer.creneauxByDay(creneaux));
        if (!sansEffet.isEmpty()) {
            // The dates, not one full sentence per date: a rule expanding onto
            // twelve days repeated the same phrase five times over 590
            // characters, in a snack bar that stays until it is dismissed.
            List<LocalDate> joursConcernes = sansEffet.stream()
                    .map(OuvertureStandsAnalyzer.Anomaly::date)
                    .filter(Objects::nonNull)
                    .distinct()
                    .sorted()
                    .toList();
            OuvertureStandsAnalyzer.Anomaly premiere = sansEffet.stream()
                    .min(Comparator.comparing(anomalie -> anomalie.date() != null ? anomalie.date() : LocalDate.MIN))
                    .orElse(sansEffet.get(0));
            avertissements.add(new Avertissement(
                    TypeAvertissement.STAND_FENETRE_SANS_EFFET,
                    "Le stand " + apres.getId() + " a " + sansEffet.size() + " fenêtre(s) qui ne recoupent aucun "
                            + "créneau de leur jour : " + citer(joursConcernes) + ". Par exemple le "
                            + premiere.date() + ", " + premiere.message()
                            + " Vérifiez les heures saisies contre la grille de créneaux. Le stand est enregistré."));
        }

        boolean ouvertQuelquePart = creneaux.stream()
                .anyMatch(creneau -> !creneau.segmentsOuvertsMinutes(apres).isEmpty());
        if (!ouvertQuelquePart) {
            avertissements.add(new Avertissement(
                    TypeAvertissement.STAND_JAMAIS_OUVERT,
                    "Le stand " + apres.getId() + " n'est ouvert sur aucun des " + creneaux.size()
                            + " créneaux de l'édition : il n'ouvrira aucun poste et le solveur n'y placera personne. "
                            + "Le stand est enregistré."));
        }
        return List.copyOf(avertissements);
    }

    /**
     * The schedule as typed, flattened to one string so two writes can be told
     * apart. Deliberately not {@code equals} on the rules, which have none
     * (see {@link HoraireStand}); the dated lists are read, never the
     * resolved ones.
     */
    private static String scheduleSignature(Stand stand) {
        StringBuilder signature = new StringBuilder();
        for (HoraireStand horaire : stand.getHoraires()) {
            signature
                    .append(horaire.getMode())
                    .append('|')
                    .append(horaire.getJours())
                    .append('|')
                    .append(horaire.getJoursSemaine())
                    .append('|')
                    .append(horaire.getDateDebut())
                    .append('|')
                    .append(horaire.getDateFin())
                    .append('|')
                    .append(horaire.getDates())
                    .append('|');
            for (FenetreHoraire fenetre : horaire.getFenetres()) {
                signature
                        .append(fenetre.getHeureDebut())
                        .append('-')
                        .append(fenetre.getHeureFin())
                        .append('@')
                        .append(fenetre.getEffectif())
                        .append(',');
            }
            signature.append(';');
        }
        signature.append('#');
        for (IndisponibiliteStand fermeture : stand.getIndisponibilites()) {
            signature
                    .append(fermeture.getDate())
                    .append(' ')
                    .append(fermeture.getHeureDebut())
                    .append('-')
                    .append(fermeture.getHeureFin())
                    .append(';');
        }
        signature.append('#');
        for (OuvertureStand ouverture : stand.getOuvertures()) {
            signature
                    .append(ouverture.getDate())
                    .append(' ')
                    .append(ouverture.getHeureDebut())
                    .append('-')
                    .append(ouverture.getHeureFin())
                    .append('@')
                    .append(ouverture.getEffectif())
                    .append(';');
        }
        return signature.toString();
    }

    /* -------------------------------- Helpers ------------------------------- */

    /** Sorted, merged union of half-open {@code [debut, fin)} minute ranges. */
    private static List<int[]> union(List<int[]> segments) {
        List<int[]> tries = new ArrayList<>(segments.stream()
                .filter(segment -> segment[1] > segment[0])
                .map(segment -> new int[] {segment[0], segment[1]})
                .sorted(Comparator.comparingInt(segment -> segment[0]))
                .toList());
        List<int[]> fusionnes = new ArrayList<>();
        for (int[] segment : tries) {
            if (!fusionnes.isEmpty() && segment[0] <= fusionnes.get(fusionnes.size() - 1)[1]) {
                int[] dernier = fusionnes.get(fusionnes.size() - 1);
                dernier[1] = Math.max(dernier[1], segment[1]);
            } else {
                fusionnes.add(segment);
            }
        }
        return fusionnes;
    }

    /** Wall-clock reading of a minute offset counted from the timeslot's start. */
    private static String heure(Creneau creneau, int minutesDepuisLeDebut) {
        int minuteDuJour = (creneau.getHeureDebut().toSecondOfDay() / 60 + minutesDepuisLeDebut) % MINUTES_PAR_JOUR;
        return LocalTime.ofSecondOfDay(minuteDuJour * 60L).toString();
    }

    private static String libelle(Creneau creneau) {
        return creneau.getDate() + " " + creneau.getHeureDebut() + "-" + creneau.getHeureFin();
    }

    private static String citer(List<LocalDate> dates) {
        if (dates.size() <= DATES_CITEES) {
            return dates.stream()
                    .map(LocalDate::toString)
                    .reduce((a, b) -> a + ", " + b)
                    .orElse("");
        }
        return dates.subList(0, DATES_CITEES).stream()
                        .map(LocalDate::toString)
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("")
                + " et " + (dates.size() - DATES_CITEES) + " autre(s)";
    }

    /* ---------------------------- Verrouillage ---------------------------- */

    /**
     * What is worth telling the operator about the lock they just posted: the
     * seats it freezes already carry a hard violation in the latest analysis.
     *
     * <p>Accepted, and deliberately so — a lock says « this one does not
     * move », not « stop judging this one »: the frozen seats stay scored, and
     * the decision that settled it is what makes a solve on a locked plan
     * honest about its hard score. But the gesture is the moment to say it. An
     * organiser who freezes a day and launches a solve reads a negative hard
     * score they did not cause with that click, and has no way back to the
     * seats that carry it.</p>
     *
     * <p>Read on the <b>latest analysis</b>, never re-derived here: this is a
     * warning about a state, and a state nobody has analysed yet has nothing to
     * say. An edition with no analysis — nothing solved, or a restart — yields
     * no warning rather than a guess.</p>
     *
     * @param diagnostics the per-constraint diagnostics of that analysis, empty
     *                    when there is none. Their violations are capped per
     *                    constraint by {@code PlanningDiagnosticService}, so
     *                    the count in the sentence is a floor — « au moins »,
     *                    never a total
     * @param creneaux    the edition's timeslots, to read the day of a
     *                    {@code JOUR} lock against the timeslot a violation
     *                    names; empty simply skips that one lock type
     */
    public static List<Avertissement> onVerrouillage(
            VerrouillagePlanning verrouillage,
            List<PlanningDiagnosticService.ConstraintDiagnostic> diagnostics,
            List<Creneau> creneaux) {
        if (verrouillage == null || diagnostics == null || diagnostics.isEmpty()) {
            return List.of();
        }
        Map<Long, LocalDate> joursParCreneau = new HashMap<>();
        for (Creneau creneau : creneaux == null ? List.<Creneau>of() : creneaux) {
            if (creneau.getId() != null && creneau.getDate() != null) {
                joursParCreneau.put(creneau.getId(), creneau.getDate());
            }
        }
        TreeSet<String> regles = new TreeSet<>();
        int places = 0;
        for (PlanningDiagnosticService.ConstraintDiagnostic diagnostic : diagnostics) {
            if (!ConstraintCatalog.NOMS_DURS.contains(diagnostic.name())) {
                continue;
            }
            long touchees = diagnostic.references().stream()
                    .filter(reference -> couvre(verrouillage, reference, joursParCreneau))
                    .count();
            if (touchees > 0) {
                regles.add(diagnostic.name());
                places += (int) touchees;
            }
        }
        if (regles.isEmpty()) {
            return List.of();
        }
        return List.of(new Avertissement(
                TypeAvertissement.VERROUILLAGE_SUR_VIOLATION_DURE,
                "Le verrouillage " + verrouillage.getId() + " fige au moins " + places + " situation(s) qui "
                        + (places == 1 ? "casse" : "cassent") + " déjà une règle dure ("
                        + String.join(", ", regles)
                        + "). Un verrou n'exempte pas de ces règles : la prochaine résolution les comptera toujours, "
                        + "et le score dur restera négatif tant que le verrou tient. Le verrouillage est enregistré."));
    }

    /**
     * Whether a violation of the latest analysis falls inside the lock's
     * target. Read on the ids the violation names, which is all
     * {@code ViolationFormatter} keeps of a match: an unnamed side matches
     * nothing, so a rule violated by a pair the lock does not cover is never
     * counted.
     */
    private static boolean couvre(
            VerrouillagePlanning verrouillage,
            ViolationFormatter.ViolationReference reference,
            Map<Long, LocalDate> joursParCreneau) {
        return switch (verrouillage.target().orElse(null)) {
            case VerrouillageTarget.OnAnimateur sur -> sur.animateurId().equals(reference.animateurId());
            case VerrouillageTarget.OnStand sur -> sur.standId().equals(reference.standId());
            case VerrouillageTarget.OnCreneau sur ->
                Long.valueOf(sur.creneauId()).equals(reference.creneauId());
            case VerrouillageTarget.OnJour sur ->
                reference.creneauId() != null && sur.jour().equals(joursParCreneau.get(reference.creneauId()));
            case VerrouillageTarget.OnAnimateurAndCreneau sur ->
                sur.animateurId().equals(reference.animateurId())
                        && Long.valueOf(sur.creneauId()).equals(reference.creneauId());
            case null -> false;
        };
    }

    /* ------------------------------ Ad hoc -------------------------------- */

    /**
     * The three ways a forced assignment can be written today and be
     * unsatisfiable tomorrow morning: its animateurs declared the days off
     * ({@link ForcedAssignmentOnDayOff}), no seat of its scope may hold them
     * ({@link ForcedAssignmentOnExcludedSeats}), or their schedules are locked
     * over the whole scope ({@link ForcedAssignmentOnLockedSchedule}).
     *
     * <p>Written all the same, all three: a day off may be withdrawn, a birth
     * date corrected, a lock lifted, and the pre-solve analysis keeps reporting
     * whichever still stands. Every sentence names the exception by its id and
     * the dates, never an animateur — {@link Avertissement} says why.</p>
     *
     * @param verrouillages the locks recorded for this edition, empty when the
     *                      caller has none to offer: the lock check is then
     *                      simply not run, never guessed at
     * @param placesTenues  the seats of the persisted plan, same doctrine
     */
    public static List<Avertissement> onContrainteAdHoc(
            ContrainteAdHoc contrainte,
            List<Animateur> animateurs,
            List<Stand> stands,
            List<Creneau> creneaux,
            List<VerrouillagePlanning> verrouillages,
            Set<ForcedAssignmentOnLockedSchedule.PlaceTenue> placesTenues) {
        List<ContrainteAdHoc> une = contrainte == null ? List.of() : List.of(contrainte);
        List<Avertissement> avertissements = new ArrayList<>();
        ForcedAssignmentOnDayOff.detectAll(une, animateurs, stands, creneaux)
                .forEach(conflit -> avertissements.add(
                        new Avertissement(TypeAvertissement.AFFECTATION_FORCEE_JOUR_INDISPONIBLE, conflit.message())));
        ForcedAssignmentOnExcludedSeats.detectAll(une, animateurs, stands, creneaux)
                .forEach(conflit -> avertissements.add(
                        new Avertissement(TypeAvertissement.AFFECTATION_FORCEE_MOTIF_LEGAL, conflit.message())));
        ForcedAssignmentOnLockedSchedule.detectAll(une, verrouillages, stands, creneaux, placesTenues)
                .forEach(conflit -> avertissements.add(
                        new Avertissement(TypeAvertissement.AFFECTATION_FORCEE_SIEGE_VERROUILLE, conflit.message())));
        return List.copyOf(avertissements);
    }
}
